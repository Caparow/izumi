package com.github.pshirshov.izumi.distage.testkit.services

import com.github.pshirshov.izumi.distage.bootstrap.BootstrapLocator
import com.github.pshirshov.izumi.distage.model.definition.DIResource.DIResourceBase
import com.github.pshirshov.izumi.distage.model.definition.{DIResource, LocatorDef}
import com.github.pshirshov.izumi.distage.model.monadic.{DIEffect, DIEffectRunner}
import com.github.pshirshov.izumi.distage.model.provisioning.PlanInterpreter.Finalizer
import com.github.pshirshov.izumi.distage.model.references.IdentifiedRef
import com.github.pshirshov.izumi.distage.testkit.services.ExternalResourceProvider.{MemoizedInstance, PreparedShutdownRuntime}
import com.github.pshirshov.izumi.fundamentals.platform.console.TrivialLogger
import com.github.pshirshov.izumi.fundamentals.platform.functional.Identity
import com.github.pshirshov.izumi.fundamentals.platform.language.Quirks
import distage.{DIKey, Locator, SafeType, TagK}

import scala.annotation.unchecked.uncheckedVariance

trait ExternalResourceProvider {
  def process(ctx: MemoizationContextId, ref: MemoizedInstance[Any]): Unit

  def isMemoized(ctx: MemoizationContextId, key: DIKey): Boolean

  def getMemoized(ctx: MemoizationContextId, key: DIKey): Option[Any]

  def size(ctx: MemoizationContextId): Int

  def size: Int

  def registerShutdownRuntime[F[_]: TagK](rt: => PreparedShutdownRuntime[F]): Unit
}

object ExternalResourceProvider {

  case class OrderedFinalizer[+F[_]](finalizer: Finalizer[F@uncheckedVariance], order: Int)

  case class MemoizedInstance[+F[_]](ref: IdentifiedRef, finalizer: Option[OrderedFinalizer[F@uncheckedVariance]])

  case class PreparedShutdownRuntime[+F[_]](runner: DIResourceBase[Identity, Locator], fType: SafeType, fTag: TagK[F @uncheckedVariance])

  object PreparedShutdownRuntime {
    def apply[F[_]: TagK](runner: DIResourceBase[Identity, Locator]) = new PreparedShutdownRuntime[F](runner, SafeType.getK[F], TagK[F])
  }

  object Null extends ExternalResourceProvider {

    override def process(memoizationContextId: MemoizationContextId, ref: MemoizedInstance[Any]): Unit = {
      Quirks.discard(memoizationContextId, ref)
    }

    override def isMemoized(ctx: MemoizationContextId, key: DIKey): Boolean = false

    override def getMemoized(ctx: MemoizationContextId, key: DIKey): Option[Any] = None

    override def size(ctx: MemoizationContextId): Int = {
      Quirks.discard(ctx)
      0
    }

    override def size: Int = 0

    override def registerShutdownRuntime[F[_]: TagK](rt: => PreparedShutdownRuntime[F]): Unit = {
      Quirks.forget(rt)
    }
  }

  def singleton[F[_]: TagK](memoize: IdentifiedRef => Boolean): Singleton[F] = new Singleton[F](memoize)

  class Singleton[F[_]: TagK](
                               memoize: IdentifiedRef => Boolean
                             ) extends ExternalResourceProvider {

    import Singleton.{MemoizationKey, cache, registerRT}

    private[this] final val keyF = SafeType.getK[F]

    override def process(ctx: MemoizationContextId, ref: MemoizedInstance[Any]): Unit = {
      if (memoize(ref.ref)) {
        cache.putIfNotExist(MemoizationKey(ctx, ref.ref.key, keyF), ref)
      }
    }

    override def isMemoized(ctx: MemoizationContextId, key: DIKey): Boolean = {
      cache.hasKey(MemoizationKey(ctx, key, keyF))
    }

    override def getMemoized(ctx: MemoizationContextId, key: DIKey): Option[Any] = {
      cache.get(MemoizationKey(ctx, key, keyF)).map(_.ref.value)
    }

    override def size(ctx: MemoizationContextId): Int = {
      cache.enumerate().count(_._1.ctx == ctx)
    }

    override def size: Int = {
      cache.size
    }

    override def registerShutdownRuntime[F1[_]: TagK](rt: => PreparedShutdownRuntime[F1]): Unit = {
      registerRT[F1](rt)
    }
  }

  object Singleton {

    case class MemoizationKey(ctx: MemoizationContextId, key: DIKey, fType: SafeType)

    private val runtimes = new SyncCache[SafeType, PreparedShutdownRuntime[Any]]()

    private val cache = new SyncCache[MemoizationKey, MemoizedInstance[Any]]()

    private val shutdownHook = new Thread(() => stop(), "termination-hook-memoizer")

    private val logger = TrivialLogger.make[BootstrapLocator]("izumi.distage.debug.memoizer", default = true)

    Runtime.getRuntime.addShutdownHook(shutdownHook)

    registerRT[Identity](
      PreparedShutdownRuntime[Identity](
        DIResource.liftF[Identity, Locator] {
          new LocatorDef {
            addImplicit[DIEffectRunner[Identity]]
            addImplicit[DIEffect[Identity]]
          }
        }
      )
    )

    private def registerRT[F[_]: TagK](rt: => PreparedShutdownRuntime[F]): Unit = {
      runtimes.putIfNotExist(SafeType.getK[F], rt)
    }



    private def stop(): Unit = {
      object fake {
        type FakeF[T]
      }
      import fake.FakeF

      runtimes.enumerate().foreach {
        case (rtType, rt) =>

          val effects = cache.enumerate()
            .filter {
              case (k, _) =>
                rtType == k.fType
            }
            .flatMap(_._2.finalizer.toSeq)
            .sortBy(_.order)
            .map(_.finalizer)
            .map {
              fin =>
                fin.asInstanceOf[Finalizer[FakeF]]
            }
          import DIEffect.syntax._

          implicit val effectTagK: TagK[FakeF] = rt.fTag.asInstanceOf[TagK[FakeF]]
          Quirks.discard(effectTagK)

          val effectTag = SafeType.get[DIEffect[FakeF]]
          val runnerTag = SafeType.get[DIEffectRunner[FakeF]]

          if (effects.nonEmpty) {
            rt.runner.use {
              locator =>
                implicit val effect: DIEffect[FakeF] = locator.instances
                  .find(_.key.tpe == effectTag)
                  .get.value.asInstanceOf[DIEffect[FakeF]]

                implicit val runner: DIEffectRunner[FakeF] = locator.instances
                  .find(_.key.tpe == runnerTag)
                  .get.value.asInstanceOf[DIEffectRunner[FakeF]]

                runner.run {
                  for {
                    _ <- effects.foldLeft(effect.maybeSuspend(logger.log(s"Running finalizers in effect type ${rt.fType}..."))) {
                      case (acc, f) =>
                        acc.guarantee {
                          for {
                            _ <- effect.maybeSuspend(logger.log(s"Closing ${f.key}..."))
                            _ <- effect.suspendF(f.effect())
                          } yield ()
                        }
                    }
                    _ <- effect.maybeSuspend(logger.log(s"Finished finalizers in effect type ${rt.fType}!"))
                  } yield ()
                }
            }
          }
      }

    }


  }

}