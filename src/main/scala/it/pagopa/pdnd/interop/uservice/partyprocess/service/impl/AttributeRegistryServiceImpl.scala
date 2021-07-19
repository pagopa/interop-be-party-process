package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.api.AttributeApi
import it.pagopa.pdnd.interop.uservice.attributeregistrymanagement.client.model.{AttributeSeed, AttributesResponse}
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{AttributeRegistryInvoker, AttributeRegistryService}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.StringPlusAny",
    "org.wartremover.warts.ImplicitParameter",
    "org.wartremover.warts.ToString"
  )
)
final case class AttributeRegistryServiceImpl(attributeRegistryInvoker: AttributeRegistryInvoker, api: AttributeApi)(
  implicit ec: ExecutionContext
) extends AttributeRegistryService {

  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def createAttribute(
    origin: String,
    description: String,
    attribute: Option[String]
  ): Future[AttributesResponse] =
    attribute match {
      case Some(attr) => sendRequest(origin, description, attr)
      case None =>
        Future.failed(new RuntimeException("No attribute has been passed"))

    }

  private def sendRequest(origin: String, description: String, attribute: String): Future[AttributesResponse] = {
    val request =
      api.createAttributes(
        Seq(
          AttributeSeed(
            code = None,
            certified = true,
            description = description,
            origin = Some(origin),
            name = attribute
          )
        )
      )
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
}
