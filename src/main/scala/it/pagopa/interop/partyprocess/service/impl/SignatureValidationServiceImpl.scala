package it.pagopa.interop.partyprocess.service.impl

import cats.data.ValidatedNel
import cats.implicits._
import eu.europa.esig.dss.enumerations.{DigestAlgorithm, Indication}
import eu.europa.esig.dss.model.DSSDocument
import eu.europa.esig.dss.validation.reports.Reports
import eu.europa.esig.dss.validation.{AdvancedSignature, SignedDocumentValidator}
import it.pagopa.interop.partyprocess.error.SignatureValidationError
import it.pagopa.interop.partyprocess.error.ValidationErrors._
import it.pagopa.interop.partyprocess.model.UserRegistryUser
import it.pagopa.interop.partyprocess.service.SignatureValidationService

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.util.matching.Regex

case object SignatureValidationServiceImpl extends SignatureValidationService {

  private final val signatureRegex: Regex = "(TINIT-)(.*)".r

  def isDocumentSigned(documentValidator: SignedDocumentValidator): ValidatedNel[SignatureValidationError, Unit] = {
    val validation: Either[SignatureValidationError, Unit] =
      Either.cond(documentValidator.getSignatures.asScala.nonEmpty, (), SignatureNotFound)

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }
  }

  def verifyDigest(
    documentValidator: SignedDocumentValidator,
    originalDigest: String
  ): ValidatedNel[SignatureValidationError, Unit] = {

    val signs: List[AdvancedSignature] = documentValidator.getSignatures.asScala.toList

    val isDigestVerified: Boolean = signs.exists { sign =>
      val incomingOriginals: List[DSSDocument] = documentValidator.getOriginalDocuments(sign.getId).asScala.toList
      val incomingDigests: List[String]        = incomingOriginals.map(_.getDigest(DigestAlgorithm.SHA256))
      incomingDigests.contains(originalDigest)
    }

    val validation = Either.cond(isDigestVerified, (), InvalidContractDigest)

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }

  }

  def verifySignature(reports: Reports): ValidatedNel[SignatureValidationError, Unit] =
    isValid(reports) match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }

  def verifyManagerTaxCode(
    reports: Reports,
    legals: Seq[UserRegistryUser]
  ): ValidatedNel[SignatureValidationError, Unit] = {

    val validation: Either[SignatureValidationError, Unit] = for {
      signatureTaxCode <- extractTaxCode(reports)
      validated <- Either.cond(legals.exists(legal => legal.taxCode == signatureTaxCode), (), InvalidSignatureTaxCode)
    } yield validated

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }
  }

  def validateDocument(documentValidator: SignedDocumentValidator)(implicit ec: ExecutionContext): Future[Reports] =
    Future(documentValidator.validateDocument()).recoverWith { case ex =>
      Future.failed(DocumentValidationFail(ex.getMessage))
    }

  private def extractTaxCode(reports: Reports): Either[SignatureValidationError, String] = {
    for {
      prefixedTaxCode <- reports.getDiagnosticData.getUsedCertificates.asScala
        .flatMap(c => Option(c.getSubjectSerialNumber))
        .headOption
        .toRight(TaxCodeNotFoundInSignature)
      taxCode         <- prefixedTaxCode match {
        case signatureRegex(_, taxCode) => Right(taxCode)
        case _                          => Left(InvalidSignatureTaxCodeFormat)
      }
    } yield taxCode
  }

  private def isValid(reports: Reports): Either[SignatureValidationError, Unit] = {
    Either
      .cond(
        reports.getEtsiValidationReportJaxb.getSignatureValidationReport
          .get(0)
          .getSignatureValidationStatus
          .getMainIndication == Indication.TOTAL_PASSED,
        (),
        InvalidDocumentSignature
      )
  }

}
