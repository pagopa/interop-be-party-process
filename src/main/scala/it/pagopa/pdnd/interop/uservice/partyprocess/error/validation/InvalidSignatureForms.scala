package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

final case class InvalidSignatureForms(invalidSignatureForms: List[String]) extends ValidationError {

  override def getMessage: String =
    s"Only CAdES signature form is admitted. Invalid signatures forms detected: ${invalidSignatureForms.mkString(",")}"

  override def getErrorCode: String = "0103"

}
