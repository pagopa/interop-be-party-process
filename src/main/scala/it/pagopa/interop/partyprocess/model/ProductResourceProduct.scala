package it.pagopa.interop.partyprocess.model

import it.pagopa.product.client.model.ProductResource

final case class ProductResourceProduct(id: String, name: String)

object ProductResourceProduct {
  def fromProductResource(resource: ProductResource): ProductResourceProduct =
    ProductResourceProduct(id = resource.id, name = resource.title)
}
