package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

case object SignatureNotFound
    extends SignatureValidationError("0107", "No tax code has been found in digital signature")
