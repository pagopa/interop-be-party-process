package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.authorizationprocess.client.api.AuthApi
import it.pagopa.pdnd.interop.uservice.authorizationprocess.client.model.ValidJWT
import it.pagopa.pdnd.interop.uservice.authorizationprocess.client.invoker.BearerToken
import it.pagopa.pdnd.interop.uservice.authorizationprocess.client.invoker.ApiRequest
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{AuthorizationProcessInvoker, AuthorizationProcessService}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.StringPlusAny",
    "org.wartremover.warts.ImplicitParameter",
    "org.wartremover.warts.ToString"
  )
)
final case class AuthorizationProcessServiceImpl(invoker: AuthorizationProcessInvoker, api: AuthApi)(implicit
  ec: ExecutionContext
) extends AuthorizationProcessService {

  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def validateToken(bearerToken: String): Future[ValidJWT] = {
    val request: ApiRequest[ValidJWT] = api.validateToken()(BearerToken(bearerToken))
    logger.info(s"Validate token ${request.toString}")
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Retrieving valid JWT token ${x.code}")
        logger.info(s"Retrieving valid JWT token ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving user ERROR: ${ex.getMessage}")
        Future.failed[ValidJWT](ex)
      }
  }
}
