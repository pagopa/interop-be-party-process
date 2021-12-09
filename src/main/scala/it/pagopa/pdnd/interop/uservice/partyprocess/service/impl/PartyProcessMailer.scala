package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.commons.mail.model.{MailAttachment, MailDataTemplate, PersistedTemplate}
import it.pagopa.pdnd.interop.commons.utils.model.TextTemplate
import it.pagopa.pdnd.interop.commons.mail.service.impl.DefaultPDNDMailer
import it.pagopa.pdnd.interop.uservice.partyprocess.service.MailEngine

import java.io.File
import java.nio.file.Files
import scala.concurrent.Future

/** Decorates mail-manager mailer with party-process features
  */
trait PartyProcessMailer extends MailEngine { pdndMailer: DefaultPDNDMailer =>

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

    pdndMailer.sendWithTemplate(mailData)
  }

  private def parseAttachmentFile(file: File): MailAttachment = {
    val filePath = file.toPath
    val content  = Files.readAllBytes(filePath)
    val mimeType = Files.probeContentType(filePath)
    MailAttachment("onboarding.pdf", content, mimeType)
  }
}
