package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import courier.Defaults._
import courier._
import it.pagopa.pdnd.interop.uservice.partyprocess.service.Mailer

import java.io.File
import scala.concurrent.Future
import scala.util.Try

class MailerImpl extends Mailer {

  private val user     = System.getenv("SMTP_USR")
  private val password = System.getenv("SMTP_PSW")

  private val mailer = Mailer("smtp.gmail.com", 587)
    .auth(true)
    .as(user, password)
    .startTls(true)()

  def send(address: String, file: File, token: String): Future[Unit] = parseEmail(address).flatMap { to =>
    mailer(
      Envelope
        .from("pdnd-interop" `@` "pagopa.it")
        .to(to)
        .subject("Chiusura procedura accreditamento interoperabilità")
        .content(
          Multipart()
            .attach(file)
            .html(createEmailBody(token))
        )
    )
  }

  private def parseEmail(address: String) = Future.fromTry(Try(address.addr))

  private def createEmailBody(token: String): String =
    s"""
       |<!doctype html>
       |<html>
       |  <head>
       |    <meta name="viewport" content="width=device-width" />
       |    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
       |    <title>Onboardin Email</title>
       |    <style>
       |      /* -------------------------------------
       |          GLOBAL RESETS
       |      ------------------------------------- */
       |
       |      /*All the styling goes here*/
       |
       |      img {
       |        border: none;
       |        -ms-interpolation-mode: bicubic;
       |        max-width: 100%;
       |      }
       |
       |      body {
       |        background-color: #f6f6f6;
       |        font-family: sans-serif;
       |        -webkit-font-smoothing: antialiased;
       |        font-size: 14px;
       |        line-height: 1.4;
       |        margin: 0;
       |        padding: 0;
       |        -ms-text-size-adjust: 100%;
       |        -webkit-text-size-adjust: 100%;
       |      }
       |
       |
       |      /* -------------------------------------
       |          BODY & CONTAINER
       |      ------------------------------------- */
       |
       |      .body {
       |        background-color: #f6f6f6;
       |        width: 100%;
       |      }
       |
       |      /* Set a max-width, and make it display as block so it will automatically stretch to that width, but will also shrink down on a phone or something */
       |      .container {
       |        display: block;
       |        margin: 0 auto !important;
       |        /* makes it centered */
       |        max-width: 580px;
       |        padding: 10px;
       |        width: 580px;
       |      }
       |
       |      /* This should also be a block element, so that it will fill 100% of the .container */
       |      .content {
       |        box-sizing: border-box;
       |        display: block;
       |        margin: 0 auto;
       |        max-width: 580px;
       |        padding: 10px;
       |      }
       |
       |      /* -------------------------------------
       |          HEADER, FOOTER, MAIN
       |      ------------------------------------- */
       |      .main {
       |        background: #ffffff;
       |        border-radius: 3px;
       |        width: 100%;
       |      }
       |
       |      .wrapper {
       |        box-sizing: border-box;
       |        padding: 20px;
       |      }
       |
       |      .content-block {
       |        padding-bottom: 10px;
       |        padding-top: 10px;
       |      }
       |
       |      .footer {
       |        clear: both;
       |        margin-top: 10px;
       |        text-align: center;
       |        width: 100%;
       |      }
       |        .footer td,
       |        .footer p,
       |        .footer span,
       |        .footer a {
       |          color: #999999;
       |          font-size: 12px;
       |          text-align: center;
       |      }
       |
       |      /* -------------------------------------
       |          TYPOGRAPHY
       |      ------------------------------------- */
       |      h1,
       |      h2,
       |      h3,
       |      h4 {
       |        color: #000000;
       |        font-family: sans-serif;
       |        font-weight: 400;
       |        line-height: 1.4;
       |        margin: 0;
       |        margin-bottom: 30px;
       |      }
       |      h1 {
       |        font-size: 35px;
       |        font-weight: 300;
       |        text-align: center;
       |        text-transform: capitalize;
       |      }
       |      p,
       |      ol {
       |        color: #888585;
       |        font-family: sans-serif;
       |        font-size: 14px;
       |        font-weight: normal;
       |        margin: 0;
       |        margin-bottom: 15px;
       |      }
       |
       |
       |
       |      .preheader {
       |        color: transparent;
       |        display: none;
       |        height: 0;
       |        max-height: 0;
       |        max-width: 0;
       |        opacity: 0;
       |        overflow: hidden;
       |        mso-hide: all;
       |        visibility: hidden;
       |        width: 0;
       |      }
       |
       |
       |  </style>
       |  </head>
       |  <body class="">
       |    <span class="preheader">Email accreditamento Interoperabilità</span>
       |    <table role="presentation" border="0" cellpadding="0" cellspacing="0" class="body">
       |      <tr>
       |        <td>&nbsp;</td>
       |        <td class="container">
       |          <div class="content">
       |
       |            <table role="presentation" class="main">
       |              <tr>
       |                <td class="wrapper">
       |                  <table role="presentation" border="0" cellpadding="0" cellspacing="0">
       |                    <tr>
       |                      <td>
       |                        <p>Buongiorno,</p>
       |                        <p>per concludere l'accreditamento sulla piattaforma di Interoperabilità caricate <a href=https://gateway.interop.pdnd.dev/ui/conferma-registrazione/$token>qui</a> il contratto firmato.</p>
       |                        <p>Se si vuole procedere con l'annullamento della richiesta di accreditamento proseguire <a href=https://gateway.interop.pdnd.dev/ui/cancella-registrazione/$token>qui</a>.</p>
       |                      </td>
       |                    </tr>
       |                  </table>
       |                </td>
       |              </tr>
       |            </table>
       |
       |          </div>
       |        </td>
       |        <td>&nbsp;</td>
       |      </tr>
       |    </table>
       |  </body>
       |</html>
       |""".stripMargin

}
