package it.pagopa.pdnd.interop.uservice.partyprocess.service

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.implicits._
import eu.europa.esig.dss.enumerations.{DigestAlgorithm, Indication, SignatureForm}
import eu.europa.esig.dss.model.DSSDocument
import eu.europa.esig.dss.validation.reports.Reports
import eu.europa.esig.dss.validation.{AdvancedSignature, SignedDocumentValidator}
import it.pagopa.pdnd.interop.uservice.partyprocess.error.InvalidSignature
import it.pagopa.pdnd.interop.uservice.partyprocess.error.validation._
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{User => UserRegistryUser}

import scala.concurrent.Future
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

trait SignatureValidationService {

  private final val signatureRegex: Regex = "(TINIT-)(.*)".r

  def verifySignatureForm(documentValidator: SignedDocumentValidator): ValidatedNel[ValidationError, Unit] = {
    val signatures: List[AdvancedSignature] = documentValidator.getSignatures.asScala.toList
    val invalidSignatureForms: List[SignatureForm] =
      signatures.map(_.getSignatureForm).filter(_ != SignatureForm.CAdES)
    val validation: Either[InvalidSignatureForms, Unit] =
      Either.cond(invalidSignatureForms.isEmpty, (), InvalidSignatureForms(invalidSignatureForms.map(_.toString)))

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[ValidationError]
    }
  }

  def isDocumentSigned(documentValidator: SignedDocumentValidator): ValidatedNel[ValidationError, Unit] = {
    val validation: Either[ValidationError, Unit] =
      Either.cond(documentValidator.getSignatures.asScala.nonEmpty, (), SignatureNotFound)

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[ValidationError]
    }
  }

  def verifyDigest(
    documentValidator: SignedDocumentValidator,
    originalDigest: String
  ): ValidatedNel[ValidationError, Unit] = {

    val sign: AdvancedSignature       = documentValidator.getSignatures.get(0)
    val incomingOriginal: DSSDocument = documentValidator.getOriginalDocuments(sign.getId).get(0)
    val incomingDigest: String        = incomingOriginal.getDigest(DigestAlgorithm.SHA256)

    val validation: Either[InvalidContractDigest, Unit] =
      Either.cond(originalDigest == incomingDigest, (), InvalidContractDigest(originalDigest, incomingDigest))

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[ValidationError]
    }

  }

  def verifySignature(documentValidator: SignedDocumentValidator): ValidatedNel[ValidationError, Unit] = {
    val validation: Either[ValidationError, Unit] = {
      for {
        reports   <- validateDocument(documentValidator)
        validated <- isValid(reports)
      } yield validated
    }

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[ValidationError]
    }
  }

  def verifyManagerTaxCode(
    documentValidator: SignedDocumentValidator,
    legals: Seq[UserRegistryUser]
  ): ValidatedNel[ValidationError, Unit] = {

    val validation: Either[ValidationError, Unit] = for {
      reports          <- validateDocument(documentValidator)
      signatureTaxCode <- extractTaxCode(reports)
      validated <- Either.cond(
        legals.exists(legal => legal.externalId == signatureTaxCode),
        (),
        InvalidSignatureTaxCode
      )
    } yield validated

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[ValidationError]
    }
  }

  private def validateDocument(documentValidator: SignedDocumentValidator): Either[ValidationError, Reports] = {
    Try(documentValidator.validateDocument()) match {
      case Success(value) => Right(value)
      case Failure(ex)    => Left(DocumentValidationFail(ex.getMessage))
    }
  }

  private def extractTaxCode(reports: Reports): Either[ValidationError, String] = {
    for {
      prefixedTaxCode <- reports.getDiagnosticData.getUsedCertificates.asScala
        .flatMap(c => Option(c.getSubjectSerialNumber))
        .headOption
        .toRight(TaxCodeNotFoundInSignature)
      taxCode <- prefixedTaxCode match {
        case signatureRegex(_, taxCode) => Right(taxCode)
        case _                          => Left(InvalidSignatureTaxCodeFormat)
      }
    } yield taxCode
  }

  private def isValid(reports: Reports): Either[ValidationError, Unit] = {
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

object SignatureValidationService extends SignatureValidationService {
  def validateSignature(validations: ValidatedNel[ValidationError, Unit]*): Future[Unit] = {
    val result: Validated[NonEmptyList[ValidationError], Unit] = validations.reduce((v1, v2) => v1.combine(v2))

    result match {
      case Valid(unit) => Future.successful(unit)
      case Invalid(e)  => Future.failed(InvalidSignature(e.toList))
    }
  }
}
