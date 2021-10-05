package it.pagopa.pdnd.interop.uservice.partyprocess.service

import java.io.File
import scala.concurrent.Future

trait Mailer {
  def send(addresses: Seq[String], file: File, token: String): Future[Unit]
}
