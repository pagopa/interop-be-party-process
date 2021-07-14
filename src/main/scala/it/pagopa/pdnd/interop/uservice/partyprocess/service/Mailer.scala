package it.pagopa.pdnd.interop.uservice.partyprocess.service

import java.io.File
import scala.concurrent.Future

trait Mailer {
  def send(address: String, file: File, token: String): Future[Unit]
}
