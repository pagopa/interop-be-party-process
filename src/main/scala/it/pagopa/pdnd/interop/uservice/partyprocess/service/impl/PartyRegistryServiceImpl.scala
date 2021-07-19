package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.partyprocess.service.{PartyProxyInvoker, PartyRegistryService}
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.api.InstitutionApi
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.invoker.ApiRequest
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.{Categories, Institution, Institutions}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.StringPlusAny",
    "org.wartremover.warts.ImplicitParameter",
    "org.wartremover.warts.ToString"
  )
)
final case class PartyRegistryServiceImpl(invoker: PartyProxyInvoker, api: InstitutionApi)(implicit
  ec: ExecutionContext
) extends PartyRegistryService {

  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def getInstitution(institutionId: String): Future[Institution] = {
    val request: ApiRequest[Institution] = api.getInstitutionById(institutionId)
    logger.info(s"getInstitution ${request.toString}")
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Retrieving institution ${x.code}")
        logger.info(s"Retrieving institution ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving institution ${ex.getMessage}")
        Future.failed[Institution](ex)
      }
  }

  override def getCategories: Future[Categories] = {
    val request: ApiRequest[Categories] = api.getCategories()
    logger.info(s"getCategories ${request.toString}")
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Retrieving categories ${x.code}")
        logger.info(s"Retrieving categories ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving categories ${ex.getMessage}")
        Future.failed[Categories](ex)
      }
  }

  override def searchInstitution(text: String, offset: Int, limit: Int): Future[List[Institution]] = {
    val request: ApiRequest[Institutions] = api.searchInstitution(text, offset, limit)
    invoker.execute(request).map(_.content.items.toList)
  }

}
