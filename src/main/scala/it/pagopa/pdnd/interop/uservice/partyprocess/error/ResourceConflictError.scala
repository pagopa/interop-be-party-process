package it.pagopa.pdnd.interop.uservice.partyprocess.error

case object ResourceConflictError extends Throwable(s"Resource already exists")
