package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

final case class InvalidSignature(validationErrors: List[ValidationError]) extends Throwable
