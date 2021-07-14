package it.pagopa.pdnd.interop.uservice.partyprocess.server.impl

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.directives.SecurityDirectives
import akka.management.scaladsl.AkkaManagement
import it.pagopa.pdnd.interop.uservice.partymanagement.client.api.PartyApi
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.{
  HealthApiMarshallerImpl,
  HealthServiceApiImpl,
  ProcessApiMarshallerImpl,
  ProcessApiServiceImpl
}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.{HealthApi, ProcessApi}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{
  ApplicationConfiguration,
  Authenticator,
  CorsSupport,
  classicActorSystem,
  executionContext
}
import it.pagopa.pdnd.interop.uservice.partyprocess.server.Controller
import it.pagopa.pdnd.interop.uservice.partyprocess.service.impl.{
  MailerImpl,
  PDFCreatorImp,
  PartyManagementServiceImpl,
  PartyRegistryServiceImpl
}
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{
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

  final val partyManagementService: PartyManagementService =
    PartyManagementServiceImpl(partyManagementInvoker, partyApi)

  final val partyProcessService: PartyRegistryService = PartyRegistryServiceImpl(partyProxyInvoker, institutionApi)
  final val mailer: Mailer                            = new MailerImpl
  final val pdfCreator: PDFCreator                    = new PDFCreatorImp

  val processApi: ProcessApi = new ProcessApi(
    new ProcessApiServiceImpl(partyManagementService, partyProcessService, mailer, pdfCreator),
    new ProcessApiMarshallerImpl(),
    SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)
  )

  val healthApi: HealthApi = new HealthApi(
    new HealthServiceApiImpl(),
    new HealthApiMarshallerImpl(),
    SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)
  )

  locally {
    val _ = AkkaManagement.get(classicActorSystem).start()

  }

  val controller: Controller = new Controller(healthApi, processApi)

  val bindingFuture: Future[Http.ServerBinding] =
    Http().newServerAt("0.0.0.0", 8088).bind(corsHandler(controller.routes))

}
