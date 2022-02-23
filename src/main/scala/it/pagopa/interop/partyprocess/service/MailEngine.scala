package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.commons.mail.model.PersistedTemplate

import java.io.File
import scala.concurrent.Future

trait MailEngine {
  def sendMail(
    mailTemplate: PersistedTemplate
  )(addresses: Seq[String], file: File, bodyParameters: Map[String, String]): Future[Unit]
}
