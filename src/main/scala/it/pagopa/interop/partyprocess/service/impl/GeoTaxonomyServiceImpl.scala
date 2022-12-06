package it.pagopa.interop.partyprocess.service.impl

import com.typesafe.scalalogging.Logger
import it.pagopa.geotaxonomy.client.api.GeographicTaxonomyApi
import it.pagopa.geotaxonomy.client.invoker.{ApiError, ApiRequest}
import it.pagopa.geotaxonomy.client.model.{GeographicTaxonomy => DependencyGeographicTaxonomy}
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.interop.partyprocess.error.PartyProcessErrors.GeoTaxonomyCodeNotFound
import it.pagopa.interop.partyprocess.model.GeographicTaxonomy
import it.pagopa.interop.partyprocess.service.{GeoTaxonomyInvoker, GeoTaxonomyService, replacementEntityId}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

final case class GeoTaxonomyServiceImpl(invoker: GeoTaxonomyInvoker, api: GeographicTaxonomyApi)
    extends GeoTaxonomyService {
  implicit val logger = Logger.takingImplicit[ContextFieldsToLog](this.getClass())

  override def getByCode(code: String)(implicit context: Seq[(String, String)]): Future[GeographicTaxonomy] = {
    val request: ApiRequest[DependencyGeographicTaxonomy] = api.findByIdUsingGET(code)
    invokeAPI(request, s"Retrieve Geographic Taxonomy By Code ${code}", Some(code))
      .map(u => GeographicTaxonomy(code = u.code, desc = u.desc))
  }

  private def invokeAPI[T](request: ApiRequest[T], logMessage: String, entityId: Option[String])(implicit
    context: ContextFieldsToLog,
    m: Manifest[T]
  ): Future[T] =
    invoker
      .invoke(
        request,
        logMessage,
        (context, logger, msg) => {
          case ex @ ApiError(code, message, _, _, _) if code == 404 =>
            logger.error(s"$msg. code > $code - message > $message", ex)(context)
            Future.failed[T](GeoTaxonomyCodeNotFound(entityId.getOrElse(replacementEntityId), message))
          case ex: ApiError[_]                                      =>
            logger.error(s"$msg. code > ${ex.code} - message > ${ex.message}", ex)(context)
            Future.failed(ex)
          case ex                                                   =>
            logger.error(s"$msg. Error: ${ex.getMessage}", ex)(context)
            Future.failed[T](ex)
        }
      )
}
