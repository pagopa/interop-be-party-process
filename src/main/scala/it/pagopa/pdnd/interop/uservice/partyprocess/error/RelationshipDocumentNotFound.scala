package it.pagopa.pdnd.interop.uservice.partyprocess.error

final case class RelationshipDocumentNotFound(relationshipId: String)
    extends Throwable(s"Relationship document not found for relationship $relationshipId")
