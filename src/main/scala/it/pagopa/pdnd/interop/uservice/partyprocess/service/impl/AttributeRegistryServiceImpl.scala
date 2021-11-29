package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.api.AttributeApi
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.invoker.{ApiRequest, ApiResponse, BearerToken}
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.model.{
  Attribute,
  AttributeSeed,
  AttributesResponse
}
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{AttributeRegistryInvoker, AttributeRegistryService}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

final case class AttributeRegistryServiceImpl(attributeRegistryInvoker: AttributeRegistryInvoker, api: AttributeApi)(
  implicit ec: ExecutionContext
) extends AttributeRegistryService {

  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def createAttribute(origin: String, description: String, attribute: String)(
    bearerToken: String
  ): Future[AttributesResponse] = {

    val seed: AttributeSeed = AttributeSeed(
      code = Some(attribute),
      certified = true,
      description = description,
      origin = Some(origin),
      name = description
    )
    val seeds: Seq[AttributeSeed] = Seq(seed)

    val request: ApiRequest[AttributesResponse] = api.createAttributes(seeds)(BearerToken(bearerToken))

    attributeRegistryInvoker
      .execute(request)
      .map { x =>
        logger.info(s"Retrieving attributes ${x.code}")
        logger.info(s"Retrieving attributes ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving attributes ${ex.getMessage}")
        Future.failed[AttributesResponse](ex)
      }
  }

  def getAttribute(id: UUID)(bearerToken: String): Future[Attribute] = {

    val request: ApiRequest[Attribute]         = api.getAttributeById(id)(BearerToken(bearerToken))
    val result: Future[ApiResponse[Attribute]] = attributeRegistryInvoker.execute(request)

    result
      .map { x =>
        logger.info(s"Retrieving attribute ${x.code}")
        logger.info(s"Retrieving attribute ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving attribute ${ex.getMessage}")
        Future.failed[Attribute](ex)
      }
  }

}
