package it.pagopa.interop.partyprocess

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.ResourceNotFoundError
import it.pagopa.interop.partymanagement.client.model.{InstitutionProduct, Relationships}
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.api.impl.geographicTaxonomyExtFormat
import it.pagopa.interop.partyprocess.common.system.{classicActorSystem, executionContext}
import it.pagopa.interop.partyprocess.model._
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json.DefaultJsonProtocol

import java.time.OffsetDateTime
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
      products = Map.empty,
      geographicTaxonomies = Seq(PartyManagementDependency.GeographicTaxonomy(code = "GEOCODE", desc = "GEODESC"))
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
      attributes = Seq(Attribute(origin, "C17", "attrs")),
      geographicTaxonomies = Seq(GeographicTaxonomy(code = "GEOCODE", desc = "GEODESC"))
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

  "Get institution/product active manager" must {
    val productId = "prod-io"

    val orgPartyId = UUID.randomUUID()
    val originId   = UUID.randomUUID().toString
    val externalId = UUID.randomUUID().toString
    val origin     = "ORIGIN"

    val managerRelationshipId = UUID.randomUUID()
    val managerId             = UUID.randomUUID()

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
      products = Map.empty,
      geographicTaxonomies = Seq.empty
    )

    val manager = PartyManagementDependency.Relationship(
      id = managerRelationshipId,
      from = managerId,
      to = orgPartyId,
      role = PartyManagementDependency.PartyRole.MANAGER,
      product =
        PartyManagementDependency.RelationshipProduct(id = productId, role = "admin", createdAt = OffsetDateTime.now),
      state = PartyManagementDependency.RelationshipState.ACTIVE,
      pricingPlan = None,
      institutionUpdate = None,
      billing = None,
      createdAt = OffsetDateTime.now,
      updatedAt = None
    )

    val expected = RelationshipInfo(
      id = manager.id,
      from = manager.from,
      to = manager.to,
      role = PartyRole.MANAGER,
      product =
        ProductInfo(id = manager.product.id, role = manager.product.role, createdAt = manager.product.createdAt),
      state = RelationshipState.ACTIVE,
      pricingPlan = manager.pricingPlan,
      institutionUpdate = None,
      billing = None,
      createdAt = manager.createdAt,
      updatedAt = manager.updatedAt
    )

    def mockPartyManagement(retrieveInstitution: Boolean, retrieveManager: Boolean) = {
      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(
          if (retrieveInstitution) Future.successful(institution)
          else Future.failed(ResourceNotFoundError(externalId))
        )
        .once()

      if (retrieveInstitution) {
        (mockPartyManagementService
          .retrieveRelationships(
            _: Option[UUID],
            _: Option[UUID],
            _: Seq[PartyManagementDependency.PartyRole],
            _: Seq[PartyManagementDependency.RelationshipState],
            _: Seq[String],
            _: Seq[String]
          )(_: String)(_: Seq[(String, String)]))
          .expects(
            None,
            Some(institution.id),
            Seq(PartyManagementDependency.PartyRole.MANAGER),
            Seq(PartyManagementDependency.RelationshipState.ACTIVE),
            Seq(productId),
            Seq.empty,
            *,
            *
          )
          .returning(
            if (retrieveManager) Future.successful(Relationships(Seq(manager)))
            else Future.successful(Relationships(Seq.empty))
          )
          .once()
      }
    }

    "succeed when exists" in {
      mockPartyManagement(retrieveInstitution = true, retrieveManager = true)

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products/$productId/manager",
              method = HttpMethods.GET
            )
          )
          .futureValue(timeout)

      val body = Unmarshal(response.entity).to[RelationshipInfo].futureValue(timeout)

      body equals expected

      response.status mustBe StatusCodes.OK
    }

    "NotFoundError when not exists" in {
      mockPartyManagement(retrieveInstitution = true, retrieveManager = false)

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products/$productId/manager",
              method = HttpMethods.GET
            )
          )
          .futureValue(timeout)

      response.status mustBe StatusCodes.NotFound
    }

    "400 when institution not exists" in {
      mockPartyManagement(retrieveInstitution = false, retrieveManager = false)

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products/$productId/manager",
              method = HttpMethods.GET
            )
          )
          .futureValue(timeout)

      response.status mustBe StatusCodes.BadRequest
    }

  }

  "Get institution/product billing data" must {
    val productId = "prod-io"

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
      products = Map(
        "prod-io" -> PartyManagementDependency.InstitutionProduct(
          product = "prod-io",
          pricingPlan = Option("pricingPlan"),
          billing = PartyManagementDependency
            .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
        )
      ),
      geographicTaxonomies =
        Seq(PartyManagementDependency.GeographicTaxonomy(code = "OVERRIDE_GEOCODE", desc = "OVERRIDE_GEODESC"))
    )

    val expected = BillingData(
      institutionId = institution.id,
      externalId = institution.externalId,
      origin = institution.origin,
      originId = institution.originId,
      description = institution.description,
      taxCode = institution.taxCode,
      digitalAddress = institution.digitalAddress,
      address = institution.address,
      zipCode = institution.zipCode,
      institutionType = institution.institutionType,
      pricingPlan = Option("pricingPlan"),
      billing = Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
    )

    def mockPartyManagementNoInstitution() = {
      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.failed(ResourceNotFoundError(externalId)))
        .once()
    }

    def mockPartyManagementWithBilling() = {
      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()
    }

    def mockPartyManagementWithoutBilling() = {
      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(
          Future.successful(
            institution.copy(products =
              Map(
                "prodX" -> InstitutionProduct(
                  product = "prodX",
                  pricingPlan = None,
                  billing = PartyManagementDependency
                    .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
                )
              )
            )
          )
        )
        .once()
    }

    "succeed when exists" in {
      mockPartyManagementWithBilling()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products/$productId/billing",
              method = HttpMethods.GET
            )
          )
          .futureValue(timeout)

      val body = Unmarshal(response.entity).to[BillingData].futureValue(timeout)

      body equals expected

      response.status mustBe StatusCodes.OK
    }

    "NotFoundError when not exists" in {
      mockPartyManagementWithoutBilling()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products/$productId/billing",
              method = HttpMethods.GET
            )
          )
          .futureValue(timeout)

      response.status mustBe StatusCodes.NotFound
    }

    "NotFoundError when institution not exists" in {
      mockPartyManagementNoInstitution()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products/$productId/billing",
              method = HttpMethods.GET
            )
          )
          .futureValue(timeout)

      response.status mustBe StatusCodes.NotFound
    }

  }

  "Institution geographic taxonomies retrieval by external institution id" must {

    "retrieve geotaxonomies" in {
      val expectedGeographicTaxonomies = Seq(
        GeographicTaxonomyExt(code = "GEOCODE", desc = "GEODESC", enable = true),
        GeographicTaxonomyExt(code = "GEOCODE2", desc = "GEODESC2", enable = true)
      )

      val body = retrieveGeoTaxonomies(expectedGeographicTaxonomies)

      body.toSet mustBe expectedGeographicTaxonomies.toSet
    }

    "retrieve empty list when no geotaxonomies" in {
      val body = retrieveGeoTaxonomies(Seq.empty)

      body.toSet mustBe Set.empty
    }

    def retrieveGeoTaxonomies(expectedGeographicTaxonomies: Seq[GeographicTaxonomyExt]): Seq[GeographicTaxonomyExt] = {
      val institutionIdUUID = UUID.randomUUID()
      val externalId        = UUID.randomUUID().toString
      val originId          = UUID.randomUUID().toString

      val institution = PartyManagementDependency.Institution(
        id = institutionIdUUID,
        externalId = externalId,
        originId = originId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = "",
        origin = "",
        institutionType = Option.empty,
        products = Map.empty,
        geographicTaxonomies = expectedGeographicTaxonomies.map(x =>
          PartyManagementDependency.GeographicTaxonomy(code = x.code, desc = x.desc)
        )
      )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

      (mockGeoTaxonomyService
        .getExtByCodes(_: Seq[String])(_: Seq[(String, String)]))
        .expects(expectedGeographicTaxonomies.map(_.code), *)
        .returning(Future.successful(expectedGeographicTaxonomies))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(uri = s"$url/external/institutions/$externalId/geotaxonomies", method = HttpMethods.GET)
          )
          .futureValue

      Unmarshal(response.entity).to[Seq[GeographicTaxonomyExt]].futureValue
    }

    "return 404 when institution not exists" in {
      val externalId = UUID.randomUUID().toString

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.failed(ResourceNotFoundError(externalId)))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(uri = s"$url/external/institutions/$externalId/geotaxonomies", method = HttpMethods.GET)
          )
          .futureValue

      response.status mustBe StatusCodes.NotFound
    }
  }
}
