package it.pagopa.pdnd.interop.uservice.partyprocess.error

final case class MultipleProductsRequestError(products: Seq[String])
    extends Throwable(s"Multi products request is forbidden: Products in request: ${products.mkString(",")} ")
