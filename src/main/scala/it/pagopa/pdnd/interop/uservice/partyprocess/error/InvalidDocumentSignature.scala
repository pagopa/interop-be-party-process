package it.pagopa.pdnd.interop.uservice.partyprocess.error

case object InvalidDocumentSignature extends Throwable(s"Document signature is invalid")
