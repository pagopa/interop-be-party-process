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

}
