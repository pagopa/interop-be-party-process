package it.pagopa.interop.partyprocess.service

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.implicits._
import eu.europa.esig.dss.validation.SignedDocumentValidator
import it.pagopa.interop.partyprocess.error.PartyProcessErrors.InvalidSignature
import it.pagopa.interop.partyprocess.error.SignatureValidationError
import it.pagopa.interop.partyprocess.model.UserRegistryUser

import scala.concurrent.Future

trait SignatureValidationService {

  def verifySignatureForm(documentValidator: SignedDocumentValidator): ValidatedNel[SignatureValidationError, Unit]

  def isDocumentSigned(documentValidator: SignedDocumentValidator): ValidatedNel[SignatureValidationError, Unit]

  def verifyDigest(
    documentValidator: SignedDocumentValidator,
    originalDigest: String
  ): ValidatedNel[SignatureValidationError, Unit]

  def verifySignature(documentValidator: SignedDocumentValidator): ValidatedNel[SignatureValidationError, Unit]

  def verifyManagerTaxCode(
    documentValidator: SignedDocumentValidator,
    legals: Seq[UserRegistryUser]
  ): ValidatedNel[SignatureValidationError, Unit]

}

object SignatureValidationService {
  def validateSignature(validations: ValidatedNel[SignatureValidationError, Unit]*): Future[Unit] = {
    val result: Validated[NonEmptyList[SignatureValidationError], Unit] = validations.reduce((v1, v2) => v1.combine(v2))

    result match {
      case Valid(unit) => Future.successful(unit)
      case Invalid(e)  => Future.failed(InvalidSignature(e.toList))
    }
  }
}
