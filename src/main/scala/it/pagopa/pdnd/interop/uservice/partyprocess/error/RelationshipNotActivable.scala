package it.pagopa.pdnd.interop.uservice.partyprocess.error

final case class RelationshipNotActivable(relationshipId: String, status: String)
    extends Throwable(s"Relationship $relationshipId is in status $status and cannot be activated")
