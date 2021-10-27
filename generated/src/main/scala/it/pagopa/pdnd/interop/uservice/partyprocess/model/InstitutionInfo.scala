package it.pagopa.pdnd.interop.uservice.partyprocess.model

/** @param institutionId  for example: ''null''
  * @param description  for example: ''null''
  * @param digitalAddress  for example: ''null''
  * @param status  for example: ''null''
  * @param role  for example: ''null''
  * @param platformRole  for example: ''null''
  * @param attributes certified attributes bound to this institution for example: ''null''
  */
final case class InstitutionInfo(
  institutionId: String,
  description: String,
  digitalAddress: String,
  status: String,
  role: String,
  platformRole: String,
  attributes: Seq[String]
)
