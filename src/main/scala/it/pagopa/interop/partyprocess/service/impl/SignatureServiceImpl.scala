package it.pagopa.interop.partyprocess.service.impl

import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.model.{DSSDocument, FileDocument, InMemoryDocument}
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.validation.SignedDocumentValidator
import it.pagopa.interop.partyprocess.service.SignatureService

import java.io.File
import scala.concurrent.Future
import scala.util.Try

case class SignatureServiceImpl(trustedListsCertificateSource: TrustedListsCertificateSource) extends SignatureService {

  def createDocumentValidator(bytes: Array[Byte]): Future[SignedDocumentValidator] = Future.fromTry {
    Try {

      SignatureService.certificateVerifier.setTrustedCertSources(trustedListsCertificateSource)

      val document: DSSDocument = new InMemoryDocument(bytes)

      val documentValidator: SignedDocumentValidator = SignedDocumentValidator.fromDocument(document)

      documentValidator.setCertificateVerifier(SignatureService.certificateVerifier)

      documentValidator
    }
  }

  def createDigest(file: File): Future[String] = Future.fromTry {
    Try {
      val document: DSSDocument = new FileDocument(file)
      document.getDigest(DigestAlgorithm.SHA256)
    }

  }

}
