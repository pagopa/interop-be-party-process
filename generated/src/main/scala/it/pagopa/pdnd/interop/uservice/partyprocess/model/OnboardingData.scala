package it.pagopa.pdnd.interop.uservice.partyprocess.model


/**
 * @param institutionId  for example: ''null''
 * @param description  for example: ''null''
 * @param digitalAddress  for example: ''null''
 * @param status  for example: ''null''
 * @param role  for example: ''null''
 * @param product  for example: ''null''
 * @param productRole  for example: ''null''
 * @param institutionProducts set of products bound to this institution for example: ''null''
 * @param attributes certified attributes bound to this institution for example: ''null''
*/
final case class OnboardingData (
  institutionId: String,
  description: String,
  digitalAddress: String,
  status: String,
  role: String,
  product: Option[String],
  productRole: String,
  institutionProducts: Seq[String],
  attributes: Seq[String]
)

