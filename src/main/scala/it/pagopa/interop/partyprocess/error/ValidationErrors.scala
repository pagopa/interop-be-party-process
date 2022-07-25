package it.pagopa.interop.partyprocess.error

object ValidationErrors {

  final case class DocumentValidationFail(errorMessage: String)
      extends SignatureValidationError("1000", s"Error trying to validate document, due: $errorMessage")

  case object InvalidContractDigest extends SignatureValidationError("1001", s"Invalid file digest")

  case object InvalidDocumentSignature extends SignatureValidationError("1002", s"Document signature is invalid")

  case object InvalidSignatureTaxCode
      extends SignatureValidationError(
        "1004",
        "The tax code related to signature does not match anyone contained in the relationships"
      )

  case object InvalidSignatureTaxCodeFormat
      extends SignatureValidationError("1005", "Invalid tax code format found in digital signature")

  case object TaxCodeNotFoundInSignature
      extends SignatureValidationError("1006", "No tax code has been found in digital signature")

  case object SignatureNotFound extends SignatureValidationError("1007", "No signature found")

  case object OriginalDocumentNotFound
      extends SignatureValidationError("1008", "Original document information not found")

}
