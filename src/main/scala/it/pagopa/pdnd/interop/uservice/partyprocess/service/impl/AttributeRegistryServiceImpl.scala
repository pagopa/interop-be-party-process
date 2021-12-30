package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.api.AttributeApi
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.invoker.{ApiRequest, BearerToken}
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.model.{
  Attribute,
  AttributeSeed,
  AttributesResponse
}
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{AttributeRegistryInvoker, AttributeRegistryService}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.concurrent.Future

final case class AttributeRegistryServiceImpl(attributeRegistryInvoker: AttributeRegistryInvoker, api: AttributeApi)
    extends AttributeRegistryService {

  implicit val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def createAttribute(origin: String, attribute: String, name: String, description: String)(
    bearerToken: String
  ): Future[AttributesResponse] = {

    val seed: AttributeSeed = AttributeSeed(
      code = Some(attribute),
      certified = true,
      description = description,
      origin = Some(origin),
      name = name
    )
    val seeds: Seq[AttributeSeed]               = Seq(seed)
    val request: ApiRequest[AttributesResponse] = api.createAttributes(seeds)(BearerToken(bearerToken))
    attributeRegistryInvoker.invoke(request, "Retrieve attributes")
  }

  def getAttribute(id: UUID)(bearerToken: String): Future[Attribute] = {
    val request: ApiRequest[Attribute] = api.getAttributeById(id)(BearerToken(bearerToken))
    attributeRegistryInvoker.invoke(request, "Retrieve attribute")
  }

}
