package it.pagopa.pdnd.interop.uservice.partyprocess.model

import java.util.UUID

/** @param id  for example: ''null''
  * @param from  for example: ''null''
  * @param role represents the generic available role types for the relationship for example: ''null''
  * @param platformRole user role in the application context (e.g.: administrator, security user). This MUST belong to the configured set of application specific platform roles for example: ''null''
  * @param status  for example: ''null''
  */
final case class RelationshipInfo(id: UUID, from: UUID, role: String, platformRole: String, status: String)
