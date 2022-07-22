package it.pagopa.interop.partyprocess.server.impl

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.directives.SecurityDirectives
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import it.pagopa.interop.commons.files.StorageConfiguration
import it.pagopa.interop.commons.files.service.FileManager
import it.pagopa.interop.commons.jwt.service.JWTReader
import it.pagopa.interop.commons.jwt.service.impl.{DefaultJWTReader, getClaimsVerifier}
import it.pagopa.interop.commons.jwt.{JWTConfiguration, PublicKeysHolder}
import it.pagopa.interop.commons.mail.model.PersistedTemplate
import it.pagopa.interop.commons.mail.service.impl.CourierMailerConfiguration.CourierMailer
import it.pagopa.interop.commons.mail.service.impl.DefaultInteropMailer
import it.pagopa.interop.commons.utils.AkkaUtils.Authenticator
import it.pagopa.interop.commons.utils.TypeConversions.TryOps
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.ValidationRequestError
import it.pagopa.interop.commons.utils.{AkkaUtils, OpenapiUtils}
import it.pagopa.interop.partymanagement.client.{api => partyManagementApi}
import it.pagopa.interop.partyprocess.api.impl.{
  ExternalApiMarshallerImpl,
  ExternalApiServiceImpl,
  HealthApiMarshallerImpl,
  HealthServiceApiImpl,
  ProcessApiMarshallerImpl,
  ProcessApiServiceImpl,
  PublicApiMarshallerImpl,
  PublicApiServiceImpl,
  problemOf
}
import it.pagopa.interop.partyprocess.api.{ExternalApi, HealthApi, ProcessApi, PublicApi}
import it.pagopa.interop.partyprocess.common.system.{ApplicationConfiguration}
import it.pagopa.interop.partyprocess.service._
import it.pagopa.interop.partyprocess.service.impl._
import it.pagopa.interop.partyregistryproxy.client.{api => partyregistryproxyApi}
import it.pagopa.userreg.client.invoker.ApiKeyValue
import it.pagopa.userreg.client.{api => userregistrymanagement}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Route
import com.atlassian.oai.validator.report.ValidationReport
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.tsl.job.TLValidationJob

trait Dependencies {
  def partyManagementService(
    blockingEc: ExecutionContextExecutor
  )(implicit actorSystem: ActorSystem[_]): PartyManagementService =
    PartyManagementServiceImpl(
      PartyManagementInvoker(blockingEc)(actorSystem.classicSystem),
      partyManagementApi.PartyApi(ApplicationConfiguration.getPartyManagementUrl),
      partyManagementApi.ExternalApi(ApplicationConfiguration.getPartyManagementUrl),
      partyManagementApi.PublicApi(ApplicationConfiguration.getPartyManagementUrl)
    )

  def partyProcessService()(implicit ec: ExecutionContext, actorSystem: ActorSystem[_]): PartyRegistryService =
    PartyRegistryServiceImpl(
      PartyProxyInvoker()(actorSystem.classicSystem),
      partyregistryproxyApi.InstitutionApi(ApplicationConfiguration.getPartyProxyUrl),
      partyregistryproxyApi.CategoryApi(ApplicationConfiguration.getPartyProxyUrl)
    )

  implicit val apiKey: ApiKeyValue = ApiKeyValue(ApplicationConfiguration.userRegistryApiKey)

  def userRegistryManagementService()(implicit actorSystem: ActorSystem[_]): UserRegistryManagementService =
    UserRegistryManagementServiceImpl(
      UserRegistryManagementInvoker()(actorSystem.classicSystem),
      userregistrymanagement.UserApi(ApplicationConfiguration.getUserRegistryURL)
    )

  def signatureValidationService(): SignatureValidationService =
    if (ApplicationConfiguration.signatureValidationEnabled) SignatureValidationServiceImpl
    else PassthroughSignatureValidationService

