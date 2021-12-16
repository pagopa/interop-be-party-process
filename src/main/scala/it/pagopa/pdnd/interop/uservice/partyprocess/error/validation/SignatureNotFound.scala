package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

case object SignatureNotFound extends ValidationError {

  override def getMessage: String = "No tax code has been found in digital signature"

  override def getErrorCode: String = "0107"

}
