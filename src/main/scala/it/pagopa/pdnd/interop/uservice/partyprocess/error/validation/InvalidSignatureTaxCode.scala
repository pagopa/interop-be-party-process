package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

case object InvalidSignatureTaxCode extends ValidationError {

  override def getMessage: String =
    "The tax code related to signature does not match anyone contained in the relationships"

  override def getErrorCode: String = "0003"

}
