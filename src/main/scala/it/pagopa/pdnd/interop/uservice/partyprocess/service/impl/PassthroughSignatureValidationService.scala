package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import cats.data.{Validated, ValidatedNel}
import eu.europa.esig.dss.validation.SignedDocumentValidator
import it.pagopa.pdnd.interop.uservice.partyprocess.error.validation.ValidationError
import it.pagopa.pdnd.interop.uservice.partyprocess.service.SignatureValidationService
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{User => UserRegistryUser}

case object PassthroughSignatureValidationService extends SignatureValidationService {

  private final val fakeValidationResult: ValidatedNel[ValidationError, Unit] = Validated.validNel(())

  override def verifySignatureForm(documentValidator: SignedDocumentValidator): ValidatedNel[ValidationError, Unit] =
    fakeValidationResult

  override def isDocumentSigned(documentValidator: SignedDocumentValidator): ValidatedNel[ValidationError, Unit] =
    fakeValidationResult

  override def verifyDigest(
    documentValidator: SignedDocumentValidator,
    originalDigest: String
  ): ValidatedNel[ValidationError, Unit] = fakeValidationResult

  override def verifySignature(documentValidator: SignedDocumentValidator): ValidatedNel[ValidationError, Unit] =
    fakeValidationResult

  override def verifyManagerTaxCode(
    documentValidator: SignedDocumentValidator,
    legals: Seq[UserRegistryUser]
  ): ValidatedNel[ValidationError, Unit] = fakeValidationResult
}
