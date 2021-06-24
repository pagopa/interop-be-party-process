package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import com.itextpdf.kernel.pdf.{PdfDocument, PdfWriter}
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.{RelationShip, RelationShips}
import it.pagopa.pdnd.interop.uservice.partyprocess.service.PDFCreator

import java.io.{File, FileOutputStream}
import java.nio.file.Files
import java.security.{DigestInputStream, MessageDigest}
import scala.concurrent.Future
import scala.util.Try
class PDFCreatorImp extends PDFCreator {
  override def create(relationShips: RelationShips): Future[(File, String)] = Future.fromTry {
    Try {
      val file               = new File(s"/tmp/contratto_interoperabilità.pdf")
      val stream             = new FileOutputStream(file)
      val writer: PdfWriter  = new PdfWriter(stream)
      val pdf: PdfDocument   = new PdfDocument(writer)
      val document: Document = new Document(pdf)

      val _ = document
        .add(new Paragraph("Rappresentati Legali"))
        .add(new Paragraph("\n"))
        .add(new Paragraph(relationShips.items.map(toText).mkString("\n")))
        .add(new Paragraph("\n\n\n\n"))
        .add(new Paragraph("Contratto:"))
        .add(
          new Paragraph(
            """
              |Lorem Ipsum is simply dummy text of the printing and typesetting industry. 
              |Lorem Ipsum has been the industry's standard dummy text ever since the 1500s, when an unknown printer 
              |took a galley of type and scrambled it to make a type specimen book. It has survived not only five 
              |centuries, but also the leap into electronic typesetting, remaining essentially unchanged. 
              |It was popularised in the 1960s with the release of Letraset sheets containing Lorem Ipsum passages, 
              |and more recently with desktop publishing software like Aldus PageMaker including versions 
              |of Lorem Ipsum.
              |""".stripMargin
          )
        )
        .add(new Paragraph("Note lagali"))
        .add(
          new Paragraph(
            """
              |Lorem Ipsum is simply dummy text of the printing and typesetting industry. 
              |Lorem Ipsum has been the industry's standard dummy text ever since the 1500s, when an unknown printer 
              |took a galley of type and scrambled it to make a type specimen book. It has survived not only five 
              |centuries, but also the leap into electronic typesetting, remaining essentially unchanged. 
              |It was popularised in the 1960s with the release of Letraset sheets containing Lorem Ipsum passages, 
              |and more recently with desktop publishing software like Aldus PageMaker including versions 
              |of Lorem Ipsum.
              |""".stripMargin
          )
        )
      document.close()
      val hash = computeHash(file)
      (file, hash)
    }
  }

  private def toText(relationShip: RelationShip): String = {
    s"""
       |${relationShip.from} 
       |è ${relationShip.role.toString.toLowerCase}
       |di ${relationShip.to}
       |""".stripMargin
  }

  private def computeHash(file: File): String = {
    val md = MessageDigest.getInstance("MD5")
    Files.walk(file.toPath).filter(!_.toFile.isDirectory).forEach { path =>
      val dis = new DigestInputStream(Files.newInputStream(path), md)
      while (dis.available > 0) {
        val _ = dis.read
      }
      dis.close()

    }
    md.digest.map(b => String.format("%02x", Byte.box(b))).mkString

  }

}
