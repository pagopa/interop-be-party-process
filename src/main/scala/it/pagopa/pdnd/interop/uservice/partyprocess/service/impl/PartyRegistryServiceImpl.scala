package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.partyprocess.service.{PartyProxyInvoker, PartyRegistryService}
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.api.InstitutionApi
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.invoker.ApiRequest
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.{Categories, Institution, Institutions}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

final case class PartyRegistryServiceImpl(invoker: PartyProxyInvoker, api: InstitutionApi)(implicit
  ec: ExecutionContext
) extends PartyRegistryService {

  implicit val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def getInstitution(institutionId: String)(bearerToken: String): Future[Institution] = {
    val request: ApiRequest[Institution] = api.getInstitutionById(institutionId)
    logger.info(s"getInstitution ${request.toString}")
    invoker.invoke(request, "Retrieving institution")
  }

  override def getCategories(bearerToken: String): Future[Categories] = {
    val request: ApiRequest[Categories] = api.getCategories()
    logger.info(s"getCategories ${request.toString}")
    invoker.invoke(request, "Retrieving categories")
  }

  override def searchInstitution(text: String, offset: Int, limit: Int)(
    bearerToken: String
  ): Future[List[Institution]] = {
    val request: ApiRequest[Institutions] = api.searchInstitution(text, offset, limit)
    invoker.execute(request).map(_.content.items.toList)
  }

}
