package it.pagopa.interop.partyprocess.service.impl

import it.pagopa.interop.partyprocess.service.{PartyProxyInvoker, PartyRegistryService}
import it.pagopa.interop.partyregistryproxy.client.api.{CategoryApi, InstitutionApi}
import it.pagopa.interop.partyregistryproxy.client.invoker.ApiRequest
import it.pagopa.interop.partyregistryproxy.client.model.{Category, Institution, Institutions}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

final case class PartyRegistryServiceImpl(
  invoker: PartyProxyInvoker,
  institutionApi: InstitutionApi,
  categoryApi: CategoryApi
)(implicit ec: ExecutionContext)
    extends PartyRegistryService {

  implicit val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def getInstitution(institutionId: String)(bearerToken: String): Future[Institution]  = {
    val request: ApiRequest[Institution] = institutionApi.getInstitutionById(institutionId)
    invoker.invoke(request, s"Retrieving institution $institutionId")
  }
  override def getCategory(origin: String, code: String)(bearerToken: String): Future[Category] = {
    val request: ApiRequest[Category] = categoryApi.getCategory(origin = origin, code = code)
    invoker.invoke(request, s"Retrieving category $code for origin $origin")
  }

  override def searchInstitution(text: String, offset: Int, limit: Int)(
    bearerToken: String
  ): Future[List[Institution]] = {
    val request: ApiRequest[Institutions] = institutionApi.searchInstitution(text, offset, limit)
    invoker
      .invoke(request, s"Searching institution from text $text, offset $offset, limit $limit")
      .map(_.items.toList)
  }

}
