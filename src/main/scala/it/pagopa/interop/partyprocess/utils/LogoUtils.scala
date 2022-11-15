package it.pagopa.interop.partyprocess.utils

import it.pagopa.interop.commons.files.service.FileManager
import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration

import java.io.File
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import it.pagopa.interop.commons.utils.TypeConversions._
import java.io.{ByteArrayOutputStream, FileOutputStream}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object LogoUtils {
  def getLogoFile(fileManager: FileManager, filePath: String)(implicit ec: ExecutionContext): Future[File] = for {
    logoStream <- fileManager.get(ApplicationConfiguration.storageContainer)(filePath)
    file       <- Try {
      createTempFile
    }.toFuture
    _          <- Try {
      getLogoAsFile(file.get, logoStream)
    }.toFuture
  } yield file.get

  private def createTempFile: Try[File] = {
    Try {
      val fileTimestamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
      File.createTempFile(s"${fileTimestamp}_${UUID.randomUUID().toString}_logo.", ".png")
    }
  }

  private def getLogoAsFile(destination: File, logo: ByteArrayOutputStream): Try[File] = {
    Try {
      val fos = new FileOutputStream(destination)
      logo.writeTo(fos)
      fos.close()
      destination
    }
  }
}
