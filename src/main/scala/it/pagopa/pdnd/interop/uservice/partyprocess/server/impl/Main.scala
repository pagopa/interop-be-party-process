package it.pagopa.pdnd.interop.uservice.partyprocess.server.impl

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.directives.SecurityDirectives
import akka.management.scaladsl.AkkaManagement
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.api.AttributeApi
import it.pagopa.pdnd.interop.uservice.authorizationprocess.client.api.AuthApi
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
  AuthorizationProcessServiceImpl,
  MailerImpl,
  PDFCreatorImpl,
  PartyManagementServiceImpl,
  PartyRegistryServiceImpl,
  UserRegistryManagementServiceImpl
}
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{
  AttributeRegistryInvoker,
  AttributeRegistryService,
  FileManager,
  AuthorizationProcessInvoker,
  AuthorizationProcessService,
  Mailer,
  PDFCreator,
  PartyManagementInvoker,
  PartyManagementService,
  PartyProxyInvoker,
  PartyRegistryService,
  UserRegistryManagementInvoker,
  UserRegistryManagementService
}
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.api.InstitutionApi
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.api.UserApi
import kamon.Kamon

import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
object Main extends App with CorsSupport {

  final val fileManager = FileManager
    .getConcreteImplementation(ApplicationConfiguration.runtimeFileManager)
    .get //end of the world here: if no valid file manager is configured, the application must break.

  Kamon.init()

  final val partyManagementInvoker: PartyManagementInvoker = PartyManagementInvoker()
  final val partyApi: PartyApi                             = PartyApi(ApplicationConfiguration.getPartyManagementUrl)

  final val partyProxyInvoker: PartyProxyInvoker = PartyProxyInvoker()
  final val institutionApi: InstitutionApi       = InstitutionApi(ApplicationConfiguration.getPartyProxyUrl)

  final val attributeRegistryInvoker: AttributeRegistryInvoker = AttributeRegistryInvoker()
  final val attributeApi: AttributeApi                         = AttributeApi(ApplicationConfiguration.getAttributeRegistryUrl)

  final val authorizationProcessInvoker: AuthorizationProcessInvoker = AuthorizationProcessInvoker()
  final val authAPI: AuthApi                                         = AuthApi(ApplicationConfiguration.getAuthorizationProcessURL)

  final val userRegistryManagementInvoker: UserRegistryManagementInvoker = UserRegistryManagementInvoker()
  final val userAPI: UserApi                                             = UserApi(ApplicationConfiguration.getUserRegistryURL)

  final val partyManagementService: PartyManagementService =
    PartyManagementServiceImpl(partyManagementInvoker, partyApi)

  final val partyProcessService: PartyRegistryService = PartyRegistryServiceImpl(partyProxyInvoker, institutionApi)

  final val attributeRegistryService: AttributeRegistryService =
    AttributeRegistryServiceImpl(attributeRegistryInvoker, attributeApi)

  final val authorizationProcessService: AuthorizationProcessService =
    AuthorizationProcessServiceImpl(authorizationProcessInvoker, authAPI)

  final val userRegistryManagementService: UserRegistryManagementService =
    UserRegistryManagementServiceImpl(userRegistryManagementInvoker, userAPI)

  final val mailer: Mailer         = new MailerImpl
  final val pdfCreator: PDFCreator = new PDFCreatorImpl

  val processApi: ProcessApi = new ProcessApi(
    new ProcessApiServiceImpl(
      partyManagementService,
      partyProcessService,
      attributeRegistryService,
      authorizationProcessService,
      userRegistryManagementService,
      mailer,
      pdfCreator,
      fileManager
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
