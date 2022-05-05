package it.pagopa.interop.partyprocess.service.impl

import cats.data.{Validated, ValidatedNel}
import eu.europa.esig.dss.validation.SignedDocumentValidator
import it.pagopa.interop.partyprocess.error.SignatureValidationError
import it.pagopa.interop.partyprocess.model.UserRegistryUser
import it.pagopa.interop.partyprocess.service.SignatureValidationService

case object PassthroughSignatureValidationService extends SignatureValidationService {

  private final val fakeValidationResult: ValidatedNel[SignatureValidationError, Unit] = Validated.validNel(())

  override def verifySignatureForm(
    documentValidator: SignedDocumentValidator
  ): ValidatedNel[SignatureValidationError, Unit] =
    fakeValidationResult

  override def isDocumentSigned(
    documentValidator: SignedDocumentValidator
  ): ValidatedNel[SignatureValidationError, Unit] =
    fakeValidationResult

  override def verifyDigest(
    documentValidator: SignedDocumentValidator,
    originalDigest: String
  ): ValidatedNel[SignatureValidationError, Unit] = fakeValidationResult

  override def verifySignature(
    documentValidator: SignedDocumentValidator
  ): ValidatedNel[SignatureValidationError, Unit] =
    fakeValidationResult

  override def verifyManagerTaxCode(
    documentValidator: SignedDocumentValidator,
    legals: Seq[UserRegistryUser]
  ): ValidatedNel[SignatureValidationError, Unit] = fakeValidationResult
}
