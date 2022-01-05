package it.pagopa.pdnd.interop.uservice.partyprocess.service

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.implicits._
import eu.europa.esig.dss.validation.SignedDocumentValidator
import it.pagopa.pdnd.interop.uservice.partyprocess.error.PartyProcessErrors.InvalidSignature
import it.pagopa.pdnd.interop.uservice.partyprocess.error.validation._
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{User => UserRegistryUser}

import scala.concurrent.Future

trait SignatureValidationService {

  def verifySignatureForm(documentValidator: SignedDocumentValidator): ValidatedNel[ValidationError, Unit]

  def isDocumentSigned(documentValidator: SignedDocumentValidator): ValidatedNel[ValidationError, Unit]

  def verifyDigest(
    documentValidator: SignedDocumentValidator,
    originalDigest: String
  ): ValidatedNel[ValidationError, Unit]

  def verifySignature(documentValidator: SignedDocumentValidator): ValidatedNel[ValidationError, Unit]

  def verifyManagerTaxCode(
    documentValidator: SignedDocumentValidator,
    legals: Seq[UserRegistryUser]
  ): ValidatedNel[ValidationError, Unit]

}

object SignatureValidationService {
  def validateSignature(validations: ValidatedNel[ValidationError, Unit]*): Future[Unit] = {
    val result: Validated[NonEmptyList[ValidationError], Unit] = validations.reduce((v1, v2) => v1.combine(v2))

    result match {
      case Valid(unit) => Future.successful(unit)
      case Invalid(e)  => Future.failed(InvalidSignature(e.toList))
    }
  }
}
