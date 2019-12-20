package izumi.distage.roles.test

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.UUID

import distage.plugins.{PluginBase, PluginDef}
import distage.{DIKey, Injector, Locator}
import izumi.distage.effect.modules.IdentityDIEffectModule
import izumi.distage.framework.config.PlanningOptions
import izumi.distage.framework.model.PluginSource
import izumi.distage.framework.services.{IntegrationChecker, RoleAppPlanner}
import izumi.distage.model.Locator.LocatorRef
import izumi.distage.model.definition.{BootstrapModule, DIResource}
import izumi.distage.roles.RoleAppMain
import izumi.distage.roles.test.fixtures.Fixture.{Resource0, Resource1, Resource2, XXX_ResourceEffectsRecorder}
import izumi.distage.roles.test.fixtures._
import izumi.distage.roles.test.fixtures.roles.TestRole00
import izumi.fundamentals.platform.functional.Identity
import izumi.fundamentals.platform.resources.ArtifactVersion
import izumi.logstage.api.IzLogger
import org.scalatest.WordSpec

class RoleAppTest extends WordSpec
  with WithProperties {
  private val prefix = "target/configwriter"

  private val overrides = Map(
    "testservice.systemPropInt" -> "265",
    "testservice.systemPropList.0" -> "111",
    "testservice.systemPropList.1" -> "222",
  )

  object TestEntrypoint extends RoleAppMain.Silent(new TestLauncher)

  class XXX_TestWhiteboxProbe extends PluginDef {
    val resources = new XXX_ResourceEffectsRecorder
    private var locator0: LocatorRef = null
    lazy val locator: Locator = locator0.get

    make[XXX_ResourceEffectsRecorder].fromValue(resources)
    make[XXX_LocatorLeak].from {
      locatorRef: LocatorRef =>
        locator0 = locatorRef
        XXX_LocatorLeak(locator0)
    }
  }

  val logLevel = "crit"
  //val logLevel = "info"

  "Role Launcher" should {
    "be able to start roles" in {
      val probe = new XXX_TestWhiteboxProbe()

      new RoleAppMain.Silent(
        new TestLauncher {
          override protected def pluginSource: PluginSource = super.pluginSource.map { l =>
            l.copy(app = Seq(l.app.merge overridenBy probe))
          }
        }
      ).main(Array(
        "-ll", logLevel,
        ":" + AdoptedAutocloseablesCase.id,
        ":" + TestRole00.id,
      ))

      assert(probe.resources.startedCloseables == probe.resources.closedCloseables.reverse)
      assert(probe.resources.checkedResources.toSet == Set(probe.locator.get[Resource1], probe.locator.get[Resource2]))
    }

    "start roles regression test" in {
      val probe = new XXX_TestWhiteboxProbe()

      new RoleAppMain.Silent(
        new TestLauncher {
          override protected def pluginSource: PluginSource = super.pluginSource.map { l =>
            l.copy(app = Seq(
              new ResourcesPluginBase().morph[PluginBase],
              new ConflictPlugin,
              new TestPlugin,
              new AdoptedAutocloseablesCasePlugin,
              probe,
              new PluginDef {
                make[Resource0].from[Resource1]
                many[Resource0]
                  .ref[Resource0]
              },
            ))
          }
        }
      ).main(Array(
        "-ll", logLevel,
        ":" + AdoptedAutocloseablesCase.id,
        ":" + TestRole00.id,
      ))

      assert(probe.resources.startedCloseables == probe.resources.closedCloseables.reverse)
      assert(probe.resources.checkedResources.toSet.size == 2)
      assert(probe.resources.checkedResources.toSet == Set(probe.locator.get[Resource0], probe.locator.get[Resource2]))
    }

    "integration checks are discovered and ran from a class binding when key is not an IntegrationCheck" in {
      val probe = new XXX_TestWhiteboxProbe()

      val logger = IzLogger()
      val definition = new ResourcesPluginBase {
        make[Resource0].from[Resource1]
        many[Resource0]
          .ref[Resource0]
      } ++ IdentityDIEffectModule ++ probe
      val roleAppPlanner = new RoleAppPlanner.Impl[Identity](
        PlanningOptions(),
        BootstrapModule.empty,
        logger,
      )
      val integrationChecker = new IntegrationChecker.Impl[Identity](logger)

      val plans = roleAppPlanner.makePlan(Set(DIKey.get[Set[Resource0]]), definition)
      Injector().produce(plans.runtime).use {
        Injector.inherit(_).produce(plans.app.shared).use {
          Injector.inherit(_).produce(plans.app.side).use {
            locator =>
              integrationChecker.checkOrFail(plans.app.side.declaredRoots, locator)

              assert(probe.resources.startedCloseables.size == 3)
              assert(probe.resources.checkedResources.size == 2)
              assert(probe.resources.checkedResources.toSet == Set(locator.get[Resource0], locator.get[Resource2]))
          }
        }
      }
    }

    "integration checks are discovered and ran from resource bindings" in {
      val probe = new XXX_TestWhiteboxProbe()

      val logger = IzLogger()
      val definition = new ResourcesPluginBase {
        make[Resource0].fromResource {
          r: Resource2 =>
            DIResource.fromAutoCloseable(new Resource1(r, probe.resources))
        }
        many[Resource0]
          .ref[Resource0]
      } ++ IdentityDIEffectModule ++ probe
      val roleAppPlanner = new RoleAppPlanner.Impl[Identity](
        PlanningOptions(),
        BootstrapModule.empty,
        logger,
      )
      val integrationChecker = new IntegrationChecker.Impl[Identity](logger)

      val plans = roleAppPlanner.makePlan(Set(DIKey.get[Set[Resource0]]), definition)
      Injector().produce(plans.runtime).use {
        Injector.inherit(_).produce(plans.app.shared).use {
          Injector.inherit(_).produce(plans.app.side).use {
            locator =>
              integrationChecker.checkOrFail(plans.app.side.declaredRoots, locator)

              assert(probe.resources.startedCloseables.size == 3)
              assert(probe.resources.checkedResources.size == 2)
              assert(probe.resources.checkedResources.toSet == Set(locator.get[Resource0], locator.get[Resource2]))
          }
        }
      }
    }

    "integration checks are discovered and ran, ignoring duplicating reference bindings" in {
      val logger = IzLogger()
      val initCounter = new XXX_ResourceEffectsRecorder
      val definition = new ResourcesPluginBase {
        make[Resource1]
        make[Resource0].using[Resource1]
        make[Resource0 with AutoCloseable].using[Resource1]
        many[Resource0]
          .ref[Resource0]
          .ref[Resource0 with AutoCloseable]
        make[XXX_ResourceEffectsRecorder].fromValue(initCounter)
      } ++ IdentityDIEffectModule
      val roleAppPlanner = new RoleAppPlanner.Impl[Identity](
        PlanningOptions(),
        BootstrapModule.empty,
        logger,
      )
      val integrationChecker = new IntegrationChecker.Impl[Identity](logger)

      val plans = roleAppPlanner.makePlan(Set(DIKey.get[Set[Resource0]]), definition)
      Injector().produce(plans.runtime).use {
        Injector.inherit(_).produce(plans.app.shared).use {
          Injector.inherit(_).produce(plans.app.side).use {
            locator =>
              integrationChecker.checkOrFail(plans.app.side.declaredRoots, locator)

              assert(initCounter.startedCloseables.size == 3)
              assert(initCounter.checkedResources.size == 2)
              assert(initCounter.checkedResources.toSet == Set(locator.get[Resource1], locator.get[Resource2]))
          }
        }
      }
    }

    "produce config dumps and support minimization" in {
      val version = ArtifactVersion(s"0.0.0-${UUID.randomUUID().toString}")
      withProperties(overrides ++ Map(TestPlugin.versionProperty -> version.version)) {
        TestEntrypoint.main(Array(
          "-ll", logLevel,
          ":configwriter", "-t", prefix
        ))
      }

      val cfg1 = cfg("configwriter", version)
      val cfg11 = cfg("configwriter-minimized", version)

      val cfg2 = cfg("testrole00", version)
      val cfg21 = cfg("testrole00-minimized", version)

      assert(cfg1.exists())
      assert(cfg11.exists())
      assert(cfg2.exists())
      assert(cfg21.exists())

      assert(cfg1.length() > cfg11.length())
      assert(new String(Files.readAllBytes(cfg21.toPath), UTF_8).contains("integrationOnlyCfg"))
    }
  }

  private def cfg(role: String, version: ArtifactVersion) = {
    Paths.get(prefix, s"$role-${version.version}.json").toFile
  }
}