/*
package it.pagopa.interop.partyprocess

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.Authenticator
import akka.http.scaladsl.server.directives.{AuthenticationDirective, Credentials, SecurityDirectives}
import akka.http.scaladsl.unmarshalling.Unmarshal
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.ResourceNotFoundError
import it.pagopa.interop.commons.utils.{BEARER, UID}
import it.pagopa.interop.partymanagement.client.model.{PartyRole => _, RelationshipState => _}
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.api.impl.{ExternalApiServiceImpl, ProcessApiServiceImpl, PublicApiServiceImpl}
import it.pagopa.interop.partyprocess.api.{ExternalApi, ProcessApi, PublicApi}
import it.pagopa.interop.partyprocess.common.system.{classicActorSystem, executionContext}
import it.pagopa.interop.partyprocess.model.{Attribute, Institution}
import it.pagopa.interop.partyprocess.server.Controller
import it.pagopa.interop.partyprocess.service.impl.{ProductServiceImpl, RelationshipServiceImpl}
import it.pagopa.interop.partyprocess.service.{ProductService, RelationshipService}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json.DefaultJsonProtocol

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class ExternalApiSpec
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

  object MockAuthenticator extends Authenticator[Seq[(String, String)]] {
    override def apply(credentials: Credentials): Option[Seq[(String, String)]] = Some(
      Seq(BEARER -> token.toString, UID -> uid.toString)
    )
  }

  val wrappingDirective: AuthenticationDirective[Seq[(String, String)]] =
    SecurityDirectives.authenticateOAuth2("SecurityRealm", MockAuthenticator)

  override def beforeAll(): Unit = {
    loadEnvVars()

    val relationshipService: RelationshipService =
      new RelationshipServiceImpl(mockPartyManagementService)
    val productService: ProductService           = new ProductServiceImpl(mockPartyManagementService)

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
        relationshipService = relationshipService,
        productService = productService
      ),
      processApiMarshaller,
      wrappingDirective
    )

    val externalApi = new ExternalApi(
      new ExternalApiServiceImpl(
        partyManagementService = mockPartyManagementService,
        relationshipService = relationshipService,
        productService = productService
      ),
      externalApiMarshaller,
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

    controller = Some(
      new Controller(health = mockHealthApi, external = externalApi, process = processApi, public = publicApi)
    )

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
        .retrieveInstitution(_: UUID)(_: String)(_: Seq[(String, String)]))
        .expects(orgPartyId, *, *)
        .returning(if (success) Future.successful(institution) else Future.failed(ResourceNotFoundError(externalId)))
        .once()
    }

    "succeed when exists" in {
      mockPartyManagement(true)

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/external/institutions/$externalId", method = HttpMethods.GET))
          .futureValue

      val body = Unmarshal(response.entity).to[Institution].futureValue

      body equals expected

      response.status mustBe StatusCodes.OK
    }

    "NotFoundError when not exists" in {
      mockPartyManagement(false)

      val response =
        Http()
          .singleRequest(HttpRequest(uri = s"$url/external/institutions/$externalId", method = HttpMethods.GET))
          .futureValue

      response.status mustBe StatusCodes.NotFound
    }

  }

}
 */
