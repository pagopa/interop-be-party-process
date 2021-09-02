package it.pagopa.pdnd.interop.uservice.partyprocess.server.impl

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.directives.SecurityDirectives
import akka.management.scaladsl.AkkaManagement
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.api.AttributeApi
import it.pagopa.pdnd.interop.uservice.partymanagement.client.api.PartyApi
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.{
  HealthApiMarshallerImpl,
  HealthServiceApiImpl,
  PlatformApiMarshallerImpl,
  PlatformApiServiceImpl,
  ProcessApiMarshallerImpl,
  ProcessApiServiceImpl
}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.{HealthApi, PlatformApi, ProcessApi}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{
  ApplicationConfiguration,
  Authenticator,
  CorsSupport,
  classicActorSystem,
  executionContext
}
import it.pagopa.pdnd.interop.uservice.partyprocess.server.Controller
import it.pagopa.pdnd.interop.uservice.partyprocess.service.impl.{
  AttributeRegistryServiceImpl,
  MailerImpl,
  PDFCreatorImp,
  PartyManagementServiceImpl,
  PartyRegistryServiceImpl
}
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{
  AttributeRegistryInvoker,
  AttributeRegistryService,
  Mailer,
  PDFCreator,
  PartyManagementInvoker,
  PartyManagementService,
  PartyProxyInvoker,
  PartyRegistryService
}
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.api.InstitutionApi
import kamon.Kamon

import scala.concurrent.Future

object Main extends App with CorsSupport {

  Kamon.init()

  final val partyManagementInvoker: PartyManagementInvoker = PartyManagementInvoker()
  final val partyApi: PartyApi                             = PartyApi(ApplicationConfiguration.getPartyManagementUrl)

  final val partyProxyInvoker: PartyProxyInvoker = PartyProxyInvoker()
  final val institutionApi: InstitutionApi       = InstitutionApi(ApplicationConfiguration.getPartyProxyUrl)

  final val attributeRegistryInvoker: AttributeRegistryInvoker = AttributeRegistryInvoker()
  final val attributeApi: AttributeApi                         = AttributeApi(ApplicationConfiguration.getAttributeRegistryUrl)

  final val partyManagementService: PartyManagementService =
    PartyManagementServiceImpl(partyManagementInvoker, partyApi)

  final val partyProcessService: PartyRegistryService = PartyRegistryServiceImpl(partyProxyInvoker, institutionApi)

  final val attributeRegistryService: AttributeRegistryService =
    AttributeRegistryServiceImpl(attributeRegistryInvoker, attributeApi)

  final val mailer: Mailer         = new MailerImpl
  final val pdfCreator: PDFCreator = new PDFCreatorImp

  val processApi: ProcessApi = new ProcessApi(
    new ProcessApiServiceImpl(
      partyManagementService,
      partyProcessService,
      attributeRegistryService,
      mailer,
      pdfCreator
    ),
    new ProcessApiMarshallerImpl(),
    SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)
  )

  val healthApi: HealthApi = new HealthApi(
    new HealthServiceApiImpl(),
    new HealthApiMarshallerImpl(),
    SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)
  )

  val platformApi: PlatformApi = new PlatformApi(
    new PlatformApiServiceImpl(),
    new PlatformApiMarshallerImpl(),
    SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)
  )

  locally {
    val _ = AkkaManagement.get(classicActorSystem).start()

  }

  val controller: Controller = new Controller(healthApi, platformApi, processApi)

  val bindingFuture: Future[Http.ServerBinding] =
    Http().newServerAt("0.0.0.0", ApplicationConfiguration.serverPort).bind(corsHandler(controller.routes))

}
