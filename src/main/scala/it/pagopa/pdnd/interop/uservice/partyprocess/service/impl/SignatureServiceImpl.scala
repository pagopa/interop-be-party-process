package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import eu.europa.esig.dss.enumerations.{DigestAlgorithm, Indication}
import eu.europa.esig.dss.model.{DSSDocument, FileDocument, InMemoryDocument}
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.validation.reports.Reports
import eu.europa.esig.dss.validation.{AdvancedSignature, SignedDocumentValidator}
import it.pagopa.pdnd.interop.commons.utils.TypeConversions.EitherOps
import it.pagopa.pdnd.interop.commons.utils.TypeConversions.OptionOps
import it.pagopa.pdnd.interop.uservice.partyprocess.error.InvalidDocumentSignature
import it.pagopa.pdnd.interop.uservice.partyprocess.service.SignatureService

import java.io.File
import scala.concurrent.Future
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

case object SignatureServiceImpl extends SignatureService {

  private final val europeanLOTL: LOTLSource      = SignatureService.getEuropeanLOTL
  private final val trustedListsCertificateSource = new TrustedListsCertificateSource()

  private val signatureRegex: Regex = "(TINIT-)(.*)".r

  private final val job: TLValidationJob = SignatureService.getJob(europeanLOTL)
  job.setTrustedListCertificateSource(trustedListsCertificateSource)
  job.onlineRefresh()

  def createDocumentValidator(bytes: Array[Byte]): Future[SignedDocumentValidator] = Future.fromTry {
    Try {
      job.offlineRefresh()

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

  override def verifyDigest(documentValidator: SignedDocumentValidator, digest: String): Future[Unit] = {

    val sign: AdvancedSignature       = documentValidator.getSignatures.get(0)
    val incomingOriginal: DSSDocument = documentValidator.getOriginalDocuments(sign.getId).get(0)
    val incomingDigest                = incomingOriginal.getDigest(DigestAlgorithm.SHA256)

    Either.cond(digest == incomingDigest, (), new RuntimeException("Invalid digest")).toFuture

  }

  override def verifySignature(documentValidator: SignedDocumentValidator): Future[Reports] = Future.fromTry {
    for {
      reports <- Try(documentValidator.validateDocument())
      _       <- isValid(reports)
    } yield reports
  }

  override def extractTaxCode(reports: Reports): Future[String] = Future.fromTry {
    for {
      prefixedTaxCode <- reports.getDiagnosticData.getUsedCertificates.asScala
        .flatMap(c => Option(c.getSubjectSerialNumber))
        .headOption
        .toTry("Certificate not found")
      taxCode <- prefixedTaxCode match {
        case signatureRegex(_, taxCode) => Success(taxCode)
        case _                          => Failure(new RuntimeException("Invalid signature format"))
      }
    } yield taxCode
  }

  private def isValid(reports: Reports): Try[Unit] = {
    Either
      .cond(
        reports.getEtsiValidationReportJaxb.getSignatureValidationReport
          .get(0)
          .getSignatureValidationStatus
          .getMainIndication == Indication.TOTAL_PASSED,
        (),
        InvalidDocumentSignature
      )
      .toTry
  }

}
