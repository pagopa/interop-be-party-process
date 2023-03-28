package it.pagopa.interop.partyprocess.server.impl

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.SecurityDirectives
import com.atlassian.oai.validator.report.ValidationReport
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.LOTLSource
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
import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration
import it.pagopa.interop.partyprocess.service._
import it.pagopa.interop.partyprocess.service.impl._
import it.pagopa.interop.partyregistryproxy.client.{api => partyregistryproxyApi}
import it.pagopa.userreg.client.{api => userregistrymanagement}
import it.pagopa.product.client.{api => productmanagement}
import it.pagopa.geotaxonomy.client.{api => geotaxonomy}
import it.pagopa.selfcare.commons.connector.soap.aruba.sign.config.ArubaSignConfig
import it.pagopa.selfcare.commons.connector.soap.aruba.sign.generated.client.Auth
import it.pagopa.selfcare.commons.connector.soap.aruba.sign.service.{
  ArubaPkcs7HashSignServiceImpl,
  ArubaSignServiceImpl
}
import it.pagopa.selfcare.commons.connector.soap.utils.SoapLoggingHandler
import it.pagopa.selfcare.commons.utils.crypto.service.{PadesSignService, PadesSignServiceImpl}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

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

  val userRegistryApiKey: it.pagopa.userreg.client.invoker.ApiKeyValue =
    it.pagopa.userreg.client.invoker.ApiKeyValue(ApplicationConfiguration.userRegistryApiKey)

  def userRegistryManagementService()(implicit actorSystem: ActorSystem[_]): UserRegistryManagementService =
    UserRegistryManagementServiceImpl(
      UserRegistryManagementInvoker()(actorSystem.classicSystem),
      userregistrymanagement.UserApi(ApplicationConfiguration.getUserRegistryURL)
    )(userRegistryApiKey)

  val externalApiKey: it.pagopa.product.client.invoker.ApiKeyValue =
    it.pagopa.product.client.invoker.ApiKeyValue(ApplicationConfiguration.externalApiKey)

  def productManagementService()(implicit actorSystem: ActorSystem[_]): ProductManagementService =
    ProductManagementServiceImpl(
      ProductManagementInvoker()(actorSystem.classicSystem),
      productmanagement.ProductApi(ApplicationConfiguration.getProductURL)
    )(externalApiKey, ApplicationConfiguration.externalApiUser)

  def geoTaxonomyService()(implicit actorSystem: ActorSystem[_]): GeoTaxonomyService =
    GeoTaxonomyServiceImpl(
      GeoTaxonomyInvoker()(actorSystem.classicSystem),
      geotaxonomy.GeographicTaxonomyApi(ApplicationConfiguration.getGeoTaxonomyURL)
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
    Future(job.onlineRefresh()).map(_ => SignatureServiceImpl(trustedListsCertificateSource))
  }

  private val onboardingInitMailer: MailEngine     = new PartyProcessMailer with DefaultInteropMailer with CourierMailer
  private val onboardingCompleteMailer: MailEngine = new PartyOnboardingCompleteMailer
    with DefaultInteropMailer
    with CourierMailer

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
    productManagementService: ProductManagementService,
    geoTaxonomy: GeoTaxonomyService,
    fileManager: FileManager,
    mailTemplate: PersistedTemplate,
    mailNotificationTemplate: PersistedTemplate,
    mailRejectTemplate: PersistedTemplate,
    mailAutoCompleteTemplate: PersistedTemplate,
    jwtReader: JWTReader,
    pdfCreator: PDFCreator
  )(implicit ec: ExecutionContext): ProcessApi = new ProcessApi(
    new ProcessApiServiceImpl(
      partyManagementService,
      partyProcessService,
      userRegistryManagementService,
      productManagementService,
      pdfCreator = pdfCreator,
      fileManager,
      signatureService,
      onboardingInitMailer,
      mailTemplate,
      mailNotificationTemplate,
      mailRejectTemplate,
      mailAutoCompleteTemplate,
      relationshipService,
      productService,
      geoTaxonomy
    ),
    ProcessApiMarshallerImpl,
    jwtReader.OAuth2JWTValidatorAsContexts
  )

  def externalApi(
    partyManagementService: PartyManagementService,
    relationshipService: RelationshipService,
    productService: ProductService,
    geoTaxonomyService: GeoTaxonomyService,
    jwtReader: JWTReader
  )(implicit ec: ExecutionContext): ExternalApi = new ExternalApi(
    new ExternalApiServiceImpl(partyManagementService, relationshipService, productService, geoTaxonomyService),
    ExternalApiMarshallerImpl,
    jwtReader.OAuth2JWTValidatorAsContexts
  )

  def publicApi(
    partyManagementService: PartyManagementService,
    userRegistryManagementService: UserRegistryManagementService,
    productManagementService: ProductManagementService,
    signatureService: SignatureService,
    signatureValidationService: SignatureValidationService,
    mailTemplate: PersistedTemplate,
    fileManager: FileManager
  )(implicit ec: ExecutionContext): PublicApi = new PublicApi(
    new PublicApiServiceImpl(
      partyManagementService,
      userRegistryManagementService,
      productManagementService,
      signatureService,
      signatureValidationService,
      onboardingCompleteMailer,
      mailTemplate,
      fileManager
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

  def getOnboardingInitMailTemplate(fileManager: FileManager)(implicit
    ec: ExecutionContext
  ): Future[PersistedTemplate] =
    MailTemplate.get(ApplicationConfiguration.mailTemplatePath, fileManager)

  def getOnboardingCompleteMailTemplate(fileManager: FileManager)(implicit
    ec: ExecutionContext
  ): Future[PersistedTemplate] =
    MailTemplate.get(ApplicationConfiguration.onboardingCompleteMailTemplatePath, fileManager)

  def getOnboardingNotificationMailTemplate(fileManager: FileManager)(implicit
    ec: ExecutionContext
  ): Future[PersistedTemplate] =
    MailTemplate.get(ApplicationConfiguration.onboardingNotificationMailTemplatePath, fileManager)

  def getOnboardingRejectMailTemplate(fileManager: FileManager)(implicit
    ec: ExecutionContext
  ): Future[PersistedTemplate] =
    MailTemplate.get(ApplicationConfiguration.onboardingRejectMailTemplatePath, fileManager)

  def getOnboardingAutoCompleteMailTemplate(fileManager: FileManager)(implicit
    ec: ExecutionContext
  ): Future[PersistedTemplate] =
    MailTemplate.get(ApplicationConfiguration.onboardingAutoCompleteMailTemplatePath, fileManager)

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

  def padesSignService: () => PadesSignService = () => {
    new PadesSignServiceImpl(
      new ArubaPkcs7HashSignServiceImpl(new ArubaSignServiceImpl(buildArubaConfig(), new SoapLoggingHandler()))
    )
  }

  def buildArubaConfig(): ArubaSignConfig = {
    val config = new ArubaSignConfig()
    config.setBaseUrl(ApplicationConfiguration.arubaServiceUrl)
    config.setConnectTimeoutMs(0)
    config.setRequestTimeoutMs(0)

    val arubaAuth = new Auth()
    arubaAuth.setOtpPwd(ApplicationConfiguration.arubaOtpPwd)
    arubaAuth.setTypeHSM("COSIGN")
    arubaAuth.setTypeOtpAuth(ApplicationConfiguration.arubaTypeOtpAuth)
    arubaAuth.setUser(ApplicationConfiguration.arubaUser)
    arubaAuth.setDelegatedUser(ApplicationConfiguration.arubaDelegatedUser)
    arubaAuth.setDelegatedPassword(ApplicationConfiguration.arubaDelegatedPassword)
    arubaAuth.setDelegatedDomain(ApplicationConfiguration.arubaDelegatedDomain)
    config.setAuth(arubaAuth)

    config
  }

  def pdfCreator(padesSignService: PadesSignService): PDFCreator = {
    new PDFCreatorImpl(padesSignService)
  }

}
