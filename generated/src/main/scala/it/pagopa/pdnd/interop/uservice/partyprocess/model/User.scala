package it.pagopa.pdnd.interop.uservice.partyprocess.model


/**
 * @param name  for example: ''null''
 * @param surname  for example: ''null''
 * @param taxCode  for example: ''null''
 * @param role  for example: ''null''
 * @param email  for example: ''null''
 * @param product  for example: ''null''
 * @param productRole  for example: ''null''
*/
final case class User (
  name: String,
  surname: String,
  taxCode: String,
  role: String,
  email: Option[String],
  product: Option[String],
  productRole: String
)

