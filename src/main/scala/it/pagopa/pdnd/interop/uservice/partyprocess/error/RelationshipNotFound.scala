package it.pagopa.pdnd.interop.uservice.partyprocess.error

final case class RelationshipNotFound(institutionId: String, taxCode: String, role: String)
    extends Throwable(s"Relationship not found for Institution $institutionId Tax Code $taxCode Role $role ")
