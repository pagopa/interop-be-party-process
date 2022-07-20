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

import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

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
      val incomingOriginal: DSSDocument = documentValidator.getOriginalDocuments(sign.getId).get(0)
      val incomingDigest: String        = incomingOriginal.getDigest(DigestAlgorithm.SHA256)

      originalDigest == incomingDigest

    }

    val validation = Either.cond(isDigestVerified, (), InvalidContractDigest)

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }

  }

  def verifySignature(documentValidator: SignedDocumentValidator): ValidatedNel[SignatureValidationError, Unit] = {
    val validation: Either[SignatureValidationError, Unit] = for {
      reports   <- validateDocument(documentValidator)
      validated <- isValid(reports)
    } yield validated

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }
  }

  def verifyManagerTaxCode(
    documentValidator: SignedDocumentValidator,
    legals: Seq[UserRegistryUser]
  ): ValidatedNel[SignatureValidationError, Unit] = {

    val validation: Either[SignatureValidationError, Unit] = for {
      reports          <- validateDocument(documentValidator)
      signatureTaxCode <- extractTaxCode(reports)
      validated <- Either.cond(legals.exists(legal => legal.taxCode == signatureTaxCode), (), InvalidSignatureTaxCode)
    } yield validated

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }
  }

  private def validateDocument(
    documentValidator: SignedDocumentValidator
  ): Either[SignatureValidationError, Reports] = {
    Try(documentValidator.validateDocument()) match {
      case Success(value) => Right(value)
      case Failure(ex)    => Left(DocumentValidationFail(ex.getMessage))
    }
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
