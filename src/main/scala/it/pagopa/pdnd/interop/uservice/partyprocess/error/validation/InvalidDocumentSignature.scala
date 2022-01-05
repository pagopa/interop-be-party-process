package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

case object InvalidDocumentSignature extends SignatureValidationError("0102", s"Document signature is invalid")
