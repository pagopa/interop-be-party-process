package it.pagopa.pdnd.interop.uservice.partyprocess.error

final case class ContractNotFound(institutionId: String) extends Throwable(s"Contract not found for $institutionId")
