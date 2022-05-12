package it.pagopa.interop.partyprocess.service.impl

import it.pagopa.interop.partyprocess.service.{PartyProxyInvoker, PartyRegistryService}
import it.pagopa.interop.partyregistryproxy.client.api.{CategoryApi, InstitutionApi}
import it.pagopa.interop.partyregistryproxy.client.invoker.{ApiRequest, BearerToken}
import it.pagopa.interop.partyregistryproxy.client.model.{Category, Institution, Institutions}
import com.typesafe.scalalogging.Logger
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}

import scala.concurrent.{ExecutionContext, Future}

final case class PartyRegistryServiceImpl(
  invoker: PartyProxyInvoker,
  institutionApi: InstitutionApi,
  categoryApi: CategoryApi
)(implicit ec: ExecutionContext)
    extends PartyRegistryService {

  implicit val logger = Logger.takingImplicit[ContextFieldsToLog](this.getClass())

  override def getInstitution(
    externalId: String
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Institution] = {
    val request: ApiRequest[Institution] = institutionApi.getInstitutionById(externalId)(BearerToken(bearerToken))
    invoker.invoke(request, s"Retrieving institution having external $externalId")
  }
  override def getCategory(origin: String, code: String)(
    bearerToken: String
  )(implicit contexts: Seq[(String, String)]): Future[Category] = {
    val request: ApiRequest[Category] = categoryApi.getCategory(origin = origin, code = code)(BearerToken(bearerToken))
    invoker.invoke(request, s"Retrieving category $code for origin $origin")
  }

  override def searchInstitution(text: String, offset: Int, limit: Int)(
    bearerToken: String
  )(implicit contexts: Seq[(String, String)]): Future[List[Institution]] = {
    val request: ApiRequest[Institutions] =
      institutionApi.searchInstitution(text, offset, limit)(BearerToken(bearerToken))
    invoker
      .invoke(request, s"Searching institution from text $text, offset $offset, limit $limit")
      .map(_.items.toList)
  }

}
