package it.pagopa.interop.partyprocess.service.impl

import com.typesafe.scalalogging.Logger
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.interop.commons.mail.model.{MailDataTemplate, PersistedTemplate}
import it.pagopa.interop.commons.mail.service.impl.DefaultInteropMailer
import it.pagopa.interop.commons.utils.model.TextTemplate
import it.pagopa.interop.partyprocess.service.MailEngine

import java.io.File
import scala.concurrent.{ExecutionContext, Future}

/** Decorates mail-manager mailer with party-process features
  */
trait PartyOnboardingCompleteMailer extends MailEngine { interopMailer: DefaultInteropMailer =>
  implicit val logger = Logger.takingImplicit[ContextFieldsToLog](this.getClass())

  def sendMail(
    mailTemplate: PersistedTemplate
  )(addresses: Seq[String], attachmentName: String, file: File, bodyParameters: Map[String, String])(
    emailPurpose: String = "onboarding-complete-email-notification"
  )(implicit executor: ExecutionContext, contexts: Seq[(String, String)]): Future[Unit] = {
    val subject = TextTemplate(mailTemplate.subject)

    val mailData = MailDataTemplate(
      recipients = addresses,
      subject = subject,
      body = TextTemplate(mailTemplate.body, bodyParameters)
    )

    interopMailer
      .sendWithTemplate(mailData)
      .map(_ => logger.info(s"[$emailPurpose] Email successful sent"))
      .recover(t => {
        logger.error(s"[$emailPurpose] An error occurred while sending email", t)
        throw t
      })
  }
}
