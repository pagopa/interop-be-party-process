package it.pagopa.interop.partyprocess

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.directives.FileInfo
import akka.http.scaladsl.unmarshalling.Unmarshal
import cats.implicits.catsSyntaxValidatedId
import eu.europa.esig.dss.validation.SignedDocumentValidator
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.{ResourceConflictError, ResourceNotFoundError}
import it.pagopa.interop.partymanagement.client.model.{
  InstitutionSeed,
  RelationshipBinding,
  RelationshipProduct,
  TokenInfo,
  PartyRole => _,
  RelationshipState => _,
  _
}
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess
import it.pagopa.interop.partyprocess.api.impl.Conversions._
import it.pagopa.interop.partyprocess.common.system.{classicActorSystem, executionContext}
import it.pagopa.interop.partyprocess.error.SignatureValidationError
import it.pagopa.interop.partyprocess.model.PartyRole.{DELEGATE, MANAGER, OPERATOR, SUB_DELEGATE}
import it.pagopa.interop.partyprocess.model.RelationshipState.ACTIVE
import it.pagopa.interop.partyprocess.model.{Attribute, Billing, Institution, InstitutionUpdate, _}
import it.pagopa.interop.partyregistryproxy.client.{model => PartyProxyDependencies}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json.DefaultJsonProtocol

import java.io.{ByteArrayOutputStream, File}
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

