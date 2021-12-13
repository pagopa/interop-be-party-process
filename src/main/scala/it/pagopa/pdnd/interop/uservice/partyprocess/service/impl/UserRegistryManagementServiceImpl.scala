package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.partyprocess.error.ResourceConflictError
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{
  UserRegistryManagementInvoker,
  UserRegistryManagementService
}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.api.UserApi
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.invoker.{ApiError, ApiKeyValue, ApiRequest}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{EmbeddedExternalId, User, UserSeed}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

final case class UserRegistryManagementServiceImpl(invoker: UserRegistryManagementInvoker, api: UserApi)(implicit
  ec: ExecutionContext,
  apiKeyValue: ApiKeyValue
) extends UserRegistryManagementService {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def getUserById(userId: UUID)(bearerToken: String): Future[User] = {
    val request: ApiRequest[User] = api.getUserById(userId)
    invoke(request, "Retrieve User By ID")
  }

  override def getUserByExternalId(externalId: String)(bearerToken: String): Future[User] = {
    val request: ApiRequest[User] = api.getUserByExternalId(EmbeddedExternalId(externalId))
    invoke(request, "Retrieve User By External ID")
  }

  override def createUser(seed: UserSeed)(bearerToken: String): Future[User] = {
    val request: ApiRequest[User] = api.createUser(seed)
    invoke(request, "Create User")
  }

  override def updateUser(seed: UserSeed)(bearerToken: String): Future[User] = {
    val request: ApiRequest[User] = api.updateUser(seed)
    invoke(request, "Update User")
  }

  private def invoke[T](request: ApiRequest[T], logMessage: String)(implicit m: Manifest[T]): Future[T] =
    invoker
      .execute[T](request)
      .map { response =>
        logger.debug(s"$logMessage. Status code: ${response.code.toString}. Content: ${response.content.toString}")
        response.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) if code == 409 =>
          logger.error(s"$logMessage. code > $code - message > $message")
          Future.failed[T](ResourceConflictError)
        case ex: ApiError[_] =>
          logger.error(s"$logMessage. code > ${ex.code} - message > ${ex.message}")
          Future.failed(ex)
        case ex =>
          logger.error(s"$logMessage. Error: ${ex.getMessage}")
          Future.failed[T](ex)
      }
}
