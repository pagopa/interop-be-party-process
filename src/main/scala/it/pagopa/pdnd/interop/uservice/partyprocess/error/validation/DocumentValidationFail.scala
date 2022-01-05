package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

final case class DocumentValidationFail(errorMessage: String)
    extends SignatureValidationError("0100", s"Error trying to validate document, due: $errorMessage")
