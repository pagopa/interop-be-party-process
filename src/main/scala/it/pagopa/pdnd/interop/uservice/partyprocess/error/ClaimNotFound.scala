package it.pagopa.pdnd.interop.uservice.partyprocess.error

final case class ClaimNotFound(claim: String) extends Throwable(s"Claim $claim not found")
