package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.ApplicationConfiguration.storageAccountInfo
import it.pagopa.pdnd.interop.uservice.partyprocess.service.FileManager
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, GetObjectResponse}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.io.{ByteArrayOutputStream, InputStream}
import scala.concurrent.Future
import scala.util.Try

final class S3ManagerImpl extends FileManager {

  lazy val s3Client: S3Client = {
    val awsCredentials =
      AwsBasicCredentials.create(storageAccountInfo.applicationId, storageAccountInfo.applicationSecret)
    val s3 = S3Client
      .builder()
      .region(Region.EU_CENTRAL_1)
      .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
      .build()
    s3
  }

  override def get(filePath: String): Future[ByteArrayOutputStream] = Future.fromTry {
    Try {
      val getObjectRequest: GetObjectRequest =
        GetObjectRequest.builder.bucket(storageAccountInfo.container).key(filePath).build
      val s3Object: ResponseBytes[GetObjectResponse] = s3Client.getObject(getObjectRequest, ResponseTransformer.toBytes)
      val inputStream: InputStream                   = s3Object.asInputStream()
      val outputStream: ByteArrayOutputStream        = new ByteArrayOutputStream()
      val _                                          = inputStream.transferTo(outputStream)
      outputStream
    }
  }

}
