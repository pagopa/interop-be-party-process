package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.model.{Attribute, AttributesResponse}

import java.util.UUID
import scala.concurrent.Future

trait AttributeRegistryService {
  def createAttribute(origin: String, attribute: String, name: String, description: String)(
    bearerToken: String
  ): Future[AttributesResponse]
  def getAttribute(id: UUID)(bearerToken: String): Future[Attribute]
}
