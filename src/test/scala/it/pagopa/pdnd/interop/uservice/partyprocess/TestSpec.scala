package it.pagopa.pdnd.interop.uservice.partyprocess

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.{Marshal, ToEntityMarshaller}
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.directives.{AuthenticationDirective, SecurityDirectives}
import akka.http.scaladsl.unmarshalling.Unmarshal
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.RelationShipEnums.Role
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.{ProcessApiMarshallerImpl, ProcessApiServiceImpl}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.{HealthApi, ProcessApi, ProcessApiMarshaller}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{Authenticator, classicActorSystem, executionContext}
import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.server.Controller
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{
  Mailer,
  PDFCreator,
  PartyManagementService,
  PartyRegistryService
}
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.Institution
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import java.io.File
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}

class TestSpec
    extends MockFactory
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with SprayJsonSupport
    with DefaultJsonProtocol {

  val processApiMarshaller: ProcessApiMarshaller     = new ProcessApiMarshallerImpl
  val mockHealthApi: HealthApi                       = mock[HealthApi]
  val partyManagementService: PartyManagementService = mock[PartyManagementService]
  val partyRegistryService: PartyRegistryService     = mock[PartyRegistryService]
  val mailer: Mailer                                 = mock[Mailer]
  val pdfCreator: PDFCreator                         = mock[PDFCreator]

  var controller: Option[Controller]                 = None
  var bindServer: Option[Future[Http.ServerBinding]] = None

  val wrappingDirective: AuthenticationDirective[Seq[(String, String)]] =
    SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)

  override def beforeAll(): Unit = {

    val processApi = new ProcessApi(
      new ProcessApiServiceImpl(partyManagementService, partyRegistryService, mailer, pdfCreator),
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
      val relationShip1 =
        RelationShip(from = taxCode1, to = institutionId1, role = Role.Manager, status = Some("active"))
      val relationShip2 =
        RelationShip(from = taxCode1, to = institutionId2, role = Role.Delegate, status = Some("active"))
      val relationShips = RelationShips(items = Seq(relationShip1, relationShip2))
      val organization1 = Organization(
        institutionId = institutionId1,
        description = "org1",
        manager = "manager1",
        digitalAddress = "digitalAddress1",
        partyId = orgPartyId1,
        attributes = Seq.empty
      )
      val organization2 = Organization(
        institutionId = institutionId2,
        description = "org2",
        manager = "manager2",
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
            status = relationShip1.status.getOrElse("")
          ),
          InstitutionInfo(
            institutionId = organization2.institutionId,
            description = organization2.description,
            digitalAddress = organization2.digitalAddress,
            status = relationShip2.status.getOrElse("")
          )
        )
      )

      (partyManagementService.retrievePerson _).expects(taxCode1).returning(Future.successful(person1)).once()
      (partyManagementService.retrieveRelationship _)
        .expects(personPartyId1)
        .returning(Future.successful(relationShips))
        .once()
      (partyManagementService.retrieveOrganization _)
        .expects(institutionId1)
        .returning(Future.successful(organization1))
        .once()
      (partyManagementService.retrieveOrganization _)
        .expects(institutionId2)
        .returning(Future.successful(organization2))
        .once()

      println(s"$url/onboarding/$taxCode1")
      val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
      val response = Await.result(
        Http().singleRequest(
          HttpRequest(uri = s"$url/onboarding/$taxCode1", method = HttpMethods.GET, headers = authorization)
        ),
        Duration.Inf
      )

      val body = Await.result(Unmarshal(response.entity).to[OnBoardingInfo], Duration.Inf)

      response.status mustBe StatusCodes.OK
      body mustBe expected

    }

    "create a legals info" in {

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
        category = None,
        managerName = None,
        managerSurname = None,
        description = "institution",
        digitalAddress = None
      )
      val organization1 = Organization(
        institutionId = institutionId1,
        description = "org1",
        manager = "manager1",
        digitalAddress = "digitalAddress1",
        partyId = orgPartyId1,
        attributes = Seq.empty
      )

      val file = new File("src/test/resources/fake.file")

      val manager  = User(name = "manager", surname = "manager", taxCode = taxCode1, role = "Manager")
      val delegate = User(name = "delegate", surname = "delegate", taxCode = taxCode2, role = "Delegate")

      (partyRegistryService.getInstitution _).expects(*).returning(Future.successful(institution1)).once()
      (partyManagementService.createOrganization _).expects(*).returning(Future.successful(organization1)).once()
      (partyManagementService.createPerson _).expects(*).returning(Future.successful(person1)).once()
      (partyManagementService.createPerson _).expects(*).returning(Future.failed(new RuntimeException)).once()
      (partyManagementService.retrievePerson _).expects(*).returning(Future.successful(person1)).once()
      (partyManagementService.createRelationShip _).expects(*, *, *).returning(Future.successful(())).repeat(2)

      (pdfCreator.create _)
        .expects(*, *)
        .returning(Future.successful((file, "hash")))
        .once()

      (partyManagementService.createToken _)
        .expects(*, *)
        .returning(Future.successful(TokenText("token")))
        .once()

      (mailer.send _)
        .expects(*, *)
        .returning(Future.successful(()))
        .once()

      val req = OnBoardingRequest(users = Seq(manager, delegate), institutionId = "institutionId1")

      implicit val userFormat: RootJsonFormat[User]                           = jsonFormat4(User)
      implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest] = jsonFormat2(OnBoardingRequest)

      implicit def fromEntityUnmarshallerOnBoardingRequest: ToEntityMarshaller[OnBoardingRequest] =
        sprayJsonMarshaller[OnBoardingRequest]

      val data     = Await.result(Marshal(req).to[MessageEntity].map(_.dataBytes), Duration.Inf)
      val response = request(data, "onboarding/legals", HttpMethods.POST)

      response.status mustBe StatusCodes.OK

    }

  }

}
