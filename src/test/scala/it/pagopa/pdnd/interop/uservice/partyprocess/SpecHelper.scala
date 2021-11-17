package it.pagopa.pdnd.interop.uservice.partyprocess

import it.pagopa.pdnd.interop.commons.files.service.FileManager
import it.pagopa.pdnd.interop.commons.mail.model.PersistedTemplate
import it.pagopa.pdnd.interop.uservice.partyprocess.api.{HealthApi, PlatformApi, ProcessApiMarshaller}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.ProcessApiMarshallerImpl
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{
  AttributeRegistryService,
  AuthorizationProcessService,
  MailEngine,
  PDFCreator,
  PartyManagementService,
  PartyRegistryService,
  UserRegistryManagementService
}
import org.scalamock.scalatest.MockFactory

import java.io.File

trait SpecHelper { self: MockFactory =>

  val processApiMarshaller: ProcessApiMarshaller                   = new ProcessApiMarshallerImpl
  val mockHealthApi: HealthApi                                     = mock[HealthApi]
  val mockPlatformApi: PlatformApi                                 = mock[PlatformApi]
  val mockPartyManagementService: PartyManagementService           = mock[PartyManagementService]
  val mockPartyRegistryService: PartyRegistryService               = mock[PartyRegistryService]
  val mockUserRegistryService: UserRegistryManagementService       = mock[UserRegistryManagementService]
  val mockAuthorizationProcessService: AuthorizationProcessService = mock[AuthorizationProcessService]
  val mockAttributeRegistryService: AttributeRegistryService       = mock[AttributeRegistryService]
  val mockMailer: MailEngine                                       = mock[MailEngine]
  val mockPdfCreator: PDFCreator                                   = mock[PDFCreator]
  val mockFileManager: FileManager                                 = mock[FileManager]
  val mockMailTemplate: PersistedTemplate                          = PersistedTemplate("mock", "mock")

  def mockMailerInvocation(addresses: Seq[String], file: File, bodyParameters: Map[String, String]) =
    mockMailer.sendMail(mockMailTemplate)(addresses, file, bodyParameters)

}
