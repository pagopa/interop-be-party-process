package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

case object InvalidDocumentSignature extends ValidationError {

  override def getErrorCode: String = "0006"

  override def getMessage: String = (s"Document signature is invalid")
}
