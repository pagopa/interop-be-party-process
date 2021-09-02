package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import it.pagopa.pdnd.interop.uservice.partymanagement.client.api.PartyApi
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{PartyManagementInvoker, PartyManagementService}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContextExecutor

object PartyProcessMockConfig {

  System.setProperty("DELEGATE_PLATFORM_ROLES", "admin")
  System.setProperty("OPERATOR_PLATFORM_ROLES", "security, api")
  System.setProperty("MANAGER_PLATFORM_ROLES", "admin")

  val testData = ConfigFactory.parseString(s"""
      akka.coordinated-shutdown.terminate-actor-system = off
      akka.coordinated-shutdown.run-by-actor-system-terminate = off
      akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      akka.cluster.run-coordinated-shutdown-when-down = off
    """)

  val config = ConfigFactory
    .parseResourcesAnySyntax("resource")
    .withFallback(testData)
}

class PartyManagementServiceImplSpec
    extends ScalaTestWithActorTestKit(PartyProcessMockConfig.config)
    with MockFactory
    with AnyWordSpecLike
    with ScalaFutures {

  val httpSystem                                          = ActorSystem(Behaviors.ignore[Any], name = system.name, config = system.settings.config)
  implicit val executionContext: ExecutionContextExecutor = httpSystem.executionContext
  implicit val classicSystem                              = httpSystem.classicSystem
  val partyManagementInvoker: PartyManagementInvoker      = PartyManagementInvoker()
  val partyApi: PartyApi                                  = mock[PartyApi]
  val partyManagementService: PartyManagementService      = PartyManagementServiceImpl(partyManagementInvoker, partyApi)

  "when a relationship creation requires a manager" must {

    "should return a failure when the platform role is not contained in the configured list" in {
      //when
      val platformRole         = "foobar"
      val createRelationshipOp = partyManagementService.createRelationship("pippo", "pluto", "Manager", platformRole)

      //then
      createRelationshipOp.failed.futureValue.getMessage shouldBe s"Invalid platform role => $platformRole not supported for ManagerRoles"
    }
  }

}
