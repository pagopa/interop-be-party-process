package it.pagopa.pdnd.interop.uservice.partyprocess

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.{Marshal, ToEntityMarshaller}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.server.directives.{AuthenticationDirective, SecurityDirectives}
import akka.http.scaladsl.unmarshalling.Unmarshal
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.model.{Attribute, AttributesResponse}
import it.pagopa.pdnd.interop.uservice.authorizationprocess.client.model.ValidJWT
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.RelationshipEnums.{Role, Status}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.{ProcessApiMarshallerImpl, ProcessApiServiceImpl, _}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.{HealthApi, PlatformApi, ProcessApi, ProcessApiMarshaller}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{Authenticator, classicActorSystem, executionContext}
import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.server.Controller
import it.pagopa.pdnd.interop.uservice.partyprocess.service._
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.{Categories, Category, Institution}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json.{DefaultJsonProtocol, RootJsonFormat, enrichAny}

import java.io.File
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{User => UserRegistryUser}

class PartyProcessSpec
    extends MockFactory
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with SprayJsonSupport
    with DefaultJsonProtocol {

  val processApiMarshaller: ProcessApiMarshaller               = new ProcessApiMarshallerImpl
  val mockHealthApi: HealthApi                                 = mock[HealthApi]
  val mockPlatformApi: PlatformApi                             = mock[PlatformApi]
  val partyManagementService: PartyManagementService           = mock[PartyManagementService]
  val partyRegistryService: PartyRegistryService               = mock[PartyRegistryService]
  val userRegistryService: UserRegistryManagementService       = mock[UserRegistryManagementService]
  val authorizationProcessService: AuthorizationProcessService = mock[AuthorizationProcessService]
  val attributeRegistryService: AttributeRegistryService       = mock[AttributeRegistryService]
  val mailer: Mailer                                           = mock[Mailer]
  val pdfCreator: PDFCreator                                   = mock[PDFCreator]

  var controller: Option[Controller]                 = None
  var bindServer: Option[Future[Http.ServerBinding]] = None

  val wrappingDirective: AuthenticationDirective[Seq[(String, String)]] =
    SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)

  override def beforeAll(): Unit = {
    System.setProperty("DELEGATE_PLATFORM_ROLES", "admin")
    System.setProperty("OPERATOR_PLATFORM_ROLES", "security, api")
    System.setProperty("MANAGER_PLATFORM_ROLES", "admin")
    System.setProperty("STORAGE_TYPE", "File")
    System.setProperty("STORAGE_CONTAINER", "local")
    System.setProperty("STORAGE_ENDPOINT", "local")
    System.setProperty("STORAGE_APPLICATION_ID", "local")
    System.setProperty("STORAGE_APPLICATION_SECRET", "local")

    val processApi = new ProcessApi(
      new ProcessApiServiceImpl(
        partyManagementService,
        partyRegistryService,
        attributeRegistryService,
        authorizationProcessService,
        userRegistryService,
        mailer,
        pdfCreator,
        mock[FileManager]
      ),
      processApiMarshaller,
      wrappingDirective
    )

    controller = Some(new Controller(mockHealthApi, mockPlatformApi, processApi))

    controller foreach { controller =>
      bindServer = Some(
        Http()
          .newServerAt("0.0.0.0", 8088)
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
        email = ""
      )

      val relationship1 =
        Relationship(
          id = UUID.randomUUID(),
          from = person1.id,
          to = institutionId1,
          role = Role.Manager,
          platformRole = "admin",
          status = Status.Active
        )
      val relationship2 =
        Relationship(
          id = UUID.randomUUID(),
          from = person1.id,
          to = institutionId2,
          role = Role.Delegate,
          platformRole = "admin",
          status = Status.Active
        )

      val relationships = Relationships(items = Seq(relationship1, relationship2))

      val organization1 = Organization(
        institutionId = institutionId1.toString,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq("1", "2", "3")
      )
      val organization2 = Organization(
        institutionId = institutionId2.toString,
        description = "org2",
        digitalAddress = "digitalAddress2",
        id = UUID.fromString(orgPartyId2),
        attributes = Seq("99", "100", "101")
      )

      val expected = OnBoardingInfo(
        person = PersonInfo(name = person1.name, surname = person1.surname, taxCode = person1.externalId),
        institutions = Seq(
          InstitutionInfo(
            institutionId = organization1.institutionId,
            description = organization1.description,
            digitalAddress = organization1.digitalAddress,
            status = relationship1.status.toString,
            role = relationship1.role.toString,
            platformRole = relationship1.platformRole,
            attributes = Seq("1", "2", "3")
          ),
          InstitutionInfo(
            institutionId = organization2.institutionId,
            description = organization2.description,
            digitalAddress = organization2.digitalAddress,
            status = relationship2.status.toString,
            role = relationship2.role.toString,
            platformRole = relationship2.platformRole,
            attributes = Seq("99", "100", "101")
          )
        )
      )

      val mockSubjectUUID = "af80fac0-2775-4646-8fcf-28e083751988"
      (authorizationProcessService.validateToken _)
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

      (userRegistryService.getUserById _)
        .expects(UUID.fromString(mockSubjectUUID))
        .returning(
          Future.successful(
            UserRegistryUser(
              id = UUID.fromString(mockSubjectUUID),
              externalId = taxCode1,
              name = "Mario",
              surname = "Rossi",
              email = "super@mario.it"
            )
          )
        )
        .once()

      (partyManagementService.retrieveRelationship _)
        .expects(Some(taxCode1), None, None)
        .returning(Future.successful(relationships))
        .once()
      (partyManagementService.retrieveOrganization _)
        .expects(institutionId1.toString)
        .returning(Future.successful(organization1))
        .once()
      (partyManagementService.retrieveOrganization _)
        .expects(institutionId2.toString)
        .returning(Future.successful(organization2))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
      val response = Await.result(
        Http().singleRequest(
          HttpRequest(uri = s"$url/onboarding/info", method = HttpMethods.GET, headers = authorization)
        ),
        Duration.Inf
      )

      val body = Await.result(Unmarshal(response.entity).to[OnBoardingInfo], Duration.Inf)

      response.status mustBe StatusCodes.OK
      body mustBe expected

    }

    "create a legals" in {
      val taxCode1       = "managerTaxCode"
      val taxCode2       = "delegateTaxCode"
      val institutionId1 = "IST2"
      val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"
      val institution1 = Institution(
        id = institutionId1,
        o = None,
        ou = None,
        aoo = None,
        taxCode = None,
        administrationCode = None,
        category = Some("C17"),
        managerName = None,
        managerSurname = None,
        description = "institution",
        digitalAddress = None
      )
      val organization1 = Organization(
        institutionId = institutionId1,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq.empty
      )

      val attr1 = AttributesResponse(
        Seq(
          Attribute(
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

      val manager =
        User(
          name = "manager",
          surname = "manager",
          taxCode = taxCode1,
          role = "Manager",
          platformRole = "admin",
          email = None
        )
      val delegate =
        User(
          name = "delegate",
          surname = "delegate",
          taxCode = taxCode2,
          role = "Delegate",
          platformRole = "admin",
          email = None
        )

      (partyRegistryService.getInstitution _).expects(*).returning(Future.successful(institution1)).once()
      (() => partyRegistryService.getCategories)
        .expects()
        .returning(Future.successful(Categories(Seq(Category("C17", "attrs", "test")))))
        .once()
      (partyManagementService.createOrganization _).expects(*).returning(Future.successful(organization1)).once()
      (userRegistryService.upsertUser _)
        .expects(*, *, *, *)
        .returning(
          Future.successful(
            UserRegistryUser(
              id = UUID.randomUUID(),
              externalId = taxCode1,
              name = "manager",
              surname = "manager",
              email = ""
            )
          )
        )
        .once()
      (userRegistryService.upsertUser _)
        .expects(*, *, *, *)
        .returning(
          Future.successful(
            UserRegistryUser(
              id = UUID.randomUUID(),
              externalId = taxCode2,
              name = "delegate",
              surname = "delegate",
              email = ""
            )
          )
        )
        .once()
      (partyManagementService.createRelationship _).expects(*, *, *, *).returning(Future.successful(())).repeat(2)
      (attributeRegistryService.createAttribute _).expects(*, *, *).returning(Future.successful(attr1)).once()
      (pdfCreator.create _).expects(*, *).returning(Future.successful((file, "hash"))).once()
      (partyManagementService.createToken _).expects(*, *).returning(Future.successful(TokenText("token"))).once()
      (mailer.send _).expects(*, *, *).returning(Future.successful(())).once()

      val req = OnBoardingRequest(users = Seq(manager, delegate), institutionId = "institutionId1")

      implicit val userFormat: RootJsonFormat[User]                           = jsonFormat6(User)
      implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest] = jsonFormat2(OnBoardingRequest)

      implicit def fromEntityUnmarshallerOnBoardingRequest: ToEntityMarshaller[OnBoardingRequest] =
        sprayJsonMarshaller[OnBoardingRequest]

      val data     = Await.result(Marshal(req).to[MessageEntity].map(_.dataBytes), Duration.Inf)
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.Created

    }
    "not create operators if does not exists any active legal for a given institution" in {

      val taxCode1 = "operator1TaxCode"
      val taxCode2 = "operator2TaxCode"

      val operator1 = User(
        name = "operator1",
        surname = "operator1",
        taxCode = taxCode1,
        role = "Operator",
        platformRole = "security",
        email = Some("operat@ore.it")
      )
      val operator2 = User(
        name = "operator2",
        surname = "operator2",
        taxCode = taxCode2,
        role = "Operator",
        platformRole = "security",
        email = None
      )
      (partyManagementService.retrieveRelationship _)
        .expects(*, *, *)
        .returning(Future.successful(Relationships(Seq.empty)))

      val req = OnBoardingRequest(users = Seq(operator1, operator2), institutionId = "institutionId1")

      implicit val userFormat: RootJsonFormat[User]                           = jsonFormat6(User)
      implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest] = jsonFormat2(OnBoardingRequest)

      implicit def fromEntityUnmarshallerOnBoardingRequest: ToEntityMarshaller[OnBoardingRequest] =
        sprayJsonMarshaller[OnBoardingRequest]

      val data     = Await.result(Marshal(req).to[MessageEntity].map(_.dataBytes), Duration.Inf)
      val response = request(data, "onboarding/operators", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest

    }

    "create operators if exists a legal active for a given institution" in {

      val taxCode1       = "operator1TaxCode"
      val taxCode2       = "operator2TaxCode"
      val institutionId1 = UUID.randomUUID()
      val personPartyId1 = "bf80fac0-2775-4646-8fcf-28e083751900"
      val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"

      val organization1 = Organization(
        institutionId = institutionId1.toString,
        description = "org1",
        digitalAddress = "digitalAddress1",
        id = UUID.fromString(orgPartyId1),
        attributes = Seq.empty
      )

      val relationships =
        Relationships(
          Seq(
            Relationship(
              id = UUID.randomUUID(),
              from = UUID.fromString(personPartyId1),
              to = institutionId1,
              role = Role.Manager,
              platformRole = "admin",
              status = Status.Active
            )
          )
        )

      val operator1 = User(
        name = "operator1",
        surname = "operator1",
        taxCode = taxCode1,
        role = "Operator",
        platformRole = "security",
        email = Some("mario@ros.si")
      )
      val operator2 = User(
        name = "operator2",
        surname = "operator2",
        taxCode = taxCode2,
        role = "Operator",
        platformRole = "security",
        email = None
      )
      (partyManagementService.retrieveRelationship _)
        .expects(None, Some(institutionId1.toString), None)
        .returning(Future.successful(relationships))
      (partyManagementService.retrieveOrganization _).expects(*).returning(Future.successful(organization1)).once()

      (userRegistryService.upsertUser _)
        .expects(*, *, *, *)
        .returning(
          Future.successful(
            UserRegistryUser(
              id = UUID.randomUUID(),
              externalId = taxCode1,
              name = "operator1",
              surname = "operator1",
              email = "mario@ros.si"
            )
          )
        )
        .once()
      (userRegistryService.upsertUser _)
        .expects(*, *, *, *)
        .returning(
          Future.successful(
            UserRegistryUser(
              id = UUID.randomUUID(),
              externalId = taxCode2,
              name = "operator2",
              surname = "operator2",
              email = ""
            )
          )
        )
        .once()

      (partyManagementService.createRelationship _).expects(*, *, *, *).returning(Future.successful(())).repeat(2)

      val req = OnBoardingRequest(users = Seq(operator1, operator2), institutionId = institutionId1.toString)

      implicit val userFormat: RootJsonFormat[User]                           = jsonFormat6(User)
      implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest] = jsonFormat2(OnBoardingRequest)

      implicit def fromEntityUnmarshallerOnBoardingRequest: ToEntityMarshaller[OnBoardingRequest] =
        sprayJsonMarshaller[OnBoardingRequest]

      val data     = Await.result(Marshal(req).to[MessageEntity].map(_.dataBytes), Duration.Inf)
      val response = request(data, "onboarding/operators", HttpMethods.POST)

      response.status mustBe StatusCodes.Created

    }

    "confirm token" in {

      val token: String =
        "eyJjaGVja3N1bSI6IjZkZGVlODIwZDA2MzgzMTI3ZWYwMjlmNTcxMjg1MzM5IiwiaWQiOiI0YjJmY2Y3My1iMmI0LTQ4N2QtYjk2MC1jM2MwNGQ5NDc3YzItM2RiZDk0ZDUtMzY0MS00MWI0LWJlMGItZjJmZjZjODU4Zjg5LU1hbmFnZXIiLCJsZWdhbHMiOlt7ImZyb20iOiI0NjAwNzg4Mi0wMDNlLTRlM2EtODMzMC1iNGYyYjA0NGJmNGUiLCJyb2xlIjoiRGVsZWdhdGUiLCJ0byI6IjNkYmQ5NGQ1LTM2NDEtNDFiNC1iZTBiLWYyZmY2Yzg1OGY4OSJ9LHsiZnJvbSI6IjRiMmZjZjczLWIyYjQtNDg3ZC1iOTYwLWMzYzA0ZDk0NzdjMiIsInJvbGUiOiJNYW5hZ2VyIiwidG8iOiIzZGJkOTRkNS0zNjQxLTQxYjQtYmUwYi1mMmZmNmM4NThmODkifV0sInNlZWQiOiJkMmE2ZWYyNy1hZTYwLTRiM2QtOGE5ZS1iMDIwMzViZDUyYzkiLCJ2YWxpZGl0eSI6IjIwMjEtMDctMTNUMTU6MTY6NDguNTU1NDM1KzAyOjAwIn0="

      val path = Paths.get("src/test/resources/contract-test-01.pdf")

      (partyManagementService.consumeToken _).expects(token, *).returning(Future.successful(()))

      val formData =
        Multipart.FormData.fromPath(name = "contract", MediaTypes.`application/octet-stream`, file = path, 100000)

      val response = Await.result(
        Http().singleRequest(
          HttpRequest(
            uri = s"$url/onboarding/complete/$token",
            method = HttpMethods.POST,
            entity = formData.toEntity,
            headers = authorization
          )
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.OK

    }

    "delete token" in {

      val token: String =
        "eyJjaGVja3N1bSI6IjZkZGVlODIwZDA2MzgzMTI3ZWYwMjlmNTcxMjg1MzM5IiwiaWQiOiI0YjJmY2Y3My1iMmI0LTQ4N2QtYjk2MC1jM2MwNGQ5NDc3YzItM2RiZDk0ZDUtMzY0MS00MWI0LWJlMGItZjJmZjZjODU4Zjg5LU1hbmFnZXIiLCJsZWdhbHMiOlt7ImZyb20iOiI0NjAwNzg4Mi0wMDNlLTRlM2EtODMzMC1iNGYyYjA0NGJmNGUiLCJyb2xlIjoiRGVsZWdhdGUiLCJ0byI6IjNkYmQ5NGQ1LTM2NDEtNDFiNC1iZTBiLWYyZmY2Yzg1OGY4OSJ9LHsiZnJvbSI6IjRiMmZjZjczLWIyYjQtNDg3ZC1iOTYwLWMzYzA0ZDk0NzdjMiIsInJvbGUiOiJNYW5hZ2VyIiwidG8iOiIzZGJkOTRkNS0zNjQxLTQxYjQtYmUwYi1mMmZmNmM4NThmODkifV0sInNlZWQiOiJkMmE2ZWYyNy1hZTYwLTRiM2QtOGE5ZS1iMDIwMzViZDUyYzkiLCJ2YWxpZGl0eSI6IjIwMjEtMDctMTNUMTU6MTY6NDguNTU1NDM1KzAyOjAwIn0="

      (partyManagementService.invalidateToken _).expects(token).returning(Future.successful(()))

      val response = Await.result(
        Http().singleRequest(
          HttpRequest(uri = s"$url/onboarding/complete/$token", method = HttpMethods.DELETE, headers = authorization)
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.OK

    }

    "retrieve all the relationships of a specific institution" in {
      val userId        = UUID.randomUUID()
      val userId2       = UUID.randomUUID()
      val userId3       = UUID.randomUUID()
      val userId4       = UUID.randomUUID()
      val institutionId = UUID.randomUUID()

      val relationship1 =
        Relationship(
          id = UUID.randomUUID(),
          from = userId,
          to = institutionId,
          role = Role.Manager,
          platformRole = "admin",
          status = Status.Active
        )
      val relationship2 =
        Relationship(
          id = UUID.randomUUID(),
          from = userId2,
          to = institutionId,
          role = Role.Delegate,
          platformRole = "admin",
          status = Status.Active
        )

      val relationship3 =
        Relationship(
          id = UUID.randomUUID(),
          from = userId3,
          to = institutionId,
          role = Role.Operator,
          platformRole = "security",
          status = Status.Active
        )

      val relationship4 =
        Relationship(
          id = UUID.randomUUID(),
          from = userId4,
          to = institutionId,
          role = Role.Operator,
          platformRole = "api",
          status = Status.Active
        )

      val relationships = Relationships(items = Seq(relationship1, relationship2, relationship3, relationship4))

      (partyManagementService.getInstitutionRelationships _)
        .expects(institutionId.toString)
        .returning(Future.successful(relationships))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
      val response = Await.result(
        Http().singleRequest(
          HttpRequest(
            uri = s"$url/institutions/$institutionId/relationships",
            method = HttpMethods.GET,
            headers = authorization
          )
        ),
        Duration.Inf
      )

      val body = Await.result(Unmarshal(response.entity).to[Seq[RelationshipInfo]], Duration.Inf)
      response.status mustBe StatusCodes.OK
      body must contain only (RelationshipInfo(
        from = userId.toString,
        role = "Manager",
        platformRole = "admin",
        status = "active"
      ),
      RelationshipInfo(from = userId2.toString, role = "Delegate", platformRole = "admin", status = "active"),
      RelationshipInfo(from = userId3.toString, role = "Operator", platformRole = "security", status = "active"),
      RelationshipInfo(from = userId4.toString, role = "Operator", platformRole = "api", status = "active"))

    }
  }

  "Relationship activation" must {
    "succeed" in {

      val userId         = UUID.randomUUID()
      val institutionId  = UUID.randomUUID()
      val platformRole   = "platformRole"
      val relationshipId = UUID.randomUUID()

      val relationships = Relationships(
        Seq(
          Relationship(
            id = relationshipId,
            from = userId,
            to = institutionId,
            filePath = None,
            fileName = None,
            contentType = None,
            role = RelationshipEnums.Role.Operator,
            platformRole = platformRole,
            status = RelationshipEnums.Status.Suspended
          )
        )
      )

      (partyManagementService.retrieveRelationship _)
        .expects(Some(userId.toString), Some(institutionId.toString), Some(platformRole))
        .returning(Future.successful(relationships))

      (partyManagementService.activateRelationship _)
        .expects(relationshipId)
        .returning(Future.successful(()))

      val data = ActivationRequest(platformRole)
      val response = Await.result(
        Http().singleRequest(
          HttpRequest(
            uri = s"$url/institutions/$institutionId/relationships/$userId/activate",
            method = HttpMethods.POST,
            entity = HttpEntity(ContentTypes.`application/json`, data.toJson.toString),
            headers = authorization
          )
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NoContent

    }

    "fail if relationship is not Suspended" in {

      val fromId         = UUID.randomUUID()
      val institutionId  = UUID.randomUUID()
      val platformRole   = "platformRole"
      val relationshipId = UUID.randomUUID()

      val relationships = Relationships(
        Seq(
          Relationship(
            id = relationshipId,
            from = fromId,
            to = institutionId,
            filePath = None,
            fileName = None,
            contentType = None,
            role = RelationshipEnums.Role.Operator,
            platformRole = platformRole,
            status = RelationshipEnums.Status.Pending
          )
        )
      )

      (partyManagementService.retrieveRelationship _)
        .expects(Some(fromId.toString), Some(institutionId.toString), Some(platformRole))
        .returning(Future.successful(relationships))

      val data = ActivationRequest(platformRole)
      val response = Await.result(
        Http().singleRequest(
          HttpRequest(
            uri = s"$url/institutions/$institutionId/relationships/$fromId/activate",
            method = HttpMethods.POST,
            entity = HttpEntity(ContentTypes.`application/json`, data.toJson.toString),
            headers = authorization
          )
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.BadRequest

    }

  }

  "Relationship suspension" must {
    "succeed" in {

      val fromId         = UUID.randomUUID()
      val institutionId  = UUID.randomUUID()
      val platformRole   = "platformRole"
      val relationshipId = UUID.randomUUID()

      val relationships = Relationships(
        Seq(
          Relationship(
            id = relationshipId,
            from = fromId,
            to = institutionId,
            filePath = None,
            fileName = None,
            contentType = None,
            role = RelationshipEnums.Role.Operator,
            platformRole = platformRole,
            status = RelationshipEnums.Status.Active
          )
        )
      )

      (partyManagementService.retrieveRelationship _)
        .expects(Some(fromId.toString), Some(institutionId.toString), Some(platformRole))
        .returning(Future.successful(relationships))

      (partyManagementService.suspendRelationship _)
        .expects(relationshipId)
        .returning(Future.successful(()))

      val data = ActivationRequest(platformRole)
      val response = Await.result(
        Http().singleRequest(
          HttpRequest(
            uri = s"$url/institutions/$institutionId/relationships/$fromId/suspend",
            method = HttpMethods.POST,
            entity = HttpEntity(ContentTypes.`application/json`, data.toJson.toString),
            headers = authorization
          )
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.NoContent

    }

    "fail if relationship is not Active" in {

      val fromId         = UUID.randomUUID()
      val institutionId  = UUID.randomUUID()
      val platformRole   = "platformRole"
      val relationshipId = UUID.randomUUID()

      val relationships = Relationships(
        Seq(
          Relationship(
            id = relationshipId,
            from = fromId,
            to = institutionId,
            filePath = None,
            fileName = None,
            contentType = None,
            role = RelationshipEnums.Role.Operator,
            platformRole = platformRole,
            status = RelationshipEnums.Status.Pending
          )
        )
      )

      (partyManagementService.retrieveRelationship _)
        .expects(Some(fromId.toString), Some(institutionId.toString), Some(platformRole))
        .returning(Future.successful(relationships))

      val data = ActivationRequest(platformRole)
      val response = Await.result(
        Http().singleRequest(
          HttpRequest(
            uri = s"$url/institutions/$institutionId/relationships/$fromId/suspend",
            method = HttpMethods.POST,
            entity = HttpEntity(ContentTypes.`application/json`, data.toJson.toString),
            headers = authorization
          )
        ),
        Duration.Inf
      )

      response.status mustBe StatusCodes.BadRequest

    }

  }

}
