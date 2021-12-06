package it.pagopa.pdnd.interop.uservice.partyprocess.server.impl

import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.directives.SecurityDirectives
import akka.management.scaladsl.AkkaManagement
import it.pagopa.pdnd.interop.commons.files.StorageConfiguration
import it.pagopa.pdnd.interop.commons.files.service.FileManager
import it.pagopa.pdnd.interop.commons.mail.model.PersistedTemplate
import it.pagopa.pdnd.interop.commons.mail.service.impl.CourierMailerConfiguration.CourierMailer
import it.pagopa.pdnd.interop.commons.mail.service.impl.DefaultPDNDMailer
import it.pagopa.pdnd.interop.commons.utils.AkkaUtils.Authenticator
import it.pagopa.pdnd.interop.commons.utils.CORSSupport
import it.pagopa.pdnd.interop.commons.utils.TypeConversions.TryOps
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.api.AttributeApi
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
  classicActorSystem,
  executionContext
}
import it.pagopa.pdnd.interop.uservice.partyprocess.server.Controller
import it.pagopa.pdnd.interop.uservice.partyprocess.service._
import it.pagopa.pdnd.interop.uservice.partyprocess.service.impl._
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.api.InstitutionApi
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.api.UserApi
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.invoker.ApiKeyValue
import kamon.Kamon

import scala.concurrent.Future
import scala.util.{Failure, Success}

//shuts down the actor system in case of startup errors
case object StartupErrorShutdown extends CoordinatedShutdown.Reason

trait PartyManagementDependency {
  final val partyManagementService: PartyManagementService =
    PartyManagementServiceImpl(PartyManagementInvoker(), PartyApi(ApplicationConfiguration.getPartyManagementUrl))
}

trait PartyProxyDependency {
  final val partyProcessService: PartyRegistryService =
    PartyRegistryServiceImpl(PartyProxyInvoker(), InstitutionApi(ApplicationConfiguration.getPartyProxyUrl))
}

trait AttributeRegistryDependency {
  final val attributeRegistryService: AttributeRegistryService =
    AttributeRegistryServiceImpl(
      AttributeRegistryInvoker(),
      AttributeApi(ApplicationConfiguration.getAttributeRegistryUrl)
    )
}

trait UserRegistryManagementDependency {
  implicit val apiKey: ApiKeyValue = ApiKeyValue(ApplicationConfiguration.userRegistryApiKey)
  final val userRegistryManagementService: UserRegistryManagementService =
    UserRegistryManagementServiceImpl(
      UserRegistryManagementInvoker(),
      UserApi(ApplicationConfiguration.getUserRegistryURL)
    )
}

object Main
    extends App
    with CORSSupport
    with PartyManagementDependency
    with PartyProxyDependency
    with AttributeRegistryDependency
    with UserRegistryManagementDependency {

  val dependenciesLoaded: Future[(FileManager, PersistedTemplate)] = for {
    fileManager  <- FileManager.getConcreteImplementation(StorageConfiguration.runtimeFileManager).toFuture
    mailTemplate <- MailTemplate.get(ApplicationConfiguration.mailTemplatePath, fileManager)
  } yield (fileManager, mailTemplate)

  dependenciesLoaded.transformWith {
    case Success((fileManager, mailTemplate)) => launchApp(fileManager, mailTemplate)
    case Failure(_)                           => CoordinatedShutdown(classicActorSystem).run(StartupErrorShutdown)
  }

  private def launchApp(fileManager: FileManager, mailTemplate: PersistedTemplate): Future[Http.ServerBinding] = {
    Kamon.init()

    val mailer: MailEngine = new PartyProcessMailer with DefaultPDNDMailer with CourierMailer

    val processApi: ProcessApi = new ProcessApi(
      new ProcessApiServiceImpl(
        partyManagementService,
        partyProcessService,
        attributeRegistryService,
        userRegistryManagementService,
        PDFCreatorImpl,
        fileManager,
        mailer,
        mailTemplate
      ),
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

    val server: Future[Http.ServerBinding] =
      Http().newServerAt("0.0.0.0", ApplicationConfiguration.serverPort).bind(corsHandler(controller.routes))

    server
  }
}
