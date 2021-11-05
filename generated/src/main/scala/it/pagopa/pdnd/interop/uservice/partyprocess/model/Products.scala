package it.pagopa.pdnd.interop.uservice.partyprocess.model


/**
 * @param products set of products to define for this institution for example: ''null''
*/
final case class Products (
  products: Set[String]
)

