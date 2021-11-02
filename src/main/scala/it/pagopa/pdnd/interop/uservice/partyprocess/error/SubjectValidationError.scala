package it.pagopa.pdnd.interop.uservice.partyprocess.error

final case class SubjectValidationError(message: String) extends Throwable(s"Error while subject validation: $message")
