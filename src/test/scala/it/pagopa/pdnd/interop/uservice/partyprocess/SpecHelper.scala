package it.pagopa.pdnd.interop.uservice.partyprocess

import it.pagopa.pdnd.interop.commons.files.service.FileManager
import it.pagopa.pdnd.interop.commons.mail.model.PersistedTemplate
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.ProcessApiMarshallerImpl
import it.pagopa.pdnd.interop.uservice.partyprocess.api.{HealthApi, PlatformApi, ProcessApiMarshaller}
import it.pagopa.pdnd.interop.uservice.partyprocess.service._
import org.scalamock.scalatest.MockFactory

import java.io.File
import scala.concurrent.Future

object MockMailEngine extends MailEngine {
  override def sendMail(
    mailTemplate: PersistedTemplate
  )(addresses: Seq[String], file: File, bodyParameters: Map[String, String]): Future[Unit] = Future.successful(())
}

trait SpecHelper { self: MockFactory =>

  val processApiMarshaller: ProcessApiMarshaller             = new ProcessApiMarshallerImpl
  val mockHealthApi: HealthApi                               = mock[HealthApi]
  val mockPlatformApi: PlatformApi                           = mock[PlatformApi]
  val mockPartyManagementService: PartyManagementService     = mock[PartyManagementService]
  val mockPartyRegistryService: PartyRegistryService         = mock[PartyRegistryService]
  val mockUserRegistryService: UserRegistryManagementService = mock[UserRegistryManagementService]
  val mockAttributeRegistryService: AttributeRegistryService = mock[AttributeRegistryService]
  val mockMailer: MailEngine                                 = MockMailEngine
  val mockPdfCreator: PDFCreator                             = mock[PDFCreator]
  val mockFileManager: FileManager                           = mock[FileManager]
  val mockMailTemplate: PersistedTemplate                    = PersistedTemplate("mock", "mock")

  def loadEnvVars() = {
    System.setProperty("DELEGATE_PRODUCT_ROLES", "admin")
    System.setProperty("OPERATOR_PRODUCT_ROLES", "security, api")
    System.setProperty("MANAGER_PRODUCT_ROLES", "admin")
    System.setProperty("STORAGE_TYPE", "File")
    System.setProperty("STORAGE_CONTAINER", "local")
    System.setProperty("STORAGE_ENDPOINT", "local")
    System.setProperty("STORAGE_APPLICATION_ID", "local")
    System.setProperty("STORAGE_APPLICATION_SECRET", "local")
    System.setProperty("PARTY_MANAGEMENT_URL", "local")
    System.setProperty("PARTY_PROXY_URL", "local")
    System.setProperty("ATTRIBUTE_REGISTRY_URL", "local")
    System.setProperty("USER_REGISTRY_MANAGEMENT_URL", "local")

    System.setProperty("SMTP_SERVER", "localhost")
    System.setProperty("SMTP_USR", "local")
    System.setProperty("SMTP_PSW", "local")
    System.setProperty("SMTP_PORT", "10")
    System.setProperty("MAIL_SENDER_ADDRESS", "betta@dante.it")
    System.setProperty("MAIL_TEMPLATE_PATH", "localPath")
    System.setProperty("MAIL_ONBOARDING_CONFIRMATION_LINK", "confirm-value")
    System.setProperty("MAIL_REJECT_PLACEHOLDER_NAME", "testRejectName")
    System.setProperty("MAIL_ONBOARDING_REJECTION_LINK", "reject-value")
  }

}