  def signatureService()(implicit ec: ExecutionContext): Future[SignatureService] = {

    val europeanLOTL: LOTLSource      = SignatureService.getEuropeanLOTL
    val trustedListsCertificateSource = new TrustedListsCertificateSource()

    val job: TLValidationJob = SignatureService.getJob(europeanLOTL)
    job.setTrustedListCertificateSource(trustedListsCertificateSource)
    // TODO this must be managed with cronjob
    Future(job.offlineRefresh()).map(_ => SignatureServiceImpl(trustedListsCertificateSource))
  }

  private val mailer: MailEngine = new PartyProcessMailer with DefaultInteropMailer with CourierMailer

  def relationshipService(partyManagementService: PartyManagementService)(implicit
    ec: ExecutionContext
  ): RelationshipService = new RelationshipServiceImpl(partyManagementService)

  def productService(partyManagementService: PartyManagementService)(implicit ec: ExecutionContext): ProductService =
    new ProductServiceImpl(partyManagementService)

  def processApi(
    partyManagementService: PartyManagementService,
    relationshipService: RelationshipService,
    productService: ProductService,
    signatureService: SignatureService,
    partyProcessService: PartyRegistryService,
    userRegistryManagementService: UserRegistryManagementService,
    fileManager: FileManager,
    mailTemplate: PersistedTemplate,
    jwtReader: JWTReader
  )(implicit ec: ExecutionContext): ProcessApi = new ProcessApi(
    new ProcessApiServiceImpl(
      partyManagementService,
      partyProcessService,
      userRegistryManagementService,
      pdfCreator = PDFCreatorImpl,
      fileManager,
      signatureService,
      mailer,
      mailTemplate,
      relationshipService,
      productService
    ),
    ProcessApiMarshallerImpl,
    jwtReader.OAuth2JWTValidatorAsContexts
  )

  def externalApi(
    partyManagementService: PartyManagementService,
    relationshipService: RelationshipService,
    productService: ProductService,
    jwtReader: JWTReader
  )(implicit ec: ExecutionContext): ExternalApi = new ExternalApi(
    new ExternalApiServiceImpl(partyManagementService, relationshipService, productService),
    ExternalApiMarshallerImpl,
    jwtReader.OAuth2JWTValidatorAsContexts
  )

  def publicApi(
    partyManagementService: PartyManagementService,
    userRegistryManagementService: UserRegistryManagementService,
    signatureService: SignatureService,
    signatureValidationService: SignatureValidationService
  )(implicit ec: ExecutionContext): PublicApi = new PublicApi(
    new PublicApiServiceImpl(
      partyManagementService,
      userRegistryManagementService,
      signatureService,
      signatureValidationService
    ),
    PublicApiMarshallerImpl,
    SecurityDirectives.authenticateBasic("Public", AkkaUtils.PassThroughAuthenticator)
  )

  val healthApi: HealthApi = new HealthApi(
    new HealthServiceApiImpl(),
    HealthApiMarshallerImpl,
    SecurityDirectives.authenticateOAuth2("SecurityRealm", Authenticator)
  )

  def getFileManager(): Future[FileManager] =
    FileManager.getConcreteImplementation(StorageConfiguration.runtimeFileManager).toFuture

  def getMailTemplate(fileManager: FileManager)(implicit ec: ExecutionContext): Future[PersistedTemplate] =
    MailTemplate.get(ApplicationConfiguration.mailTemplatePath, fileManager)

  def getJwtValidator()(implicit ec: ExecutionContext): Future[JWTReader] = JWTConfiguration.jwtReader
    .loadKeyset()
    .toFuture
    .map(keyset =>
      new DefaultJWTReader with PublicKeysHolder {
        var publicKeyset                                                                 = keyset
        override protected val claimsVerifier: DefaultJWTClaimsVerifier[SecurityContext] =
          getClaimsVerifier(audience = ApplicationConfiguration.jwtAudience)
      }
    )

  def validationExceptionToRoute: ValidationReport => Route = report => {
    val error =
      problemOf(StatusCodes.BadRequest, ValidationRequestError(OpenapiUtils.errorFromRequestValidationReport(report)))
    complete(error.status, error)(HealthApiMarshallerImpl.toEntityMarshallerProblem)
  }

}
