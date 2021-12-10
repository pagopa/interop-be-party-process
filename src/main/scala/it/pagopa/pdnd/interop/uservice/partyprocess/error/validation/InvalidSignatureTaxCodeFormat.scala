package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

case object InvalidSignatureTaxCodeFormat extends ValidationError {

  override def getMessage: String = "Invalid tax code format found in digital signature"

  override def getErrorCode: String = "0105"

}
