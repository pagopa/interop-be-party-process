package it.pagopa.pdnd.interop.uservice.partyprocess.error

import it.pagopa.pdnd.interop.uservice.partyprocess.error.validation.ValidationError

final case class InvalidSignature(validationErrors: List[ValidationError]) extends Throwable
