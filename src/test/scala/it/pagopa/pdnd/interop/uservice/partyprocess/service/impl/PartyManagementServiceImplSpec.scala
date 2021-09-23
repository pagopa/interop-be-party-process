package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import akka.actor
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.typesafe.config.ConfigFactory
import it.pagopa.pdnd.interop.uservice.partymanagement.client.api.PartyApi
import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker._
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.{Problem, RelationshipSeed, RelationshipSeedEnums}
import it.pagopa.pdnd.interop.uservice.partyprocess.service.PartyManagementService
import org.json4s.Formats
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.{ExecutionContextExecutor, Future}

//ApiInvoker manually mocked because of scalamock limitations for this scenario (i.e.: Json4s formats MUST not be null)
private class MockPartyApiInvoker(implicit json4sFormats: Formats, system: actor.ActorSystem)
    extends ApiInvoker(json4sFormats) {
  override def execute[T](r: ApiRequest[T])(implicit evidence$2: Manifest[T]): Future[ApiResponse[T]] =
    Future.successful(new ApiResponse[T](200, ().asInstanceOf[T]))
}

private object MockPartyApiInvoker {
  def apply(implicit json4sFormats: Formats, system: actor.ActorSystem): ApiInvoker =
    new ApiInvoker(json4sFormats)(system)
}

object PartyProcessMockConfig {
  System.setProperty("DELEGATE_PLATFORM_ROLES", "admin")
  System.setProperty("OPERATOR_PLATFORM_ROLES", "security, api")
  System.setProperty("MANAGER_PLATFORM_ROLES", "admin")

  val testDataConfig = ConfigFactory.parseString(s"""
      akka.coordinated-shutdown.terminate-actor-system = off
      akka.coordinated-shutdown.run-by-actor-system-terminate = off
      akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      akka.cluster.run-coordinated-shutdown-when-down = off
    """)

  val config = ConfigFactory
    .parseResourcesAnySyntax("resource")
    .withFallback(testDataConfig)
}

/** Tests if the platform role checks work when creating a relationship.
  */
class PartyManagementServiceImplSpec
    extends ScalaTestWithActorTestKit(PartyProcessMockConfig.config)
    with MockFactory
    with AnyWordSpecLike
    with ScalaFutures {

  //getting Akka globals as implicits
  implicit val executionContext: ExecutionContextExecutor = system.executionContext
  implicit val classicSystem: actor.ActorSystem           = system.classicSystem
  //getting implicit Json4s format
  implicit val formats: Formats = org.json4s.DefaultFormats

  //mocking integrations
  val mockPartyAPI: PartyApi                         = mock[PartyApi]
  val partyManagementService: PartyManagementService = PartyManagementServiceImpl(new MockPartyApiInvoker, mockPartyAPI)

  "when the service creates a relationship" must {

    "return a failure when the organization role is not contained in the supported enumeration set" in {
      //given
      val invalidRole = "JavaScriptNinja"
      //when
      val operation = partyManagementService.createRelationship("DFFDCK", "ACME Corp.", invalidRole, "admin")
      //then
      operation.failed.futureValue.getMessage shouldBe s"No value found for '$invalidRole'"
    }

    "return a failure when the platform role is not contained in the configured list for the defined role" in {
      //given
      val invalidPlatformRole = "foobar"
      //when
      val createRelationshipOp =
        partyManagementService.createRelationship("BGSBNNY", "ACME Corp.", "Manager", invalidPlatformRole)
      //then
      createRelationshipOp.failed.futureValue.getMessage shouldBe s"Invalid platform role => $invalidPlatformRole not supported for ManagerRoles"
    }

    "return a success when the platform role is contained in the configured list for the defined role" in {
      //given the request payload
      val taxCode          = "MRRRSS1345"
      val partyIdTo        = "Miro Gardens"
      val relationshipRole = "Operator"
      val platformRole     = "api"

      //given mocked integration API behavior
      val partyRelationship: RelationshipSeed =
        RelationshipSeed(
          from = taxCode,
          to = partyIdTo,
          role = RelationshipSeedEnums.Role.withName(relationshipRole),
          platformRole = platformRole
        )
      val mockApiRequest = ApiRequest[Unit](ApiMethods.POST, "http://localhost", "/relationships", "application/json")
        .withBody(partyRelationship)
        .withSuccessResponse[Unit](201)
        .withErrorResponse[Problem](400)
      (mockPartyAPI.createRelationship _).expects(partyRelationship).returning(mockApiRequest).once()

      //when
      val operation = partyManagementService.createRelationship(taxCode, partyIdTo, relationshipRole, platformRole)

      //then
      operation.futureValue shouldBe ()
    }

  }

}
