package it.pagopa.pdnd.interop.uservice.partyprocess

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.server.directives.{AuthenticationDirective, SecurityDirectives}
import akka.http.scaladsl.unmarshalling.Unmarshal
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.model.{
  Attribute => ClientAttribute,
  AttributesResponse
}
import it.pagopa.pdnd.interop.uservice.authorizationprocess.client.model.ValidJWT
import it.pagopa.pdnd.interop.uservice.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.ProcessApi
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.Conversions.{relationshipStateToApi, roleToApi}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.ProcessApiServiceImpl
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{classicActorSystem, executionContext}
import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.{model => PartyProcess}
import it.pagopa.pdnd.interop.uservice.partyprocess.server.Controller
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.{Categories, Category, Institution, Manager}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{
  User => UserRegistryUser,
  UserExtras => UserRegistryUserExtras,
  UserSeed => UserRegistryUserSeed
}

import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.Certification.{
  NONE => CertificationEnumsNone
}
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{Products => ModelProducts}
import it.pagopa.pdnd.interop.commons.utils.AkkaUtils.Authenticator
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json.DefaultJsonProtocol

import java.io.File
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}

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

  override def beforeAll(): Unit = {
    System.setProperty("DELEGATE_PRODUCT_ROLES", "admin")
    System.setProperty("OPERATOR_PRODUCT_ROLES", "security, api")
    System.setProperty("MANAGER_PRODUCT_ROLES", "admin")
    System.setProperty("STORAGE_TYPE", "File")
    System.setProperty("STORAGE_CONTAINER", "local")
    System.setProperty("STORAGE_ENDPOINT", "local")
    System.setProperty("STORAGE_APPLICATION_ID", "local")
    System.setProperty("STORAGE_APPLICATION_SECRET", "local")
    System.setProperty("PARTY_MANAGEMENT_URL", "local")
    System.setProperty("PARTY_PROXY_URL", "local")
    System.setProperty("ATTRIBUTE_REGISTRY_URL", "local")
    System.setProperty("AUTHORIZATION_PROCESS_URL", "local")
    System.setProperty("USER_REGISTRY_MANAGEMENT_URL", "local")

    System.setProperty("SMTP_SERVER", "localhost")
    System.setProperty("SMTP_USR", "local")
    System.setProperty("SMTP_PSW", "local")
    System.setProperty("SMTP_PORT", "10")
    System.setProperty("MAIL_SENDER_ADDRESS", "betta@dante.it")
    System.setProperty("MAIL_TEMPLATE_PATH", "localPath")

    val processApi = new ProcessApi(
      new ProcessApiServiceImpl(
        mockPartyManagementService,
        mockPartyRegistryService,
        mockAttributeRegistryService,
        mockAuthorizationProcessService,
        mockUserRegistryService,
        mockPdfCreator,
        mockFileManager,
        mockMailer,
        mockMailTemplate
      ),
      processApiMarshaller,
      wrappingDirective
    )

    controller = Some(new Controller(mockHealthApi, mockPlatformApi, processApi))

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

  "Processing a request payload" must {

    "retrieve a onboarding info" in {
      val taxCode1       = "CF1"
      val institutionId1 = UUID.randomUUID()
      val institutionId2 = UUID.randomUUID()
      val personPartyId1 = "af80fac0-2775-4646-8fcf-28e083751900"
      val orgPartyId1    = "af80fac0-2775-4646-8fcf-28e083751901"
      val orgPartyId2    = "af80fac0-2775-4646-8fcf-28e083751902"
      val person1 = UserRegistryUser(
        id = UUID.fromString(personPartyId1),
        externalId = "CF1",
        name = "Mario",
        surname = "Rossi",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = None, birthDate = None)
      )

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = person1.id,
          to = institutionId1,
          role = PartyManagementDependency.PartyRole.MANAGER,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )
      val relationship2 =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = person1.id,
          to = institutionId2,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )

      val relationships = PartyManagementDependency.Relationships(items = Seq(relationship1, relationship2))

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1.toString,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq("1", "2", "3"),
        products = Set.empty,
        taxCode = "123"
      )
      val organization2 = PartyManagementDependency.Organization(
        institutionId = institutionId2.toString,
        description = "org2",
        digitalAddress = "digitalAddress2",
        id = UUID.fromString(orgPartyId2),
        attributes = Seq("99", "100", "101"),
        products = Set.empty,
        taxCode = "123"
      )

      val expected = OnBoardingInfo(
        person = PersonInfo(name = person1.name, surname = person1.surname, taxCode = person1.externalId),
        institutions = Seq(
          OnboardingData(
            institutionId = organization1.institutionId,
            description = organization1.description,
            digitalAddress = organization1.digitalAddress,
            state = relationshipStateToApi(relationship1.state),
            role = roleToApi(relationship1.role),
            productRole = relationship1.productRole,
            relationshipProducts = relationship1.products,
            attributes = Seq(Attribute("1", "name1", "description1")),
            institutionProducts = Set.empty
          ),
          OnboardingData(
            institutionId = organization2.institutionId,
            description = organization2.description,
            digitalAddress = organization2.digitalAddress,
            state = relationshipStateToApi(relationship2.state),
            role = roleToApi(relationship2.role),
            relationshipProducts = relationship2.products,
            productRole = relationship2.productRole,
            attributes = Seq(Attribute("2", "name2", "description2")),
            institutionProducts = Set.empty
          )
        )
      )

      val mockSubjectUUID = "af80fac0-2775-4646-8fcf-28e083751988"
      (mockAuthorizationProcessService.validateToken _)
        .expects(*)
        .returning(
          Future.successful(
            ValidJWT(
              iss = UUID.randomUUID().toString,
              sub = mockSubjectUUID,
              aud = List("test"),
              exp = OffsetDateTime.now(),
              nbf = OffsetDateTime.now(),
              iat = OffsetDateTime.now(),
              jti = "123"
            )
          )
        )
        .once()

      (mockUserRegistryService.getUserById _)
        .expects(UUID.fromString(mockSubjectUUID))
        .returning(
          Future.successful(
            UserRegistryUser(
              id = UUID.fromString(mockSubjectUUID),
              externalId = taxCode1,
              name = "Mario",
              surname = "Rossi",
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(email = Some("super@mario.it"), birthDate = None)
            )
          )
        )
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(Some(UUID.fromString(mockSubjectUUID)), None, None)
        .returning(Future.successful(relationships))
        .once()
      (mockPartyManagementService.retrieveOrganization _)
        .expects(institutionId1)
        .returning(Future.successful(organization1))
        .once()
      (mockPartyManagementService.retrieveOrganization _)
        .expects(institutionId2)
        .returning(Future.successful(organization2))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
      val response = Await.result(
        Http().singleRequest(
          HttpRequest(uri = s"$url/onboarding/info", method = HttpMethods.GET, headers = authorization)
        ),
        Duration.Inf
      )

      val body = Unmarshal(response.entity).to[OnBoardingInfo].futureValue

      response.status mustBe StatusCodes.OK
      body mustBe expected

    }

    "retrieve an onboarding info with institution id filter" in {
      val taxCode1       = "CF1"
      val institutionId1 = UUID.randomUUID()
      val personPartyId1 = "af80fac0-2775-4646-8fcf-28e083751800"
      val orgPartyId1    = "af80fac0-2775-4646-8fcf-28e083751801"
      val person1 = UserRegistryUser(
        id = UUID.fromString(personPartyId1),
        externalId = "CF1",
        name = "Mario",
        surname = "Rossi",
        certification = CertificationEnumsNone,
        extras = UserRegistryUserExtras(email = None, birthDate = None)
      )

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = person1.id,
          to = institutionId1,
          role = PartyManagementDependency.PartyRole.MANAGER,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )

      val relationships = PartyManagementDependency.Relationships(items = Seq(relationship1))

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1.toString,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq("1", "2", "3"),
        products = Set.empty,
        taxCode = "123"
      )

      val expected = OnBoardingInfo(
        person = PersonInfo(name = person1.name, surname = person1.surname, taxCode = person1.externalId),
        institutions = Seq(
          OnboardingData(
            institutionId = organization1.institutionId,
            description = organization1.description,
            digitalAddress = organization1.digitalAddress,
            state = relationshipStateToApi(relationship1.state),
            role = roleToApi(relationship1.role),
            productRole = relationship1.productRole,
            attributes = Seq(Attribute("1", "name", "description")),
            institutionProducts = Set.empty,
            relationshipProducts = Set.empty
          )
        )
      )

      val mockSubjectUUID = UUID.randomUUID().toString
      (mockAuthorizationProcessService.validateToken _)
        .expects(*)
        .returning(
          Future.successful(
            ValidJWT(
              iss = UUID.randomUUID().toString,
              sub = mockSubjectUUID,
              aud = List("test"),
              exp = OffsetDateTime.now(),
              nbf = OffsetDateTime.now(),
              iat = OffsetDateTime.now(),
              jti = "123"
            )
          )
        )
        .once()

      (mockUserRegistryService.getUserById _)
        .expects(UUID.fromString(mockSubjectUUID))
        .returning(
          Future.successful(
            UserRegistryUser(
              id = UUID.fromString(mockSubjectUUID),
              externalId = taxCode1,
              name = "Mario",
              surname = "Rossi",
              certification = CertificationEnumsNone,
              extras = UserRegistryUserExtras(email = Some("super@mario.it"), birthDate = None)
            )
          )
        )
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(Some(UUID.fromString(mockSubjectUUID)), Some(institutionId1), None)
        .returning(Future.successful(relationships))
        .once()
      (mockPartyManagementService.retrieveOrganization _)
        .expects(institutionId1)
        .returning(Future.successful(organization1))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
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

      val body = Unmarshal(response.entity).to[OnBoardingInfo].futureValue

      response.status mustBe StatusCodes.OK
      body mustBe expected

    }

    "fail the onboarding info retrieval when the institution id filter contains an invalid string" in {
      val mockSubjectUUID = "af80fac0-2775-4646-8fcf-28e083751988"
      (mockAuthorizationProcessService.validateToken _)
        .expects(*)
        .returning(
          Future.successful(
            ValidJWT(
              iss = UUID.randomUUID().toString,
              sub = mockSubjectUUID,
              aud = List("test"),
              exp = OffsetDateTime.now(),
              nbf = OffsetDateTime.now(),
              iat = OffsetDateTime.now(),
              jti = "123"
            )
          )
        )
        .once()

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

    "onboard an organization with a legal and a delegate" in {
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
          category = "category",
          manager = Manager("name", "surname"),
          description = "description",
          digitalAddress = "digitalAddress"
        )

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq.empty,
        products = Set.empty,
        taxCode = "123"
      )

      val attr1 = AttributesResponse(
        Seq(
          ClientAttribute(
            id = "1",
            code = Some("1"),
            certified = true,
            description = "attrs",
            origin = Some("test"),
            name = "C17",
            creationTime = OffsetDateTime.now()
          )
        )
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
          productRole = "admin",
          email = None,
          products = Set.empty
        )
      val delegate =
        User(
          name = "delegate",
          surname = "delegate",
          taxCode = taxCode2,
          role = PartyProcess.PartyRole.DELEGATE,
          productRole = "admin",
          email = None,
          products = Set.empty
        )

      (mockPartyRegistryService.getInstitution _).expects(*).returning(Future.successful(institution1)).once()
      (() => mockPartyRegistryService.getCategories)
        .expects()
        .returning(Future.successful(Categories(Seq(Category("C17", "attrs", "test")))))
        .once()
      (mockPartyManagementService.createOrganization _).expects(*).returning(Future.successful(organization1)).once()
      (mockUserRegistryService.createUser _)
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

      (mockPartyManagementService.createPerson _)
        .expects(PartyManagementDependency.PersonSeed(managerId))
        .returning(Future.successful(PartyManagementDependency.Person(managerId)))
        .once()

      (mockUserRegistryService.createUser _)
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

      (mockPartyManagementService.createPerson _)
        .expects(PartyManagementDependency.PersonSeed(delegateId))
        .returning(Future.successful(PartyManagementDependency.Person(delegateId)))
        .once()

      (mockPartyManagementService.createRelationship _)
        .expects(*, *, *, *, *)
        .returning(Future.successful(()))
        .repeat(2)
      (mockAttributeRegistryService.createAttribute _).expects(*, *, *).returning(Future.successful(attr1)).once()
      (mockPdfCreator.create _).expects(*, *).returning(Future.successful((file, "hash"))).once()
      (mockPartyManagementService.createToken _)
        .expects(*, *)
        .returning(Future.successful(PartyManagementDependency.TokenText("token")))
        .once()

      (mockMailer.sendMail(mockMailTemplate) _).expects(*, *, *).returning(Future.successful(())).once()

      val req = OnBoardingRequest(users = Seq(manager, delegate), institutionId = "institutionId1")

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/organization", HttpMethods.POST)

      response.status mustBe StatusCodes.Created

    }

    "not create operators if does not exists any active legal for a given institution" in {

      val taxCode1 = "operator1TaxCode"
      val taxCode2 = "operator2TaxCode"

      val operator1 = User(
        name = "operator1",
        surname = "operator1",
        taxCode = taxCode1,
        role = PartyProcess.PartyRole.OPERATOR,
        productRole = "security",
        email = Some("operat@ore.it"),
        products = Set("PDND")
      )
      val operator2 = User(
        name = "operator2",
        surname = "operator2",
        taxCode = taxCode2,
        role = PartyProcess.PartyRole.OPERATOR,
        productRole = "security",
        email = None,
        products = Set("PDND")
      )

      (mockPartyManagementService.retrieveOrganizationByExternalId _)
        .expects(*)
        .returning(
          Future.successful(
            PartyManagementDependency.Organization(
              id = UUID.randomUUID(),
              institutionId = "d4r3",
              description = "test",
              digitalAddress = "big@fish.it",
              attributes = Seq.empty,
              products = Set.empty,
              taxCode = "123"
            )
          )
        )
      (mockPartyManagementService.retrieveRelationships _)
        .expects(*, *, *)
        .returning(Future.successful(PartyManagementDependency.Relationships(Seq.empty)))

      val req = OnBoardingRequest(users = Seq(operator1, operator2), institutionId = "institutionId1")

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
        products = Set.empty,
        taxCode = "123"
      )

      val relationships =
        PartyManagementDependency.Relationships(
          Seq(
            PartyManagementDependency.Relationship(
              id = UUID.randomUUID(),
              from = UUID.fromString(personPartyId1),
              to = institutionId1,
              role = PartyManagementDependency.PartyRole.MANAGER,
              productRole = "admin",
              state = PartyManagementDependency.RelationshipState.ACTIVE,
              products = Set.empty
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
        productRole = "security",
        email = Some("mario@ros.si"),
        products = Set("PDND")
      )
      val operator2 = User(
        name = "operator2",
        surname = "operator2",
        taxCode = taxCode2,
        role = PartyProcess.PartyRole.OPERATOR,
        productRole = "security",
        email = None,
        products = Set("PDND")
      )

      (mockPartyManagementService.retrieveOrganizationByExternalId _)
        .expects(*)
        .returning(Future.successful(organization1))
      (mockPartyManagementService.retrieveRelationships _)
        .expects(None, Some(UUID.fromString(orgPartyId1)), None)
        .returning(Future.successful(relationships))

      (mockUserRegistryService.createUser _)
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

      (mockPartyManagementService.createPerson _)
        .expects(PartyManagementDependency.PersonSeed(operatorId1))
        .returning(Future.successful(PartyManagementDependency.Person(operatorId1)))
        .once()

      (mockUserRegistryService.createUser _)
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

      (mockPartyManagementService.createPerson _)
        .expects(PartyManagementDependency.PersonSeed(operatorId2))
        .returning(Future.successful(PartyManagementDependency.Person(operatorId2)))
        .once()

      (mockPartyManagementService.createRelationship _)
        .expects(*, *, *, *, *)
        .returning(Future.successful(()))
        .repeat(2)

      val req = OnBoardingRequest(users = Seq(operator1, operator2), institutionId = institutionId1.toString)

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/operators", HttpMethods.POST)

      response.status mustBe StatusCodes.Created

    }

    "confirm token" in {

      val token: String =
        "eyJjaGVja3N1bSI6IjZkZGVlODIwZDA2MzgzMTI3ZWYwMjlmNTcxMjg1MzM5IiwiaWQiOiI0YjJmY2Y3My1iMmI0LTQ4N2QtYjk2MC1jM2MwNGQ5NDc3YzItM2RiZDk0ZDUtMzY0MS00MWI0LWJlMGItZjJmZjZjODU4Zjg5LU1hbmFnZXIiLCJsZWdhbHMiOlt7ImZyb20iOiI0NjAwNzg4Mi0wMDNlLTRlM2EtODMzMC1iNGYyYjA0NGJmNGUiLCJyb2xlIjoiRGVsZWdhdGUiLCJ0byI6IjNkYmQ5NGQ1LTM2NDEtNDFiNC1iZTBiLWYyZmY2Yzg1OGY4OSJ9LHsiZnJvbSI6IjRiMmZjZjczLWIyYjQtNDg3ZC1iOTYwLWMzYzA0ZDk0NzdjMiIsInJvbGUiOiJNYW5hZ2VyIiwidG8iOiIzZGJkOTRkNS0zNjQxLTQxYjQtYmUwYi1mMmZmNmM4NThmODkifV0sInNlZWQiOiJkMmE2ZWYyNy1hZTYwLTRiM2QtOGE5ZS1iMDIwMzViZDUyYzkiLCJ2YWxpZGl0eSI6IjIwMjEtMDctMTNUMTU6MTY6NDguNTU1NDM1KzAyOjAwIn0="

      val path = Paths.get("src/test/resources/contract-test-01.pdf")

      (mockPartyManagementService.consumeToken _).expects(token, *).returning(Future.successful(()))

      val formData =
        Multipart.FormData.fromPath(name = "contract", MediaTypes.`application/octet-stream`, file = path, 100000)

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/onboarding/complete/$token",
              method = HttpMethods.POST,
              entity = formData.toEntity,
              headers = authorization
            )
          )
          .futureValue

      response.status mustBe StatusCodes.OK

    }

    "delete token" in {

      val token: String =
        "eyJjaGVja3N1bSI6IjZkZGVlODIwZDA2MzgzMTI3ZWYwMjlmNTcxMjg1MzM5IiwiaWQiOiI0YjJmY2Y3My1iMmI0LTQ4N2QtYjk2MC1jM2MwNGQ5NDc3YzItM2RiZDk0ZDUtMzY0MS00MWI0LWJlMGItZjJmZjZjODU4Zjg5LU1hbmFnZXIiLCJsZWdhbHMiOlt7ImZyb20iOiI0NjAwNzg4Mi0wMDNlLTRlM2EtODMzMC1iNGYyYjA0NGJmNGUiLCJyb2xlIjoiRGVsZWdhdGUiLCJ0byI6IjNkYmQ5NGQ1LTM2NDEtNDFiNC1iZTBiLWYyZmY2Yzg1OGY4OSJ9LHsiZnJvbSI6IjRiMmZjZjczLWIyYjQtNDg3ZC1iOTYwLWMzYzA0ZDk0NzdjMiIsInJvbGUiOiJNYW5hZ2VyIiwidG8iOiIzZGJkOTRkNS0zNjQxLTQxYjQtYmUwYi1mMmZmNmM4NThmODkifV0sInNlZWQiOiJkMmE2ZWYyNy1hZTYwLTRiM2QtOGE5ZS1iMDIwMzViZDUyYzkiLCJ2YWxpZGl0eSI6IjIwMjEtMDctMTNUMTU6MTY6NDguNTU1NDM1KzAyOjAwIn0="

      (mockPartyManagementService.invalidateToken _).expects(token).returning(Future.successful(()))

      val response =
        Http()
          .singleRequest(
            HttpRequest(uri = s"$url/onboarding/complete/$token", method = HttpMethods.DELETE, headers = authorization)
          )
          .futureValue

      response.status mustBe StatusCodes.OK

    }

    "retrieve all the relationships of a specific institution" in {
      val userId          = UUID.randomUUID()
      val adminIdentifier = UUID.randomUUID()
      val userId3         = UUID.randomUUID()
      val userId4         = UUID.randomUUID()
      val institutionId   = UUID.randomUUID()

      val relationshipId1 = UUID.randomUUID()
      val relationshipId2 = UUID.randomUUID()
      val relationshipId3 = UUID.randomUUID()
      val relationshipId4 = UUID.randomUUID()

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )
      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = adminIdentifier,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          productRole = "security",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set("PDND")
        )

      val relationship4 =
        PartyManagementDependency.Relationship(
          id = relationshipId4,
          from = userId4,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          productRole = "api",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set("PDND")
        )

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship1, relationship2, relationship3, relationship4))

      (mockAuthorizationProcessService.validateToken _)
        .expects(*)
        .returning(
          Future.successful(
            ValidJWT(
              iss = UUID.randomUUID().toString,
              sub = adminIdentifier.toString,
              aud = List("test"),
              exp = OffsetDateTime.now(),
              nbf = OffsetDateTime.now(),
              iat = OffsetDateTime.now(),
              jti = "123"
            )
          )
        )
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(Some(adminIdentifier), Some(institutionId), None)
        .returning(Future.successful(relationships))
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(None, Some(institutionId), None)
        .returning(Future.successful(relationships))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
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
        id = relationshipId1,
        from = userId,
        role = PartyProcess.PartyRole.MANAGER,
        productRole = "admin",
        state = PartyProcess.RelationshipState.ACTIVE,
        products = Set.empty
      ),
      RelationshipInfo(
        id = relationshipId2,
        from = adminIdentifier,
        role = PartyProcess.PartyRole.DELEGATE,
        productRole = "admin",
        state = PartyProcess.RelationshipState.ACTIVE,
        products = Set.empty
      ),
      RelationshipInfo(
        id = relationshipId3,
        from = userId3,
        role = PartyProcess.PartyRole.OPERATOR,
        productRole = "security",
        state = PartyProcess.RelationshipState.ACTIVE,
        products = Set("PDND")
      ),
      RelationshipInfo(
        id = relationshipId4,
        from = userId4,
        role = PartyProcess.PartyRole.OPERATOR,
        productRole = "api",
        state = PartyProcess.RelationshipState.ACTIVE,
        products = Set("PDND")
      ))

    }

    "retrieve all the relationships of a specific institution with filter by productRole" in {
      val userId          = UUID.randomUUID()
      val adminIdentifier = UUID.randomUUID()
      val userId3         = UUID.randomUUID()
      val userId4         = UUID.randomUUID()
      val institutionId   = UUID.randomUUID()

      val relationshipId1 = UUID.randomUUID()
      val relationshipId2 = UUID.randomUUID()
      val relationshipId3 = UUID.randomUUID()
      val relationshipId4 = UUID.randomUUID()

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )
      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = adminIdentifier,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          productRole = "security",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set("PDND")
        )

      val relationship4 =
        PartyManagementDependency.Relationship(
          id = relationshipId4,
          from = userId4,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          productRole = "api",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set("PDND")
        )

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship1, relationship2, relationship3, relationship4))

      (mockAuthorizationProcessService.validateToken _)
        .expects(*)
        .returning(
          Future.successful(
            ValidJWT(
              iss = UUID.randomUUID().toString,
              sub = adminIdentifier.toString,
              aud = List("test"),
              exp = OffsetDateTime.now(),
              nbf = OffsetDateTime.now(),
              iat = OffsetDateTime.now(),
              jti = "123"
            )
          )
        )
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(Some(adminIdentifier), Some(institutionId), None)
        .returning(Future.successful(relationships))
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(None, Some(institutionId), None)
        .returning(Future.successful(relationships))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
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
        id = relationshipId3,
        from = userId3,
        role = PartyProcess.PartyRole.OPERATOR,
        productRole = "security",
        state = PartyProcess.RelationshipState.ACTIVE,
        products = Set("PDND")
      ),
      RelationshipInfo(
        id = relationshipId4,
        from = userId4,
        role = PartyProcess.PartyRole.OPERATOR,
        productRole = "api",
        state = PartyProcess.RelationshipState.ACTIVE,
        products = Set("PDND")
      ))

    }

    "retrieve only the relationships of a specific role when the current user is not an admin" in {
      val userId          = UUID.randomUUID()
      val adminIdentifier = UUID.randomUUID()
      val userId3         = UUID.randomUUID()
      val userId4         = UUID.randomUUID()
      val institutionId   = UUID.randomUUID()

      val relationshipId1 = UUID.randomUUID()
      val relationshipId2 = UUID.randomUUID()
      val relationshipId3 = UUID.randomUUID()
      val relationshipId4 = UUID.randomUUID()

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )
      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = adminIdentifier,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          productRole = "security",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set("PDND")
        )

      val relationship4 =
        PartyManagementDependency.Relationship(
          id = relationshipId4,
          from = userId4,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          productRole = "api",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set("PDND")
        )

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship1, relationship2, relationship3, relationship4))
      val selectedRelationships = PartyManagementDependency.Relationships(items = Seq(relationship3))

      (mockAuthorizationProcessService.validateToken _)
        .expects(*)
        .returning(
          Future.successful(
            ValidJWT(
              iss = UUID.randomUUID().toString,
              sub = userId3.toString,
              aud = List("test"),
              exp = OffsetDateTime.now(),
              nbf = OffsetDateTime.now(),
              iat = OffsetDateTime.now(),
              jti = "123"
            )
          )
        )
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(Some(userId3), Some(institutionId), None)
        .returning(Future.successful(selectedRelationships))
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(None, Some(institutionId), None)
        .returning(Future.successful(relationships))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
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
          id = relationshipId3,
          from = userId3,
          role = PartyProcess.PartyRole.OPERATOR,
          productRole = "security",
          state = PartyProcess.RelationshipState.ACTIVE,
          products = Set("PDND")
        )
    }

    "retrieve all the relationships of a specific institution with filter by productRole and products" in {
      val userId          = UUID.randomUUID()
      val adminIdentifier = UUID.randomUUID()
      val userId3         = UUID.randomUUID()
      val userId4         = UUID.randomUUID()
      val institutionId   = UUID.randomUUID()

      val relationshipId1 = UUID.randomUUID()
      val relationshipId2 = UUID.randomUUID()
      val relationshipId3 = UUID.randomUUID()
      val relationshipId4 = UUID.randomUUID()

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )
      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = adminIdentifier,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          productRole = "security",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set("PDND")
        )

      val relationship4 =
        PartyManagementDependency.Relationship(
          id = relationshipId4,
          from = userId4,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          productRole = "api",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set("AppIO")
        )

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship1, relationship2, relationship3, relationship4))

      (mockAuthorizationProcessService.validateToken _)
        .expects(*)
        .returning(
          Future.successful(
            ValidJWT(
              iss = UUID.randomUUID().toString,
              sub = adminIdentifier.toString,
              aud = List("test"),
              exp = OffsetDateTime.now(),
              nbf = OffsetDateTime.now(),
              iat = OffsetDateTime.now(),
              jti = "123"
            )
          )
        )
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(Some(adminIdentifier), Some(institutionId), None)
        .returning(Future.successful(relationships))
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(None, Some(institutionId), None)
        .returning(Future.successful(relationships))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
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
        id = relationshipId3,
        from = userId3,
        role = PartyRole.OPERATOR,
        productRole = "security",
        state = RelationshipState.ACTIVE,
        products = Set("PDND")
      )
    }

    "retrieve all the relationships of a specific institution with filter by productRole and products when no intersection occurs" in {
      val userId          = UUID.randomUUID()
      val adminIdentifier = UUID.randomUUID()
      val userId3         = UUID.randomUUID()
      val userId4         = UUID.randomUUID()
      val institutionId   = UUID.randomUUID()

      val relationshipId1 = UUID.randomUUID()
      val relationshipId2 = UUID.randomUUID()
      val relationshipId3 = UUID.randomUUID()
      val relationshipId4 = UUID.randomUUID()

      val relationship1 =
        PartyManagementDependency.Relationship(
          id = relationshipId1,
          from = userId,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.MANAGER,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )
      val relationship2 =
        PartyManagementDependency.Relationship(
          id = relationshipId2,
          from = adminIdentifier,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.DELEGATE,
          productRole = "admin",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )

      val relationship3 =
        PartyManagementDependency.Relationship(
          id = relationshipId3,
          from = userId3,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          productRole = "security",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set("PDND")
        )

      val relationship4 =
        PartyManagementDependency.Relationship(
          id = relationshipId4,
          from = userId4,
          to = institutionId,
          role = PartyManagementDependency.PartyRole.OPERATOR,
          productRole = "api",
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set("AppIO")
        )

      val relationships =
        PartyManagementDependency.Relationships(items = Seq(relationship1, relationship2, relationship3, relationship4))

      (mockAuthorizationProcessService.validateToken _)
        .expects(*)
        .returning(
          Future.successful(
            ValidJWT(
              iss = UUID.randomUUID().toString,
              sub = adminIdentifier.toString,
              aud = List("test"),
              exp = OffsetDateTime.now(),
              nbf = OffsetDateTime.now(),
              iat = OffsetDateTime.now(),
              jti = "123"
            )
          )
        )
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(Some(adminIdentifier), Some(institutionId), None)
        .returning(Future.successful(relationships))
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(None, Some(institutionId), None)
        .returning(Future.successful(relationships))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$institutionId/relationships?productRoles=security,api&products=Interop,Test",
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
          productRole = productRole,
          state = PartyManagementDependency.RelationshipState.SUSPENDED,
          products = Set.empty
        )

      (mockPartyManagementService.getRelationshipById _)
        .expects(relationshipId)
        .returning(Future.successful(relationship))

      (mockPartyManagementService.activateRelationship _)
        .expects(relationshipId)
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
          productRole = productRole,
          state = PartyManagementDependency.RelationshipState.PENDING,
          products = Set.empty
        )

      (mockPartyManagementService.getRelationshipById _)
        .expects(relationshipId)
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
          productRole = productRole,
          state = PartyManagementDependency.RelationshipState.ACTIVE,
          products = Set.empty
        )

      (mockPartyManagementService.getRelationshipById _)
        .expects(relationshipId)
        .returning(Future.successful(relationship))

      (mockPartyManagementService.suspendRelationship _)
        .expects(relationshipId)
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
          productRole = productRole,
          state = PartyManagementDependency.RelationshipState.PENDING,
          products = Set.empty
        )

      (mockPartyManagementService.getRelationshipById _)
        .expects(relationshipId)
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

      mockSubjectAuthorizationValidation(UUID.randomUUID())

      (mockPartyManagementService.deleteRelationshipById _)
        .expects(relationshipId)
        .returning(Future.successful(()))

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

      mockSubjectAuthorizationValidation(UUID.randomUUID())

      (mockPartyManagementService.deleteRelationshipById _)
        .expects(relationshipId)
        .returning(Future.failed(new RuntimeException("Party Management Error")))

      val response = Await.result(
        Http().singleRequest(
          HttpRequest(uri = s"$url/relationships/$relationshipId", method = HttpMethods.DELETE, headers = authorization)
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NotFound

    }
  }

  private def mockSubjectAuthorizationValidation(mockUUID: UUID) = {
    val mockSubjectUUID = mockUUID.toString
    (mockAuthorizationProcessService.validateToken _)
      .expects(*)
      .returning(
        Future.successful(
          ValidJWT(
            iss = UUID.randomUUID().toString,
            sub = mockSubjectUUID,
            aud = List("test"),
            exp = OffsetDateTime.now(),
            nbf = OffsetDateTime.now(),
            iat = OffsetDateTime.now(),
            jti = "123"
          )
        )
      )
      .once()
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
        products = Set.empty,
        taxCode = "123"
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
          productRole = "admin",
          products = Set.empty,
          email = None
        )
      val delegate =
        User(
          name = "delegate",
          surname = "delegate",
          taxCode = taxCode2,
          role = PartyRole.DELEGATE,
          productRole = "admin",
          products = Set.empty,
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
          productRole = "admin",
          products = Set.empty,
          state = PartyManagementDependency.RelationshipState.ACTIVE
        )

      (mockPartyManagementService.retrieveOrganizationByExternalId _)
        .expects(*)
        .returning(Future.successful(organization1))
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(None, Some(organization1.id), None)
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq(relationship))))
        .once()

      (mockUserRegistryService.createUser _)
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

      (mockPartyManagementService.createPerson _)
        .expects(PartyManagementDependency.PersonSeed(managerId))
        .returning(Future.successful(PartyManagementDependency.Person(managerId)))
        .once()

      (mockUserRegistryService.createUser _)
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

      (mockPartyManagementService.createPerson _)
        .expects(PartyManagementDependency.PersonSeed(delegateId))
        .returning(Future.successful(PartyManagementDependency.Person(delegateId)))
        .once()

      (mockPartyManagementService.createRelationship _)
        .expects(*, *, *, *, *)
        .returning(Future.successful(()))
        .repeat(2)
      (mockPdfCreator.create _).expects(*, *).returning(Future.successful((file, "hash"))).once()
      (mockPartyManagementService.createToken _)
        .expects(*, *)
        .returning(Future.successful(PartyManagementDependency.TokenText("token")))
        .once()
      (mockMailer.sendMail(mockMailTemplate) _).expects(*, *, *).returning(Future.successful(())).once()

      val req = OnBoardingRequest(users = Seq(manager, delegate), institutionId = "institutionId1")

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
        products = Set.empty,
        taxCode = "123"
      )

      val managerId = UUID.randomUUID()
      val manager =
        User(
          name = "manager",
          surname = "manager",
          taxCode = taxCode1,
          role = PartyRole.MANAGER,
          productRole = "admin",
          products = Set.empty,
          email = None
        )
      val delegate =
        User(
          name = "delegate",
          surname = "delegate",
          taxCode = taxCode2,
          role = PartyRole.DELEGATE,
          productRole = "admin",
          products = Set.empty,
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
          productRole = "admin",
          products = Set.empty,
          state = PartyManagementDependency.RelationshipState.PENDING
        )

      (mockPartyManagementService.retrieveOrganizationByExternalId _)
        .expects(*)
        .returning(Future.successful(organization1))
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(None, Some(organization1.id), None)
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq(relationship))))
        .once()

      val req = OnBoardingRequest(users = Seq(manager, delegate), institutionId = "institutionId1")

      val data     = Marshal(req).to[MessageEntity].map(_.dataBytes).futureValue
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest
    }
  }

  "Institution products retrieval" must {
    "retrieve products when the organization had an onboarding" in {
      val institutionId1 = "TAXCODE"
      val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq.empty,
        products = Set("PDND", "APP IO", "APP VOI"),
        taxCode = "123"
      )

      val managerId = UUID.randomUUID()

      val relationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization1.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          productRole = "admin",
          products = Set.empty,
          state = PartyManagementDependency.RelationshipState.ACTIVE
        )

      val mockSubjectUUID = UUID.randomUUID()
      (mockAuthorizationProcessService.validateToken _)
        .expects(*)
        .returning(
          Future.successful(
            ValidJWT(
              iss = UUID.randomUUID().toString,
              sub = mockSubjectUUID.toString,
              aud = List("test"),
              exp = OffsetDateTime.now(),
              nbf = OffsetDateTime.now(),
              iat = OffsetDateTime.now(),
              jti = "123"
            )
          )
        )
        .once()

      (mockPartyManagementService.retrieveOrganization _)
        .expects(*)
        .returning(Future.successful(organization1))
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(None, Some(organization1.id), None)
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq(relationship))))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$orgPartyId1/products",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      val body = Unmarshal(response.entity).to[ModelProducts].futureValue

      body.products must contain only ("PDND", "APP IO", "APP VOI")
    }

    "retrieve no products when the organization had not an onboarding" in {
      val institutionId1 = "TAXCODE"
      val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"

      val organization1 = PartyManagementDependency.Organization(
        institutionId = institutionId1,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq.empty,
        products = Set("PDND", "APP IO", "APP VOI"),
        taxCode = "123"
      )

      val managerId = UUID.randomUUID()

      val relationship =
        PartyManagementDependency.Relationship(
          id = UUID.randomUUID(),
          from = managerId,
          to = organization1.id,
          filePath = None,
          fileName = None,
          contentType = None,
          role = PartyManagementDependency.PartyRole.MANAGER,
          productRole = "admin",
          products = Set.empty,
          state = PartyManagementDependency.RelationshipState.PENDING
        )

      val mockSubjectUUID = UUID.randomUUID()
      (mockAuthorizationProcessService.validateToken _)
        .expects(*)
        .returning(
          Future.successful(
            ValidJWT(
              iss = UUID.randomUUID().toString,
              sub = mockSubjectUUID.toString,
              aud = List("test"),
              exp = OffsetDateTime.now(),
              nbf = OffsetDateTime.now(),
              iat = OffsetDateTime.now(),
              jti = "123"
            )
          )
        )
        .once()

      (mockPartyManagementService.retrieveOrganization _)
        .expects(*)
        .returning(Future.successful(organization1))
        .once()

      (mockPartyManagementService.retrieveRelationships _)
        .expects(None, Some(organization1.id), None)
        .returning(Future.successful(PartyManagementDependency.Relationships(items = Seq(relationship))))
        .once()

      val response =
        Http()
          .singleRequest(
            HttpRequest(
              uri = s"$url/institutions/$orgPartyId1/products",
              method = HttpMethods.GET,
              headers = authorization
            )
          )
          .futureValue

      response.status mustBe StatusCodes.NotFound
    }

  }

}
