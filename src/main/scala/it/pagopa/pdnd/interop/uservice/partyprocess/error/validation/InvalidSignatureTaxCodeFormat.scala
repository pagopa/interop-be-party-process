package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

case object InvalidSignatureTaxCodeFormat
    extends SignatureValidationError("0105", "Invalid tax code format found in digital signature")
