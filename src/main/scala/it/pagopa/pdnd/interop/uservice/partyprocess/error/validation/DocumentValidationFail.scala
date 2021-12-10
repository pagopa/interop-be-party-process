package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

final case class DocumentValidationFail(errorMessage: String) extends ValidationError {
  override def getMessage: String = s"Error trying to validate document, due: $errorMessage"

  override def getErrorCode: String = "0007"
}
