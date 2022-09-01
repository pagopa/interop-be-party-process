package it.pagopa.interop.partyprocess.service.impl

import cats.data.ValidatedNel
import cats.implicits._
import eu.europa.esig.dss.enumerations.{DigestAlgorithm, Indication, SignatureForm}
import eu.europa.esig.dss.model.DSSDocument
import eu.europa.esig.dss.validation.reports.Reports
import eu.europa.esig.dss.validation.{AdvancedSignature, SignedDocumentValidator}
import eu.europa.esig.validationreport.jaxb.SignatureValidationReportType
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

  def verifyOriginalDocument(
    documentValidator: SignedDocumentValidator
  ): ValidatedNel[SignatureValidationError, Unit] = {

    val signs: List[AdvancedSignature] = documentValidator.getSignatures.asScala.toList

    val existOriginalDocuments: Boolean = signs.foldLeft(true) { (res, sign) =>
      val original: List[DSSDocument] = documentValidator.getOriginalDocuments(sign.getId).asScala.toList
      res && original.nonEmpty
    }

    val validation = Either.cond(existOriginalDocuments, (), OriginalDocumentNotFound)

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
      signatureTaxCodes <- extractTaxCodes(reports)
      validated         <- Either.cond(isSignedByLegals(legals, signatureTaxCodes), (), InvalidSignatureTaxCode)
    } yield validated

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }
  }

  private def isSignedByLegals(legals: Seq[UserRegistryUser], signatureTaxCodes: List[String]): Boolean = {
    val legalsTaxCodes: Seq[String] = legals.map(_.taxCode.getOrElse(""))

    signatureTaxCodes.nonEmpty &&
    legalsTaxCodes.nonEmpty &&
    legalsTaxCodes.forall(signatureTaxCodes.contains)

  }

  def validateDocument(documentValidator: SignedDocumentValidator)(implicit ec: ExecutionContext): Future[Reports] =
    Future(documentValidator.validateDocument()).recoverWith { case ex =>
      Future.failed(DocumentValidationFail(ex.getMessage))
    }

  private def extractTaxCodes(reports: Reports): Either[SignatureValidationError, List[String]] = {

    def extractTaxCodeFromSubjectSN(subjectSerialNumber: String): Either[SignatureValidationError, String] = {
      subjectSerialNumber match {
        case signatureRegex(_, taxCode) => Right(taxCode)
        case _                          => Left(InvalidSignatureTaxCodeFormat)
      }
    }

    val subjectSerialNumbers: Either[SignatureValidationError, List[String]] = {
      val subjectSNs: List[String] =
        reports.getDiagnosticData.getUsedCertificates.asScala.toList.flatMap(c => Option(c.getSubjectSerialNumber))
      if (subjectSNs.nonEmpty) Right(subjectSNs) else Left(TaxCodeNotFoundInSignature)
    }

    for {
      subjectSNs <- subjectSerialNumbers
      taxCodes   <- subjectSNs.traverse(extractTaxCodeFromSubjectSN)
    } yield taxCodes

  }

  def verifySignatureForm(documentValidator: SignedDocumentValidator): ValidatedNel[SignatureValidationError, Unit] = {
    val signatures: List[AdvancedSignature]                = documentValidator.getSignatures.asScala.toList
    val invalidSignatureForms: List[SignatureForm]         =
      signatures.map(_.getSignatureForm).filter(_ != SignatureForm.CAdES)
    val validation: Either[SignatureValidationError, Unit] =
      Either.cond(invalidSignatureForms.isEmpty, (), InvalidSignatureForms(invalidSignatureForms.map(_.toString)))

    validation match {
      case Left(throwable)  => throwable.invalidNel[Unit]
      case Right(validated) => validated.validNel[SignatureValidationError]
    }
  }

  private def isValid(reports: Reports): Either[SignatureValidationError, Unit] = {
    val signatureValidationReportTypes: List[SignatureValidationReportType] =
      reports.getEtsiValidationReportJaxb.getSignatureValidationReport.asScala.toList

    Either
      .cond(
        signatureValidationReportTypes.nonEmpty && signatureValidationReportTypes.forall(
          _.getSignatureValidationStatus.getMainIndication == Indication.TOTAL_PASSED
        ),
        (),
        InvalidDocumentSignature
      )
  }

}
