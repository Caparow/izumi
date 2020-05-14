package izumi.distage.planning

import com.github.ghik.silencer.silent
import izumi.distage.model.definition.{Binding, BindingTag, ModuleBase}
import izumi.distage.model.exceptions.{ConflictResolutionException, DIBugException, SanityCheckFailedException}
import izumi.distage.model.plan.ExecutableOp.{CreateSet, ImportDependency, InstantiationOp, MonadicOp, WiringOp}
import izumi.distage.model.plan._
import izumi.distage.model.plan.operations.OperationOrigin
import izumi.distage.model.planning._
import izumi.distage.model.reflection.{DIKey, MirrorProvider}
import izumi.distage.model.{Planner, PlannerInput}
import izumi.distage.planning.gc.TracingDIGC
import izumi.functional.Value
import izumi.fundamentals.graphs
import izumi.fundamentals.graphs.struct.IncidenceMatrix
import izumi.fundamentals.graphs.tools.MutationResolver._
import izumi.fundamentals.graphs.tools.{GC, Toposort}
import izumi.fundamentals.graphs.{ConflictResolutionError, DG, GraphMeta}

import scala.collection.compat._

final class PlannerDefaultImpl(
  forwardingRefResolver: ForwardingRefResolver,
  sanityChecker: SanityChecker,
  gc: DIGarbageCollector,
  planningObserver: PlanningObserver,
  hook: PlanningHook,
  bindingTranslator: BindingTranslator,
  analyzer: PlanAnalyzer,
  mirrorProvider: MirrorProvider,
) extends Planner {

  override def plan(input: PlannerInput): OrderedPlan = {
    planNoRewrite(input.copy(bindings = rewrite(input.bindings)))
  }

  override def planNoRewrite(input: PlannerInput): OrderedPlan = {
    resolveConflicts(input) match {
      case Left(value) =>
        // TODO: better error message
        throw new ConflictResolutionException(s"Failed to resolve conflicts: $value", value)

      case Right((resolved, _)) =>
        val mappedGraph = resolved.predcessors.links.toSeq.map {
          case (target, deps) =>
            val mappedTarget = updateKey(target)
            val mappedDeps = deps.map(updateKey)

            val op = resolved.meta.nodes.get(target).map {
              op =>
                val remaps = op.remapped.map {
                  case (original, remapped) =>
                    (original, updateKey(remapped))
                }
                val mapper = (key: DIKey) => remaps.getOrElse(key, key)
                op.meta.replaceKeys(key => Map(op.meta.target -> mappedTarget).getOrElse(key, key), mapper)
            }

            ((mappedTarget, mappedDeps), op.map(o => (mappedTarget, o)))
        }

        val mappedMatrix = mappedGraph.map(_._1).toMap
        val mappedOps = mappedGraph.flatMap(_._2).toMap

        val remappedKeysGraph = DG.fromPred(IncidenceMatrix(mappedMatrix), GraphMeta(mappedOps))

        // TODO: migrate semiplan to DG
        val steps = remappedKeysGraph.meta.nodes.values.toVector

        Value(SemiPlan(steps, input.mode))
          .map(addImports)
          .map(order)
          .get
    }
  }

  private def updateKey(mutSel: MutSel[DIKey]): DIKey = {
    mutSel.mut match {
      case Some(value) =>
        updateKey(mutSel.key, value)
      case None =>
        mutSel.key
    }
  }

  private def updateKey(key: DIKey, mindex: Int): DIKey = {
    key match {
      case DIKey.TypeKey(tpe, _) =>
        DIKey.TypeKey(tpe, Some(mindex))
      case k @ DIKey.IdKey(_, _, _) =>
        k.withMutatorIndex(Some(mindex))
      case s: DIKey.SetElementKey =>
        s.copy(set = updateKey(s.set, mindex))
      case r: DIKey.ResourceKey =>
        r.copy(key = updateKey(r.key, mindex))
      case e: DIKey.EffectKey =>
        e.copy(key = updateKey(e.key, mindex))
      case k =>
        throw DIBugException(s"Unexpected key mutator: $k, m=$mindex")
    }
  }

  private def resolveConflicts(
    input: PlannerInput
  ): Either[List[ConflictResolutionError[DIKey]], (DG[MutSel[DIKey], RemappedValue[InstantiationOp, DIKey]], GC.GCOutput[MutSel[DIKey]])] = {
    val activations: Set[AxisPoint] = input.activation.activeChoices.map { case (a, c) => AxisPoint(a.name, c.id) }.toSet
    val activationChoices = ActivationChoices(activations)

    val allOps: Vector[(Annotated[DIKey], InstantiationOp)] = input
      .bindings.bindings.iterator
      .filter(b => activationChoices.allValid(toAxis(b)))
      .flatMap {
        b =>
          val next = bindingTranslator.computeProvisioning(b)
          (next.provisions ++ next.sets.values).map((b, _))
      }
      .zipWithIndex
      .map {
        case ((b, n), idx) =>
          val mutIndex = b match {
            case Binding.SingletonBinding(_, _, _, _, true) =>
              Some(idx)
            case _ =>
              None
          }
          (Annotated(n.target, mutIndex, toAxis(b)), n)
      }.toVector

    val ops: Vector[(Annotated[DIKey], Node[DIKey, InstantiationOp])] = allOps.collect {
      case (target, op: WiringOp) => (target, Node(op.wiring.requiredKeys, op: InstantiationOp))
      case (target, op: MonadicOp) => (target, Node(Set(op.effectKey), op: InstantiationOp))
    }

    val sets: Map[Annotated[DIKey], Node[DIKey, InstantiationOp]] = allOps
      .collect { case (target, op: CreateSet) => (target, op) }
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .mapValues {
        v =>
          val mergedSet = v.tail.foldLeft(v.head) {
            case (op, acc) =>
              acc.copy(members = acc.members ++ op.members)
          }
          val filtered = mergedSet
          Node(filtered.members, filtered: InstantiationOp)
      }
      .toMap

    val matrix = SemiEdgeSeq(ops ++ sets)

    val roots = input.mode match {
      case GCMode.GCRoots(roots) =>
        roots.toSet
      case GCMode.NoGC =>
        allOps.map(_._1.key).toSet
    }

    val weakSetMembers = findWeakSetMembers(sets, matrix, roots)

//    println("BEGIN")
//    println(s"ROOTS: $roots")
//    println(s"WEAK: $weakSetMembers")
    for {
      resolution <- new MutationResolverImpl[DIKey, Int, InstantiationOp]().resolve(matrix, roots, activations, weakSetMembers)
      retainedKeys = resolution.graph.meta.nodes.map(_._1.key).toSet
      originalKeys = allOps.map(_._1.key).toSet
      membersToDrop =
        resolution
          .graph.meta.nodes.collect {
            case (k, RemappedValue(ExecutableOp.WiringOp.ReferenceKey(_, Wiring.SingletonWiring.Reference(_, referenced, true), _), _))
                if !retainedKeys.contains(referenced) && !roots.contains(k.key) =>
//              println(s"TOREMOVE: $k")
              k
          }.toSet
      keysToDrop = membersToDrop.map(_.key)
      filteredWeakMembers = resolution.graph.meta.nodes.filterNot(m => keysToDrop.contains(m._1.key)).map {
        case (k, RemappedValue(set: CreateSet, remaps)) =>
          val withoutUnreachableWeakMebers = set.members.diff(keysToDrop)
//          if (withoutUnreachableWeakMebers != set.members) {
//            println(s"RETAINED SET MEMBERS in $k: $withoutUnreachableWeakMebers")
//          }
          (k, RemappedValue(set.copy(members = withoutUnreachableWeakMebers): InstantiationOp, remaps))
        case (k, o) =>
          (k, o)
      }
      resolved =
        resolution
          .graph.copy(
            meta = GraphMeta(filteredWeakMembers),
            successors = resolution.graph.successors.without(membersToDrop),
            predcessors = resolution.graph.predcessors.without(membersToDrop),
          )
//      collected <- {
//        val resolvedWeakSetMembers = findResolvedWeakSetMembers(resolved)
//        new graphs.tools.GC.GCTracer[MutSel[DIKey]].collect(GC.GCInput(resolved.predcessors, roots.map(MutSel(_, None)), resolvedWeakSetMembers))
//      }
      out <- Right((resolved, null))
    } yield {
      out
    }
  }

//  private def findResolvedWeakSetMembers(resolved: DG[MutSel[DIKey], RemappedValue[InstantiationOp, DIKey]]): Set[GC.WeakEdge[MutSel[DIKey]]] = {
//    val setTargets = resolved.meta.nodes.collect {
//      case (target, RemappedValue(_: CreateSet, _)) => target
//    }
//
//    setTargets.flatMap {
//      set =>
//        val setMembers = resolved.predcessors.links(set)
//        setMembers
//          .filter {
//            member =>
//              resolved.meta.nodes.get(member).map(_.meta).exists {
//                case ExecutableOp.WiringOp.ReferenceKey(_, Wiring.SingletonWiring.Reference(_, _, weak), _) =>
//                  weak
//                case _ =>
//                  false
//              }
//          }
//          .map(member => GC.WeakEdge(member, set))
//    }.toSet
//  }

  private def findWeakSetMembers(
    sets: Map[Annotated[DIKey], Node[DIKey, InstantiationOp]],
    matrix: SemiEdgeSeq[Annotated[DIKey], DIKey, InstantiationOp],
    roots: Set[DIKey],
  ): Set[GC.WeakEdge[DIKey]] = {
    import izumi.fundamentals.collections.IzCollections._
    val indexed = matrix
      .links.map {
        case (successor, node) =>
          (successor.key, node.meta)
      }
      .toMultimap
    sets
      .collect {
        case (target, Node(_, s: CreateSet)) => (target, s.members)
      }.flatMap {
        case (_, members) =>
//          println(s"∂ ${members.intersect(roots)}")
          members
            .diff(roots)
            .flatMap {
              member =>
                indexed.get(member).toSeq.flatten.collect {
                  case ExecutableOp.WiringOp.ReferenceKey(_, Wiring.SingletonWiring.Reference(_, referenced, true), _) =>
                    GC.WeakEdge(referenced, member)
                }
            }
      }
      .toSet
  }

  private def toAxis(b: Binding): Set[AxisPoint] = {
    b.tags.collect {
      case BindingTag.AxisTag(choice) =>
        AxisPoint(choice.axis.name, choice.id)
    }
  }

  override def rewrite(module: ModuleBase): ModuleBase = {
    hook.hookDefinition(module)
  }

  @deprecated("used in tests only!", "")
  override def finish(semiPlan: SemiPlan): OrderedPlan = {
    Value(semiPlan)
      .map(addImports)
      .eff(planningObserver.onPhase05PreGC)
      .map(gc.gc)
      .map(order)
      .get
  }

  private[this] def order(semiPlan: SemiPlan): OrderedPlan = {
    Value(semiPlan)
      .eff(planningObserver.onPhase10PostGC)
      .map(hook.phase20Customization)
      .eff(planningObserver.onPhase20Customization)
      .map(hook.phase50PreForwarding)
      .eff(planningObserver.onPhase50PreForwarding)
      .map(reorderOperations)
      .map(postOrdering)
      .get
  }

  @silent("Unused import")
  private[this] def addImports(plan: SemiPlan): SemiPlan = {
    import scala.collection.compat._

    val topology = analyzer.topology(plan.steps)
    val imports = topology
      .dependees
      .graph
      .view
      .filterKeys(k => !plan.index.contains(k))
      .map {
        case (missing, refs) =>
          val maybeFirstOrigin = refs.headOption.flatMap(key => plan.index.get(key)).map(_.origin.toSynthetic)
          missing -> ImportDependency(missing, refs.toSet, maybeFirstOrigin.getOrElse(OperationOrigin.Unknown))
      }
      .toMap

    val allOps = (imports.values ++ plan.steps).toVector
    val roots = plan.gcMode.toSet
    val missingRoots = roots
      .diff(allOps.map(_.target).toSet).map {
        root =>
          ImportDependency(root, Set.empty, OperationOrigin.Unknown)
      }.toVector

    SemiPlan(missingRoots ++ allOps, plan.gcMode)
  }

  private[this] def reorderOperations(completedPlan: SemiPlan): OrderedPlan = {
    val topology = analyzer.topology(completedPlan.steps)

    val index = completedPlan.index

    val maybeBrokenLoops = new Toposort().cycleBreaking(
      predcessors = IncidenceMatrix(topology.dependencies.graph),
      break = new LoopBreaker(analyzer, mirrorProvider, index, topology, completedPlan),
    )

    val sortedKeys = maybeBrokenLoops match {
      case Left(value) =>
        throw new SanityCheckFailedException(s"Integrity check failed: cyclic reference not detected while it should be, $value")

      case Right(value) =>
        value
    }

    val sortedOps = sortedKeys.flatMap(index.get).toVector

    val roots = completedPlan.gcMode match {
      case GCMode.GCRoots(roots) =>
        roots
      case GCMode.NoGC =>
        topology.effectiveRoots
    }
    OrderedPlan(sortedOps, roots, topology)
  }

  private[distage] override def truncate(plan: OrderedPlan, roots: Set[DIKey]): OrderedPlan = {
    if (roots.isEmpty) {
      OrderedPlan.empty
    } else {
      assert(roots.diff(plan.index.keySet).isEmpty)
      val collected = new TracingDIGC(roots, plan.index, ignoreMissingDeps = false).gc(plan.steps)
      OrderedPlan(collected.nodes, roots, analyzer.topology(collected.nodes))
    }
  }

  private[this] def postOrdering(almostPlan: OrderedPlan): OrderedPlan = {
    Value(almostPlan)
      .map(forwardingRefResolver.resolve)
      .map(hook.phase90AfterForwarding)
      .eff(planningObserver.onPhase90AfterForwarding)
      .eff(sanityChecker.assertFinalPlanSane)
      .get
  }

}
