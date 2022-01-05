package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

case object TaxCodeNotFoundInSignature
    extends SignatureValidationError("0106", "No tax code has been found in digital signature")
