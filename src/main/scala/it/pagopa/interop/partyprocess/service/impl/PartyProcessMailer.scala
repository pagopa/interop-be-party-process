package it.pagopa.interop.partyprocess.service.impl

import it.pagopa.interop.commons.mail.model.{MailAttachment, MailDataTemplate, PersistedTemplate}
import it.pagopa.interop.commons.mail.service.impl.DefaultInteropMailer
import it.pagopa.interop.commons.utils.model.TextTemplate
import it.pagopa.interop.partyprocess.service.MailEngine

import java.io.File
import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}
import com.typesafe.scalalogging.Logger
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}

/** Decorates mail-manager mailer with party-process features
  */
trait PartyProcessMailer extends MailEngine { interopMailer: DefaultInteropMailer =>
  implicit val logger = Logger.takingImplicit[ContextFieldsToLog](this.getClass())

  def sendMail(
    mailTemplate: PersistedTemplate
  )(addresses: Seq[String], file: File, bodyParameters: Map[String, String])(
    emailPurpose: String = "email-notification"
  )(implicit executor: ExecutionContext, contexts: Seq[(String, String)]): Future[Unit] = {
    val subject = TextTemplate(mailTemplate.subject)

    val mailData = MailDataTemplate(
      recipients = addresses,
      subject = subject,
      body = TextTemplate(mailTemplate.body, bodyParameters),
      attachments = Seq(parseAttachmentFile(file, bodyParameters.get("productName")))
    )

    interopMailer
      .sendWithTemplate(mailData)
      .map(_ => logger.info(s"[$emailPurpose] Email successful sent"))
      .recover(t => {
        logger.error(s"[$emailPurpose] An error occurred while sending email", t)
        throw t
      })
  }

  private def parseAttachmentFile(file: File, productName: Option[String]): MailAttachment = {
    val filePath = file.toPath
    val content  = Files.readAllBytes(filePath)
    val mimeType = Files.probeContentType(filePath)
    val prefix = if (productName.isEmpty) "" else productName.toString
    MailAttachment(prefix + "_accordo_adesione.pdf", content, mimeType)
  }
}
