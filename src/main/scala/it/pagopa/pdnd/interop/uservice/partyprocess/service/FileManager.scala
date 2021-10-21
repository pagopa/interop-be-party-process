package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.partyprocess.service.impl.{
  BlobStorageManagerImpl,
  FileManagerImpl,
  S3ManagerImpl
}

import java.io.ByteArrayOutputStream
import scala.concurrent.Future
import scala.util.{Failure, Try}

trait FileManager {
  def get(filePath: String): Future[ByteArrayOutputStream]
}

object FileManager {
  def getConcreteImplementation(fileManager: String): Try[FileManager] = {
    fileManager match {
      case "File"           => Try { new FileManagerImpl() }
      case "BlobStorage"    => Try { new BlobStorageManagerImpl() }
      case "S3"             => Try { new S3ManagerImpl() }
      case wrongManager @ _ => Failure(new RuntimeException(s"Unsupported file manager: $wrongManager"))
    }
  }
}
