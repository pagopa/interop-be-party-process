package it.pagopa.pdnd.interop.uservice.partyprocess.error

import java.util.UUID

final case class RelationshipNotFoundInInstitution(institutionId: UUID, relationshipId: UUID)
    extends Throwable(s"Relationship ${relationshipId.toString} not found for Institution ${institutionId.toString}")
