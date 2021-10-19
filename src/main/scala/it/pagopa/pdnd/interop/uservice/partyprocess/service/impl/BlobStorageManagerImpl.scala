package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import com.azure.storage.blob.specialized.BlockBlobClient
import com.azure.storage.blob.{BlobServiceClient, BlobServiceClientBuilder}
import com.azure.storage.common.StorageSharedKeyCredential
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.ApplicationConfiguration.storageAccountInfo
import it.pagopa.pdnd.interop.uservice.partyprocess.service.FileManager

import java.io.ByteArrayOutputStream
import scala.concurrent.Future
import scala.util.Try

final class BlobStorageManagerImpl extends FileManager {

  lazy val azureBlobClient = {
    val accountName: String = storageAccountInfo.applicationId
    val accountKey: String  = storageAccountInfo.applicationSecret
    val endpoint: String    = storageAccountInfo.endpoint
    val credential          = new StorageSharedKeyCredential(accountName, accountKey)
    val storageClient: BlobServiceClient =
      new BlobServiceClientBuilder().endpoint(endpoint).credential(credential).buildClient

    storageClient
  }

  override def get(filePath: String): Future[ByteArrayOutputStream] = Future.fromTry {
    Try {
      val blobContainerClient         = azureBlobClient.getBlobContainerClient(storageAccountInfo.container)
      val blobClient: BlockBlobClient = blobContainerClient.getBlobClient(filePath).getBlockBlobClient

      val dataSize: Int                       = blobClient.getProperties.getBlobSize.toInt
      val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream(dataSize)
      val _                                   = blobClient.downloadStream(outputStream)
      outputStream
    }
  }

}
