package it.pagopa.interop.partyprocess.service.impl

import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.service.crl.OnlineCRLSource
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.validation.{CommonCertificateVerifier, SignedDocumentValidator}
import it.pagopa.interop.partyprocess.model.UserRegistryUser
import it.pagopa.interop.partyprocess.service.{SignatureService, SignatureValidationService}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class SignatureSpec extends AnyWordSpecLike with Matchers {

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

  "signature must work" in {

    val unsignedDocumentPath: Path = Paths.get("unsigned doc path")

    val signedDocumentPath: Path = Paths.get("signed doc path")

    val signedDocumentBytes: Array[Byte] = Files.readAllBytes(signedDocumentPath)

    assert(signedDocumentBytes.nonEmpty)

    def docValidator(certificateVerifier: CommonCertificateVerifier): SignedDocumentValidator = {
      println("Creating Validator")
      val documentValidator = SignedDocumentValidator.fromDocument(new InMemoryDocument(signedDocumentBytes))
      documentValidator.setCertificateVerifier(certificateVerifier)
      documentValidator
    }

    val testResult: Future[String] = for {
      tl <- trustedListsCertificateSourceJob()
      signatureService = SignatureServiceImpl(tl)
      unsignedDigest <- signatureService.createDigest(unsignedDocumentPath.toFile)
      _         = println(s"Unsigned Digest: $unsignedDigest")
      validator = docValidator(certificateViewer(tl))
      _ <- SignatureValidationService.validateSignature(SignatureValidationServiceImpl.isDocumentSigned(validator))
      reports <- SignatureValidationServiceImpl.validateDocument(validator)
      _       <-
        SignatureValidationService.validateSignature(
          SignatureValidationServiceImpl.verifySignature(reports),
          SignatureValidationServiceImpl.verifyDigest(validator, unsignedDigest),
          SignatureValidationServiceImpl.verifyManagerTaxCode(
            reports,
            Seq(
              UserRegistryUser(id = UUID.randomUUID(), name = "", surname = "", taxCode = "tax code 1"),
              UserRegistryUser(id = UUID.randomUUID(), name = "", surname = "", taxCode = "tax code 2")
            )
          )
        )
    } yield "OK"

    val result = Await.result(testResult, Duration.Inf)

    assert(result == "OK")
  }

}
