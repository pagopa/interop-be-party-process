package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import courier.Defaults._
import courier._
import it.pagopa.pdnd.interop.uservice.partyprocess.service.Mailer

import java.io.File
import scala.concurrent.Future

class MailerImpl extends Mailer {

  private val user     = System.getenv("USER_MAIL")
  private val password = System.getenv("USER_PASSWORD")

  private val mailer = Mailer("smtp.gmail.com", 587)
    .auth(true)
    .as(user, password)
    .startTls(true)()

  def send(file: File, token: String): Future[Unit] = {
    mailer(
      Envelope
        .from("pdnd-interop" `@` "work.com")
        .to("stefano.perazzolo" `@` "pagopa.it")
        .subject("tps report")
        .content(
          Multipart()
            .attach(file)
            .html(s"<html><body><h1>${token}</h1></body></html>")
        )
    )

  }

}
