package it.pagopa.pdnd.interop.uservice.partyprocess.error

final case class InstitutionNotOnboarded(institutionId: String, productId: String)
    extends Throwable(s"InstitutionId $institutionId is not onboarded for product $productId")