trait PartyApiSpec
    extends MockFactory
    with AnyWordSpecLike
    with Matchers
    with SprayJsonSupport
    with DefaultJsonProtocol
    with SpecHelper
    with ScalaFutures {

  final val defaultProductTimestamp: OffsetDateTime      = OffsetDateTime.now()
  final val defaultRelationshipTimestamp: OffsetDateTime = OffsetDateTime.now()

  final val defaultProduct: RelationshipProduct =
    PartyManagementDependency.RelationshipProduct(id = "productId", role = "admin", createdAt = defaultProductTimestamp)

  final val defaultProductInfo: ProductInfo =
    ProductInfo(id = "productId", role = "admin", createdAt = defaultProductTimestamp)

  final val relationship: Relationship = PartyManagementDependency.Relationship(
    id = UUID.randomUUID(),
    from = UUID.randomUUID(),
    to = UUID.randomUUID(),
    role = PartyManagementDependency.PartyRole.MANAGER,
    product = defaultProduct,
    state = PartyManagementDependency.RelationshipState.PENDING,
    createdAt = OffsetDateTime.now()
  )

  def performOnboardingRequestByRoleForFailure(state: PartyManagementDependency.RelationshipState): HttpResponse = {
    val taxCode1   = "managerTaxCode"
    val taxCode2   = "delegateTaxCode"
    val originId   = UUID.randomUUID().toString
    val origin     = "IPA"
    val externalId = UUID.randomUUID().toString
    val orgPartyId = UUID.randomUUID()

    val institution1 = PartyManagementDependency.Institution(
      id = orgPartyId,
      externalId = externalId,
      originId = originId,
      description = "org1",
      digitalAddress = "digitalAddress1",
      attributes = Seq.empty,
      taxCode = "123",
      address = "address",
      zipCode = "zipCode",
      origin = origin,
      institutionType = Option("PA"),
      products = Map.empty
    )

    val managerId = UUID.randomUUID()
    val manager   =
      User(
        id = managerId,
        name = "manager",
        surname = "manager",
        taxCode = taxCode1,
        role = MANAGER,
        email = None,
        productRole = "admin"
      )

    val delegateId = UUID.randomUUID()
    val delegate   =
      User(
        id = delegateId,
        name = "delegate",
        surname = "delegate",
        taxCode = taxCode2,
        role = DELEGATE,
        email = None,
        productRole = "admin"
      )

    val managerRelationship = PartyManagementDependency.Relationship(
      id = UUID.randomUUID(),
      from = managerId,
      to = UUID.randomUUID(),
      filePath = None,
      fileName = None,
      contentType = None,
      role = PartyManagementDependency.PartyRole.MANAGER,
      product =
        PartyManagementDependency.RelationshipProduct(id = "product", role = "admin", createdAt = OffsetDateTime.now()),
      state = state,
      createdAt = OffsetDateTime.now(),
      updatedAt = None,
      pricingPlan = Option("pricingPlan"),
      institutionUpdate = Option(
        PartyManagementDependency.InstitutionUpdate(
          institutionType = Option("OVERRIDE_institutionType"),
          description = Option("OVERRIDE_description"),
          digitalAddress = Option("OVERRIDE_digitalAddress"),
          address = Option("OVERRIDE_address"),
          zipCode = Option("OVERRIDE_zipCode"),
          taxCode = Option("OVERRIDE_taxCode")
        )
      ),
      billing = Option(
        PartyManagementDependency
          .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
      )
    )

    (mockUserRegistryService
      .getUserById(_: UUID)(_: Seq[(String, String)]))
      .expects(*, *)
      .returning(Future.successful(UserRegistryUser(id = UUID.randomUUID(), taxCode = "", name = "", surname = "")))
      .once()

    (mockPartyManagementService
      .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
      .expects(externalId, *, *)
      .returning(Future.successful(institution1))
      .once()

    (mockPartyManagementService
      .retrieveRelationships(
        _: Option[UUID],
        _: Option[UUID],
        _: Seq[PartyManagementDependency.PartyRole],
        _: Seq[PartyManagementDependency.RelationshipState],
        _: Seq[String],
        _: Seq[String]
      )(_: String)(_: Seq[(String, String)]))
      .expects(None, Some(institution1.id), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *, *)
      .returning(Future.successful(PartyManagementDependency.Relationships(Seq(managerRelationship))))
      .once()

    val req = OnboardingInstitutionRequest(
      productId = "productId",
      productName = "productName",
      users = Seq(manager, delegate),
      institutionExternalId = externalId,
      contract = OnboardingContract("a", "b"),
      billing = Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE")
    )

    val data = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue

    request(data, "onboarding/institution", HttpMethods.POST)
  }

  def performOnboardingRequest(
    state: Option[PartyManagementDependency.RelationshipState],
    product: Option[String]
  ): HttpResponse = {
    val taxCode1   = "managerTaxCode"
    val taxCode2   = "delegateTaxCode"
    val originId   = UUID.randomUUID().toString
    val externalId = UUID.randomUUID().toString
    val origin     = "ORIGIN"
    val orgPartyId = UUID.randomUUID()

    val institution1 = PartyManagementDependency.Institution(
      id = orgPartyId,
      externalId = externalId,
      originId = originId,
      description = "org1",
      digitalAddress = "digitalAddress1",
      attributes = Seq.empty,
      taxCode = "123",
      address = "address",
      zipCode = "zipCode",
      origin = origin,
      institutionType = Option("PA"),
      products = Map.empty
    )

    val file = new File("src/test/resources/fake.file")

    val managerId  = UUID.randomUUID()
    val delegateId = UUID.randomUUID()
    val manager    =
      User(
        id = managerId,
        name = "manager",
        surname = "managerSurname",
        taxCode = taxCode1,
        role = PartyRole.MANAGER,
        email = Option("manager@email.it"),
        productRole = "admin"
      )
    val delegate   =
      User(
        id = delegateId,
        name = "delegate",
        surname = "delegateSurname",
        taxCode = taxCode2,
        role = PartyRole.DELEGATE,
        email = Option("delegate@email.it"),
        productRole = "admin"
      )

    val relationships = (state, product) match {
      case (Some(st), Some(pr)) =>
        Seq(
          PartyManagementDependency.Relationship(
            id = UUID.randomUUID(),
            from = UUID.randomUUID(),
            to = UUID.randomUUID(),
            filePath = None,
            fileName = None,
            contentType = None,
            role = PartyManagementDependency.PartyRole.MANAGER,
            product = PartyManagementDependency
              .RelationshipProduct(id = pr, role = "admin", createdAt = OffsetDateTime.now()),
            state = st,
            createdAt = OffsetDateTime.now(),
            updatedAt = None,
            pricingPlan = Option("pricingPlan"),
            institutionUpdate = Option(
              PartyManagementDependency.InstitutionUpdate(
                institutionType = Option("OVERRIDE_institutionType"),
                description = Option("OVERRIDE_description"),
                digitalAddress = Option("OVERRIDE_digitalAddress"),
                address = Option("OVERRIDE_address"),
                zipCode = Option("OVERRIDE_zipCode"),
                taxCode = Option("OVERRIDE_taxCode")
              )
            ),
            billing = Option(
              PartyManagementDependency
                .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
            )
          )
        )
      case (Some(st), None)     =>
        Seq(
          PartyManagementDependency.Relationship(
            id = UUID.randomUUID(),
            from = UUID.randomUUID(),
            to = UUID.randomUUID(),
            filePath = None,
            fileName = None,
            contentType = None,
            role = PartyManagementDependency.PartyRole.MANAGER,
            product = PartyManagementDependency
              .RelationshipProduct(id = "product", role = "admin", createdAt = OffsetDateTime.now()),
            state = st,
            createdAt = OffsetDateTime.now(),
            updatedAt = None,
            pricingPlan = Option("pricingPlan"),
            institutionUpdate = Option(
              PartyManagementDependency.InstitutionUpdate(
                institutionType = Option("OVERRIDE_institutionType"),
                description = Option("OVERRIDE_description"),
                digitalAddress = Option("OVERRIDE_digitalAddress"),
                address = Option("OVERRIDE_address"),
                zipCode = Option("OVERRIDE_zipCode"),
                taxCode = Option("OVERRIDE_taxCode")
              )
            ),
            billing = Option(
              PartyManagementDependency
                .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
            )
          )
        )
      case _                    => Seq.empty
    }

    (mockUserRegistryService
      .getUserById(_: UUID)(_: Seq[(String, String)]))
      .expects(*, *)
      .returning(Future.successful(UserRegistryUser(id = UUID.randomUUID(), taxCode = "", name = "", surname = "")))
      .once()

    (mockSignatureService
      .createDigest(_: File))
      .expects(*)
      .returning(Future.successful("hash"))
      .once()

    (mockPartyManagementService
      .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
      .expects(externalId, *, *)
      .returning(Future.successful(institution1))
      .once()

    (mockPartyManagementService
      .retrieveRelationships(
        _: Option[UUID],
        _: Option[UUID],
        _: Seq[PartyManagementDependency.PartyRole],
        _: Seq[PartyManagementDependency.RelationshipState],
        _: Seq[String],
        _: Seq[String]
      )(_: String)(_: Seq[(String, String)]))
      .expects(None, Some(institution1.id), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *, *)
      .returning(Future.successful(PartyManagementDependency.Relationships(relationships)))
      .once()

    (mockPartyManagementService
      .createPerson(_: PartyManagementDependency.PersonSeed)(_: String)(_: Seq[(String, String)]))
      .expects(PartyManagementDependency.PersonSeed(managerId), *, *)
      .returning(Future.successful(PartyManagementDependency.Person(managerId)))
      .once()

    (mockPartyManagementService
      .createPerson(_: PartyManagementDependency.PersonSeed)(_: String)(_: Seq[(String, String)]))
      .expects(PartyManagementDependency.PersonSeed(delegateId), *, *)
      .returning(Future.successful(PartyManagementDependency.Person(delegateId)))
      .once()

    (mockPartyManagementService
      .createRelationship(_: RelationshipSeed)(_: String)(_: Seq[(String, String)]))
      .expects(*, *, *)
      .returning(Future.successful(relationship))
      .repeat(2)

    (mockFileManager
      .get(_: String)(_: String))
      .expects(*, *)
      .returning(Future.successful(new ByteArrayOutputStream()))
      .once()
    (mockPdfCreator.createContract _).expects(*, *, *, *).returning(Future.successful(file)).once()
    (mockPartyManagementService
      .createToken(_: PartyManagementDependency.Relationships, _: String, _: String, _: String)(_: String)(
        _: Seq[(String, String)]
      ))
      .expects(*, *, *, *, *, *)
      .returning(Future.successful(PartyManagementDependency.TokenText("token")))
      .once()

    val req = OnboardingInstitutionRequest(
      productId = "productId",
      productName = "productName",
      users = Seq(manager, delegate),
      institutionExternalId = externalId,
      contract = OnboardingContract("a", "b"),
      pricingPlan = Option("pricingPlan"),
      institutionUpdate = Option(
        InstitutionUpdate(
          institutionType = Option("OVERRIDE_institutionType"),
          description = Option("OVERRIDE_description"),
          digitalAddress = Option("OVERRIDE_digitalAddress"),
          address = Option("OVERRIDE_address"),
          zipCode = Option("OVERRIDE_zipCode"),
          taxCode = Option("OVERRIDE_taxCode")
        )
      ),
      billing = Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
    )

    val data = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue

    request(data, "onboarding/institution", HttpMethods.POST)
  }

  "Processing a request payload" must {

    "verify that an institution is already onboarded for a given product" in {
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val origin     = "IPA"
      val orgPartyId = UUID.randomUUID()

      val personPartyId = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "",
        digitalAddress = "",
        attributes = Seq.empty,
        taxCode = "",
        address = "",
        zipCode = "",
        origin = origin,
        institutionType = Option("PA"),
        products = Map.empty
      )

      val relationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = personPartyId,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )

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
          Some(orgPartyId),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.DELETED,
            PartyManagementDependency.RelationshipState.SUSPENDED
          ),
          Seq(defaultProduct.id),
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(Relationships(Seq(relationship))))
        .once()

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

      val response = Await.result(
        Http().singleRequest(
          HttpRequest(
            uri = s"$url/onboarding/institution/$externalId/products/${defaultProduct.id}",
            method = HttpMethods.HEAD
          )
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NoContent

    }

    "verify that an institution is not already onboarded for a given product" in {
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val origin     = "IPA"
      val orgPartyId = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "",
        digitalAddress = "",
        attributes = Seq.empty,
        taxCode = "",
        address = "",
        zipCode = "",
        origin = origin,
        institutionType = Option("PA"),
        products = Map.empty
      )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Some(orgPartyId),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.DELETED,
            PartyManagementDependency.RelationshipState.SUSPENDED
          ),
          Seq(defaultProduct.id),
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(Relationships(Seq.empty)))
        .once()

      val response = Await.result(
        Http().singleRequest(
          HttpRequest(
            uri = s"$url/onboarding/institution/$externalId/products/${defaultProduct.id}",
            method = HttpMethods.HEAD
          )
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NotFound

    }

    "retrieve a onboarding info" in {
      val externalId1 = UUID.randomUUID().toString
      val originId1   = UUID.randomUUID().toString
      val externalId2 = UUID.randomUUID().toString
      val originId2   = UUID.randomUUID().toString
      val origin      = "IPA"
      val orgPartyId1 = UUID.randomUUID()
      val orgPartyId2 = UUID.randomUUID()

      val attribute1 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name1", origin)
      val attribute2 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name2", origin)
      val attribute3 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name3", origin)
      val attribute4 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name4", origin)
      val attribute5 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name5", origin)
      val attribute6 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name6", origin)

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = uid,
          to = orgPartyId1,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )
      val relationship2 =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = uid,
          to = orgPartyId2,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val relationships = PartyManagementDependency.Relationships(items = Seq(relationship1, relationship2))

      val institution1 = PartyManagementDependency.Institution(
        id = orgPartyId1,
        externalId = externalId1,
        originId = originId1,
        description = "org1",
        digitalAddress = "digitalAddress1",
        attributes = Seq(
          PartyManagementDependency.Attribute(attribute1.origin, attribute1.code, attribute1.description),
          PartyManagementDependency.Attribute(attribute2.origin, attribute2.code, attribute2.description),
          PartyManagementDependency.Attribute(attribute3.origin, attribute3.code, attribute3.description)
        ),
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option("PA"),
        products = Map.empty
      )
      val institution2 = PartyManagementDependency.Institution(
        id = orgPartyId2,
        externalId = externalId2,
        originId = originId2,
        description = "org2",
        digitalAddress = "digitalAddress2",
        attributes = Seq(
          PartyManagementDependency.Attribute(attribute4.origin, attribute4.code, attribute4.description),
          PartyManagementDependency.Attribute(attribute5.origin, attribute5.code, attribute5.description),
          PartyManagementDependency.Attribute(attribute6.origin, attribute6.code, attribute6.description)
        ),
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option("PA"),
        products = Map(
          defaultProduct.id -> InstitutionProduct(
            product = defaultProduct.id,
            pricingPlan = Option("INSTITUTIONSAVED_pricingPlan"),
            billing = PartyManagementDependency.Billing(
              vatNumber = "INSTITUTIONSAVED_VATNUMBER",
              recipientCode = "INSTITUTIONSAVED_RECIPIENTCODE",
              publicServices = Option(true)
            )
          ),
          "p2"              -> InstitutionProduct(
            product = "p2",
            pricingPlan = Option("pricingPlan"),
            billing = PartyManagementDependency.Billing(
              vatNumber = "VATNUMBER",
              recipientCode = "RECIPIENTCODE",
              publicServices = Option(true)
            )
          )
        )
      )

      val expected = OnboardingInfo(
        userId = Option(uid),
        institutions = Seq(
          OnboardingData(
            id = institution1.id,
            externalId = institution1.externalId,
            originId = institution1.originId,
            origin = origin,
            institutionType = institution1.institutionType,
            description = institution1.description,
            taxCode = institution1.taxCode,
            digitalAddress = institution1.digitalAddress,
            address = institution1.address,
            zipCode = institution1.zipCode,
            state = relationshipStateToApi(relationship1.state),
            role = roleToApi(relationship1.role),
            productInfo = defaultProductInfo,
            attributes = Seq(attribute1, attribute2, attribute3),
            billing = Option.empty,
            pricingPlan = Option.empty
          ),
          OnboardingData(
            id = institution2.id,
            externalId = institution2.externalId,
            originId = institution2.originId,
            origin = institution2.origin,
            institutionType = institution2.institutionType,
            description = institution2.description,
            taxCode = institution2.taxCode,
            digitalAddress = institution2.digitalAddress,
            address = institution2.address,
            zipCode = institution2.zipCode,
            state = relationshipStateToApi(relationship2.state),
            role = roleToApi(relationship2.role),
            productInfo = defaultProductInfo,
            attributes = Seq(
              partyprocess.model.Attribute(attribute4.origin, attribute4.code, attribute4.description),
              partyprocess.model.Attribute(attribute5.origin, attribute5.code, attribute5.description),
              partyprocess.model.Attribute(attribute6.origin, attribute6.code, attribute6.description)
            ),
            billing = Option(
              Billing(
                vatNumber = "INSTITUTIONSAVED_VATNUMBER",
                recipientCode = "INSTITUTIONSAVED_RECIPIENTCODE",
                publicServices = Option(true)
              )
            ),
            pricingPlan = Option("INSTITUTIONSAVED_pricingPlan")
          )
        )
      )

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
          Some(uid),
          None,
          Seq.empty,
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId1, *, *)
        .returning(Future.successful(institution1))
        .once()

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId2, *, *)
        .returning(Future.successful(institution2))
        .once()

      val response = Await.result(
        Http().singleRequest(HttpRequest(uri = s"$url/onboarding/info", method = HttpMethods.GET)),
        Duration.Inf
      )

      val body = Unmarshal(response.entity).to[OnboardingInfo].futureValue

      response.status mustBe StatusCodes.OK
      body mustBe expected

    }

    def configureOnboardingInfoTest(
      institution: PartyManagementDependency.Institution,
      attribute1: Attribute,
      attribute2: Attribute,
      attribute3: Attribute,
      pricingPlan: Option[String],
      billing: Option[Billing]
    ): OnboardingInfo = {
      val relationship1 =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = uid,
          to = institution.id,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )

      val relationships = PartyManagementDependency.Relationships(items = Seq(relationship1))

      val expected = OnboardingInfo(
        userId = Option(uid),
        institutions = Seq(
          OnboardingData(
            id = institution.id,
            externalId = institution.externalId,
            originId = institution.originId,
            origin = institution.origin,
            institutionType = institution.institutionType,
            description = institution.description,
            taxCode = institution.taxCode,
            digitalAddress = institution.digitalAddress,
            address = institution.address,
            zipCode = institution.zipCode,
            state = relationshipStateToApi(relationship1.state),
            role = roleToApi(relationship1.role),
            productInfo = defaultProductInfo,
            attributes = Seq(attribute1, attribute2, attribute3),
            billing = billing,
            pricingPlan = pricingPlan
          )
        )
      )

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
          Some(uid),
          Some(institution.id),
          Seq.empty,
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(institution.id, *, *)
        .returning(Future.successful(institution))
        .once()

      expected
    }

    "retrieve an onboarding info with institutionId filter" in {
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val origin     = "IPA"
      val orgPartyId = UUID.randomUUID()
      val attribute1 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name1", "origin")
      val attribute2 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name2", "origin")
      val attribute3 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name3", "origin")

      val pricingPlan = Option("INSTITUTIONSAVED_pricingPlan")
      val billing     = Billing(
        vatNumber = "INSTITUTIONSAVED_VATNUMBER",
        recipientCode = "INSTITUTIONSAVED_RECIPIENTCODE",
        publicServices = Option(true)
      )

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "org1",
        digitalAddress = "digitalAddress1",
        attributes = Seq(
          PartyManagementDependency.Attribute(attribute1.origin, attribute1.code, attribute1.description),
          PartyManagementDependency.Attribute(attribute2.origin, attribute2.code, attribute2.description),
          PartyManagementDependency.Attribute(attribute3.origin, attribute3.code, attribute3.description)
        ),
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option("PA"),
        products = Map(
          defaultProduct.id -> InstitutionProduct(
            product = defaultProduct.id,
            pricingPlan = pricingPlan,
            billing = PartyManagementDependency.Billing(
              vatNumber = billing.vatNumber,
              recipientCode = billing.recipientCode,
              publicServices = billing.publicServices
            )
          )
        )
      )
      val expected    =
        configureOnboardingInfoTest(institution, attribute1, attribute2, attribute3, pricingPlan, Option(billing))

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId, *, *)
        .returning(Future.successful(institution))
        .once()

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/onboarding/info?institutionId=$orgPartyId", method = HttpMethods.GET))
          .futureValue

      val body = Unmarshal(response.entity).to[OnboardingInfo].futureValue

      response.status mustBe StatusCodes.OK
      body mustBe expected
    }

    "retrieve an onboarding info with institution externalId filter" in {
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val origin     = "IPA"
      val orgPartyId = UUID.randomUUID()
      val attribute1 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name1", "origin")
      val attribute2 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name2", "origin")
      val attribute3 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name3", "origin")

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "org1",
        digitalAddress = "digitalAddress1",
        attributes = Seq(
          PartyManagementDependency.Attribute(attribute1.origin, attribute1.code, attribute1.description),
          PartyManagementDependency.Attribute(attribute2.origin, attribute2.code, attribute2.description),
          PartyManagementDependency.Attribute(attribute3.origin, attribute3.code, attribute3.description)
        ),
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option("PA"),
        products = Map(
          "P2" -> InstitutionProduct(
            product = "P2",
            pricingPlan = Option("PricingPlan"),
            billing = PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(false))
          )
        )
      )
      val expected    = configureOnboardingInfoTest(institution, attribute1, attribute2, attribute3, None, None)

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(uri = s"$url/onboarding/info?institutionExternalId=$externalId", method = HttpMethods.GET)
          )
          .futureValue

      val body = Unmarshal(response.entity).to[OnboardingInfo].futureValue

      response.status mustBe StatusCodes.OK
      body mustBe expected
    }

    "retrieve an onboarding info with states filter" in {
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val origin     = "IPA"
      val orgPartyId = UUID.randomUUID()
      val attribute1 = PartyManagementDependency.Attribute(UUID.randomUUID().toString, "name1", "origin")
      val attribute2 = PartyManagementDependency.Attribute(UUID.randomUUID().toString, "name2", "origin")
      val attribute3 = PartyManagementDependency.Attribute(UUID.randomUUID().toString, "name3", "origin")

      val relationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = uid,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )

      val relationships = PartyManagementDependency.Relationships(items = Seq(relationship))

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "org1",
        digitalAddress = "digitalAddress1",
        attributes = Seq(attribute1, attribute2, attribute3),
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option("PA"),
        products = Map.empty
      )

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
          Some(uid),
          None,
          Seq.empty,
          Seq(PartyManagementDependency.RelationshipState.SUSPENDED),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId, *, *)
        .returning(Future.successful(institution))
        .once()

      val expected = OnboardingInfo(
        userId = Option(uid),
        institutions = Seq(
          OnboardingData(
            id = institution.id,
            externalId = institution.externalId,
            originId = institution.originId,
            origin = institution.origin,
            institutionType = institution.institutionType,
            description = institution.description,
            taxCode = institution.taxCode,
            digitalAddress = institution.digitalAddress,
            address = institution.address,
            zipCode = institution.zipCode,
            state = relationshipStateToApi(relationship.state),
            role = roleToApi(relationship.role),
            productInfo = defaultProductInfo,
            attributes = Seq(
              partyprocess.model.Attribute(attribute1.origin, attribute1.code, attribute1.description),
              partyprocess.model.Attribute(attribute2.origin, attribute2.code, attribute2.description),
              partyprocess.model.Attribute(attribute3.origin, attribute3.code, attribute3.description)
            ),
            billing = None,
            pricingPlan = None
          )
        )
      )

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/onboarding/info?states=SUSPENDED", method = HttpMethods.GET))
          .futureValue

      val body = Unmarshal(response.entity).to[OnboardingInfo].futureValue

      response.status mustBe StatusCodes.OK
      body mustBe expected

    }

    "fail the onboarding info retrieval when the institution id filter contains an invalid string" in {

      val response =
        Http()
          .singleRequest(
            HttpRequest(uri = s"$url/onboarding/info?externalId=wrong-external-id", method = HttpMethods.GET)
          )
          .futureValue

      response.status mustBe StatusCodes.BadRequest

    }

    "not onboard an institution if it doesn't exists" in {
      val externalId = UUID.randomUUID.toString
      (mockUserRegistryService
        .getUserById(_: UUID)(_: Seq[(String, String)]))
        .expects(*, *)
        .returning(Future.successful(UserRegistryUser(id = UUID.randomUUID(), taxCode = "", name = "", surname = "")))
        .once()

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.failed(ResourceNotFoundError(externalId)))
        .once()

      val req = OnboardingInstitutionRequest(
        productId = "productId",
        productName = "productName",
        users = Seq.empty,
        institutionExternalId = externalId,
        contract = OnboardingContract("a", "b"),
        billing = Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE")
      )

      val data = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue

      val response = request(data, "onboarding/institution", HttpMethods.POST)

      response.status mustBe StatusCodes.NotFound

    }

    "not onboard an institution if is already onboarded (MANAGER ACTIVE)" in {

      val response =
        performOnboardingRequestByRoleForFailure(PartyManagementDependency.RelationshipState.ACTIVE)

      response.status mustBe StatusCodes.BadRequest

    }

    "not onboard an institution if is already onboarded (MANAGER SUSPENDED)" in {

      val response =
        performOnboardingRequestByRoleForFailure(PartyManagementDependency.RelationshipState.SUSPENDED)

      response.status mustBe StatusCodes.BadRequest

    }

    "not onboard an institution if is already onboarded (MANAGER DELETED)" in {

      val response =
        performOnboardingRequestByRoleForFailure(PartyManagementDependency.RelationshipState.DELETED)

      response.status mustBe StatusCodes.BadRequest

    }

    "onboard an institution with a legal and a delegate" in {

      val response = performOnboardingRequest(Some(PartyManagementDependency.RelationshipState.PENDING), None)

      response.status mustBe StatusCodes.NoContent

    }

    "onboard an institution with a legal and a delegate (MANAGER PENDING)" in {

      val response = performOnboardingRequest(Some(PartyManagementDependency.RelationshipState.PENDING), None)

      response.status mustBe StatusCodes.NoContent

    }

    "onboard an institution with a legal and a delegate (MANAGER REJECTED)" in {

      val response =
        performOnboardingRequest(Some(PartyManagementDependency.RelationshipState.REJECTED), None)

      response.status mustBe StatusCodes.NoContent

    }

    "onboard an institution with a legal and a delegate (MANAGER ACTIVE for a different product)" in {

      val response =
        performOnboardingRequest(Some(PartyManagementDependency.RelationshipState.ACTIVE), Some("product1"))

      response.status mustBe StatusCodes.NoContent

    }

    "onboard an institution with a legal and a delegate (MANAGER SUSPENDED for a different product)" in {

      val response =
        performOnboardingRequest(Some(PartyManagementDependency.RelationshipState.SUSPENDED), Some("product1"))

      response.status mustBe StatusCodes.NoContent

    }

    "not create operators if does not exists any active legal for a given institution" in {
      val orgPartyId  = UUID.randomUUID()
      val externalId  = UUID.randomUUID().toString
      val originId    = UUID.randomUUID().toString
      val origin      = "IPA"
      val taxCode1    = "operator1TaxCode"
      val taxCode2    = "operator2TaxCode"
      val operatorId1 = UUID.randomUUID()
      val operatorId2 = UUID.randomUUID()

      val operator1 = User(
        id = operatorId1,
        name = "operator1",
        surname = "operator1",
        taxCode = taxCode1,
        role = OPERATOR,
        email = Some("operat@ore.it"),
        productRole = "admin"
      )
      val operator2 = User(
        id = operatorId2,
        name = "operator2",
        surname = "operator2",
        taxCode = taxCode2,
        role = PartyRole.OPERATOR,
        email = None,
        productRole = "security"
      )

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId, *, *)
        .returning(
          Future.successful(
            PartyManagementDependency.Institution(
              id = orgPartyId,
              externalId = externalId,
              originId = originId,
              description = "test",
              digitalAddress = "big@fish.it",
              attributes = Seq.empty,
              taxCode = "123",
              address = "address",
              zipCode = "zipCode",
              origin = origin,
              institutionType = Option("PA"),
              products = Map.empty
            )
          )
        )
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(*, *, *, *, *, *, *, *)
        .returning(Future.successful(PartyManagementDependency.Relationships(Seq.empty)))

      val req =
        OnboardingUsersRequest(productId = "productId", users = Seq(operator1, operator2), institutionId = orgPartyId)

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/operators", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest

    }

    "create operators if exists a legal active for a given institution" in {

      val taxCode1   = "operator1TaxCode"
      val taxCode2   = "operator2TaxCode"
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val origin     = "IPA"
      val orgPartyId = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "org1",
        digitalAddress = "digitalAddress1",
        attributes = Seq.empty,
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option("PA"),
        products = Map.empty
      )

      val relationships =
        PartyManagementDependency.Relationships(
          Seq(
            PartyManagementDependency.Relationship(
              id = UUID.randomUUID(),
              from = uid,
              to = orgPartyId,
              role = PartyManagementDependency.PartyRole.MANAGER,
              product = defaultProduct,
              state = PartyManagementDependency.RelationshipState.ACTIVE,
              createdAt = defaultRelationshipTimestamp,
              updatedAt = None,
              pricingPlan = Option("pricingPlan"),
              institutionUpdate = Option(
                PartyManagementDependency.InstitutionUpdate(
                  institutionType = Option("OVERRIDE_institutionType"),
                  description = Option("OVERRIDE_description"),
                  digitalAddress = Option("OVERRIDE_digitalAddress"),
                  address = Option("OVERRIDE_address"),
                  zipCode = Option("OVERRIDE_zipCode"),
                  taxCode = Option("OVERRIDE_taxCode")
                )
              ),
              billing = Option(
                PartyManagementDependency
                  .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
              )
            )
          )
        )

      val operatorId1 = UUID.randomUUID()
      val operatorId2 = UUID.randomUUID()

      val operator1 = User(
        id = operatorId1,
        name = "operator1",
        surname = "operator1",
        taxCode = taxCode1,
        role = PartyRole.OPERATOR,
        email = Some("mario@ros.si"),
        productRole = "security"
      )
      val operator2 = User(
        id = operatorId2,
        name = "operator2",
        surname = "operator2",
        taxCode = taxCode2,
        role = PartyRole.OPERATOR,
        email = Some("operator2@email.it"),
        productRole = "security"
      )

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId, *, *)
        .returning(Future.successful(institution))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(None, Some(orgPartyId), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *, *)
        .returning(Future.successful(relationships))

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String)(_: Seq[(String, String)]))
        .expects(PartyManagementDependency.PersonSeed(operatorId1), *, *)
        .returning(Future.successful(PartyManagementDependency.Person(operatorId1)))
        .once()

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String)(_: Seq[(String, String)]))
        .expects(PartyManagementDependency.PersonSeed(operatorId2), *, *)
        .returning(Future.successful(PartyManagementDependency.Person(operatorId2)))
        .once()

      (mockPartyManagementService
        .createRelationship(_: RelationshipSeed)(_: String)(_: Seq[(String, String)]))
        .expects(*, *, *)
        .returning(Future.successful(relationship))
        .repeat(2)

      val req =
        OnboardingUsersRequest(productId = "productId", users = Seq(operator1, operator2), institutionId = orgPartyId)

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/operators", HttpMethods.POST)

      response.status mustBe StatusCodes.OK

    }

    "not create subdelegates if does not exists any active legal for a given institution" in {

      val orgPartyID  = UUID.randomUUID()
      val externalId  = UUID.randomUUID().toString
      val originId    = UUID.randomUUID().toString
      val origin      = "IPA"
      val taxCode1    = "subdelegate1TaxCode"
      val taxCode2    = "subdelegate2TaxCode"
      val operatorId1 = UUID.randomUUID()
      val operatorId2 = UUID.randomUUID()

      val subdelegate1 = User(
        id = operatorId1,
        name = "subdelegate1",
        surname = "subdelegate1",
        taxCode = taxCode1,
        role = SUB_DELEGATE,
        email = Some("subdel@ore.it"),
        productRole = "admin"
      )
      val subdelegate2 = User(
        id = operatorId2,
        name = "subdelegate2",
        surname = "subdelegate2",
        taxCode = taxCode2,
        role = PartyRole.SUB_DELEGATE,
        email = None,
        productRole = "security"
      )

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyID, *, *)
        .returning(
          Future.successful(
            PartyManagementDependency.Institution(
              id = orgPartyID,
              externalId = externalId,
              originId = originId,
              description = "test",
              digitalAddress = "big@fish.it",
              attributes = Seq.empty,
              taxCode = "123",
              address = "address",
              zipCode = "zipCode",
              origin = origin,
              institutionType = Option("PA"),
              products = Map.empty
            )
          )
        )
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(*, *, *, *, *, *, *, *)
        .returning(Future.successful(PartyManagementDependency.Relationships(Seq.empty)))

      val req = OnboardingUsersRequest(
        productId = "productId",
        users = Seq(subdelegate1, subdelegate2),
        institutionId = orgPartyID
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/subdelegates", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest

    }

    "create subdelegates if exists a legal active for a given institution" in {

      val taxCode1       = "subdelegate1TaxCode"
      val taxCode2       = "subdelegate2TaxCode"
      val externalId     = UUID.randomUUID().toString
      val originId       = UUID.randomUUID().toString
      val origin         = "IPA"
      val orgPartyId     = UUID.randomUUID()
      val personPartyId1 = "bf80fac0-2775-4646-8fcf-28e083751900"

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "org1",
        digitalAddress = "digitalAddress1",
        attributes = Seq.empty,
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option("PA"),
        products = Map.empty
      )

      val relationships =
        PartyManagementDependency.Relationships(
          Seq(
            PartyManagementDependency.Relationship(
              id = UUID.randomUUID(),
              from = UUID.fromString(personPartyId1),
              to = orgPartyId,
              role = PartyManagementDependency.PartyRole.MANAGER,
              product = defaultProduct,
              state = PartyManagementDependency.RelationshipState.ACTIVE,
              createdAt = defaultRelationshipTimestamp,
              updatedAt = None,
              pricingPlan = Option("pricingPlan"),
              institutionUpdate = Option(
                PartyManagementDependency.InstitutionUpdate(
                  institutionType = Option("OVERRIDE_institutionType"),
                  description = Option("OVERRIDE_description"),
                  digitalAddress = Option("OVERRIDE_digitalAddress"),
                  address = Option("OVERRIDE_address"),
                  zipCode = Option("OVERRIDE_zipCode"),
                  taxCode = Option("OVERRIDE_taxCode")
                )
              ),
              billing = Option(
                PartyManagementDependency
                  .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
              )
            )
          )
        )

      val subdelegateId1 = UUID.randomUUID()
      val subdelegateId2 = UUID.randomUUID()

      val subdelegate1 = User(
        id = subdelegateId1,
        name = "subdelegate1",
        surname = "subdelegate1",
        taxCode = taxCode1,
        role = PartyRole.SUB_DELEGATE,
        email = Some("mario@ros.si"),
        productRole = "admin"
      )
      val subdelegate2 = User(
        id = subdelegateId2,
        name = "subdelegate2",
        surname = "subdelegate2",
        taxCode = taxCode2,
        role = PartyRole.SUB_DELEGATE,
        email = Some("subdelegate2@email.it"),
        productRole = "admin"
      )

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId, *, *)
        .returning(Future.successful(institution))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(None, Some(orgPartyId), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *, *)
        .returning(Future.successful(relationships))

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String)(_: Seq[(String, String)]))
        .expects(PartyManagementDependency.PersonSeed(subdelegateId1), *, *)
        .returning(Future.successful(PartyManagementDependency.Person(subdelegateId1)))
        .once()

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String)(_: Seq[(String, String)]))
        .expects(PartyManagementDependency.PersonSeed(subdelegateId2), *, *)
        .returning(Future.successful(PartyManagementDependency.Person(subdelegateId2)))
        .once()

      (mockPartyManagementService
        .createRelationship(_: RelationshipSeed)(_: String)(_: Seq[(String, String)]))
        .expects(*, *, *)
        .returning(Future.successful(relationship))
        .repeat(2)

      val req = OnboardingUsersRequest(
        productId = "productId",
        users = Seq(subdelegate1, subdelegate2),
        institutionId = orgPartyId
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/subdelegates", HttpMethods.POST)

      response.status mustBe StatusCodes.OK

    }

    "confirm token" in {

      val tokenId: UUID                = UUID.randomUUID()
      val partyIdManager: UUID         = UUID.randomUUID()
      val relationshipIdManager: UUID  = UUID.randomUUID()
      val managerTaxCode: String       = "TINIT-MANAGER"
      val partyIdDelegate: UUID        = UUID.randomUUID()
      val relationshipIdDelegate: UUID = UUID.randomUUID()
      val delegateTaxCode: String      = "TINIT-DELEGATE"

      val token: TokenInfo =
        TokenInfo(
          id = tokenId,
          checksum = "6ddee820d06383127ef029f571285339",
          legals = Seq(
            RelationshipBinding(partyId = partyIdManager, relationshipId = relationshipIdManager),
            RelationshipBinding(partyId = partyIdDelegate, relationshipId = relationshipIdDelegate)
          )
        )
      val path             = Paths.get("src/test/resources/contract-test-01.pdf")

      (mockSignatureService
        .createDocumentValidator(_: Array[Byte]))
        .expects(*)
        .returning(Future.successful(mockSignedDocumentValidator))
        .once()

      (mockSignatureValidationService
        .isDocumentSigned(_: SignedDocumentValidator))
        .expects(*)
        .returning(().validNel[SignatureValidationError])
        .once()

      (mockSignatureValidationService
        .verifySignature(_: SignedDocumentValidator))
        .expects(*)
        .returning(().validNel[SignatureValidationError])
        .once()

      (mockSignatureValidationService
        .verifyDigest(_: SignedDocumentValidator, _: String))
        .expects(*, *)
        .returning(().validNel[SignatureValidationError])
        .once()

      (mockSignatureValidationService
        .verifyManagerTaxCode(_: SignedDocumentValidator, _: Seq[UserRegistryUser]))
        .expects(*, *)
        .returning(().validNel[SignatureValidationError])
        .once()

      (mockPartyManagementService
        .verifyToken(_: UUID)(_: Seq[(String, String)]))
        .expects(tokenId, *)
        .returning(Future.successful(token))

      (mockUserRegistryService
        .getUserById(_: UUID)(_: Seq[(String, String)]))
        .expects(partyIdManager, *)
        .returning(
          Future.successful(UserRegistryUser(id = partyIdManager, taxCode = managerTaxCode, name = "", surname = ""))
        )

      (mockUserRegistryService
        .getUserById(_: UUID)(_: Seq[(String, String)]))
        .expects(partyIdDelegate, *)
        .returning(
          Future.successful(UserRegistryUser(id = partyIdDelegate, taxCode = delegateTaxCode, name = "", surname = ""))
        )

      (mockPartyManagementService
        .consumeToken(_: UUID, _: (FileInfo, File))(_: Seq[(String, String)]))
        .expects(tokenId, *, *)
        .returning(Future.successful(()))

      val formData =
        Multipart.FormData.fromPath(name = "contract", MediaTypes.`application/octet-stream`, file = path, 100000)

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/onboarding/complete/${tokenId.toString}",
              method = HttpMethods.POST,
              entity = formData.toEntity
            )
          )
          .futureValue

      response.status mustBe StatusCodes.NoContent

    }

    "fail trying to confirm a token on already confirmed" in {

      val tokenId: UUID = UUID.randomUUID()

      val path = Paths.get("src/test/resources/contract-test-01.pdf")

      (mockPartyManagementService
        .verifyToken(_: UUID)(_: Seq[(String, String)]))
        .expects(tokenId, *)
        .returning(Future.failed(ResourceConflictError(tokenId.toString)))

      val formData =
        Multipart.FormData.fromPath(name = "contract", MediaTypes.`application/octet-stream`, file = path, 100000)

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/onboarding/complete/${tokenId.toString}",
              method = HttpMethods.POST,
              entity = formData.toEntity
            )
          )
          .futureValue

      response.status mustBe StatusCodes.Conflict

    }

    "delete token" in {

      val tokenId: UUID                = UUID.randomUUID()
      val relationshipIdManager: UUID  = UUID.randomUUID()
      val partyIdManager: UUID         = UUID.randomUUID()
      val relationshipIdDelegate: UUID = UUID.randomUUID()
      val partyIdDelegate: UUID        = UUID.randomUUID()

      val token: TokenInfo =
        TokenInfo(
          id = tokenId,
          checksum = "6ddee820d06383127ef029f571285339",
          legals = Seq(
            RelationshipBinding(partyId = partyIdManager, relationshipId = relationshipIdManager),
            RelationshipBinding(partyId = partyIdDelegate, relationshipId = relationshipIdDelegate)
          )
        )

      (mockPartyManagementService
        .verifyToken(_: UUID)(_: Seq[(String, String)]))
        .expects(tokenId, *)
        .returning(Future.successful(token))

      (mockPartyManagementService
        .invalidateToken(_: UUID)(_: Seq[(String, String)]))
        .expects(tokenId, *)
        .returning(Future.successful(()))

      val response =
        Http()
          .singleRequest(
            HttpRequest(uri = s"$url/onboarding/complete/${tokenId.toString}", method = HttpMethods.DELETE)
          )
          .futureValue

      response.status mustBe StatusCodes.NoContent

    }

    "fail trying to delete a token on already confirmed" in {

      val tokenId: UUID = UUID.randomUUID()

      (mockPartyManagementService
        .verifyToken(_: UUID)(_: Seq[(String, String)]))
        .expects(tokenId, *)
        .returning(Future.failed(ResourceConflictError(tokenId.toString)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(uri = s"$url/onboarding/complete/${tokenId.toString}", method = HttpMethods.DELETE)
          )
          .futureValue

      response.status mustBe StatusCodes.Conflict

    }

    "retrieve all the relationships of a specific institution when requested by the admin" in {

      val userId1    = UUID.randomUUID()
      val userId2    = UUID.randomUUID()
      val userId3    = UUID.randomUUID()
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val orgPartyId = UUID.randomUUID()

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId1     = UUID.randomUUID()
      val relationshipId2     = UUID.randomUUID()
      val relationshipId3     = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
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
        products = Map.empty
      )

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = uid,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId1,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )

      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = userId2,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = defaultProduct.copy(role = "security"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = defaultProduct.copy(role = "api"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships = PartyManagementDependency.Relationships(items = Seq(adminRelationship))

      val relationships =
        PartyManagementDependency.Relationships(items =
          Seq(adminRelationship, relationship1, relationship2, relationship3)
        )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Some(uid),
          Some(orgPartyId),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(adminRelationships))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(None, Some(orgPartyId), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *, *)
        .returning(Future.successful(relationships))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(uri = s"$url/external/institutions/$externalId/relationships", method = HttpMethods.GET)
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only (
        RelationshipInfo(
          id = adminRelationshipId,
          from = uid,
          to = orgPartyId,
          role = PartyRole.DELEGATE,
          product = defaultProductInfo,
          state = ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        ),
        RelationshipInfo(
          id = relationshipId1,
          from = userId1,
          to = orgPartyId,
          role = PartyRole.MANAGER,
          product = defaultProductInfo,
          state = RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing =
            Option(Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true)))
        ),
        RelationshipInfo(
          id = relationshipId2,
          from = userId2,
          to = orgPartyId,
          role = PartyRole.OPERATOR,
          product = defaultProductInfo.copy(role = "security"),
          state = RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        ),
        RelationshipInfo(
          id = relationshipId3,
          from = userId3,
          to = orgPartyId,
          role = PartyRole.OPERATOR,
          product = defaultProductInfo.copy(role = "api"),
          state = RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )
      )

    }

    "retrieve all the relationships of a specific institution with filter by productRole when requested by the admin" in {
      val userId1    = UUID.randomUUID()
      val userId2    = UUID.randomUUID()
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val orgPartyId = UUID.randomUUID()

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId1     = UUID.randomUUID()
      val relationshipId2     = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
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
        products = Map.empty
      )

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = uid,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId1,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = defaultProduct.copy(role = "security"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = userId2,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = defaultProduct.copy(role = "api"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships =
        PartyManagementDependency.Relationships(items = Seq(adminRelationship))

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship1, relationship2))

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Some(uid),
          Some(orgPartyId),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(adminRelationships))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(None, Some(orgPartyId), Seq.empty, Seq.empty, Seq.empty, Seq("security", "api"), *, *)
        .returning(Future.successful(relationships))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/relationships?productRoles=security,api",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only (RelationshipInfo(
        id = relationshipId1,
        from = userId1,
        to = orgPartyId,
        role = PartyRole.OPERATOR,
        product = defaultProductInfo.copy(role = "security"),
        state = RelationshipState.ACTIVE,
        createdAt = defaultRelationshipTimestamp,
        updatedAt = None
      ),
      RelationshipInfo(
        id = relationshipId2,
        from = userId2,
        to = orgPartyId,
        role = PartyRole.OPERATOR,
        product = defaultProductInfo.copy(role = "api"),
        state = RelationshipState.ACTIVE,
        createdAt = defaultRelationshipTimestamp,
        updatedAt = None
      ))

    }

    "retrieve only the relationships of a specific role when the current user is not an admin" in {
      val adminIdentifier = UUID.randomUUID()
      val userId1         = UUID.randomUUID()
      val userId2         = uid
      val userId3         = UUID.randomUUID()
      val externalId      = UUID.randomUUID().toString
      val originId        = UUID.randomUUID().toString
      val orgPartyId      = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
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
        products = Map.empty
      )

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId1     = UUID.randomUUID()
      val relationshipId2     = UUID.randomUUID()
      val relationshipId3     = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )
      val relationship1     =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId1,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )

      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = userId2,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = defaultProduct.copy(role = "security"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = defaultProduct.copy(role = "api"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val relationships =
        PartyManagementDependency.Relationships(items =
          Seq(adminRelationship, relationship1, relationship2, relationship3)
        )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Some(userId2),
          Some(orgPartyId),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq.empty)))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(None, Some(orgPartyId), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *, *)
        .returning(Future.successful(relationships))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(uri = s"$url/external/institutions/$externalId/relationships", method = HttpMethods.GET)
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only
        RelationshipInfo(
          id = relationshipId2,
          from = userId2,
          to = orgPartyId,
          role = PartyRole.OPERATOR,
          product = defaultProductInfo.copy(role = "security"),
          state = RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )
    }

    "retrieve all the relationships of a specific institution with filter by productRole and products" in {
      val adminIdentifier = UUID.randomUUID()
      val userId1         = uid
      val externalId      = UUID.randomUUID().toString
      val originId        = UUID.randomUUID().toString
      val orgPartyId      = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
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
        products = Map.empty
      )

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId1     = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId1,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = defaultProduct.copy(id = "Interop", role = "security"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship1))

      val adminRelationships = PartyManagementDependency.Relationships(items = Seq(adminRelationship))

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Some(userId1),
          Some(orgPartyId),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(adminRelationships))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(None, Some(orgPartyId), Seq.empty, Seq.empty, Seq("Interop"), Seq("security", "api"), *, *)
        .returning(Future.successful(relationships))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/relationships?productRoles=security,api&products=Interop",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only RelationshipInfo(
        id = relationshipId1,
        from = userId1,
        to = orgPartyId,
        role = PartyRole.OPERATOR,
        product = defaultProductInfo.copy(id = "Interop", role = "security"),
        state = RelationshipState.ACTIVE,
        createdAt = defaultRelationshipTimestamp,
        updatedAt = None
      )
    }

    "retrieve all the relationships of a specific institution with filter by states requested by admin" in {
      val adminIdentifier = uid
      val externalId      = UUID.randomUUID().toString
      val originId        = UUID.randomUUID().toString
      val orgPartyId      = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
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
        products = Map.empty
      )

      val relationshipId1 = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = adminIdentifier,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )

      val adminRelationships =
        PartyManagementDependency.Relationships(items = Seq(adminRelationship))
      val relationships      =
        PartyManagementDependency.Relationships(items = Seq(adminRelationship))

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Some(adminIdentifier),
          Some(orgPartyId),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(adminRelationships))
        .once()

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
          Some(orgPartyId),
          Seq.empty,
          Seq(PartyManagementDependency.RelationshipState.ACTIVE),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/relationships?states=ACTIVE",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only RelationshipInfo(
        id = relationshipId1,
        from = adminIdentifier,
        to = orgPartyId,
        role = PartyRole.MANAGER,
        product = defaultProductInfo,
        state = RelationshipState.ACTIVE,
        createdAt = defaultRelationshipTimestamp,
        updatedAt = None,
        pricingPlan = Option("pricingPlan"),
        institutionUpdate = Option(
          InstitutionUpdate(
            institutionType = Option("OVERRIDE_institutionType"),
            description = Option("OVERRIDE_description"),
            digitalAddress = Option("OVERRIDE_digitalAddress"),
            address = Option("OVERRIDE_address"),
            zipCode = Option("OVERRIDE_zipCode"),
            taxCode = Option("OVERRIDE_taxCode")
          )
        ),
        billing =
          Option(Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true)))
      )
    }

    "retrieve all the relationships of a specific institution with filter by roles when requested by admin" in {
      val adminIdentifier = uid
      val userId2         = UUID.randomUUID()
      val externalId      = UUID.randomUUID().toString
      val originId        = UUID.randomUUID().toString
      val orgPartyId      = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
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
        products = Map.empty
      )

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId2     = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )
      val relationship2     =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = userId2,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships = PartyManagementDependency.Relationships(items = Seq(adminRelationship))
      val relationships      = PartyManagementDependency.Relationships(items = Seq(relationship2))

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Some(adminIdentifier),
          Some(orgPartyId),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(adminRelationships))
        .once()

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
          Some(orgPartyId),
          Seq(PartyManagementDependency.PartyRole.DELEGATE),
          Seq.empty,
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/relationships?roles=DELEGATE",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only RelationshipInfo(
        id = relationshipId2,
        from = userId2,
        to = orgPartyId,
        role = PartyRole.DELEGATE,
        product = defaultProductInfo,
        state = RelationshipState.PENDING,
        createdAt = defaultRelationshipTimestamp,
        updatedAt = None
      )
    }

    "retrieve all the relationships of a specific institution using all filters" in {
      val adminIdentifier = uid
      val userId3         = UUID.randomUUID()
      val userId4         = UUID.randomUUID()
      val externalId      = UUID.randomUUID().toString
      val originId        = UUID.randomUUID().toString
      val orgPartyId      = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
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
        products = Map.empty
      )

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId3     = UUID.randomUUID()
      val relationshipId4     = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = defaultProduct.copy(id = "Interop", role = "security"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val relationship4 =
        PartyManagementDependency.Relationship(
          id = relationshipId4,
          from = userId4,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = defaultProduct.copy(id = "Interop", role = "api"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships =
        PartyManagementDependency.Relationships(items = Seq(adminRelationship))

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship3, relationship4))

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Some(adminIdentifier),
          Some(orgPartyId),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(adminRelationships))
        .once()

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
          Some(orgPartyId),
          Seq(PartyManagementDependency.PartyRole.OPERATOR),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE),
          Seq("Interop"),
          Seq("security", "api"),
          *,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri =
                s"$url/external/institutions/$externalId/relationships?productRoles=security,api&products=Interop&roles=OPERATOR&states=ACTIVE",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body mustBe Seq(
        RelationshipInfo(
          id = relationshipId3,
          from = userId3,
          to = orgPartyId,
          role = PartyRole.OPERATOR,
          product = defaultProductInfo.copy(id = "Interop", role = "security"),
          state = RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        ),
        RelationshipInfo(
          id = relationshipId4,
          from = userId4,
          to = orgPartyId,
          role = PartyRole.OPERATOR,
          product = defaultProductInfo.copy(id = "Interop", role = "api"),
          state = RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )
      )
    }

    "retrieve all the relationships of a specific institution with all filter when no intersection occurs" in {
      val adminIdentifier = uid
      val externalId      = UUID.randomUUID().toString
      val originId        = UUID.randomUUID().toString
      val orgPartyId      = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
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
        products = Map.empty
      )

      val adminRelationshipId = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships = PartyManagementDependency.Relationships(items = Seq(adminRelationship))
      val relationships      = PartyManagementDependency.Relationships(items = Seq())

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Some(adminIdentifier),
          Some(orgPartyId),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(adminRelationships))
        .once()

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
          Some(orgPartyId),
          Seq(PartyManagementDependency.PartyRole.OPERATOR),
          Seq(PartyManagementDependency.RelationshipState.PENDING),
          Seq("Interop", "Interop"),
          Seq("security", "api"),
          *,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri =
                s"$url/external/institutions/$externalId/relationships?productRoles=security,api&products=Interop,Interop&roles=OPERATOR&states=PENDING",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body mustBe empty
    }
  }

  "Relationship activation" must {
    "succeed" in {

      val userId         = UUID.randomUUID()
      val orgPartyId     = UUID.randomUUID()
      val product        = "product"
      val productRole    = "productRole"
      val relationshipId = UUID.randomUUID()

      val relationship =
        PartyManagementDependency.Relationship(
          id = relationshipId,
          from = userId,
          to = orgPartyId,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = PartyManagementDependency
            .RelationshipProduct(id = product, role = productRole, createdAt = defaultProductTimestamp),
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(relationshipId, *, *)
        .returning(Future.successful(relationship))

      (mockPartyManagementService
        .activateRelationship(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(relationshipId, *, *)
        .returning(Future.successful(()))

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/relationships/$relationshipId/activate", method = HttpMethods.POST))
          .futureValue

      response.status mustBe StatusCodes.NoContent

    }

    "fail if relationship is not Suspended" in {

      val fromId         = UUID.randomUUID()
      val product        = "product"
      val productRole    = "productRole"
      val relationshipId = UUID.randomUUID()

      val relationships =
        PartyManagementDependency.Relationship(
          id = relationshipId,
          from = fromId,
          to = UUID.randomUUID(),
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = PartyManagementDependency
            .RelationshipProduct(id = product, role = productRole, createdAt = defaultProductTimestamp),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(relationshipId, *, *)
        .returning(Future.successful(relationships))

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/relationships/$relationshipId/activate", method = HttpMethods.POST))
          .futureValue

      response.status mustBe StatusCodes.BadRequest

    }

  }

  "Relationship suspension" must {
    "succeed" in {

      val fromId         = UUID.randomUUID()
      val product        = "product"
      val productRole    = "productRole"
      val relationshipId = UUID.randomUUID()

      val relationship =
        PartyManagementDependency.Relationship(
          id = relationshipId,
          from = fromId,
          to = UUID.randomUUID(),
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = PartyManagementDependency
            .RelationshipProduct(id = product, role = productRole, createdAt = defaultProductTimestamp),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(relationshipId, *, *)
        .returning(Future.successful(relationship))

      (mockPartyManagementService
        .suspendRelationship(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(relationshipId, *, *)
        .returning(Future.successful(()))

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/relationships/$relationshipId/suspend", method = HttpMethods.POST))
          .futureValue

      response.status mustBe StatusCodes.NoContent

    }

    "fail if relationship is not Active" in {

      val fromId         = UUID.randomUUID()
      val product        = "product"
      val productRole    = "productRole"
      val relationshipId = UUID.randomUUID()

      val relationship =
        PartyManagementDependency.Relationship(
          id = relationshipId,
          from = fromId,
          to = UUID.randomUUID(),
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = PartyManagementDependency
            .RelationshipProduct(id = product, role = productRole, createdAt = defaultProductTimestamp),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(relationshipId, *, *)
        .returning(Future.successful(relationship))

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/relationships/$relationshipId/suspend", method = HttpMethods.POST))
          .futureValue

      response.status mustBe StatusCodes.BadRequest

    }

  }

  "Relationship removal" must {
    "succeed when the relationship id is bound to the selected institution" in {
      val relationshipId = UUID.randomUUID()

      (mockPartyManagementService
        .deleteRelationshipById(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(relationshipId, *, *)
        .returning(Future.successful(()))

      val response = Await.result(
        Http().singleRequest(HttpRequest(uri = s"$url/relationships/$relationshipId", method = HttpMethods.DELETE)),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NoContent

    }

    "fail if party management deletion returns a failed future" in {
      val relationshipId = UUID.randomUUID()

      (mockPartyManagementService
        .deleteRelationshipById(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(relationshipId, *, *)
        .returning(Future.failed(new RuntimeException("Party Management Error")))

      val response = Await.result(
        Http().singleRequest(HttpRequest(uri = s"$url/relationships/$relationshipId", method = HttpMethods.DELETE)),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NotFound

    }
  }

  "Users creation" must {
    def configureCreateLegalTest(orgPartyId: UUID, manager: User, delegate: User) = {
      val file = new File("src/test/resources/fake.file")

      val managerRelationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = manager.id,
          to = orgPartyId,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )

      val delegateRelationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = delegate.id,
          to = orgPartyId,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = defaultProduct,
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None
        )

      (mockUserRegistryService
        .getUserById(_: UUID)(_: Seq[(String, String)]))
        .expects(*, *)
        .returning(Future.successful(UserRegistryUser(id = UUID.randomUUID(), taxCode = "", name = "", surname = "")))
        .once()

      (mockUserRegistryService
        .getUserById(_: UUID)(_: Seq[(String, String)]))
        .expects(manager.id, *)
        .returning(
          Future.successful(
            UserRegistryUser(id = manager.id, taxCode = manager.taxCode, name = manager.name, surname = manager.surname)
          )
        )
        .once()

      (mockSignatureService
        .createDigest(_: File))
        .expects(*)
        .returning(Future.successful("hash"))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(None, Some(orgPartyId), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *, *)
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq(managerRelationship))))
        .once()

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String)(_: Seq[(String, String)]))
        .expects(PartyManagementDependency.PersonSeed(manager.id), *, *)
        .returning(Future.successful(PartyManagementDependency.Person(manager.id)))
        .once()

      (mockPartyManagementService
        .createRelationship(_: RelationshipSeed)(_: String)(_: Seq[(String, String)]))
        .expects(
          RelationshipSeed(
            from = managerRelationship.from,
            to = managerRelationship.to,
            role = managerRelationship.role,
            product =
              RelationshipProductSeed(id = managerRelationship.product.id, role = managerRelationship.product.role)
          ),
          *,
          *
        )
        .returning(Future.failed(ResourceConflictError(managerRelationship.id.toString)))
        .once()

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
          Some(managerRelationship.from),
          Some(managerRelationship.to),
          Seq(managerRelationship.role),
          Seq.empty,
          Seq(managerRelationship.product.id),
          Seq(managerRelationship.product.role),
          *,
          *
        )
        .returning(Future.successful(Relationships(Seq(managerRelationship))))
        .once()

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String)(_: Seq[(String, String)]))
        .expects(PartyManagementDependency.PersonSeed(delegate.id), *, *)
        .returning(Future.successful(PartyManagementDependency.Person(delegate.id)))
        .once()

      (mockPartyManagementService
        .createRelationship(_: RelationshipSeed)(_: String)(_: Seq[(String, String)]))
        .expects(
          RelationshipSeed(
            from = delegateRelationship.from,
            to = delegateRelationship.to,
            role = delegateRelationship.role,
            product =
              RelationshipProductSeed(id = delegateRelationship.product.id, role = delegateRelationship.product.role)
          ),
          *,
          *
        )
        .returning(Future.successful(delegateRelationship))
        .once()

      (mockFileManager
        .get(_: String)(_: String))
        .expects(*, *)
        .returning(Future.successful(new ByteArrayOutputStream()))
        .once()

      (mockPdfCreator.createContract _).expects(*, *, *, *).returning(Future.successful(file)).once()

      (mockPartyManagementService
        .createToken(_: PartyManagementDependency.Relationships, _: String, _: String, _: String)(_: String)(
          _: Seq[(String, String)]
        ))
        .expects(*, *, *, *, *, *)
        .returning(Future.successful(PartyManagementDependency.TokenText("token")))
        .once()
    }

    "create delegate with a manager active" in {
      val taxCode1   = "managerTaxCode"
      val taxCode2   = "delegateTaxCode"
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val origin     = "IPA"
      val orgPartyId = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "org1",
        digitalAddress = "digitalAddress1",
        attributes = Seq.empty,
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option.empty,
        products = Map.empty
      )

      val managerId  = UUID.randomUUID()
      val delegateId = UUID.randomUUID()
      val manager    =
        User(
          id = managerId,
          name = "manager",
          surname = "managerSurname",
          taxCode = taxCode1,
          role = PartyRole.MANAGER,
          productRole = "admin",
          email = Option("manager@email.it")
        )
      val delegate   =
        User(
          id = delegateId,
          name = "delegate",
          surname = "delegateSurname",
          taxCode = taxCode2,
          role = PartyRole.DELEGATE,
          productRole = "admin",
          email = Option("delegate@email.it")
        )

      configureCreateLegalTest(orgPartyId, manager, delegate)

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId, *, *)
        .returning(Future.successful(institution))
        .once()

      val req = OnboardingLegalUsersRequest(
        productId = "productId",
        productName = "productName",
        users = Seq(manager, delegate),
        institutionId = Option(orgPartyId),
        contract = OnboardingContract("a", "b")
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.NoContent
    }
    "create delegate with a manager active using externalId" in {
      val taxCode1   = "managerTaxCode"
      val taxCode2   = "delegateTaxCode"
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val origin     = "IPA"
      val orgPartyId = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "org1",
        digitalAddress = "digitalAddress1",
        attributes = Seq.empty,
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option.empty,
        products = Map.empty
      )

      val managerId  = UUID.randomUUID()
      val delegateId = UUID.randomUUID()
      val manager    =
        User(
          id = managerId,
          name = "manager",
          surname = "manager",
          taxCode = taxCode1,
          role = PartyRole.MANAGER,
          productRole = "admin",
          email = Option("manager@email.it")
        )
      val delegate   =
        User(
          id = delegateId,
          name = "delegate",
          surname = "delegate",
          taxCode = taxCode2,
          role = PartyRole.DELEGATE,
          productRole = "admin",
          email = Option("delegate@email.it")
        )

      configureCreateLegalTest(orgPartyId, manager, delegate)

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

      val req = OnboardingLegalUsersRequest(
        productId = "productId",
        productName = "productName",
        users = Seq(manager, delegate),
        institutionExternalId = Option(externalId),
        contract = OnboardingContract("a", "b")
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.NoContent
    }

    "fail trying to create a delegate with a manager pending" in {
      val users = createInvalidManagerTest(PartyManagementDependency.RelationshipState.PENDING)

      val req = OnboardingLegalUsersRequest(
        productId = "productId",
        productName = "productName",
        users = users,
        institutionId = Option(UUID.randomUUID()),
        contract = OnboardingContract("a", "b")
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest

    }

    "fail trying to create a delegate with a manager rejected" in {
      val users = createInvalidManagerTest(PartyManagementDependency.RelationshipState.REJECTED)

      val req = OnboardingLegalUsersRequest(
        productId = "productId",
        productName = "productName",
        users = users,
        institutionId = Option(UUID.randomUUID()),
        contract = OnboardingContract("a", "b")
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest

    }

    "fail trying to create a delegate with a manager deleted" in {
      val users = createInvalidManagerTest(PartyManagementDependency.RelationshipState.DELETED)

      val req = OnboardingLegalUsersRequest(
        productId = "productId",
        productName = "productName",
        users = users,
        institutionId = Option(UUID.randomUUID()),
        contract = OnboardingContract("a", "b")
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest

    }

    "fail trying to create a delegate with a manager suspended" in {
      val users = createInvalidManagerTest(PartyManagementDependency.RelationshipState.SUSPENDED)

      val req = OnboardingLegalUsersRequest(
        productId = "productId",
        productName = "productName",
        users = users,
        institutionId = Option(UUID.randomUUID()),
        contract = OnboardingContract("a", "b")
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest

    }

    def createInvalidManagerTest(managerState: PartyManagementDependency.RelationshipState): Seq[User] = {
      val taxCode    = UUID.randomUUID().toString
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val origin     = "IPA"
      val orgPartyId = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "org1",
        digitalAddress = "digitalAddress1",
        attributes = Seq.empty,
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option("PA"),
        products = Map.empty
      )

      val managerId = UUID.randomUUID()

      val delegate =
        User(
          id = UUID.randomUUID(),
          name = "delegate",
          surname = "delegate",
          taxCode = taxCode,
          role = PartyRole.DELEGATE,
          productRole = "admin",
          email = None
        )

      val managerRelationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = orgPartyId,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct,
          state = managerState,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )

      (mockUserRegistryService
        .getUserById(_: UUID)(_: Seq[(String, String)]))
        .expects(*, *)
        .returning(Future.successful(UserRegistryUser(id = UUID.randomUUID(), taxCode = "", name = "", surname = "")))
        .once()

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(*, *, *)
        .returning(Future.successful(institution))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(None, Some(orgPartyId), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *, *)
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq(managerRelationship))))
        .once()

      Seq(delegate)
    }

    "not create legals when no active manager exist for a relationship" in {
      val taxCode1   = "managerTaxCode"
      val taxCode2   = "delegateTaxCode"
      val externalId = UUID.randomUUID().toString
      val originId   = UUID.randomUUID().toString
      val origin     = "IPA"
      val orgPartyId = UUID.randomUUID()

      val institution = PartyManagementDependency.Institution(
        id = orgPartyId,
        externalId = externalId,
        originId = originId,
        description = "org1",
        digitalAddress = "digitalAddress1",
        attributes = Seq.empty,
        taxCode = "123",
        address = "address",
        zipCode = "zipCode",
        origin = origin,
        institutionType = Option("PA"),
        products = Map.empty
      )

      val managerId = UUID.randomUUID()
      val manager   =
        User(
          id = managerId,
          name = "manager",
          surname = "manager",
          taxCode = taxCode1,
          role = PartyRole.MANAGER,
          productRole = "admin",
          email = None
        )
      val delegate  =
        User(
          id = UUID.randomUUID(),
          name = "delegate",
          surname = "delegate",
          taxCode = taxCode2,
          role = PartyRole.DELEGATE,
          productRole = "admin",
          email = None
        )

      val relationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = orgPartyId,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = PartyManagementDependency
            .RelationshipProduct(id = "product", role = "admin", createdAt = defaultProductTimestamp),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )

      (mockUserRegistryService
        .getUserById(_: UUID)(_: Seq[(String, String)]))
        .expects(*, *)
        .returning(Future.successful(UserRegistryUser(id = UUID.randomUUID(), taxCode = "", name = "", surname = "")))
        .once()

      (mockPartyManagementService
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId, *, *)
        .returning(Future.successful(institution))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String)(_: Seq[(String, String)]))
        .expects(None, Some(orgPartyId), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *, *)
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq(relationship))))
        .once()

      val req = OnboardingLegalUsersRequest(
        productId = "productId",
        productName = "productName",
        users = Seq(manager, delegate),
        institutionId = Option(orgPartyId),
        contract = OnboardingContract("a", "b")
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest
    }
  }

  "Institution products retrieval" must {

    "retrieve products" in {
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
        products = Map.empty
      )

      val managerId        = UUID.randomUUID()
      val activeProduct    = "activeProduct"
      val pendingProduct   = "pendingProduct"
      val suspendedProduct = "suspendedProduct"

      val relationships = Seq(
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = activeProduct),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = pendingProduct),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = suspendedProduct),
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )
      )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(uri = s"$url/external/institutions/$externalId/products", method = HttpMethods.GET)
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Products].futureValue

      val expected = Set(
        Product(id = activeProduct, ProductState.ACTIVE),
        Product(id = pendingProduct, ProductState.PENDING),
        Product(id = suspendedProduct, ProductState.ACTIVE)
      )

      body.products.toSet mustBe expected
    }

    "retrieve products asking PENDING" in {
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
        products = Map.empty
      )

      val managerId        = UUID.randomUUID()
      val activeProduct    = "activeProduct"
      val suspendedProduct = "suspendedProduct"
      val deletedProduct   = "deletedProduct"
      val pendingProduct   = "pendingProduct"

      val relationships = Seq(
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = pendingProduct),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = activeProduct),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = suspendedProduct),
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = deletedProduct),
          state = PartyManagementDependency.RelationshipState.DELETED,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )
      )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products?states=PENDING",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Products].futureValue

      val expected = Product(pendingProduct, ProductState.PENDING)

      body.products must contain only expected
    }

    "retrieve products asking ACTIVE" in {
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
        products = Map.empty
      )

      val managerId        = UUID.randomUUID()
      val activeProduct    = "activeProduct"
      val suspendedProduct = "suspendedProduct"
      val deletedProduct   = "deletedProduct"
      val pendingProduct   = "pendingProduct"

      val relationships = Seq(
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = pendingProduct),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              zipCode = Option("OVERRIDE_zipCode"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = activeProduct),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = suspendedProduct),
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = deletedProduct),
          state = PartyManagementDependency.RelationshipState.DELETED,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = deletedProduct),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )
      )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products?states=ACTIVE",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Products].futureValue

      val expected = Set(
        Product(activeProduct, ProductState.ACTIVE),
        Product(suspendedProduct, ProductState.ACTIVE),
        Product(deletedProduct, ProductState.ACTIVE)
      )

      body.products.toSet mustBe expected
    }

    "retrieve products asking ACTIVE/PENDING" in {
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
        products = Map.empty
      )

      val managerId        = UUID.randomUUID()
      val activeProduct    = "activeProduct"
      val suspendedProduct = "suspendedProduct"
      val deletedProduct   = "deletedProduct"
      val pendingProduct   = "pendingProduct"

      val relationships = Seq(
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = pendingProduct),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = activeProduct),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = suspendedProduct),
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = deletedProduct),
          state = PartyManagementDependency.RelationshipState.DELETED,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )
      )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products?states=ACTIVE,PENDING",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Products].futureValue

      val expected = Seq(
        Product(deletedProduct, ProductState.ACTIVE),
        Product(pendingProduct, ProductState.PENDING),
        Product(suspendedProduct, ProductState.ACTIVE),
        Product(activeProduct, ProductState.ACTIVE)
      )
      body.products mustBe expected
    }

    "retrieve products asking ACTIVE/PENDING even when there are only PENDING products" in {
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
        products = Map.empty
      )

      val managerId       = UUID.randomUUID()
      val pendingProduct1 = "pendingProduct1"
      val pendingProduct2 = "pendingProduct2"

      val relationships = Seq(
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = pendingProduct1),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = pendingProduct2),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )
      )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products?states=ACTIVE,PENDING",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Products].futureValue

      val expected = Set(Product(pendingProduct1, ProductState.PENDING), Product(pendingProduct2, ProductState.PENDING))

      body.products.toSet mustBe expected
    }

    "retrieve products asking ACTIVE/PENDING even when there are only ACTIVE products" in {
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
        products = Map.empty
      )

      val managerId      = UUID.randomUUID()
      val activeProduct1 = "activeProduct1"
      val activeProduct2 = "activeProduct2"

      val relationships = Seq(
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = activeProduct1),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = institution.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = defaultProduct.copy(id = activeProduct2),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = defaultRelationshipTimestamp,
          updatedAt = None,
          pricingPlan = Option("pricingPlan"),
          institutionUpdate = Option(
            PartyManagementDependency.InstitutionUpdate(
              institutionType = Option("OVERRIDE_institutionType"),
              description = Option("OVERRIDE_description"),
              digitalAddress = Option("OVERRIDE_digitalAddress"),
              address = Option("OVERRIDE_address"),
              taxCode = Option("OVERRIDE_taxCode")
            )
          ),
          billing = Option(
            PartyManagementDependency
              .Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE", publicServices = Option(true))
          )
        )
      )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products?states=ACTIVE,PENDING",
              method = HttpMethods.GET
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Products].futureValue

      val expected = Set(Product(activeProduct1, ProductState.ACTIVE), Product(activeProduct2, ProductState.ACTIVE))

      body.products.toSet mustBe expected
    }

    "retrieve no products" in {
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
        products = Map.empty
      )

      (mockPartyManagementService
        .retrieveInstitutionByExternalId(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(Future.successful(institution))
        .once()

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
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq.empty)))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/external/institutions/$externalId/products?states=ACTIVE",
              method = HttpMethods.GET
            )
          )
          .futureValue

      response.status mustBe StatusCodes.NotFound
    }

  }

  "Create institution" must {
    val originId   = UUID.randomUUID().toString
    val externalId = UUID.randomUUID().toString
    val origin     = "ORIGIN"

    val orgPartyId = UUID.randomUUID()

    val institutionFromProxy =
      PartyProxyDependencies.Institution(
        id = externalId,
        originId = originId,
        o = Some(originId),
        ou = None,
        aoo = None,
        taxCode = "taxCode",
        category = "C17",
        description = "description",
        digitalAddress = "digitalAddress",
        origin = origin,
        address = "address",
        zipCode = "zipCode"
      )

    val institutionSeed = PartyManagementDependency.InstitutionSeed(
      externalId = institutionFromProxy.id,
      originId = institutionFromProxy.originId,
      description = institutionFromProxy.description,
      digitalAddress = institutionFromProxy.digitalAddress,
      attributes = Seq(PartyManagementDependency.Attribute(origin, "C17", "attrs")),
      taxCode = institutionFromProxy.taxCode,
      address = institutionFromProxy.address,
      zipCode = institutionFromProxy.zipCode,
      origin = institutionFromProxy.origin
    )

    val institution = PartyManagementDependency.Institution(
      id = orgPartyId,
      externalId = institutionSeed.externalId,
      originId = institutionSeed.originId,
      description = institutionSeed.description,
      digitalAddress = institutionSeed.digitalAddress,
      attributes = institutionSeed.attributes,
      taxCode = institutionSeed.taxCode,
      address = institutionSeed.address,
      zipCode = institutionSeed.zipCode,
      origin = institutionSeed.origin,
      institutionType = institutionSeed.institutionType,
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

    def mockPartyRegistry(success: Boolean) = {
      (mockPartyRegistryService
        .getInstitution(_: String)(_: String)(_: Seq[(String, String)]))
        .expects(externalId, *, *)
        .returning(
          if (success) Future.successful(institutionFromProxy)
          else Future.failed(ResourceNotFoundError(externalId))
        )
        .once()

      if (success)(mockPartyRegistryService
        .getCategory(_: String, _: String)(_: String)(_: Seq[(String, String)]))
        .expects(origin, *, *, *)
        .returning(Future.successful(PartyProxyDependencies.Category("C17", "attrs", "test", origin)))
        .once()
    }

    def mockPartyManagement(success: Boolean) = {
      (mockPartyManagementService
        .createInstitution(_: InstitutionSeed)(_: String)(_: Seq[(String, String)]))
        .expects(institutionSeed, *, *)
        .returning(if (success) Future.successful(institution) else Future.failed(ResourceConflictError(externalId)))
        .once()
    }

    "succeed when valid and not exists yet" in {
      mockPartyRegistry(true)
      mockPartyManagement(true)

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/institutions/$externalId", method = HttpMethods.POST))
          .futureValue

      val body = Unmarshal(response.entity).to[Institution].futureValue

      body equals expected

      response.status mustBe StatusCodes.Created
    }

    "NotFoundError when externalId not found in userRegistry" in {
      mockPartyRegistry(false)

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/institutions/$externalId", method = HttpMethods.POST))
          .futureValue

      response.status mustBe StatusCodes.NotFound
    }

    "ConflictError when already exists an institution having the same externalId" in {
      mockPartyRegistry(true)
      mockPartyManagement(false)

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/institutions/$externalId", method = HttpMethods.POST))
          .futureValue

      response.status mustBe StatusCodes.Conflict
    }
  }

  "Get institution" must {
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
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId, *, *)
        .returning(if (success) Future.successful(institution) else Future.failed(ResourceNotFoundError(externalId)))
        .once()
    }

    "succeed when exists" in {
      mockPartyManagement(true)

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/institutions/$orgPartyId", method = HttpMethods.GET))
          .futureValue

      val body = Unmarshal(response.entity).to[Institution].futureValue

      body equals expected

      response.status mustBe StatusCodes.OK
    }

    "NotFoundError when not exists" in {
      mockPartyManagement(false)

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/institutions/$orgPartyId", method = HttpMethods.GET))
          .futureValue

      response.status mustBe StatusCodes.NotFound
    }

  }

}
