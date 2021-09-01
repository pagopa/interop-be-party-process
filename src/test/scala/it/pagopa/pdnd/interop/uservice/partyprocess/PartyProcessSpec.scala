package it.pagopa.pdnd.interop.uservice.partyprocess

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.{Marshal, ToEntityMarshaller}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.server.directives.{AuthenticationDirective, SecurityDirectives}
import akka.http.scaladsl.unmarshalling.Unmarshal
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.model.{Attribute, AttributesResponse}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.RelationshipEnums.{Role, Status}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.{
  Organization,
  Person,
  Relationship,
  Relationships,
  TokenText
}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.{ProcessApiMarshallerImpl, ProcessApiServiceImpl}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.{HealthApi, ProcessApi, ProcessApiMarshaller}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{Authenticator, classicActorSystem, executionContext}
import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.server.Controller
import it.pagopa.pdnd.interop.uservice.partyprocess.service._
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.{Categories, Category, Institution}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import java.io.File
import java.nio.file.Paths
import java.time.OffsetDateTime
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}

class PartyProcessSpec
    extends MockFactory
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with SprayJsonSupport
    with DefaultJsonProtocol {

  val processApiMarshaller: ProcessApiMarshaller         = new ProcessApiMarshallerImpl
  val mockHealthApi: HealthApi                           = mock[HealthApi]
  val partyManagementService: PartyManagementService     = mock[PartyManagementService]
  val partyRegistryService: PartyRegistryService         = mock[PartyRegistryService]
  val attributeRegistryService: AttributeRegistryService = mock[AttributeRegistryService]
  val mailer: Mailer                                     = mock[Mailer]
  val pdfCreator: PDFCreator                             = mock[PDFCreator]

  var controller: Option[Controller]                 = None
  var bindServer: Option[Future[Http.ServerBinding]] = None

  val wrappingDirective: AuthenticationDirective[Seq[(String, String)]] =
    SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)

  override def beforeAll(): Unit = {

    val processApi = new ProcessApi(
      new ProcessApiServiceImpl(
        partyManagementService,
        partyRegistryService,
        attributeRegistryService,
        mailer,
        pdfCreator
      ),
      processApiMarshaller,
      wrappingDirective
    )

    controller = Some(new Controller(mockHealthApi, processApi))

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
      val institutionId1 = "IST1"
      val institutionId2 = "IST2"
      val personPartyId1 = "af80fac0-2775-4646-8fcf-28e083751900"
      val orgPartyId1    = "af80fac0-2775-4646-8fcf-28e083751901"
      val orgPartyId2    = "af80fac0-2775-4646-8fcf-28e083751902"
      val person1        = Person(taxCode = taxCode1, surname = "Doe", name = "John", partyId = personPartyId1)

      val relationship1 =
        Relationship(from = taxCode1, to = institutionId1, role = Role.Manager, status = Some(Status.Active))
      val relationship2 =
        Relationship(from = taxCode1, to = institutionId2, role = Role.Delegate, status = Some(Status.Active))

      val relationships = Relationships(items = Seq(relationship1, relationship2))

      val organization1 = Organization(
        institutionId = institutionId1,
        description = "org1",
        managerName = "managerName1",
        managerSurname = "managerSurname1",
        digitalAddress = "digitalAddress1",
        partyId = orgPartyId1,
        attributes = Seq.empty
      )
      val organization2 = Organization(
        institutionId = institutionId2,
        description = "org2",
        managerName = "managerName2",
        managerSurname = "managerSurname2",
        digitalAddress = "digitalAddress2",
        partyId = orgPartyId2,
        attributes = Seq.empty
      )

      val expected = OnBoardingInfo(
        person = PersonInfo(name = person1.name, surname = person1.surname, taxCode = person1.taxCode),
        institutions = Seq(
          InstitutionInfo(
            institutionId = organization1.institutionId,
            description = organization1.description,
            digitalAddress = organization1.digitalAddress,
            status = relationship1.status.get.toString,
            role = relationship1.role.toString
          ),
          InstitutionInfo(
            institutionId = organization2.institutionId,
            description = organization2.description,
            digitalAddress = organization2.digitalAddress,
            status = relationship2.status.get.toString,
            role = relationship2.role.toString
          )
        )
      )

      (partyManagementService.retrievePerson _).expects(taxCode1).returning(Future.successful(person1)).once()
      (partyManagementService.retrieveRelationship _)
        .expects(Some(taxCode1), None)
        .returning(Future.successful(relationships))
        .once()
      (partyManagementService.retrieveOrganization _)
        .expects(institutionId1)
        .returning(Future.successful(organization1))
        .once()
      (partyManagementService.retrieveOrganization _)
        .expects(institutionId2)
        .returning(Future.successful(organization2))
        .once()

      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
      val response = Await.result(
        Http().singleRequest(
          HttpRequest(uri = s"$url/onboarding/info/$taxCode1", method = HttpMethods.GET, headers = authorization)
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
      val personPartyId1 = "bf80fac0-2775-4646-8fcf-28e083751900"
      val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"
      val person1        = Person(taxCode = taxCode1, surname = "Doe", name = "John", partyId = personPartyId1)
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
        managerName = "managerName1",
        managerSurname = "managerSurname1",
        digitalAddress = "digitalAddress1",
        partyId = orgPartyId1,
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

      val manager = User(
        name = "manager",
        surname = "manager",
        taxCode = taxCode1,
        organizationRole = "Manager",
        platformRole = "admin"
      )
      val delegate = User(
        name = "delegate",
        surname = "delegate",
        taxCode = taxCode2,
        organizationRole = "Delegate",
        platformRole = "admin"
      )

      (partyRegistryService.getInstitution _).expects(*).returning(Future.successful(institution1)).once()
      (() => partyRegistryService.getCategories)
        .expects()
        .returning(Future.successful(Categories(Seq(Category("C17", "attrs", "test")))))
        .once()
      (partyManagementService.createOrganization _).expects(*).returning(Future.successful(organization1)).once()
      (partyManagementService.createPerson _).expects(*).returning(Future.successful(person1)).once()
      (partyManagementService.createPerson _).expects(*).returning(Future.failed(new RuntimeException)).once()
      (partyManagementService.retrievePerson _).expects(*).returning(Future.successful(person1)).once()
      (partyManagementService.createRelationship _).expects(*, *, *, *).returning(Future.successful(())).repeat(2)
      (attributeRegistryService.createAttribute _).expects(*, *, *).returning(Future.successful(attr1)).once()
      (pdfCreator.create _).expects(*, *).returning(Future.successful((file, "hash"))).once()
      (partyManagementService.createToken _).expects(*, *).returning(Future.successful(TokenText("token"))).once()
      (mailer.send _).expects(*, *, *).returning(Future.successful(())).once()

      val req = OnBoardingRequest(users = Seq(manager, delegate), institutionId = "institutionId1")

      implicit val userFormat: RootJsonFormat[User]                           = jsonFormat5(User)
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
        organizationRole = "Operator",
        platformRole = "security"
      )
      val operator2 = User(
        name = "operator2",
        surname = "operator2",
        taxCode = taxCode2,
        organizationRole = "Operator",
        platformRole = "security"
      )
      (partyManagementService.retrieveRelationship _)
        .expects(*, *)
        .returning(Future.successful(Relationships(Seq.empty)))

      val req = OnBoardingRequest(users = Seq(operator1, operator2), institutionId = "institutionId1")

      implicit val userFormat: RootJsonFormat[User]                           = jsonFormat5(User)
      implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest] = jsonFormat2(OnBoardingRequest)

      implicit def fromEntityUnmarshallerOnBoardingRequest: ToEntityMarshaller[OnBoardingRequest] =
        sprayJsonMarshaller[OnBoardingRequest]

      val data     = Await.result(Marshal(req).to[MessageEntity].map(_.dataBytes), Duration.Inf)
      val response = request(data, "onboarding/operators", HttpMethods.POST)

      response.status mustBe StatusCodes.BadRequest

    }

    "create operators if exits a legal active for a given institution" in {

      val taxCode1       = "operator1TaxCode"
      val taxCode2       = "operator2TaxCode"
      val institutionId1 = "IST2"
      val personPartyId1 = "bf80fac0-2775-4646-8fcf-28e083751900"
      val personPartyId2 = "bf80fac0-2775-4646-8fcf-28e083751901"
      val orgPartyId1    = "bf80fac0-2775-4646-8fcf-28e083751901"
      val person1        = Person(taxCode = taxCode1, surname = "Ripley", name = "Ellen", partyId = personPartyId1)
      val person2        = Person(taxCode = taxCode2, surname = "Maschetti", name = "Raffaello", partyId = personPartyId2)

      val organization1 = Organization(
        institutionId = institutionId1,
        description = "org1",
        managerName = "managerName1",
        managerSurname = "managerSurname1",
        digitalAddress = "digitalAddress1",
        partyId = orgPartyId1,
        attributes = Seq.empty
      )

      val relationships =
        Relationships(
          Seq(Relationship(from = "", to = institutionId1, role = Role.Manager, status = Some(Status.Active)))
        )

      val operator1 = User(
        name = "operator1",
        surname = "operator1",
        taxCode = taxCode1,
        organizationRole = "Operator",
        platformRole = "security"
      )
      val operator2 = User(
        name = "operator2",
        surname = "operator2",
        taxCode = taxCode2,
        organizationRole = "Operator",
        platformRole = "security"
      )
      (partyManagementService.retrieveRelationship _)
        .expects(None, Some(institutionId1))
        .returning(Future.successful(relationships))
      (partyManagementService.retrieveOrganization _).expects(*).returning(Future.successful(organization1)).once()
      (partyManagementService.createPerson _).expects(*).returning(Future.successful(person1)).once()
      (partyManagementService.createPerson _).expects(*).returning(Future.successful(person2)).once()
      (partyManagementService.createRelationship _).expects(*, *, *, *).returning(Future.successful(())).repeat(2)

      val req = OnBoardingRequest(users = Seq(operator1, operator2), institutionId = institutionId1)

      implicit val userFormat: RootJsonFormat[User]                           = jsonFormat5(User)
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

      (partyManagementService.consumeToken _).expects(token).returning(Future.successful(()))

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

  }

}
