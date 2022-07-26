package it.pagopa.interop.partyprocess.service.impl

import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.model.{DSSDocument, FileDocument, InMemoryDocument}
import eu.europa.esig.dss.service.crl.OnlineCRLSource
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource
import eu.europa.esig.dss.validation.{CommonCertificateVerifier, SignedDocumentValidator}
import it.pagopa.interop.partyprocess.service.SignatureService

import java.io.File
import scala.concurrent.Future
import scala.util.Try

case class SignatureServiceImpl(trustedListsCertificateSource: TrustedListsCertificateSource) extends SignatureService {

  private final val certificateViewer: CommonCertificateVerifier = {
    val certificateVerifier = new CommonCertificateVerifier
    certificateVerifier.setTrustedCertSources(trustedListsCertificateSource)
    certificateVerifier.setAIASource(new DefaultAIASource())
    certificateVerifier.setOcspSource(new OnlineOCSPSource())
    certificateVerifier.setCrlSource(new OnlineCRLSource())
    certificateVerifier
  }

  def createDocumentValidator(bytes: Array[Byte]): Future[SignedDocumentValidator] = Future.fromTry {
    Try {

      val document: DSSDocument = new InMemoryDocument(bytes)

      val documentValidator: SignedDocumentValidator = SignedDocumentValidator.fromDocument(document)

      documentValidator.setCertificateVerifier(certificateViewer)

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
