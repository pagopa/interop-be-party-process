package it.pagopa.pdnd.interop.uservice.partyprocess.error

final case class UidValidationError(message: String) extends Throwable(s"Error while uid validation: $message")
