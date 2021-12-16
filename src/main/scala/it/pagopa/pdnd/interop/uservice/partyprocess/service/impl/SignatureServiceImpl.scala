package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.model.{DSSDocument, FileDocument, InMemoryDocument}
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.validation.SignedDocumentValidator
import it.pagopa.pdnd.interop.uservice.partyprocess.service.SignatureService

import java.io.File
import scala.concurrent.Future
import scala.util.Try

case object SignatureServiceImpl extends SignatureService {

  private final val europeanLOTL: LOTLSource      = SignatureService.getEuropeanLOTL
  private final val trustedListsCertificateSource = new TrustedListsCertificateSource()

  private final val job: TLValidationJob = SignatureService.getJob(europeanLOTL)
  job.setTrustedListCertificateSource(trustedListsCertificateSource)
  // TODO this must be managed with cronjob
  job.onlineRefresh()
//  job.offlineRefresh()

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
