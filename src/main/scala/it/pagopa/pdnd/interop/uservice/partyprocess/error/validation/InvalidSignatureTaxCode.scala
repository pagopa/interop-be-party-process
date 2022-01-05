package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

case object InvalidSignatureTaxCode
    extends SignatureValidationError(
      "0104",
      "The tax code related to signature does not match anyone contained in the relationships"
    )
