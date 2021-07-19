package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.model.AttributesResponse

import scala.concurrent.Future

trait AttributeRegistryService {
  def createAttribute(origin: String, description: String, attribute: Option[String]): Future[AttributesResponse]
}
