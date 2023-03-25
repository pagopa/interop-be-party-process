package it.pagopa.interop.partyprocess

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.Authenticator
import akka.http.scaladsl.server.directives.{AuthenticationDirective, Credentials, SecurityDirectives}
import it.pagopa.interop.commons.utils.{BEARER, UID}
import it.pagopa.interop.partymanagement.client.model.{PartyRole => _, RelationshipState => _}
import it.pagopa.interop.partyprocess.api.impl.{ExternalApiServiceImpl, ProcessApiServiceImpl, PublicApiServiceImpl}
import it.pagopa.interop.partyprocess.api.{ExternalApi, ProcessApi, PublicApi}
import it.pagopa.interop.partyprocess.common.system.{classicActorSystem, executionContext}
import it.pagopa.interop.partyprocess.server.Controller
import it.pagopa.interop.partyprocess.service.impl.{ProductServiceImpl, RelationshipServiceImpl}
import it.pagopa.interop.partyprocess.service.{ProductService, RelationshipService}
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class PartyProcessSuites extends ExternalApiSpec with PartyApiSpec with BeforeAndAfterAll {
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
        productManagementService = mockProductService,
        pdfCreator = mockPdfCreator,
        fileManager = mockFileManager,
        signatureService = mockSignatureService,
        mailer = mockMailer,
        mailTemplate = mockMailTemplate,
        mailNotificationTemplate = mockMailNotificationTemplate,
        mailRejectTemplate = mockMailRejectTemplate,
        mailAutoCompleteTemplate = mockMailRejectTemplate,
        relationshipService = relationshipService,
        productService = productService,
        geoTaxonomyService = mockGeoTaxonomyService
      ),
      processApiMarshaller,
      wrappingDirective
    )

    val externalApi = new ExternalApi(
      new ExternalApiServiceImpl(
        partyManagementService = mockPartyManagementService,
        relationshipService = relationshipService,
        productService = productService,
        geoTaxonomyService = mockGeoTaxonomyService
      ),
      externalApiMarshaller,
      wrappingDirective
    )

    val publicApi = new PublicApi(
      new PublicApiServiceImpl(
        partyManagementService = mockPartyManagementService,
        userRegistryManagementService = mockUserRegistryService,
        productManagementService = mockProductService,
        signatureService = mockSignatureService,
        signatureValidationService = mockSignatureValidationService,
        mailer = mockMailer,
        mailTemplate = mockMailTemplate,
        fileManager = mockFileManager
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
}
