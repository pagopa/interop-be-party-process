package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.partyprocess.model.ProductResourceProduct

import scala.concurrent.Future

trait ProductManagementService {
  def getProductById(productId: String)(implicit context: Seq[(String, String)]): Future[ProductResourceProduct]
}
