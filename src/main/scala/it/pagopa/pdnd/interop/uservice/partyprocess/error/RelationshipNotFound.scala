package it.pagopa.pdnd.interop.uservice.partyprocess.error

import java.util.UUID

final case class RelationshipNotFound(institutionId: UUID, userId: UUID, role: String)
    extends Throwable(
      s"Relationship not found for Institution ${institutionId.toString} User ${userId.toString} Role $role "
    )
