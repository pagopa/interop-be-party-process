package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import com.itextpdf.kernel.pdf.{PdfDocument, PdfWriter}
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.Organization
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.Digester
import it.pagopa.pdnd.interop.uservice.partyprocess.model.User
import it.pagopa.pdnd.interop.uservice.partyprocess.service.PDFCreator

import java.io.{File, FileOutputStream}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.Future
import scala.util.Try

class PDFCreatorImp extends PDFCreator {

  def create(users: Seq[User], organization: Organization): Future[(File, String)] = Future.fromTry {
    Try {
      val fileTimestamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
      val file: File =
        File.createTempFile(s"${fileTimestamp}_${UUID.randomUUID().toString}_contratto_interoperabilità.", ".pdf")
      val stream: FileOutputStream = new FileOutputStream(file)
      val writer: PdfWriter        = new PdfWriter(stream)
      val pdf: PdfDocument         = new PdfDocument(writer)
      val document: Document       = new Document(pdf)

      val _ = document
        .add(new Paragraph(s"Le persone\n${users.map(userToText).mkString("\n")}"))
        .add(
          new Paragraph(
            s"si accreditano presso la piattaforma di Interoperabilità in qualità di Rappresentanti Legali dell'Ente\n${orgToText(organization)}"
          )
        )
        .add(
          new Paragraph(
            """
              |Contratto:
              |Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et
              |dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip
              |ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore
              |eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia
              |deserunt mollit anim id est laborum.
              |""".stripMargin
          )
        )
        .add(
          new Paragraph(
            """
              |Note lagali:
              |Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et
              |dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip
              |ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore
              |eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia
              |deserunt mollit anim id est laborum.
              |""".stripMargin
          )
        )
      document.close()
      val hash = Digester.createHash(file)
      (file, hash)
    }
  }

  private def userToText(user: User): String = {
    s"""
       |Nome: ${user.name} 
       |Cognome: ${user.surname}
       |Codice fiscale: ${user.taxCode}
       |Ruolo: ${user.role}
       |""".stripMargin
  }

  private def orgToText(organization: Organization): String = {
    s"""
       |Nome: ${organization.description}
       |Rappresentante Legale: ${organization.managerName} ${organization.managerSurname}
       |Domicilio Digitale: ${organization.digitalAddress}
       |""".stripMargin
  }

}
