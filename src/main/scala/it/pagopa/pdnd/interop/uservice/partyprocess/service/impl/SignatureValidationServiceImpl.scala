package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import cats.data.ValidatedNel
import cats.implicits._
import eu.europa.esig.dss.enumerations.{DigestAlgorithm, Indication, SignatureForm}
import eu.europa.esig.dss.model.DSSDocument
import eu.europa.esig.dss.validation.reports.Reports
import eu.europa.esig.dss.validation.{AdvancedSignature, SignedDocumentValidator}
import it.pagopa.pdnd.interop.uservice.partyprocess.error.SignatureValidationError
import it.pagopa.pdnd.interop.uservice.partyprocess.error.ValidationErrors._
import it.pagopa.pdnd.interop.uservice.partyprocess.service.SignatureValidationService
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{User => UserRegistryUser}

import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

case object SignatureValidationServiceImpl extends SignatureValidationService {

  private final val signatureRegex: Regex = "(TINIT-)(.*)".r

  def verifySignatureForm(documentValidator: SignedDocumentValidator): ValidatedNel[SignatureValidationError, Unit] = {
    val signatures: List[AdvancedSignature] = documentValidator.getSignatures.asScala.toList
    val invalidSignatureForms: List[SignatureForm] =
      signatures.map(_.getSignatureForm).filter(_ != SignatureForm.CAdES)
    val validation: Either[InvalidSignatureForms, Unit] =
      Either.cond(invalidSignatureForms.isEmpty, (), InvalidSignatureForms(invalidSignatureForms.map(_.toString)))

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }
  }

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

    val sign: AdvancedSignature       = documentValidator.getSignatures.get(0)
    val incomingOriginal: DSSDocument = documentValidator.getOriginalDocuments(sign.getId).get(0)
    val incomingDigest: String        = incomingOriginal.getDigest(DigestAlgorithm.SHA256)

    val validation: Either[InvalidContractDigest, Unit] =
      Either.cond(originalDigest == incomingDigest, (), InvalidContractDigest(originalDigest, incomingDigest))

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }

  }

  def verifySignature(documentValidator: SignedDocumentValidator): ValidatedNel[SignatureValidationError, Unit] = {
    val validation: Either[SignatureValidationError, Unit] = {
      for {
        reports   <- validateDocument(documentValidator)
        validated <- isValid(reports)
      } yield validated
    }

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
      validated <- Either.cond(
        legals.exists(legal => legal.externalId == signatureTaxCode),
        (),
        InvalidSignatureTaxCode
      )
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
      taxCode <- prefixedTaxCode match {
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
