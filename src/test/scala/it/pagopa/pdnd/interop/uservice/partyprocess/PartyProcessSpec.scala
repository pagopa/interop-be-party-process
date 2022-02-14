package it.pagopa.pdnd.interop.uservice.partyprocess

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.server.directives.{AuthenticationDirective, FileInfo, SecurityDirectives}
import akka.http.scaladsl.unmarshalling.Unmarshal
import cats.implicits.catsSyntaxValidatedId
import com.nimbusds.jwt.JWTClaimsSet
import eu.europa.esig.dss.validation.SignedDocumentValidator
import it.pagopa.pdnd.interop.commons.utils.AkkaUtils.Authenticator
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.{
  OrganizationSeed,
  RelationshipBinding,
  RelationshipProduct,
  TokenInfo,
  PartyRole => _,
  RelationshipState => _,
  _
}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.pdnd.interop.uservice.partyprocess
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.Conversions.{relationshipStateToApi, roleToApi}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.{ProcessApiServiceImpl, PublicApiServiceImpl}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.{ProcessApi, PublicApi}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{classicActorSystem, executionContext}
import it.pagopa.pdnd.interop.uservice.partyprocess.error.SignatureValidationError
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{Products => ModelProducts, _}
import it.pagopa.pdnd.interop.uservice.partyprocess.server.Controller
import it.pagopa.pdnd.interop.uservice.partyprocess.{model => PartyProcess}
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.{Category, Institution}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.Certification.{
  NONE => CertificationEnumsNone
}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{
  User => UserRegistryUser,
  UserExtras => UserRegistryUserExtras,
  UserSeed => UserRegistryUserSeed
}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json.DefaultJsonProtocol

import java.io.{ByteArrayOutputStream, File}
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}
import scala.util.Success

