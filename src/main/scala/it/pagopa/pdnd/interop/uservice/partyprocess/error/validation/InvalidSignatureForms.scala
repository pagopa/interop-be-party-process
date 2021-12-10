package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

final case class InvalidSignatureForms(invalidSignatureForms: List[String]) extends ValidationError {

  override def getMessage: String = s"Invalid signatures forms detected: ${invalidSignatureForms.mkString(",")}"

  override def getErrorCode: String = "0002"

}
