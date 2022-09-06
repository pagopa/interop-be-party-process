package it.pagopa.interop.partyprocess.service.impl

import com.typesafe.scalalogging.Logger
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.ResourceConflictError
import it.pagopa.interop.partyprocess.model.ProductResourceProduct
import it.pagopa.interop.partyprocess.service.{ProductManagementInvoker, ProductManagementService, replacementEntityId}
import it.pagopa.product.client.api.ProductApi
import it.pagopa.product.client.invoker.{ApiError, ApiKeyValue, ApiRequest}
import it.pagopa.product.client.model.ProductResource

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

final case class ProductManagementServiceImpl(invoker: ProductManagementInvoker, api: ProductApi)(implicit
  apiKeyValue: ApiKeyValue,
  xSelfcareUid: String
) extends ProductManagementService {
  implicit val logger = Logger.takingImplicit[ContextFieldsToLog](this.getClass())

  override def getProductById(
    productId: String
  )(implicit context: Seq[(String, String)]): Future[ProductResourceProduct] = {
    val request: ApiRequest[ProductResource] = api.getProductUsingGET(xSelfcareUid, productId)(apiKeyValue)
    invokeAPI(request, s"Retrieve Product By ID ${productId}", Some(productId))
      .map(p => ProductResourceProduct.fromProductResource(p))
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
          case ex @ ApiError(code, message, _, _, _) if code == 409 =>
            logger.error(s"$msg. code > $code - message > $message", ex)(context)
            Future.failed[T](ResourceConflictError(entityId.getOrElse(replacementEntityId)))
          case ex: ApiError[_]                                      =>
            logger.error(s"$msg. code > ${ex.code} - message > ${ex.message}", ex)(context)
            Future.failed(ex)
          case ex                                                   =>
            logger.error(s"$msg. Error: ${ex.getMessage}", ex)(context)
            Future.failed[T](ex)
        }
      )
}
