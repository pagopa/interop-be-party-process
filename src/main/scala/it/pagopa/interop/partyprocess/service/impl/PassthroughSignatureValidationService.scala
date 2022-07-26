package it.pagopa.interop.partyprocess.service.impl

import cats.data.{Validated, ValidatedNel}
import eu.europa.esig.dss.detailedreport.jaxb.XmlDetailedReport
import eu.europa.esig.dss.diagnostic.jaxb.XmlDiagnosticData
import eu.europa.esig.dss.simplereport.jaxb.XmlSimpleReport
import eu.europa.esig.dss.validation.SignedDocumentValidator
import eu.europa.esig.dss.validation.reports.Reports
import eu.europa.esig.validationreport.jaxb.ValidationReportType
import it.pagopa.interop.partyprocess.error.SignatureValidationError
import it.pagopa.interop.partyprocess.service.SignatureValidationService
import it.pagopa.interop.partyprocess.model.UserRegistryUser

import scala.concurrent.{ExecutionContext, Future}

case object PassthroughSignatureValidationService extends SignatureValidationService {

  private final val fakeValidationResult: ValidatedNel[SignatureValidationError, Unit] = Validated.validNel(())

  override def isDocumentSigned(
    documentValidator: SignedDocumentValidator
  ): ValidatedNel[SignatureValidationError, Unit] =
    fakeValidationResult

  override def verifyDigest(
    documentValidator: SignedDocumentValidator,
    originalDigest: String
  ): ValidatedNel[SignatureValidationError, Unit] = fakeValidationResult

  override def verifyOriginalDocument(
    documentValidator: SignedDocumentValidator
  ): ValidatedNel[SignatureValidationError, Unit] = fakeValidationResult

  override def verifySignature(reports: Reports): ValidatedNel[SignatureValidationError, Unit] =
    fakeValidationResult

  override def verifyManagerTaxCode(
    reports: Reports,
    legals: Seq[UserRegistryUser]
  ): ValidatedNel[SignatureValidationError, Unit] = fakeValidationResult

  override def validateDocument(
    documentValidator: SignedDocumentValidator
  )(implicit ec: ExecutionContext): Future[Reports] = {
    val reports: Reports =
      new Reports(new XmlDiagnosticData(), new XmlDetailedReport(), new XmlSimpleReport(), new ValidationReportType())

    Future.successful(reports)
  }

  override def verifySignatureForm(
    documentValidator: SignedDocumentValidator
  ): ValidatedNel[SignatureValidationError, Unit] = fakeValidationResult
}
