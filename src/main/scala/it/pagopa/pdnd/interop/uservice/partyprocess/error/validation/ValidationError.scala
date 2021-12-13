package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

trait ValidationError extends Throwable {
  def getErrorCode: String
}
