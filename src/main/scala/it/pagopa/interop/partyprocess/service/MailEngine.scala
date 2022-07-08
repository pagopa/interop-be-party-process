package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.commons.mail.model.PersistedTemplate

import java.io.File
import scala.concurrent.{ExecutionContext, Future}

trait MailEngine {
  def sendMail(
    mailTemplate: PersistedTemplate
  )(addresses: Seq[String], attachmentName: String, file: File, bodyParameters: Map[String, String])(
    emailPurpose: String
  )(implicit executor: ExecutionContext, contexts: Seq[(String, String)]): Future[Unit]
}
