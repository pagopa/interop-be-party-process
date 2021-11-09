package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import akka.actor
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import it.pagopa.pdnd.interop.uservice.partymanagement.client.api.PartyApi
import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker._
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.SpecConfig
import it.pagopa.pdnd.interop.uservice.partyprocess.service.PartyManagementService
import org.json4s.Formats
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}

//ApiInvoker manually mocked because of scalamock limitations for this scenario (i.e.: Json4s formats MUST not be null)
private class MockPartyApiInvoker(implicit json4sFormats: Formats, system: actor.ActorSystem)
    extends ApiInvoker(json4sFormats) {
  override def execute[T](r: ApiRequest[T])(implicit evidence$2: Manifest[T]): Future[ApiResponse[T]] = {
    val mockRelationshipResponse = Relationship(
      id = UUID.randomUUID(),
      from = UUID.randomUUID(),
      to = UUID.randomUUID(),
      role = MANAGER,
      productRole = "admin",
      status = ACTIVE,
      products = Set.empty
    )
    Future.successful(new ApiResponse[T](200, mockRelationshipResponse.asInstanceOf[T]))
  }
}

private object MockPartyApiInvoker {
  def apply(implicit json4sFormats: Formats, system: actor.ActorSystem): ApiInvoker =
    new ApiInvoker(json4sFormats)(system)
}

/** Tests if the platform role checks work when creating a relationship.
  */
class PartyManagementServiceImplSpec
    extends ScalaTestWithActorTestKit(SpecConfig.config)
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

    "return a failure when the platform role is not contained in the configured list for the defined role" in {
      //given
      val invalidProductRole = "foobar"
      //when
      val createRelationshipOp =
        partyManagementService.createRelationship(
          UUID.randomUUID(),
          UUID.randomUUID(),
          MANAGER,
          productRole = "foobar",
          products = Set.empty
        )
      //then
      createRelationshipOp.failed.futureValue.getMessage shouldBe s"Invalid platform role => $invalidProductRole not supported for ManagerRoles"
    }

    "return a success when the platform role is contained in the configured list for the defined role" in {
      //given the request payload
      val userId           = UUID.randomUUID()
      val partyIdTo        = UUID.randomUUID()
      val relationshipRole = OPERATOR
      val productRole      = "api"

      //given mocked integration API behavior
      val partyRelationship: RelationshipSeed =
        RelationshipSeed(
          from = userId,
          to = partyIdTo,
          role = relationshipRole,
          productRole = productRole,
          products = Set("PDND")
        )
      val mockApiRequest =
        ApiRequest[Relationship](ApiMethods.POST, "http://localhost", "/relationships", "application/json")
          .withBody(partyRelationship)
          .withSuccessResponse[Relationship](201)
          .withErrorResponse[Problem](400)
      (mockPartyAPI.createRelationship _).expects(partyRelationship).returning(mockApiRequest).once()

      //when
      val operation =
        partyManagementService.createRelationship(
          userId,
          partyIdTo,
          relationshipRole,
          productRole = productRole,
          products = Set("PDND")
        )

      //then
      operation.futureValue shouldBe ()
    }

  }

}
