package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.partyprocess.service.{PartyProxyInvoker, PartyRegistryService}
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.api.{CategoryApi, InstitutionApi}
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.invoker.ApiRequest
import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.{Category, Institution, Institutions}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

final case class PartyRegistryServiceImpl(
  invoker: PartyProxyInvoker,
  institutionApi: InstitutionApi,
  categoryApi: CategoryApi
)(implicit ec: ExecutionContext)
    extends PartyRegistryService {

  implicit val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def getInstitution(institutionId: String)(bearerToken: String): Future[Institution] = {
    val request: ApiRequest[Institution] = institutionApi.getInstitutionById(institutionId)
    logger.info(s"getInstitution ${request.toString}")
    invoker.invoke(request, "Retrieving institution")
  }
  override def getCategory(origin: String, code: String)(bearerToken: String): Future[Category] = {
    val request: ApiRequest[Category] = categoryApi.getCategory(origin = origin, code = code)
    logger.info(s"getCategory ${request.toString}")
    invoker.invoke(request, "Retrieving category")
  }

  override def searchInstitution(text: String, offset: Int, limit: Int)(
    bearerToken: String
  ): Future[List[Institution]] = {
    val request: ApiRequest[Institutions] = institutionApi.searchInstitution(text, offset, limit)
    invoker.execute(request).map(_.content.items.toList)
  }

}
