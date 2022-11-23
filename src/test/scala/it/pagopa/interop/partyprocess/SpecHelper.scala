package it.pagopa.interop.partyprocess

import eu.europa.esig.dss.validation.SignedDocumentValidator
import eu.europa.esig.dss.validation.reports.Reports
import it.pagopa.interop.commons.files.service.FileManager
import it.pagopa.interop.commons.mail.model.PersistedTemplate
import it.pagopa.interop.partyprocess.api.impl.{
  ExternalApiMarshallerImpl,
  ProcessApiMarshallerImpl,
  PublicApiMarshallerImpl
}
import it.pagopa.interop.partyprocess.api.{ExternalApiMarshaller, HealthApi, ProcessApiMarshaller, PublicApiMarshaller}
import it.pagopa.interop.partyprocess.service._
import org.scalamock.scalatest.MockFactory

import java.io.File
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

object MockMailEngine extends MailEngine {
  override def sendMail(
    mailTemplate: PersistedTemplate
  )(addresses: Seq[String], attachmentName: String, file: File, bodyParameters: Map[String, String])(
    emailPurpose: String = "email-notification"
  )(implicit executor: ExecutionContext, contexts: Seq[(String, String)]): Future[Unit] = Future.successful(())
}

trait SpecHelper { self: MockFactory =>

  val processApiMarshaller: ProcessApiMarshaller                 = ProcessApiMarshallerImpl
  val externalApiMarshaller: ExternalApiMarshaller               = ExternalApiMarshallerImpl
  val publicApiMarshaller: PublicApiMarshaller                   = PublicApiMarshallerImpl
  val mockHealthApi: HealthApi                                   = mock[HealthApi]
  val mockPartyManagementService: PartyManagementService         = mock[PartyManagementService]
  val mockPartyRegistryService: PartyRegistryService             = mock[PartyRegistryService]
  val mockUserRegistryService: UserRegistryManagementService     = mock[UserRegistryManagementService]
  val mockProductService: ProductManagementService               = mock[ProductManagementService]
  val mockMailer: MailEngine                                     = MockMailEngine
  val mockPdfCreator: PDFCreator                                 = mock[PDFCreator]
  val mockFileManager: FileManager                               = mock[FileManager]
  val mockSignatureService: SignatureService                     = mock[SignatureService]
  val mockSignatureValidationService: SignatureValidationService = mock[SignatureValidationService]
  val mockReports: Reports                                       = mock[Reports]
  val mockSignedDocumentValidator: SignedDocumentValidator       = mock[SignedDocumentValidator]
  val mockMailTemplate: PersistedTemplate                        = PersistedTemplate("mock", "mock")
  val mockMailNotificationTemplate: PersistedTemplate            = PersistedTemplate("mock", "mock")
  val mockMailRejectTemplate: PersistedTemplate                  = PersistedTemplate("mock", "mock")

  val token = UUID.randomUUID()
  val uid   = UUID.randomUUID()

  def loadEnvVars() = {
    System.setProperty("DELEGATE_PRODUCT_ROLES", "admin")
    System.setProperty("OPERATOR_PRODUCT_ROLES", "security, api")
    System.setProperty("MANAGER_PRODUCT_ROLES", "admin")
    System.setProperty("STORAGE_TYPE", "File")
    System.setProperty("STORAGE_CONTAINER", "local")
    System.setProperty("STORAGE_ENDPOINT", "local")
    System.setProperty("STORAGE_CREDENTIAL_ID", "local")
    System.setProperty("STORAGE_CREDENTIAL_SECRET", "local")
    System.setProperty("PARTY_MANAGEMENT_URL", "local")
    System.setProperty("PARTY_PROXY_URL", "local")
    System.setProperty("ATTRIBUTE_REGISTRY_URL", "local")
    System.setProperty("USER_REGISTRY_MANAGEMENT_URL", "local")
    System.setProperty("USER_REGISTRY_API_KEY", "apiKey")
    System.setProperty("EU_LIST_OF_TRUSTED_LISTS_URL", "url")
    System.setProperty("EU_OFFICIAL_JOURNAL_URL", "url")

    System.setProperty("SMTP_HOST", "localhost")
    System.setProperty("SMTP_USR", "local")
    System.setProperty("SMTP_PSW", "local")
    System.setProperty("SMTP_PORT", "10")
    System.setProperty("MAIL_SENDER_ADDRESS", "betta@dante.it")
    System.setProperty("MAIL_TEMPLATE_PATH", "localPath")
    System.setProperty("MAIL_ONBOARDING_CONFIRMATION_LINK", "confirm-value")
    System.setProperty("MAIL_REJECT_PLACEHOLDER_NAME", "testRejectName")
    System.setProperty("MAIL_ONBOARDING_REJECTION_LINK", "reject-value")
    System.setProperty("WELL_KNOWN_URL", "http://localhost/.well-known/jwks.json")
    System.setProperty("MAIN_AUDIENCE", "audience")
  }

}
