package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.partyprocess.service.FileManager

import java.io.{ByteArrayOutputStream, FileInputStream, InputStream}
import scala.concurrent.Future
import scala.util.Try

@SuppressWarnings(Array("org.wartremover.warts.ToString"))
final class FileManagerImpl extends FileManager {

  def get(filePath: String): Future[ByteArrayOutputStream] = Future.fromTry {
    Try {
      val inputStream: InputStream            = new FileInputStream(filePath)
      val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()
      val _                                   = inputStream.transferTo(outputStream)
      outputStream
    }
  }
}
