package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.partyprocess.service.{PartyProcessService, PartyProxyInvoker}
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.api.InstitutionApi
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.invoker.ApiRequest
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.{Institution, Institutions}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

final case class PartyProcessServiceImpl(invoker: PartyProxyInvoker, api: InstitutionApi)(implicit ec: ExecutionContext)
    extends PartyProcessService {

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
        Future.failed(ex)
      }
  }

  override def searchInstitution(text: String, offset: Int, limit: Int): Future[List[Institution]] = {
    val request: ApiRequest[Institutions] = api.searchInstitution(text, offset, limit)
    invoker.execute(request).map(_.content.items.toList)
  }

}
