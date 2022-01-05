package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

final case class InvalidSignatureForms(invalidSignatureForms: List[String])
    extends SignatureValidationError(
      "0103",
      s"Only CAdES signature form is admitted. Invalid signatures forms detected: ${invalidSignatureForms.mkString(",")}"
    )
