package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import eu.europa.esig.dss.enumerations.{DigestAlgorithm, Indication}
import eu.europa.esig.dss.model.{DSSDocument, FileDocument}
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.validation.reports.Reports
import eu.europa.esig.dss.validation.{AdvancedSignature, SignedDocumentValidator}
import it.pagopa.pdnd.interop.uservice.partyprocess.error.InvalidDocumentSignature
import it.pagopa.pdnd.interop.uservice.partyprocess.service.SignatureValidator

import java.io.File
import java.nio.file.Paths
import scala.concurrent.Future
import scala.util.Try

case object SignatureValidatorImpl extends SignatureValidator {

  private final val europeanLOTL: LOTLSource      = SignatureValidator.getEuropeanLOTL
  private final val trustedListsCertificateSource = new TrustedListsCertificateSource()

  private final val job: TLValidationJob = SignatureValidator.getJob(europeanLOTL)
  job.setTrustedListCertificateSource(trustedListsCertificateSource)
  job.onlineRefresh()

  override def validate(file: File): Future[String] = {

    val reports: Try[Reports] = Try {
      job.offlineRefresh()

      SignatureValidator.certificateVerifier.setTrustedCertSources(trustedListsCertificateSource)

      val document: DSSDocument = new FileDocument(file)

      val documentValidator: SignedDocumentValidator = SignedDocumentValidator.fromDocument(document)

      documentValidator.setCertificateVerifier(SignatureValidator.certificateVerifier)

      val sign: AdvancedSignature = documentValidator.getSignatures.get(0)
      val originalPath =
        "/Users/stefanoperazzolo/projects/workspace/pdnd-interop/pdnd-interop-uservice-party-process/src/main/resources/TA_SSO_1.0_19 nov 2020.pdf"
      val original: DSSDocument = new FileDocument(Paths.get(originalPath).toFile)

      val originalDigest = original.getDigest(DigestAlgorithm.SHA256)

      val incomingOriginal: DSSDocument = documentValidator.getOriginalDocuments(sign.getId).get(0)

      val incomingDigest = incomingOriginal.getDigest(DigestAlgorithm.SHA256)

      println(originalDigest == incomingDigest)

      documentValidator.validateDocument()

    }

    Future.fromTry {
      for {
        reports    <- reports
        validated  <- isValid(reports)
        fiscalCode <- extract(validated)
      } yield fiscalCode
    }
  }

  private def isValid(reports: Reports): Try[Reports] = {
    Either
      .cond(
        reports.getEtsiValidationReportJaxb.getSignatureValidationReport
          .get(0)
          .getSignatureValidationStatus
          .getMainIndication == Indication.TOTAL_PASSED,
        reports,
        InvalidDocumentSignature
      )
      .toTry
  }

  private def extract(reports: Reports): Try[String] = Try(
    reports.getDiagnosticData.getUsedCertificates.get(0).getSubjectSerialNumber
  )

}
