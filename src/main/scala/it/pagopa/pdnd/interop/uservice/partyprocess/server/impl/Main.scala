package it.pagopa.pdnd.interop.uservice.partyprocess.server.impl

import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.directives.SecurityDirectives
import akka.management.scaladsl.AkkaManagement
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import it.pagopa.pdnd.interop.commons.files.StorageConfiguration
import it.pagopa.pdnd.interop.commons.files.service.FileManager
import it.pagopa.pdnd.interop.commons.jwt.service.JWTReader
import it.pagopa.pdnd.interop.commons.jwt.service.impl.{DefaultJWTReader, getClaimsVerifier}
import it.pagopa.pdnd.interop.commons.jwt.{JWTConfiguration, PublicKeysHolder}
import it.pagopa.pdnd.interop.commons.mail.model.PersistedTemplate
import it.pagopa.pdnd.interop.commons.mail.service.impl.CourierMailerConfiguration.CourierMailer
import it.pagopa.pdnd.interop.commons.mail.service.impl.DefaultPDNDMailer
import it.pagopa.pdnd.interop.commons.utils.AkkaUtils.Authenticator
import it.pagopa.pdnd.interop.commons.utils.TypeConversions.TryOps
import it.pagopa.pdnd.interop.commons.utils.errors.GenericComponentErrors.ValidationRequestError
import it.pagopa.pdnd.interop.commons.utils.{AkkaUtils, CORSSupport, OpenapiUtils}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.{api => partyManagementApi}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.{
  HealthApiMarshallerImpl,
  HealthServiceApiImpl,
  ProcessApiMarshallerImpl,
  ProcessApiServiceImpl,
  PublicApiMarshallerImpl,
  PublicApiServiceImpl,
  problemOf
}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.{HealthApi, ProcessApi, PublicApi}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{
  ApplicationConfiguration,
  classicActorSystem,
  executionContext
}
import it.pagopa.pdnd.interop.uservice.partyprocess.server.Controller
import it.pagopa.pdnd.interop.uservice.partyprocess.service._
import it.pagopa.pdnd.interop.uservice.partyprocess.service.impl._
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.{api => partyregistryproxyApi}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.{api => userregistrymanagement}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.invoker.ApiKeyValue
import kamon.Kamon

import scala.concurrent.Future
import scala.util.{Failure, Success}

//shuts down the actor system in case of startup errors
case object StartupErrorShutdown extends CoordinatedShutdown.Reason

trait PartyManagementDependency {
  final val partyManagementService: PartyManagementService =
    PartyManagementServiceImpl(
      PartyManagementInvoker(),
      partyManagementApi.PartyApi(ApplicationConfiguration.getPartyManagementUrl),
      partyManagementApi.PublicApi(ApplicationConfiguration.getPartyManagementUrl)
    )
}

trait PartyProxyDependency {
  final val partyProcessService: PartyRegistryService =
    PartyRegistryServiceImpl(
      PartyProxyInvoker(),
      partyregistryproxyApi.InstitutionApi(ApplicationConfiguration.getPartyProxyUrl),
      partyregistryproxyApi.CategoryApi(ApplicationConfiguration.getPartyProxyUrl)
    )
}

trait UserRegistryManagementDependency {
  implicit val apiKey: ApiKeyValue = ApiKeyValue(ApplicationConfiguration.userRegistryApiKey)
  final val userRegistryManagementService: UserRegistryManagementService =
    UserRegistryManagementServiceImpl(
      UserRegistryManagementInvoker(),
      userregistrymanagement.UserApi(ApplicationConfiguration.getUserRegistryURL)
    )
}

trait SignatureValidationServiceDependency {
  final val signatureValidationService: SignatureValidationService =
    if (ApplicationConfiguration.signatureValidationEnabled) SignatureValidationServiceImpl
    else PassthroughSignatureValidationService
}

object Main
    extends App
    with CORSSupport
    with PartyManagementDependency
    with PartyProxyDependency
    with UserRegistryManagementDependency
    with SignatureValidationServiceDependency {

  val dependenciesLoaded: Future[(FileManager, PersistedTemplate, JWTReader)] = for {
    fileManager  <- FileManager.getConcreteImplementation(StorageConfiguration.runtimeFileManager).toFuture
    mailTemplate <- MailTemplate.get(ApplicationConfiguration.mailTemplatePath, fileManager)
    keyset       <- JWTConfiguration.jwtReader.loadKeyset().toFuture
    jwtValidator = new DefaultJWTReader with PublicKeysHolder {
      var publicKeyset = keyset

      override protected val claimsVerifier: DefaultJWTClaimsVerifier[SecurityContext] =
        getClaimsVerifier(audience = ApplicationConfiguration.jwtAudience)
    }
  } yield (fileManager, mailTemplate, jwtValidator)

  dependenciesLoaded.transformWith {
    case Success((fileManager, mailTemplate, jwtValidator)) => launchApp(fileManager, mailTemplate, jwtValidator)
    case Failure(ex) => {
      classicActorSystem.log.error(s"Startup error: ${ex.getMessage}")
      classicActorSystem.log.error(s"${ex.getStackTrace.mkString("\n")}")
      CoordinatedShutdown(classicActorSystem).run(StartupErrorShutdown)
    }
  }

  private def launchApp(
    fileManager: FileManager,
    mailTemplate: PersistedTemplate,
    jwtReader: JWTReader
  ): Future[Http.ServerBinding] = {
    Kamon.init()

    val signatureService: SignatureService = SignatureServiceImpl
    val mailer: MailEngine                 = new PartyProcessMailer with DefaultPDNDMailer with CourierMailer

    val processApi: ProcessApi = new ProcessApi(
      new ProcessApiServiceImpl(
        partyManagementService = partyManagementService,
        partyRegistryService = partyProcessService,
        userRegistryManagementService = userRegistryManagementService,
        pdfCreator = PDFCreatorImpl,
        fileManager = fileManager,
        signatureService = signatureService,
        mailer = mailer,
        mailTemplate = mailTemplate,
        jwtReader = jwtReader
      ),
      ProcessApiMarshallerImpl,
      jwtReader.OAuth2JWTValidatorAsContexts
    )

    val publicApi: PublicApi = new PublicApi(
      new PublicApiServiceImpl(
        partyManagementService = partyManagementService,
        userRegistryManagementService = userRegistryManagementService,
        signatureService = signatureService,
        signatureValidationService = signatureValidationService
      ),
      PublicApiMarshallerImpl,
      SecurityDirectives.authenticateBasic("Public", AkkaUtils.PassThroughAuthenticator)
    )

    val healthApi: HealthApi = new HealthApi(
      new HealthServiceApiImpl(),
      HealthApiMarshallerImpl,
      SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)
    )

    locally {
      val _ = AkkaManagement.get(classicActorSystem).start()
    }

    val controller: Controller = new Controller(
      health = healthApi,
      process = processApi,
      public = publicApi,
      validationExceptionToRoute = Some(report => {
        val error =
          problemOf(
            StatusCodes.BadRequest,
            ValidationRequestError(OpenapiUtils.errorFromRequestValidationReport(report))
          )
        complete(error.status, error)(HealthApiMarshallerImpl.toEntityMarshallerProblem)
      })
    )

    val server: Future[Http.ServerBinding] =
      Http().newServerAt("0.0.0.0", ApplicationConfiguration.serverPort).bind(corsHandler(controller.routes))

    server
  }
}
