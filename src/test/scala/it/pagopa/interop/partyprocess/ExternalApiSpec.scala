package it.pagopa.interop.partyprocess

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.ResourceNotFoundError
import it.pagopa.interop.partymanagement.client.model.{PartyRole => _, RelationshipState => _}
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.common.system.{classicActorSystem, executionContext}
import it.pagopa.interop.partyprocess.model.{Attribute, Institution}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json.DefaultJsonProtocol

import java.util.UUID
import scala.concurrent.Future

trait ExternalApiSpec
    extends MockFactory
    with AnyWordSpecLike
    with Matchers
    with SprayJsonSupport
    with DefaultJsonProtocol
    with SpecHelper
    with ScalaFutures {

  val timeout: Timeout = Timeout(Span(3, Seconds))

  "Get institution by externalId" must {
    val orgPartyId = UUID.randomUUID()
    val originId   = UUID.randomUUID().toString
    val externalId = UUID.randomUUID().toString
    val origin     = "ORIGIN"

    val institution = PartyManagementDependency.Institution(
      id = orgPartyId,
      externalId = externalId,
      originId = originId,
      description = "description",
      digitalAddress = "digitalAddress",
      attributes = Seq(PartyManagementDependency.Attribute(origin, "C17", "attrs")),
      taxCode = "taxCode",
      origin = origin,
      address = "address",
      zipCode = "zipCode",
      products = Map.empty
    )

    val expected = Institution(
      id = institution.id,
      externalId = institution.externalId,
      originId = institution.originId,
      description = institution.description,
      digitalAddress = institution.digitalAddress,
      address = institution.address,
      zipCode = institution.zipCode,
      taxCode = institution.taxCode,
      origin = institution.origin,
      institutionType = institution.institutionType,
      attributes = Seq(Attribute(origin, "C17", "attrs"))
    )

    def mockPartyManagement(success: Boolean) = {
      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(if (success) Future.successful(institution) else Future.failed(ResourceNotFoundError(externalId)))
        .once()
    }

    "succeed when exists" in {
      mockPartyManagement(true)

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/external/institutions/$externalId", method = HttpMethods.GET))
          .futureValue(timeout)

      val body = Unmarshal(response.entity).to[Institution].futureValue(timeout)

      body equals expected

      response.status mustBe StatusCodes.OK
    }

    "NotFoundError when not exists" in {
      mockPartyManagement(false)

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/external/institutions/$externalId", method = HttpMethods.GET))
          .futureValue(timeout)

      response.status mustBe StatusCodes.NotFound
    }

  }

}