class PartyProcessSpec
    extends MockFactory
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with SprayJsonSupport
    with DefaultJsonProtocol
    with SpecHelper
    with ScalaFutures {

  var controller: Option[Controller]                 = None
  var bindServer: Option[Future[Http.ServerBinding]] = None

  val wrappingDirective: AuthenticationDirective[Seq[(String, String)]] =
    SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)

  final val productTimestamp: OffsetDateTime      = OffsetDateTime.now()
  final val relationshipTimestamp: OffsetDateTime = OffsetDateTime.now()

  final val product: RelationshipProduct =
    PartyManagementDependency.RelationshipProduct(id = "product", role = "admin", createdAt = productTimestamp)

  final val productInfo: ProductInfo =
    ProductInfo(id = "product", role = "admin", createdAt = productTimestamp)

  final val relationship: Relationship = PartyManagementDependency.Relationship(
    id = UUID.randomUUID(),
    from = UUID.randomUUID(),
    to = UUID.randomUUID(),
    role = PartyManagementDependency.PartyRole.MANAGER,
    product = product,
    state = PartyManagementDependency.RelationshipState.PENDING,
    createdAt = OffsetDateTime.now()
  )
  val claims: String             = s"""{"uid" : "${UUID.randomUUID().toString}"}"""
  val jwtClaimsSet: JWTClaimsSet = JWTClaimsSet.parse(claims)

  override def beforeAll(): Unit = {
    loadEnvVars()
    val processApi = new ProcessApi(
      new ProcessApiServiceImpl(
        partyManagementService = mockPartyManagementService,
        partyRegistryService = mockPartyRegistryService,
        userRegistryManagementService = mockUserRegistryService,
        pdfCreator = mockPdfCreator,
        fileManager = mockFileManager,
        signatureService = mockSignatureService,
        mailer = mockMailer,
        mailTemplate = mockMailTemplate,
        jwtReader = mockJWTReader
      ),
      processApiMarshaller,
      wrappingDirective
    )

    val publicApi = new PublicApi(
      new PublicApiServiceImpl(
        partyManagementService = mockPartyManagementService,
        userRegistryManagementService = mockUserRegistryService,
        signatureService = mockSignatureService,
        signatureValidationService = mockSignatureValidationService
      ),
      publicApiMarshaller,
      wrappingDirective
    )

    controller = Some(new Controller(health = mockHealthApi, process = processApi, public = publicApi))

    controller foreach { controller =>
      bindServer = Some(
        Http()
          .newServerAt("0.0.0.0", SpecConfig.port)
          .bind(controller.routes)
      )

      Await.result(bindServer.get, 100.seconds)
    }
  }

  override def afterAll(): Unit = {
    bindServer.foreach(_.foreach(_.unbind()))
  }

  def performOnboardingRequestByRoleForFailure(state: PartyManagementDependency.RelationshipState): HttpResponse = {
    val taxCode1       = "managerTaxCode"
    val taxCode2       = "delegateTaxCode"
    val institutionId1 = "IST2"
    val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"
    val institution1 =
      Institution(
        id = institutionId1,
        o = Some(institutionId1),
        ou = None,
        aoo = None,
        taxCode = "taxCode",
        category = "C17",
        description = "description",
        digitalAddress = "digitalAddress",
        origin = "origin",
        address = "address",
        zipCode = "zipCode"
      )

    val organization1 = PartyManagementDependency.Organization(
      institutionId = institutionId1,
      description = "org1",
      digitalAddress = "digitalAddress1",
      id = UUID.fromString(orgPartyId1),
      attributes = Seq.empty,
      taxCode = "123",
      address = "address",
      zipCode = "zipCode"
    )

    val manager =
      User(
        name = "manager",
        surname = "manager",
        taxCode = taxCode1,
        role = PartyProcess.PartyRole.MANAGER,
        email = None,
        product = "product",
        productRole = "admin"
      )

    val delegate =
      User(
        name = "delegate",
        surname = "delegate",
        taxCode = taxCode2,
        role = PartyProcess.PartyRole.DELEGATE,
        email = None,
        product = "product",
        productRole = "admin"
      )

    val managerRelationship = PartyManagementDependency.Relationship(
      id = UUID.randomUUID(),
      from = UUID.randomUUID(),
      to = UUID.randomUUID(),
      filePath = None,
      fileName = None,
      contentType = None,
      role = PartyManagementDependency.PartyRole.MANAGER,
      product =
        PartyManagementDependency.RelationshipProduct(id = "product", role = "admin", createdAt = OffsetDateTime.now()),
      state = state,
      createdAt = OffsetDateTime.now(),
      updatedAt = None
    )

    (mockJWTReader
      .getClaims(_: String))
      .expects(*)
      .returning(Success(jwtClaimsSet))
      .once()

    (mockUserRegistryService
      .getUserById(_: UUID))
      .expects(*)
      .returning(
        Future.successful(
          UserRegistryUser(
            id = UUID.randomUUID(),
            externalId = "",
            name = "",
            surname = "",
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = None, birthDate = None)
          )
        )
      )
      .once()

    (mockPartyRegistryService
      .getInstitution(_: String)(_: String))
      .expects(*, *)
      .returning(Future.successful(institution1))
      .once()

    (mockPartyRegistryService
      .getCategory(_: String, _: String)(_: String))
      .expects(*, *, *)
      .returning(Future.successful(Category("C17", "attrs", "test", "IPA")))
      .once()

    (mockPartyManagementService
      .createOrganization(_: OrganizationSeed)(_: String))
      .expects(*, *)
      .returning(Future.successful(organization1))
      .once()

    (mockPartyManagementService
      .retrieveRelationships(
        _: Option[UUID],
        _: Option[UUID],
        _: Seq[PartyManagementDependency.PartyRole],
        _: Seq[PartyManagementDependency.RelationshipState],
        _: Seq[String],
        _: Seq[String]
      )(_: String))
      .expects(None, Some(organization1.id), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *)
      .returning(Future.successful(PartyManagementDependency.Relationships(Seq(managerRelationship))))
      .once()

    val req = OnboardingRequest(
      users = Seq(manager, delegate),
      institutionId = "institutionId1",
      contract = Some(OnboardingContract("a", "b"))
    )

    val data = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue

    request(data, "onboarding/organization", HttpMethods.POST)
  }

  def performOnboardingRequest(
    state: Option[PartyManagementDependency.RelationshipState],
    product: Option[String]
  ): HttpResponse = {
    val taxCode1       = "managerTaxCode"
    val taxCode2       = "delegateTaxCode"
    val institutionId1 = "IST2"
    val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"
    val institution1 =
      Institution(
        id = institutionId1,
        o = Some(institutionId1),
        ou = None,
        aoo = None,
        taxCode = "taxCode",
        category = "C17",
        description = "description",
        digitalAddress = "digitalAddress",
        origin = "origin",
        address = "address",
        zipCode = "zipCode"
      )

    val organization1 = PartyManagementDependency.Organization(
      institutionId = institutionId1,
      description = "org1",
      digitalAddress = "digitalAddress1",
      id = UUID.fromString(orgPartyId1),
      attributes = Seq.empty,
      taxCode = "123",
      address = "address",
      zipCode = "zipCode"
    )

    val file = new File("src/test/resources/fake.file")

    val managerId  = UUID.randomUUID()
    val delegateId = UUID.randomUUID()
    val manager =
      User(
        name = "manager",
        surname = "manager",
        taxCode = taxCode1,
        role = PartyProcess.PartyRole.MANAGER,
        email = None,
        product = "product",
        productRole = "admin"
      )
    val delegate =
      User(
        name = "delegate",
        surname = "delegate",
        taxCode = taxCode2,
        role = PartyProcess.PartyRole.DELEGATE,
        email = None,
        product = "product",
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
            updatedAt = None
          )
        )
      case (Some(st), None) =>
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
            updatedAt = None
          )
        )
      case _ => Seq.empty
    }

    (mockJWTReader
      .getClaims(_: String))
      .expects(*)
      .returning(Success(jwtClaimsSet))
      .once()

    (mockUserRegistryService
      .getUserById(_: UUID))
      .expects(*)
      .returning(
        Future.successful(
          UserRegistryUser(
            id = UUID.randomUUID(),
            externalId = "",
            name = "",
            surname = "",
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = None, birthDate = None)
          )
        )
      )
      .once()

    (mockSignatureService
      .createDigest(_: File))
      .expects(*)
      .returning(Future.successful("hash"))
      .once()

    (mockPartyRegistryService
      .getInstitution(_: String)(_: String))
      .expects(*, *)
      .returning(Future.successful(institution1))
      .once()

    (mockPartyRegistryService
      .getCategory(_: String, _: String)(_: String))
      .expects(*, *, *)
      .returning(Future.successful(Category("C17", "attrs", "test", "IPA")))
      .once()

    (mockPartyManagementService
      .createOrganization(_: OrganizationSeed)(_: String))
      .expects(*, *)
      .returning(Future.successful(organization1))
      .once()

    (mockPartyManagementService
      .retrieveRelationships(
        _: Option[UUID],
        _: Option[UUID],
        _: Seq[PartyManagementDependency.PartyRole],
        _: Seq[PartyManagementDependency.RelationshipState],
        _: Seq[String],
        _: Seq[String]
      )(_: String))
      .expects(None, Some(organization1.id), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *)
      .returning(Future.successful(PartyManagementDependency.Relationships(relationships)))
      .once()

    (mockUserRegistryService
      .createUser(_: UserRegistryUserSeed))
      .expects(
        UserRegistryUserSeed(
          externalId = manager.taxCode,
          name = manager.name,
          surname = manager.surname,
          certification = CertificationEnumsNone,
          extras = UserRegistryUserExtras(email = manager.email, birthDate = None)
        )
      )
      .returning(
        Future.successful(
          UserRegistryUser(
            id = managerId,
            externalId = manager.taxCode,
            name = manager.name,
            surname = manager.surname,
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = manager.email, birthDate = None)
          )
        )
      )
      .once()

    (mockPartyManagementService
      .createPerson(_: PartyManagementDependency.PersonSeed)(_: String))
      .expects(PartyManagementDependency.PersonSeed(managerId), *)
      .returning(Future.successful(PartyManagementDependency.Person(managerId)))
      .once()

    (mockUserRegistryService
      .createUser(_: UserRegistryUserSeed))
      .expects(
        UserRegistryUserSeed(
          externalId = delegate.taxCode,
          name = delegate.name,
          surname = delegate.surname,
          certification = CertificationEnumsNone,
          extras = UserRegistryUserExtras(email = delegate.email, birthDate = None)
        )
      )
      .returning(
        Future.successful(
          UserRegistryUser(
            id = delegateId,
            externalId = delegate.taxCode,
            name = delegate.name,
            surname = delegate.surname,
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = delegate.email, birthDate = None)
          )
        )
      )
      .once()

    (mockPartyManagementService
      .createPerson(_: PartyManagementDependency.PersonSeed)(_: String))
      .expects(PartyManagementDependency.PersonSeed(delegateId), *)
      .returning(Future.successful(PartyManagementDependency.Person(delegateId)))
      .once()

    (mockPartyManagementService
      .createRelationship(_: RelationshipSeed)(_: String))
      .expects(*, *)
      .returning(Future.successful(relationship))
      .repeat(2)

    (mockFileManager
      .get(_: String)(_: String))
      .expects(*, *)
      .returning(Future.successful(new ByteArrayOutputStream()))
      .once()
    (mockPdfCreator.createContract _).expects(*, *, *).returning(Future.successful(file)).once()
    (mockPartyManagementService
      .createToken(_: PartyManagementDependency.Relationships, _: String, _: String, _: String)(_: String))
      .expects(*, *, *, *, *)
      .returning(Future.successful(PartyManagementDependency.TokenText("token")))
      .once()

    val req = OnboardingRequest(
      users = Seq(manager, delegate),
      institutionId = "institutionId1",
      contract = Some(OnboardingContract("a", "b"))
    )

    val data = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue

    request(data, "onboarding/organization", HttpMethods.POST)
  }

  "Processing a request payload" must {

    "verify that an organization is already onboarded for a given product" in {
      val institutionId = "institutionId"
      val mockUidUUID   = UUID.randomUUID()
      val orgPartyId    = UUID.randomUUID()
      val personPartyId = UUID.randomUUID()

      val organization = PartyManagementDependency.Organization(
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        id = orgPartyId,
        attributes = Seq.empty,
        taxCode = "",
        address = "",
        zipCode = ""
      )

      val relationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = personPartyId,
          to = orgPartyId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          None,
          Some(orgPartyId),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.DELETED,
            PartyManagementDependency.RelationshipState.SUSPENDED
          ),
          Seq(product.id),
          Seq.empty,
          *
        )
        .returning(Future.successful(Relationships(Seq(relationship))))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(mockUidUUID.toString)))

      val response = Await.result(
        Http().singleRequest(
          HttpRequest(
            uri = s"$url/onboarding/organization/$institutionId/products/${product.id}",
            method = HttpMethods.HEAD,
            headers = authorization
          )
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NoContent

    }

    "verify that an organization is not already onboarded for a given product" in {
      val institutionId = "institutionId"
      val mockUidUUID   = UUID.randomUUID()
      val orgPartyId    = UUID.randomUUID()

      val organization = PartyManagementDependency.Organization(
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        id = orgPartyId,
        attributes = Seq.empty,
        taxCode = "",
        address = "",
        zipCode = ""
      )

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          None,
          Some(orgPartyId),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.DELETED,
            PartyManagementDependency.RelationshipState.SUSPENDED
          ),
          Seq(product.id),
          Seq.empty,
          *
        )
        .returning(Future.successful(Relationships(Seq.empty)))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(mockUidUUID.toString)))

      val response = Await.result(
        Http().singleRequest(
          HttpRequest(
            uri = s"$url/onboarding/organization/$institutionId/products/${product.id}",
            method = HttpMethods.HEAD,
            headers = authorization
          )
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NotFound

    }

    "retrieve a onboarding info" in {
      val taxCode1       = "CF1"
      val institutionId1 = UUID.randomUUID()
      val institutionId2 = UUID.randomUUID()
      val personPartyId1 = "af80fac0-2775-4646-8fcf-28e083751900"
      val orgPartyId1    = "af80fac0-2775-4646-8fcf-28e083751901"
      val orgPartyId2    = "af80fac0-2775-4646-8fcf-28e083751902"

      val attribute1 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name1", "origin")
      val attribute2 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name2", "origin")
      val attribute3 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name3", "origin")
      val attribute4 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name4", "origin")
      val attribute5 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name5", "origin")
      val attribute6 = partyprocess.model.Attribute(UUID.randomUUID().toString, "name6", "origin")

      val user = UserRegistryUser(
        id = UUID.fromString(personPartyId1),
        externalId = taxCode1,
        name = "Mario",
        surname = "Rossi",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = None, birthDate = None)
      )

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = user.id,
          to = institutionId1,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      val relationship2 =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = user.id,
          to = institutionId2,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = product,
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationships = PartyManagementDependency.Relationships(items = Seq(relationship1, relationship2))

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1.toString,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq(
          PartyManagementDependency.Attribute(attribute1.origin, attribute1.code, attribute1.description),
          PartyManagementDependency.Attribute(attribute2.origin, attribute2.code, attribute2.description),
          PartyManagementDependency.Attribute(attribute3.origin, attribute3.code, attribute3.description)
        ),
        taxCode = "123",
        address = "address",
        zipCode = "zipCode"
      )
      val organization2 = PartyManagementDependency.Organization(
        institutionId = institutionId2.toString,
        description = "org2",
        digitalAddress = "digitalAddress2",
        id = UUID.fromString(orgPartyId2),
        attributes = Seq(
          PartyManagementDependency.Attribute(attribute4.origin, attribute4.code, attribute4.description),
          PartyManagementDependency.Attribute(attribute5.origin, attribute5.code, attribute5.description),
          PartyManagementDependency.Attribute(attribute6.origin, attribute6.code, attribute6.description)
        ),
        taxCode = "123",
        address = "address",
        zipCode = "zipCode"
      )

      val expected = OnboardingInfo(
        person = PersonInfo(
          name = user.name,
          surname = user.surname,
          taxCode = user.externalId,
          certification = Certification.NONE,
          institutionContacts = Map.empty
        ),
        institutions = Seq(
          OnboardingData(
            institutionId = organization1.institutionId,
            description = organization1.description,
            taxCode = organization1.taxCode,
            digitalAddress = organization1.digitalAddress,
            state = relationshipStateToApi(relationship1.state),
            role = roleToApi(relationship1.role),
            productInfo = productInfo,
            attributes = Seq(attribute1, attribute2, attribute3)
          ),
          OnboardingData(
            institutionId = organization2.institutionId,
            description = organization2.description,
            taxCode = organization2.taxCode,
            digitalAddress = organization2.digitalAddress,
            state = relationshipStateToApi(relationship2.state),
            role = roleToApi(relationship2.role),
            productInfo = productInfo,
            attributes = Seq(
              partyprocess.model.Attribute(attribute4.origin, attribute4.code, attribute4.description),
              partyprocess.model.Attribute(attribute5.origin, attribute5.code, attribute5.description),
              partyprocess.model.Attribute(attribute6.origin, attribute6.code, attribute6.description)
            )
          )
        )
      )

      val mockUidUUID = "af80fac0-2775-4646-8fcf-28e083751988"

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(mockUidUUID))
        .once()

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(UUID.fromString(mockUidUUID))
        .returning(Future.successful(user))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(UUID.fromString(mockUidUUID)),
          None,
          Seq.empty,
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      (mockPartyManagementService
        .retrieveOrganization(_: UUID)(_: String))
        .expects(institutionId1, *)
        .returning(Future.successful(organization1))
        .once()

      (mockPartyManagementService
        .retrieveOrganization(_: UUID)(_: String))
        .expects(institutionId2, *)
        .returning(Future.successful(organization2))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(mockUidUUID)))

      val response = Await.result(
        Http().singleRequest(
          HttpRequest(uri = s"$url/onboarding/info", method = HttpMethods.GET, headers = authorization)
        ),
        Duration.Inf
      )

      val body = Unmarshal(response.entity).to[OnboardingInfo].futureValue

      response.status mustBe StatusCodes.OK
      body mustBe expected

    }

    "retrieve an onboarding info with institution id filter" in {
      val taxCode1       = "CF1"
      val institutionId1 = "institutionId1"
      val orgPartyId1    = UUID.randomUUID()
      val personPartyId1 = "af80fac0-2775-4646-8fcf-28e083751800"
      val attribute1     = partyprocess.model.Attribute(UUID.randomUUID().toString, "name1", "origin")
      val attribute2     = partyprocess.model.Attribute(UUID.randomUUID().toString, "name2", "origin")
      val attribute3     = partyprocess.model.Attribute(UUID.randomUUID().toString, "name3", "origin")

      val user = UserRegistryUser(
        id = UUID.fromString(personPartyId1),
        externalId = taxCode1,
        name = "Mario",
        surname = "Rossi",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("super@mario.it"), birthDate = None)
      )

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = user.id,
          to = orgPartyId1,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationships = PartyManagementDependency.Relationships(items = Seq(relationship1))

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = orgPartyId1,
        attributes = Seq(
          PartyManagementDependency.Attribute(attribute1.origin, attribute1.code, attribute1.description),
          PartyManagementDependency.Attribute(attribute2.origin, attribute2.code, attribute2.description),
          PartyManagementDependency.Attribute(attribute3.origin, attribute3.code, attribute3.description)
        ),
        taxCode = "123",
        address = "address",
        zipCode = "zipCode"
      )

      val expected = OnboardingInfo(
        person = PersonInfo(
          name = user.name,
          surname = user.surname,
          taxCode = user.externalId,
          certification = Certification.NONE,
          institutionContacts = Map(orgPartyId1.toString -> Seq(Contact(email = user.extras.email.get)))
        ),
        institutions = Seq(
          OnboardingData(
            institutionId = organization1.institutionId,
            description = organization1.description,
            taxCode = organization1.taxCode,
            digitalAddress = organization1.digitalAddress,
            state = relationshipStateToApi(relationship1.state),
            role = roleToApi(relationship1.role),
            productInfo = productInfo,
            attributes = Seq(attribute1, attribute2, attribute3)
          )
        )
      )

      val mockUidUUID = UUID.randomUUID().toString

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(mockUidUUID))
        .once()

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(UUID.fromString(mockUidUUID))
        .returning(Future.successful(user))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId1, *)
        .returning(Future.successful(organization1))

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(UUID.fromString(mockUidUUID)),
          Some(orgPartyId1),
          Seq.empty,
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      (mockPartyManagementService
        .retrieveOrganization(_: UUID)(_: String))
        .expects(orgPartyId1, *)
        .returning(Future.successful(organization1))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(mockUidUUID)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/onboarding/info?institutionId=$institutionId1",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[OnboardingInfo].futureValue

      response.status mustBe StatusCodes.OK
      body mustBe expected

    }

    "retrieve an onboarding info with states filter" in {
      val taxCode1       = "CF1"
      val institutionId1 = UUID.randomUUID()
      val personPartyId1 = "af80fac0-2775-4646-8fcf-28e083751800"
      val orgPartyId1    = "af80fac0-2775-4646-8fcf-28e083751801"
      val attribute1     = PartyManagementDependency.Attribute(UUID.randomUUID().toString, "name1", "origin")
      val attribute2     = PartyManagementDependency.Attribute(UUID.randomUUID().toString, "name2", "origin")
      val attribute3     = PartyManagementDependency.Attribute(UUID.randomUUID().toString, "name3", "origin")

      val user =
        UserRegistryUser(
          id = UUID.fromString(personPartyId1),
          externalId = taxCode1,
          name = "Mario",
          surname = "Rossi",
          certification = CertificationEnumsNone,
          extras = UserRegistryUserExtras(email = Some("super@mario.it"), birthDate = None)
        )

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = user.id,
          to = institutionId1,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product,
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationships = PartyManagementDependency.Relationships(items = Seq(relationship1))

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1.toString,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq(attribute1, attribute2, attribute3),
        taxCode = "123",
        address = "address",
        zipCode = "zipCode"
      )

      val expected = OnboardingInfo(
        person = PersonInfo(
          name = user.name,
          surname = user.surname,
          taxCode = user.externalId,
          certification = Certification.NONE,
          institutionContacts = Map(institutionId1.toString -> Seq(Contact(email = user.extras.email.get)))
        ),
        institutions = Seq(
          OnboardingData(
            institutionId = organization1.institutionId,
            description = organization1.description,
            taxCode = organization1.taxCode,
            digitalAddress = organization1.digitalAddress,
            state = relationshipStateToApi(relationship1.state),
            role = roleToApi(relationship1.role),
            productInfo = productInfo,
            attributes = Seq(
              partyprocess.model.Attribute(attribute1.origin, attribute1.code, attribute1.description),
              partyprocess.model.Attribute(attribute2.origin, attribute2.code, attribute2.description),
              partyprocess.model.Attribute(attribute3.origin, attribute3.code, attribute3.description)
            )
          )
        )
      )

      val mockUidUUID = UUID.randomUUID().toString

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(mockUidUUID))
        .once()

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(UUID.fromString(mockUidUUID))
        .returning(Future.successful(user))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(UUID.fromString(mockUidUUID)),
          None,
          Seq.empty,
          Seq(PartyManagementDependency.RelationshipState.SUSPENDED),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      (mockPartyManagementService
        .retrieveOrganization(_: UUID)(_: String))
        .expects(institutionId1, *)
        .returning(Future.successful(organization1))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(mockUidUUID)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/onboarding/info?states=SUSPENDED",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[OnboardingInfo].futureValue

      response.status mustBe StatusCodes.OK
      body mustBe expected

    }

    "fail the onboarding info retrieval when the institution id filter contains an invalid string" in {

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/onboarding/info?institutionId=wrong-institution-id",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      response.status mustBe StatusCodes.BadRequest

    }

    "not onboard an organization if is already onboarded (MANAGER ACTIVE)" in {

      val response =
        performOnboardingRequestByRoleForFailure(PartyManagementDependency.RelationshipState.ACTIVE)

      response.status mustBe StatusCodes.BadRequest

    }

    "not onboard an organization if is already onboarded (MANAGER SUSPENDED)" in {

      val response =
        performOnboardingRequestByRoleForFailure(PartyManagementDependency.RelationshipState.SUSPENDED)

      response.status mustBe StatusCodes.BadRequest

    }

    "not onboard an organization if is already onboarded (MANAGER DELETED)" in {

      val response =
        performOnboardingRequestByRoleForFailure(PartyManagementDependency.RelationshipState.DELETED)

      response.status mustBe StatusCodes.BadRequest

    }

    "onboard an organization with a legal and a delegate" in {

      val response = performOnboardingRequest(Some(PartyManagementDependency.RelationshipState.PENDING), None)

      response.status mustBe StatusCodes.OK

    }

    "onboard an organization with a legal and a delegate (MANAGER PENDING)" in {

      val response = performOnboardingRequest(Some(PartyManagementDependency.RelationshipState.PENDING), None)

      response.status mustBe StatusCodes.OK

    }

    "onboard an organization with a legal and a delegate (MANAGER REJECTED)" in {

      val response =
        performOnboardingRequest(Some(PartyManagementDependency.RelationshipState.REJECTED), None)

      response.status mustBe StatusCodes.OK

    }

    "onboard an organization with a legal and a delegate (MANAGER ACTIVE for a different product)" in {

      val response =
        performOnboardingRequest(Some(PartyManagementDependency.RelationshipState.ACTIVE), Some("product1"))

      response.status mustBe StatusCodes.OK

    }

    "onboard an organization with a legal and a delegate (MANAGER SUSPENDED for a different product)" in {

      val response =
        performOnboardingRequest(Some(PartyManagementDependency.RelationshipState.SUSPENDED), Some("product1"))

      response.status mustBe StatusCodes.OK

    }

    "not create operators if does not exists any active legal for a given institution" in {

      val taxCode1 = "operator1TaxCode"
      val taxCode2 = "operator2TaxCode"

      val operator1 = User(
        name = "operator1",
        surname = "operator1",
        taxCode = taxCode1,
        role = PartyProcess.PartyRole.OPERATOR,
        email = Some("operat@ore.it"),
        product = "product",
        productRole = "admin"
      )
      val operator2 = User(
        name = "operator2",
        surname = "operator2",
        taxCode = taxCode2,
        role = PartyProcess.PartyRole.OPERATOR,
        email = None,
        product = "product",
        productRole = "security"
      )

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(*, *)
        .returning(
          Future.successful(
            PartyManagementDependency.Organization(
              id = UUID.randomUUID(),
              institutionId = "d4r3",
              description = "test",
              digitalAddress = "big@fish.it",
              attributes = Seq.empty,
              taxCode = "123",
              address = "address",
              zipCode = "zipCode"
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
        )(_: String))
        .expects(*, *, *, *, *, *, *)
        .returning(Future.successful(PartyManagementDependency.Relationships(Seq.empty)))

      val req = OnboardingRequest(
        users = Seq(operator1, operator2),
        institutionId = "institutionId1",
        contract = Some(OnboardingContract("a", "b"))
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/operators", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest

    }

    "create operators if exists a legal active for a given institution" in {

      val taxCode1       = "operator1TaxCode"
      val taxCode2       = "operator2TaxCode"
      val institutionId1 = UUID.randomUUID()
      val personPartyId1 = "bf80fac0-2775-4646-8fcf-28e083751900"
      val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1.toString,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq.empty,
        taxCode = "123",
        address = "address",
        zipCode = "zipCode"
      )

      val relationships =
        PartyManagementDependency.Relationships(
          Seq(
            PartyManagementDependency.Relationship(
              id = UUID.randomUUID(),
              from = UUID.fromString(personPartyId1),
              to = institutionId1,
              role = PartyManagementDependency.PartyRole.MANAGER,
              product = product,
              state = PartyManagementDependency.RelationshipState.ACTIVE,
              createdAt = relationshipTimestamp,
              updatedAt = None
            )
          )
        )

      val operatorId1 = UUID.randomUUID()
      val operatorId2 = UUID.randomUUID()

      val operator1 = User(
        name = "operator1",
        surname = "operator1",
        taxCode = taxCode1,
        role = PartyProcess.PartyRole.OPERATOR,
        email = Some("mario@ros.si"),
        product = "product",
        productRole = "security"
      )
      val operator2 = User(
        name = "operator2",
        surname = "operator2",
        taxCode = taxCode2,
        role = PartyProcess.PartyRole.OPERATOR,
        email = None,
        product = "product",
        productRole = "security"
      )

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(*, *)
        .returning(Future.successful(organization1))

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(None, Some(UUID.fromString(orgPartyId1)), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *)
        .returning(Future.successful(relationships))

      (mockUserRegistryService
        .createUser(_: UserRegistryUserSeed))
        .expects(
          UserRegistryUserSeed(
            externalId = operator1.taxCode,
            name = operator1.name,
            surname = operator1.surname,
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = operator1.email, birthDate = None)
          )
        )
        .returning(
          Future.successful(
            UserRegistryUser(
              id = operatorId1,
              externalId = operator1.taxCode,
              name = operator1.name,
              surname = operator1.surname,
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(email = operator1.email, birthDate = None)
            )
          )
        )
        .once()

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String))
        .expects(PartyManagementDependency.PersonSeed(operatorId1), *)
        .returning(Future.successful(PartyManagementDependency.Person(operatorId1)))
        .once()

      (mockUserRegistryService
        .createUser(_: UserRegistryUserSeed))
        .expects(
          UserRegistryUserSeed(
            externalId = operator2.taxCode,
            name = operator2.name,
            surname = operator2.surname,
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = operator2.email, birthDate = None)
          )
        )
        .returning(
          Future.successful(
            UserRegistryUser(
              id = operatorId2,
              externalId = operator2.taxCode,
              name = operator2.name,
              surname = operator2.surname,
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(email = operator2.email, birthDate = None)
            )
          )
        )
        .once()

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String))
        .expects(PartyManagementDependency.PersonSeed(operatorId2), *)
        .returning(Future.successful(PartyManagementDependency.Person(operatorId2)))
        .once()

      (mockPartyManagementService
        .createRelationship(_: RelationshipSeed)(_: String))
        .expects(*, *)
        .returning(Future.successful(relationship))
        .repeat(2)

      val req = OnboardingRequest(
        users = Seq(operator1, operator2),
        institutionId = institutionId1.toString,
        contract = Some(OnboardingContract("a", "b"))
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/operators", HttpMethods.POST)

      response.status mustBe StatusCodes.OK

    }

    "not create subdelegates if does not exists any active legal for a given institution" in {

      val taxCode1 = "subdelegate1TaxCode"
      val taxCode2 = "subdelegate2TaxCode"

      val subdelegate1 = User(
        name = "subdelegate1",
        surname = "subdelegate1",
        taxCode = taxCode1,
        role = PartyProcess.PartyRole.SUB_DELEGATE,
        email = Some("subdel@ore.it"),
        product = "product",
        productRole = "admin"
      )
      val subdelegate2 = User(
        name = "subdelegate2",
        surname = "subdelegate2",
        taxCode = taxCode2,
        role = PartyProcess.PartyRole.SUB_DELEGATE,
        email = None,
        product = "product",
        productRole = "security"
      )

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(*, *)
        .returning(
          Future.successful(
            PartyManagementDependency.Organization(
              id = UUID.randomUUID(),
              institutionId = "d4r3",
              description = "test",
              digitalAddress = "big@fish.it",
              attributes = Seq.empty,
              taxCode = "123",
              address = "address",
              zipCode = "zipCode"
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
        )(_: String))
        .expects(*, *, *, *, *, *, *)
        .returning(Future.successful(PartyManagementDependency.Relationships(Seq.empty)))

      val req = OnboardingRequest(
        users = Seq(subdelegate1, subdelegate2),
        institutionId = "institutionId1",
        contract = Some(OnboardingContract("a", "b"))
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/subdelegates", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest

    }

    "create subdelegates if exists a legal active for a given institution" in {

      val taxCode1       = "subdelegate1TaxCode"
      val taxCode2       = "subdelegate2TaxCode"
      val institutionId1 = UUID.randomUUID()
      val personPartyId1 = "bf80fac0-2775-4646-8fcf-28e083751900"
      val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1.toString,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq.empty,
        taxCode = "123",
        address = "address",
        zipCode = "zipCode"
      )

      val relationships =
        PartyManagementDependency.Relationships(
          Seq(
            PartyManagementDependency.Relationship(
              id = UUID.randomUUID(),
              from = UUID.fromString(personPartyId1),
              to = institutionId1,
              role = PartyManagementDependency.PartyRole.MANAGER,
              product = product,
              state = PartyManagementDependency.RelationshipState.ACTIVE,
              createdAt = relationshipTimestamp,
              updatedAt = None
            )
          )
        )

      val subdelegateId1 = UUID.randomUUID()
      val subdelegateId2 = UUID.randomUUID()

      val subdelegate1 = User(
        name = "subdelegate1",
        surname = "subdelegate1",
        taxCode = taxCode1,
        role = PartyProcess.PartyRole.SUB_DELEGATE,
        email = Some("mario@ros.si"),
        product = "product",
        productRole = "admin"
      )
      val subdelegate2 = User(
        name = "subdelegate2",
        surname = "subdelegate2",
        taxCode = taxCode2,
        role = PartyProcess.PartyRole.SUB_DELEGATE,
        email = None,
        product = "product",
        productRole = "admin"
      )

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(*, *)
        .returning(Future.successful(organization1))

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(None, Some(UUID.fromString(orgPartyId1)), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *)
        .returning(Future.successful(relationships))

      (mockUserRegistryService
        .createUser(_: UserRegistryUserSeed))
        .expects(
          UserRegistryUserSeed(
            externalId = subdelegate1.taxCode,
            name = subdelegate1.name,
            surname = subdelegate1.surname,
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = subdelegate1.email, birthDate = None)
          )
        )
        .returning(
          Future.successful(
            UserRegistryUser(
              id = subdelegateId1,
              externalId = subdelegate1.taxCode,
              name = subdelegate1.name,
              surname = subdelegate1.surname,
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(email = subdelegate1.email, birthDate = None)
            )
          )
        )
        .once()

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String))
        .expects(PartyManagementDependency.PersonSeed(subdelegateId1), *)
        .returning(Future.successful(PartyManagementDependency.Person(subdelegateId1)))
        .once()

      (mockUserRegistryService
        .createUser(_: UserRegistryUserSeed))
        .expects(
          UserRegistryUserSeed(
            externalId = subdelegate2.taxCode,
            name = subdelegate2.name,
            surname = subdelegate2.surname,
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = subdelegate2.email, birthDate = None)
          )
        )
        .returning(
          Future.successful(
            UserRegistryUser(
              id = subdelegateId2,
              externalId = subdelegate2.taxCode,
              name = subdelegate2.name,
              surname = subdelegate2.surname,
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(email = subdelegate2.email, birthDate = None)
            )
          )
        )
        .once()

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String))
        .expects(PartyManagementDependency.PersonSeed(subdelegateId2), *)
        .returning(Future.successful(PartyManagementDependency.Person(subdelegateId2)))
        .once()

      (mockPartyManagementService
        .createRelationship(_: RelationshipSeed)(_: String))
        .expects(*, *)
        .returning(Future.successful(relationship))
        .repeat(2)

      val req = OnboardingRequest(
        users = Seq(subdelegate1, subdelegate2),
        institutionId = institutionId1.toString,
        contract = Some(OnboardingContract("a", "b"))
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
      val institutionId: UUID          = UUID.randomUUID()

      val token: TokenInfo =
        TokenInfo(
          id = tokenId,
          checksum = "6ddee820d06383127ef029f571285339",
          legals = Seq(
            RelationshipBinding(partyId = partyIdManager, relationshipId = relationshipIdManager),
            RelationshipBinding(partyId = partyIdDelegate, relationshipId = relationshipIdDelegate)
          )
        )
      val path = Paths.get("src/test/resources/contract-test-01.pdf")

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
        .verifySignatureForm(_: SignedDocumentValidator))
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
        .getToken(_: UUID)(_: String))
        .expects(tokenId, *)
        .returning(Future.successful(token))

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(*, *)
        .returning(
          Future.successful(
            Relationship(
              id = relationshipIdManager,
              from = relationshipIdDelegate,
              to = institutionId,
              filePath = None,
              fileName = None,
              contentType = None,
              tokenId = None,
              role = PartyManagementDependency.PartyRole.MANAGER,
              product = PartyManagementDependency.RelationshipProduct("product", "admin", OffsetDateTime.now()),
              state = PartyManagementDependency.RelationshipState.PENDING,
              createdAt = OffsetDateTime.now(),
              updatedAt = None
            )
          )
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(*, *)
        .returning(
          Future.successful(
            Relationship(
              id = relationshipIdDelegate,
              from = partyIdDelegate,
              to = institutionId,
              filePath = None,
              fileName = None,
              contentType = None,
              tokenId = None,
              role = PartyManagementDependency.PartyRole.DELEGATE,
              product = PartyManagementDependency.RelationshipProduct("product", "admin", OffsetDateTime.now()),
              state = PartyManagementDependency.RelationshipState.PENDING,
              createdAt = OffsetDateTime.now(),
              updatedAt = None
            )
          )
        )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(partyIdManager)
        .returning(
          Future.successful(
            UserRegistryUser(
              id = partyIdManager,
              externalId = managerTaxCode,
              name = "",
              surname = "",
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(None, None)
            )
          )
        )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(partyIdDelegate)
        .returning(
          Future.successful(
            UserRegistryUser(
              id = partyIdDelegate,
              externalId = delegateTaxCode,
              name = "",
              surname = "",
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(None, None)
            )
          )
        )

      (mockPartyManagementService
        .consumeToken(_: UUID, _: (FileInfo, File))(_: String))
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
              entity = formData.toEntity,
              headers = authorization
            )
          )
          .futureValue

      response.status mustBe StatusCodes.OK

    }

    "fail trying to confirm a token on already confirmed" in {

      val tokenId: UUID                = UUID.randomUUID()
      val partyIdManager: UUID         = UUID.randomUUID()
      val relationshipIdManager: UUID  = UUID.randomUUID()
      val partyIdDelegate: UUID        = UUID.randomUUID()
      val relationshipIdDelegate: UUID = UUID.randomUUID()
      val institutionId: UUID          = UUID.randomUUID()

      val token: TokenInfo =
        TokenInfo(
          id = tokenId,
          checksum = "6ddee820d06383127ef029f571285339",
          legals = Seq(
            RelationshipBinding(partyId = partyIdManager, relationshipId = relationshipIdManager),
            RelationshipBinding(partyId = partyIdDelegate, relationshipId = relationshipIdDelegate)
          )
        )
      val path = Paths.get("src/test/resources/contract-test-01.pdf")

      (mockPartyManagementService
        .getToken(_: UUID)(_: String))
        .expects(tokenId, *)
        .returning(Future.successful(token))

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(*, *)
        .returning(
          Future.successful(
            Relationship(
              id = relationshipIdManager,
              from = relationshipIdDelegate,
              to = institutionId,
              filePath = None,
              fileName = None,
              contentType = None,
              tokenId = None,
              role = PartyManagementDependency.PartyRole.MANAGER,
              product = PartyManagementDependency.RelationshipProduct("product", "admin", OffsetDateTime.now()),
              state = PartyManagementDependency.RelationshipState.ACTIVE,
              createdAt = OffsetDateTime.now(),
              updatedAt = None
            )
          )
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(*, *)
        .returning(
          Future.successful(
            Relationship(
              id = relationshipIdDelegate,
              from = partyIdDelegate,
              to = institutionId,
              filePath = None,
              fileName = None,
              contentType = None,
              tokenId = None,
              role = PartyManagementDependency.PartyRole.DELEGATE,
              product = PartyManagementDependency.RelationshipProduct("product", "admin", OffsetDateTime.now()),
              state = PartyManagementDependency.RelationshipState.ACTIVE,
              createdAt = OffsetDateTime.now(),
              updatedAt = None
            )
          )
        )

      val formData =
        Multipart.FormData.fromPath(name = "contract", MediaTypes.`application/octet-stream`, file = path, 100000)

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/onboarding/complete/${tokenId.toString}",
              method = HttpMethods.POST,
              entity = formData.toEntity,
              headers = authorization
            )
          )
          .futureValue

      response.status mustBe StatusCodes.BadRequest

    }

    "delete token" in {

      val tokenId: UUID                = UUID.randomUUID()
      val relationshipIdManager: UUID  = UUID.randomUUID()
      val partyIdManager: UUID         = UUID.randomUUID()
      val relationshipIdDelegate: UUID = UUID.randomUUID()
      val partyIdDelegate: UUID        = UUID.randomUUID()
      val institutionId: UUID          = UUID.randomUUID()

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
        .getToken(_: UUID)(_: String))
        .expects(tokenId, *)
        .returning(Future.successful(token))

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(*, *)
        .returning(
          Future.successful(
            Relationship(
              id = relationshipIdManager,
              from = relationshipIdDelegate,
              to = institutionId,
              filePath = None,
              fileName = None,
              contentType = None,
              tokenId = None,
              role = PartyManagementDependency.PartyRole.MANAGER,
              product = PartyManagementDependency.RelationshipProduct("product", "admin", OffsetDateTime.now()),
              state = PartyManagementDependency.RelationshipState.PENDING,
              createdAt = OffsetDateTime.now(),
              updatedAt = None
            )
          )
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(*, *)
        .returning(
          Future.successful(
            Relationship(
              id = relationshipIdDelegate,
              from = partyIdDelegate,
              to = institutionId,
              filePath = None,
              fileName = None,
              contentType = None,
              tokenId = None,
              role = PartyManagementDependency.PartyRole.DELEGATE,
              product = PartyManagementDependency.RelationshipProduct("product", "admin", OffsetDateTime.now()),
              state = PartyManagementDependency.RelationshipState.PENDING,
              createdAt = OffsetDateTime.now(),
              updatedAt = None
            )
          )
        )

      (mockPartyManagementService
        .invalidateToken(_: UUID)(_: String))
        .expects(tokenId, *)
        .returning(Future.successful(()))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/onboarding/complete/${tokenId.toString}",
              method = HttpMethods.DELETE,
              headers = authorization
            )
          )
          .futureValue

      response.status mustBe StatusCodes.OK

    }

    "fail trying to delete a token on already confirmed" in {

      val tokenId: UUID                = UUID.randomUUID()
      val relationshipIdManager: UUID  = UUID.randomUUID()
      val partyIdManager: UUID         = UUID.randomUUID()
      val relationshipIdDelegate: UUID = UUID.randomUUID()
      val partyIdDelegate: UUID        = UUID.randomUUID()
      val institutionId: UUID          = UUID.randomUUID()

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
        .getToken(_: UUID)(_: String))
        .expects(tokenId, *)
        .returning(Future.successful(token))

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(*, *)
        .returning(
          Future.successful(
            Relationship(
              id = relationshipIdManager,
              from = relationshipIdDelegate,
              to = institutionId,
              filePath = None,
              fileName = None,
              contentType = None,
              tokenId = None,
              role = PartyManagementDependency.PartyRole.MANAGER,
              product = PartyManagementDependency.RelationshipProduct("product", "admin", OffsetDateTime.now()),
              state = PartyManagementDependency.RelationshipState.ACTIVE,
              createdAt = OffsetDateTime.now(),
              updatedAt = None
            )
          )
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(*, *)
        .returning(
          Future.successful(
            Relationship(
              id = relationshipIdDelegate,
              from = partyIdDelegate,
              to = institutionId,
              filePath = None,
              fileName = None,
              contentType = None,
              tokenId = None,
              role = PartyManagementDependency.PartyRole.DELEGATE,
              product = PartyManagementDependency.RelationshipProduct("product", "admin", OffsetDateTime.now()),
              state = PartyManagementDependency.RelationshipState.ACTIVE,
              createdAt = OffsetDateTime.now(),
              updatedAt = None
            )
          )
        )

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/onboarding/complete/${tokenId.toString}",
              method = HttpMethods.DELETE,
              headers = authorization
            )
          )
          .futureValue

      response.status mustBe StatusCodes.BadRequest

    }

    "retrieve all the relationships of a specific institution when requested by the admin" in {
      val adminIdentifier   = UUID.randomUUID()
      val userId1           = UUID.randomUUID()
      val userId2           = UUID.randomUUID()
      val userId3           = UUID.randomUUID()
      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId1     = UUID.randomUUID()
      val relationshipId2     = UUID.randomUUID()
      val relationshipId3     = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId1,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = userId2,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = product.copy(role = "security"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = product.copy(role = "api"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships = PartyManagementDependency.Relationships(items = Seq(adminRelationship))

      val relationships =
        PartyManagementDependency.Relationships(items =
          Seq(adminRelationship, relationship1, relationship2, relationship3)
        )

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(adminIdentifier.toString))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(adminIdentifier),
          Some(institutionIdUUID),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
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
        )(_: String))
        .expects(None, Some(institutionIdUUID), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *)
        .returning(Future.successful(relationships))
        .once()

      val userRegistryUser = UserRegistryUser(
        id = adminIdentifier,
        externalId = "taxCode",
        name = "name",
        surname = "surname",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(adminIdentifier)
        .returning(Future.successful(userRegistryUser))
        .once()

      val userRegistryUser1 = UserRegistryUser(
        id = userId1,
        externalId = "taxCode",
        name = "name1",
        surname = "surname1",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email1@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(userId1)
        .returning(Future.successful(userRegistryUser1))
        .once()

      val userRegistryUser2 = UserRegistryUser(
        id = userId2,
        externalId = "taxCode",
        name = "name2",
        surname = "surname2",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email2@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(userId2)
        .returning(Future.successful(userRegistryUser2))
        .once()

      val userRegistryUser3 = UserRegistryUser(
        id = userId3,
        externalId = "taxCode",
        name = "name3",
        surname = "surname3",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email3@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(userId3)
        .returning(Future.successful(userRegistryUser3))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(adminIdentifier.toString)))
      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/relationships",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only (RelationshipInfo(
        id = adminRelationshipId,
        from = adminIdentifier,
        name = "name",
        surname = "surname",
        email = Some("email@mail.com"),
        role = PartyProcess.PartyRole.DELEGATE,
        product = productInfo,
        state = PartyProcess.RelationshipState.ACTIVE,
        createdAt = relationshipTimestamp,
        updatedAt = None
      ),
      RelationshipInfo(
        id = relationshipId1,
        from = userId1,
        name = "name1",
        surname = "surname1",
        email = Some("email1@mail.com"),
        role = PartyProcess.PartyRole.MANAGER,
        product = productInfo,
        state = PartyProcess.RelationshipState.ACTIVE,
        createdAt = relationshipTimestamp,
        updatedAt = None
      ),
      RelationshipInfo(
        id = relationshipId2,
        from = userId2,
        name = "name2",
        surname = "surname2",
        email = Some("email2@mail.com"),
        role = PartyProcess.PartyRole.OPERATOR,
        product = productInfo.copy(role = "security"),
        state = PartyProcess.RelationshipState.ACTIVE,
        createdAt = relationshipTimestamp,
        updatedAt = None
      ),
      RelationshipInfo(
        id = relationshipId3,
        from = userId3,
        name = "name3",
        surname = "surname3",
        email = Some("email3@mail.com"),
        role = PartyProcess.PartyRole.OPERATOR,
        product = productInfo.copy(role = "api"),
        state = PartyProcess.RelationshipState.ACTIVE,
        createdAt = relationshipTimestamp,
        updatedAt = None
      ))

    }

    "retrieve all the relationships of a specific institution with filter by productRole when requested by the admin" in {
      val adminIdentifier   = UUID.randomUUID()
      val userId1           = UUID.randomUUID()
      val userId2           = UUID.randomUUID()
      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId1     = UUID.randomUUID()
      val relationshipId2     = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId1,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = product.copy(role = "security"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = userId2,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = product.copy(role = "api"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships =
        PartyManagementDependency.Relationships(items = Seq(adminRelationship))

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship1, relationship2))

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(adminIdentifier.toString))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(adminIdentifier),
          Some(institutionIdUUID),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
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
        )(_: String))
        .expects(
          None,
          Some(institutionIdUUID),
          Seq.empty,
          Seq.empty,
          Seq.empty,
          Seq("security", "api"),
          adminIdentifier.toString
        )
        .returning(Future.successful(relationships))
        .once()

      val userRegistryUser2 = UserRegistryUser(
        id = userId1,
        externalId = "taxCode",
        name = "name2",
        surname = "surname2",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email2@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(userId1)
        .returning(Future.successful(userRegistryUser2))
        .once()

      val userRegistryUser3 = UserRegistryUser(
        id = userId2,
        externalId = "taxCode",
        name = "name3",
        surname = "surname3",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email3@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(userId2)
        .returning(Future.successful(userRegistryUser3))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(adminIdentifier.toString)))
      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/relationships?productRoles=security,api",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only (RelationshipInfo(
        id = relationshipId1,
        from = userId1,
        name = "name2",
        surname = "surname2",
        email = Some("email2@mail.com"),
        role = PartyProcess.PartyRole.OPERATOR,
        product = productInfo.copy(role = "security"),
        state = PartyProcess.RelationshipState.ACTIVE,
        createdAt = relationshipTimestamp,
        updatedAt = None
      ),
      RelationshipInfo(
        id = relationshipId2,
        from = userId2,
        name = "name3",
        surname = "surname3",
        email = Some("email3@mail.com"),
        role = PartyProcess.PartyRole.OPERATOR,
        product = productInfo.copy(role = "api"),
        state = PartyProcess.RelationshipState.ACTIVE,
        createdAt = relationshipTimestamp,
        updatedAt = None
      ))

    }

    "retrieve only the relationships of a specific role when the current user is not an admin" in {
      val adminIdentifier   = UUID.randomUUID()
      val userId1           = UUID.randomUUID()
      val userId2           = UUID.randomUUID()
      val userId3           = UUID.randomUUID()
      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId1     = UUID.randomUUID()
      val relationshipId2     = UUID.randomUUID()
      val relationshipId3     = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId1,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = userId2,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = product.copy(role = "security"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = product.copy(role = "api"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationships =
        PartyManagementDependency.Relationships(items =
          Seq(adminRelationship, relationship1, relationship2, relationship3)
        )

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(userId2.toString))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(userId2),
          Some(institutionIdUUID),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
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
        )(_: String))
        .expects(None, Some(institutionIdUUID), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *)
        .returning(Future.successful(relationships))
        .once()

      val userRegistryUser2 = UserRegistryUser(
        id = userId2,
        externalId = "taxCode",
        name = "name2",
        surname = "surname2",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email2@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(userId2)
        .returning(Future.successful(userRegistryUser2))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(userId2.toString)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/relationships",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only
        RelationshipInfo(
          id = relationshipId2,
          from = userId2,
          name = "name2",
          surname = "surname2",
          email = Some("email2@mail.com"),
          role = PartyProcess.PartyRole.OPERATOR,
          product = productInfo.copy(role = "security"),
          state = PartyProcess.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
    }

    "retrieve all the relationships of a specific institution with filter by productRole and products" in {
      val adminIdentifier   = UUID.randomUUID()
      val userId1           = UUID.randomUUID()
      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId1     = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId1,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = product.copy(id = "PDND", role = "security"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship1))

      val adminRelationships = PartyManagementDependency.Relationships(items = Seq(adminRelationship))

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(adminIdentifier.toString))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(adminIdentifier),
          Some(institutionIdUUID),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
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
        )(_: String))
        .expects(None, Some(institutionIdUUID), Seq.empty, Seq.empty, Seq("PDND"), Seq("security", "api"), *)
        .returning(Future.successful(relationships))
        .once()

      val userRegistryUser2 = UserRegistryUser(
        id = userId1,
        externalId = "taxCode",
        name = "name2",
        surname = "surname2",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email2@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(userId1)
        .returning(Future.successful(userRegistryUser2))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(adminIdentifier.toString)))
      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/relationships?productRoles=security,api&products=PDND",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only RelationshipInfo(
        id = relationshipId1,
        from = userId1,
        name = "name2",
        surname = "surname2",
        email = Some("email2@mail.com"),
        role = PartyRole.OPERATOR,
        product = productInfo.copy(id = "PDND", role = "security"),
        state = RelationshipState.ACTIVE,
        createdAt = relationshipTimestamp,
        updatedAt = None
      )
    }

    "retrieve all the relationships of a specific institution with filter by states requested by admin" in {
      val adminIdentifier   = UUID.randomUUID()
      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val relationshipId1 = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = adminIdentifier,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships =
        PartyManagementDependency.Relationships(items = Seq(adminRelationship))
      val relationships =
        PartyManagementDependency.Relationships(items = Seq(adminRelationship))

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(adminIdentifier.toString))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(adminIdentifier),
          Some(institutionIdUUID),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
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
        )(_: String))
        .expects(
          None,
          Some(institutionIdUUID),
          Seq.empty,
          Seq(PartyManagementDependency.RelationshipState.ACTIVE),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      val userRegistryUser = UserRegistryUser(
        id = adminIdentifier,
        externalId = "taxCode",
        name = "name1",
        surname = "surname1",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email1@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(adminIdentifier)
        .returning(Future.successful(userRegistryUser))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(adminIdentifier.toString)))
      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/relationships?states=ACTIVE",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only RelationshipInfo(
        id = relationshipId1,
        from = adminIdentifier,
        name = "name1",
        surname = "surname1",
        email = Some("email1@mail.com"),
        role = PartyRole.MANAGER,
        product = productInfo,
        state = RelationshipState.ACTIVE,
        createdAt = relationshipTimestamp,
        updatedAt = None
      )
    }

    "retrieve all the relationships of a specific institution with filter by roles when requested by admin" in {
      val adminIdentifier   = UUID.randomUUID()
      val userId2           = UUID.randomUUID()
      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId2     = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = userId2,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = product,
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships =
        PartyManagementDependency.Relationships(items = Seq(adminRelationship))
      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship2))

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(adminIdentifier.toString))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(adminIdentifier),
          Some(institutionIdUUID),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
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
        )(_: String))
        .expects(
          None,
          Some(institutionIdUUID),
          Seq(PartyManagementDependency.PartyRole.DELEGATE),
          Seq.empty,
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(relationships))
        .once()

      val userRegistryUser2 = UserRegistryUser(
        id = userId2,
        externalId = "taxCode",
        name = "name2",
        surname = "surname2",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email2@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(userId2)
        .returning(Future.successful(userRegistryUser2))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(adminIdentifier.toString)))
      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/relationships?roles=DELEGATE",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body must contain only RelationshipInfo(
        id = relationshipId2,
        from = userId2,
        name = "name2",
        surname = "surname2",
        email = Some("email2@mail.com"),
        role = PartyRole.DELEGATE,
        product = productInfo,
        state = RelationshipState.PENDING,
        createdAt = relationshipTimestamp,
        updatedAt = None
      )
    }

    "retrieve all the relationships of a specific institution using all filters" in {
      val adminIdentifier   = UUID.randomUUID()
      val userId3           = UUID.randomUUID()
      val userId4           = UUID.randomUUID()
      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val adminRelationshipId = UUID.randomUUID()
      val relationshipId3     = UUID.randomUUID()
      val relationshipId4     = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = product.copy(id = "PDND", role = "security"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val relationship4 =
        PartyManagementDependency.Relationship(
          id = relationshipId4,
          from = userId4,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = product.copy(id = "PDND", role = "api"),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships =
        PartyManagementDependency.Relationships(items = Seq(adminRelationship))

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship3, relationship4))

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(adminIdentifier.toString))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(adminIdentifier),
          Some(institutionIdUUID),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
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
        )(_: String))
        .expects(
          None,
          Some(institutionIdUUID),
          Seq(PartyManagementDependency.PartyRole.OPERATOR),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE),
          Seq("PDND"),
          Seq("security", "api"),
          *
        )
        .returning(Future.successful(relationships))
        .once()

      val userRegistryUser3 = UserRegistryUser(
        id = userId3,
        externalId = "taxCode",
        name = "name3",
        surname = "surname3",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email3@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(userId3)
        .returning(Future.successful(userRegistryUser3))
        .once()

      val userRegistryUser4 = UserRegistryUser(
        id = userId4,
        externalId = "taxCode",
        name = "name4",
        surname = "surname4",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = Some("email4@mail.com"), birthDate = None)
      )

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(userId4)
        .returning(Future.successful(userRegistryUser4))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(adminIdentifier.toString)))
      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri =
                s"$url/institutions/$institutionId/relationships?productRoles=security,api&products=PDND&roles=OPERATOR&states=ACTIVE",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[Seq[RelationshipInfo]].futureValue
      response.status mustBe StatusCodes.OK
      body mustBe Seq(
        RelationshipInfo(
          id = relationshipId3,
          from = userId3,
          name = "name3",
          surname = "surname3",
          email = Some("email3@mail.com"),
          role = PartyRole.OPERATOR,
          product = productInfo.copy(id = "PDND", role = "security"),
          state = RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        RelationshipInfo(
          id = relationshipId4,
          from = userId4,
          name = "name4",
          surname = "surname4",
          email = Some("email4@mail.com"),
          role = PartyRole.OPERATOR,
          product = productInfo.copy(id = "PDND", role = "api"),
          state = RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      )
    }

    "retrieve all the relationships of a specific institution with all filter when no intersection occurs" in {
      val adminIdentifier   = UUID.randomUUID()
      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val adminRelationshipId = UUID.randomUUID()

      val adminRelationship =
        PartyManagementDependency.Relationship(
          id = adminRelationshipId,
          from = adminIdentifier,
          to = institutionIdUUID,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      val adminRelationships =
        PartyManagementDependency.Relationships(items = Seq(adminRelationship))
      val relationships =
        PartyManagementDependency.Relationships(items = Seq())

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(adminIdentifier.toString))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          Some(adminIdentifier),
          Some(institutionIdUUID),
          Seq(
            PartyManagementDependency.PartyRole.MANAGER,
            PartyManagementDependency.PartyRole.DELEGATE,
            PartyManagementDependency.PartyRole.SUB_DELEGATE
          ),
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
          Seq.empty,
          Seq.empty,
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
        )(_: String))
        .expects(
          None,
          Some(institutionIdUUID),
          Seq(PartyManagementDependency.PartyRole.OPERATOR),
          Seq(PartyManagementDependency.RelationshipState.PENDING),
          Seq("Interop", "PDND"),
          Seq("security", "api"),
          *
        )
        .returning(Future.successful(relationships))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(adminIdentifier.toString)))
      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri =
                s"$url/institutions/$institutionId/relationships?productRoles=security,api&products=Interop,PDND&roles=OPERATOR&states=PENDING",
              method = HttpMethods.GET,
              headers = authorization
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
      val institutionId  = UUID.randomUUID()
      val product        = "product"
      val productRole    = "productRole"
      val relationshipId = UUID.randomUUID()

      val relationship =
        PartyManagementDependency.Relationship(
          id = relationshipId,
          from = userId,
          to = institutionId,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          product = PartyManagementDependency
            .RelationshipProduct(id = product, role = productRole, createdAt = productTimestamp),
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(relationshipId, *)
        .returning(Future.successful(relationship))

      (mockPartyManagementService
        .activateRelationship(_: UUID)(_: String))
        .expects(relationshipId, *)
        .returning(Future.successful(()))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/relationships/$relationshipId/activate",
              method = HttpMethods.POST,
              headers = authorization
            )
          )
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
            .RelationshipProduct(id = product, role = productRole, createdAt = productTimestamp),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(relationshipId, *)
        .returning(Future.successful(relationships))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/relationships/$relationshipId/activate",
              method = HttpMethods.POST,
              headers = authorization
            )
          )
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
            .RelationshipProduct(id = product, role = productRole, createdAt = productTimestamp),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(relationshipId, *)
        .returning(Future.successful(relationship))

      (mockPartyManagementService
        .suspendRelationship(_: UUID)(_: String))
        .expects(relationshipId, *)
        .returning(Future.successful(()))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/relationships/$relationshipId/suspend",
              method = HttpMethods.POST,
              headers = authorization
            )
          )
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
            .RelationshipProduct(id = product, role = productRole, createdAt = productTimestamp),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      (mockPartyManagementService
        .getRelationshipById(_: UUID)(_: String))
        .expects(relationshipId, *)
        .returning(Future.successful(relationship))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/relationships/$relationshipId/suspend",
              method = HttpMethods.POST,
              headers = authorization
            )
          )
          .futureValue

      response.status mustBe StatusCodes.BadRequest

    }

  }

  "Relationship removal" must {
    "succeed when the relationship id is bound to the selected institution" in {
      val relationshipId = UUID.randomUUID()

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(relationshipId.toString))
        .once()

      (mockPartyManagementService
        .deleteRelationshipById(_: UUID)(_: String))
        .expects(relationshipId, *)
        .returning(Future.successful(()))

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(relationshipId.toString)))

      val response = Await.result(
        Http().singleRequest(
          HttpRequest(uri = s"$url/relationships/$relationshipId", method = HttpMethods.DELETE, headers = authorization)
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NoContent

    }

    "fail if party management deletion returns a failed future" in {
      val relationshipId = UUID.randomUUID()

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(relationshipId.toString))
        .once()

      (mockPartyManagementService
        .deleteRelationshipById(_: UUID)(_: String))
        .expects(relationshipId, *)
        .returning(Future.failed(new RuntimeException("Party Management Error")))

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(relationshipId.toString)))

      val response = Await.result(
        Http().singleRequest(
          HttpRequest(uri = s"$url/relationships/$relationshipId", method = HttpMethods.DELETE, headers = authorization)
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NotFound

    }
  }

  "Users creation" must {
    "create users" in {
      val taxCode1       = "managerTaxCode"
      val taxCode2       = "delegateTaxCode"
      val institutionId1 = "IST2"
      val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq.empty,
        taxCode = "123",
        address = "address",
        zipCode = "zipCode"
      )

      val file = new File("src/test/resources/fake.file")

      val managerId  = UUID.randomUUID()
      val delegateId = UUID.randomUUID()
      val manager =
        User(
          name = "manager",
          surname = "manager",
          taxCode = taxCode1,
          role = PartyRole.MANAGER,
          product = "product",
          productRole = "admin",
          email = None
        )
      val delegate =
        User(
          name = "delegate",
          surname = "delegate",
          taxCode = taxCode2,
          role = PartyRole.DELEGATE,
          product = "product",
          productRole = "admin",
          email = None
        )

      val relationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization1.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(Success(jwtClaimsSet))
        .once()

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(*)
        .returning(
          Future.successful(
            UserRegistryUser(
              id = UUID.randomUUID(),
              externalId = "",
              name = "",
              surname = "",
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(email = None, birthDate = None)
            )
          )
        )
        .once()

      (mockSignatureService
        .createDigest(_: File))
        .expects(*)
        .returning(Future.successful("hash"))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(*, *)
        .returning(Future.successful(organization1))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(None, Some(organization1.id), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *)
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq(relationship))))
        .once()

      (mockUserRegistryService
        .createUser(_: UserRegistryUserSeed))
        .expects(
          UserRegistryUserSeed(
            externalId = manager.taxCode,
            name = manager.name,
            surname = manager.surname,
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = manager.email, birthDate = None)
          )
        )
        .returning(
          Future.successful(
            UserRegistryUser(
              id = managerId,
              externalId = manager.taxCode,
              name = manager.name,
              surname = manager.surname,
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(email = manager.email, birthDate = None)
            )
          )
        )
        .once()

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String))
        .expects(PartyManagementDependency.PersonSeed(managerId), *)
        .returning(Future.successful(PartyManagementDependency.Person(managerId)))
        .once()

      (mockUserRegistryService
        .createUser(_: UserRegistryUserSeed))
        .expects(
          UserRegistryUserSeed(
            externalId = delegate.taxCode,
            name = delegate.name,
            surname = delegate.surname,
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = delegate.email, birthDate = None)
          )
        )
        .returning(
          Future.successful(
            UserRegistryUser(
              id = delegateId,
              externalId = delegate.taxCode,
              name = delegate.name,
              surname = delegate.surname,
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(email = delegate.email, birthDate = None)
            )
          )
        )
        .once()

      (mockPartyManagementService
        .createPerson(_: PartyManagementDependency.PersonSeed)(_: String))
        .expects(PartyManagementDependency.PersonSeed(delegateId), *)
        .returning(Future.successful(PartyManagementDependency.Person(delegateId)))
        .once()

      (mockPartyManagementService
        .createRelationship(_: RelationshipSeed)(_: String))
        .expects(*, *)
        .returning(Future.successful(relationship))
        .repeat(2)
      (mockFileManager
        .get(_: String)(_: String))
        .expects(*, *)
        .returning(Future.successful(new ByteArrayOutputStream()))
        .once()
      (mockPdfCreator.createContract _).expects(*, *, *).returning(Future.successful(file)).once()
      (mockPartyManagementService
        .createToken(_: PartyManagementDependency.Relationships, _: String, _: String, _: String)(_: String))
        .expects(*, *, *, *, *)
        .returning(Future.successful(PartyManagementDependency.TokenText("token")))
        .once()

      val req = OnboardingRequest(
        users = Seq(manager, delegate),
        institutionId = "institutionId1",
        contract = Some(OnboardingContract("a", "b"))
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.OK

    }

    "not create users when no active manager exist for a relationship" in {
      val taxCode1       = "managerTaxCode"
      val taxCode2       = "delegateTaxCode"
      val institutionId1 = "IST2"
      val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq.empty,
        taxCode = "123",
        address = "address",
        zipCode = "zipCode"
      )

      val managerId = UUID.randomUUID()
      val manager =
        User(
          name = "manager",
          surname = "manager",
          taxCode = taxCode1,
          role = PartyRole.MANAGER,
          product = "product",
          productRole = "admin",
          email = None
        )
      val delegate =
        User(
          name = "delegate",
          surname = "delegate",
          taxCode = taxCode2,
          role = PartyRole.DELEGATE,
          product = "product",
          productRole = "admin",
          email = None
        )

      val relationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization1.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product =
            PartyManagementDependency.RelationshipProduct(id = "product", role = "admin", createdAt = productTimestamp),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(Success(jwtClaimsSet))
        .once()

      (mockUserRegistryService
        .getUserById(_: UUID))
        .expects(*)
        .returning(
          Future.successful(
            UserRegistryUser(
              id = UUID.randomUUID(),
              externalId = "",
              name = "",
              surname = "",
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(email = None, birthDate = None)
            )
          )
        )
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(*, *)
        .returning(Future.successful(organization1))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(None, Some(organization1.id), Seq.empty, Seq.empty, Seq.empty, Seq.empty, *)
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq(relationship))))
        .once()

      val req = OnboardingRequest(
        users = Seq(manager, delegate),
        institutionId = "institutionId1",
        contract = Some(OnboardingContract("a", "b"))
      )

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest
    }
  }

  "Institution products retrieval" must {

    "retrieve products" in {
      val uid = "bf80fac0-2775-4646-8fcf-28e083751901"

      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val managerId        = UUID.randomUUID()
      val activeProduct    = "activeProduct"
      val pendingProduct   = "pendingProduct"
      val suspendedProduct = "suspendedProduct"

      val relationships = Seq(
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = activeProduct),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = pendingProduct),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = suspendedProduct),
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      )

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(uid))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          None,
          Some(organization.id),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(managerId.toString)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/products",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[ModelProducts].futureValue

      val expected = Set(
        Product(id = activeProduct, ProductState.ACTIVE),
        Product(id = pendingProduct, ProductState.PENDING),
        Product(id = suspendedProduct, ProductState.ACTIVE)
      )

      body.products.toSet mustBe expected
    }

    "retrieve products asking PENDING" in {
      val uid = "bf80fac0-2775-4646-8fcf-28e083751901"

      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
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
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = pendingProduct),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = activeProduct),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = suspendedProduct),
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = deletedProduct),
          state = PartyManagementDependency.RelationshipState.DELETED,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      )

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(uid))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          None,
          Some(organization.id),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(managerId.toString)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/products?states=PENDING",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[ModelProducts].futureValue

      val expected = Product(pendingProduct, ProductState.PENDING)

      body.products must contain only expected
    }

    "retrieve products asking ACTIVE" in {
      val uid = "bf80fac0-2775-4646-8fcf-28e083751901"

      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
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
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = pendingProduct),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = activeProduct),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = suspendedProduct),
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = deletedProduct),
          state = PartyManagementDependency.RelationshipState.DELETED,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = deletedProduct),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      )

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(uid))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          None,
          Some(organization.id),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(managerId.toString)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/products?states=ACTIVE",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[ModelProducts].futureValue

      val expected = Set(
        Product(activeProduct, ProductState.ACTIVE),
        Product(suspendedProduct, ProductState.ACTIVE),
        Product(deletedProduct, ProductState.ACTIVE)
      )

      body.products.toSet mustBe expected
    }

    "retrieve products asking ACTIVE/PENDING" in {
      val uid = "bf80fac0-2775-4646-8fcf-28e083751901"

      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
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
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = pendingProduct),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = activeProduct),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = suspendedProduct),
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = deletedProduct),
          state = PartyManagementDependency.RelationshipState.DELETED,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      )

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(uid))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          None,
          Some(organization.id),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(managerId.toString)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/products?states=ACTIVE,PENDING",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[ModelProducts].futureValue

      val expected = Seq(
        Product(deletedProduct, ProductState.ACTIVE),
        Product(pendingProduct, ProductState.PENDING),
        Product(suspendedProduct, ProductState.ACTIVE),
        Product(activeProduct, ProductState.ACTIVE)
      )
      body.products mustBe expected
    }

    "retrieve products asking ACTIVE/PENDING even when there are only PENDING products" in {
      val uid = "bf80fac0-2775-4646-8fcf-28e083751901"

      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val managerId       = UUID.randomUUID()
      val pendingProduct1 = "pendingProduct1"
      val pendingProduct2 = "pendingProduct2"

      val relationships = Seq(
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = pendingProduct1),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = pendingProduct2),
          state = PartyManagementDependency.RelationshipState.PENDING,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      )

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(uid))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          None,
          Some(organization.id),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(managerId.toString)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/products?states=ACTIVE,PENDING",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[ModelProducts].futureValue

      val expected = Set(Product(pendingProduct1, ProductState.PENDING), Product(pendingProduct2, ProductState.PENDING))

      body.products.toSet mustBe expected
    }

    "retrieve products asking ACTIVE/PENDING even when there are only ACTIVE products" in {
      val uid = "bf80fac0-2775-4646-8fcf-28e083751901"

      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val managerId      = UUID.randomUUID()
      val activeProduct1 = "activeProduct1"
      val activeProduct2 = "activeProduct2"

      val relationships = Seq(
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = activeProduct1),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        ),
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          product = product.copy(id = activeProduct2),
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          createdAt = relationshipTimestamp,
          updatedAt = None
        )
      )

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(uid))
        .once()

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          None,
          Some(organization.id),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = relationships)))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(managerId.toString)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/products?states=ACTIVE,PENDING",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[ModelProducts].futureValue

      val expected = Set(Product(activeProduct1, ProductState.ACTIVE), Product(activeProduct2, ProductState.ACTIVE))

      body.products.toSet mustBe expected
    }

    "retrieve no products" in {
      val uid               = "bf80fac0-2775-4646-8fcf-28e083751901"
      val institutionId     = "institutionId"
      val institutionIdUUID = UUID.randomUUID()

      val organization = Organization(
        id = institutionIdUUID,
        institutionId = institutionId,
        description = "",
        digitalAddress = "",
        taxCode = "",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      (mockPartyManagementService
        .retrieveOrganizationByExternalId(_: String)(_: String))
        .expects(institutionId, *)
        .returning(Future.successful(organization))
        .once()

      val managerId = UUID.randomUUID()

      (mockJWTReader
        .getClaims(_: String))
        .expects(*)
        .returning(mockUid(uid))
        .once()

      (mockPartyManagementService
        .retrieveRelationships(
          _: Option[UUID],
          _: Option[UUID],
          _: Seq[PartyManagementDependency.PartyRole],
          _: Seq[PartyManagementDependency.RelationshipState],
          _: Seq[String],
          _: Seq[String]
        )(_: String))
        .expects(
          None,
          Some(organization.id),
          Seq(PartyManagementDependency.PartyRole.MANAGER),
          Seq(
            PartyManagementDependency.RelationshipState.PENDING,
            PartyManagementDependency.RelationshipState.ACTIVE,
            PartyManagementDependency.RelationshipState.SUSPENDED,
            PartyManagementDependency.RelationshipState.DELETED
          ),
          Seq.empty,
          Seq.empty,
          *
        )
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq.empty)))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken(managerId.toString)))

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/products?states=ACTIVE",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      response.status mustBe StatusCodes.NotFound
    }

  }

}
