package it.pagopa.interop.partyprocess.service.impl

import it.pagopa.interop.commons.mail.model.{MailAttachment, MailDataTemplate, PersistedTemplate}
import it.pagopa.interop.commons.mail.service.impl.DefaultInteropMailer
import it.pagopa.interop.commons.utils.model.TextTemplate
import it.pagopa.interop.partyprocess.service.MailEngine

import java.io.File
import java.nio.file.Files
import scala.concurrent.Future

/** Decorates mail-manager mailer with party-process features
  */
trait PartyProcessMailer extends MailEngine { interopMailer: DefaultInteropMailer =>

  def sendMail(
    mailTemplate: PersistedTemplate
  )(addresses: Seq[String], file: File, bodyParameters: Map[String, String]): Future[Unit] = {
    val subject = TextTemplate(mailTemplate.subject)

    val mailData = MailDataTemplate(
      recipients = addresses,
      subject = subject,
      body = TextTemplate(mailTemplate.body, bodyParameters),
      attachments = Seq(parseAttachmentFile(file))
    )

    interopMailer.sendWithTemplate(mailData)
  }

  private def parseAttachmentFile(file: File): MailAttachment = {
    val filePath = file.toPath
    val content  = Files.readAllBytes(filePath)
    val mimeType = Files.probeContentType(filePath)
    MailAttachment("onboarding.pdf", content, mimeType)
  }
}
