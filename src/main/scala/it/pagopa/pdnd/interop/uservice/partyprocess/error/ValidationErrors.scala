package it.pagopa.pdnd.interop.uservice.partyprocess.error

object ValidationErrors {

  final case class DocumentValidationFail(errorMessage: String)
      extends SignatureValidationError("1000", s"Error trying to validate document, due: $errorMessage")

  final case class InvalidContractDigest(originalDigest: String, incomingDigest: String)
      extends SignatureValidationError(
        "1001",
        s"Original contract digest $originalDigest is not equal to $incomingDigest"
      )

  case object InvalidDocumentSignature extends SignatureValidationError("0102", s"Document signature is invalid")

  final case class InvalidSignatureForms(invalidSignatureForms: List[String])
      extends SignatureValidationError(
        "1003",
        s"Only CAdES signature form is admitted. Invalid signatures forms detected: ${invalidSignatureForms.mkString(",")}"
      )

  case object InvalidSignatureTaxCode
      extends SignatureValidationError(
        "1004",
        "The tax code related to signature does not match anyone contained in the relationships"
      )

  case object InvalidSignatureTaxCodeFormat
      extends SignatureValidationError("1005", "Invalid tax code format found in digital signature")

  case object TaxCodeNotFoundInSignature
      extends SignatureValidationError("0106", "No tax code has been found in digital signature")

  case object SignatureNotFound
      extends SignatureValidationError("1007", "No tax code has been found in digital signature")

}
