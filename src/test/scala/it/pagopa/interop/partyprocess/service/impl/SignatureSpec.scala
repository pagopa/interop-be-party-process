package it.pagopa.interop.partyprocess.service.impl

import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext.Implicits.global
import java.nio.file.{Files, Paths, Path}
import scala.concurrent.Await
import scala.concurrent.duration._
import it.pagopa.interop.partyprocess.server.impl.Dependencies
import it.pagopa.interop.partyprocess.service.SignatureService
import eu.europa.esig.dss.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource
import eu.europa.esig.dss.service.crl.OnlineCRLSource
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.validation.SignedDocumentValidator
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.tsl.job.TLValidationJob
import scala.concurrent.Future

class SignatureSpec extends AnyWordSpecLike with Matchers with Dependencies {

  def trustedListsCertificateSourceJob(): Future[TrustedListsCertificateSource] = {
    val europeanLOTL: LOTLSource      = SignatureService.getEuropeanLOTL
    val trustedListsCertificateSource = new TrustedListsCertificateSource()
    val job: TLValidationJob          = SignatureService.getJob(europeanLOTL)
    job.setTrustedListCertificateSource(trustedListsCertificateSource)
    Future(job.onlineRefresh()).map(_ => trustedListsCertificateSource)
  }

  def certificateViewer(trustedListsCertificateSource: TrustedListsCertificateSource): CommonCertificateVerifier = {
    val certificateVerifier = new CommonCertificateVerifier
    certificateVerifier.setTrustedCertSources(trustedListsCertificateSource)
    certificateVerifier.setAIASource(new DefaultAIASource())
    certificateVerifier.setOcspSource(new OnlineOCSPSource())
    certificateVerifier.setCrlSource(new OnlineCRLSource())
    certificateVerifier
  }

  def docValidator(certificateVerifier: CommonCertificateVerifier): SignedDocumentValidator = {
    println("Creating Validator")
    val documentValidator = SignedDocumentValidator.fromDocument(new InMemoryDocument(documentBytes))
    documentValidator.setCertificateVerifier(certificateVerifier)
    documentValidator
  }

  val documentPath: Path         = Paths.get("path to document")
  val documentBytes: Array[Byte] = Files.readAllBytes(documentPath)

  "signature must work" in {
    assert(documentBytes.nonEmpty)

    val validator: Future[SignedDocumentValidator] =
      trustedListsCertificateSourceJob().map(certificateViewer).map(docValidator)

    val actualValidator = Await.result(validator, 10.minutes)

    println(actualValidator.validateDocument())
  }

}
