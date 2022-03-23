package it.pagopa.interop.partyprocess.service.impl

import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.ResourceConflictError
import it.pagopa.interop.partyprocess.service.{
  UserRegistryManagementInvoker,
  UserRegistryManagementService,
  replacementEntityId
}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.api.UserApi
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.invoker.{ApiError, ApiKeyValue, ApiRequest}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{EmbeddedExternalId, User, UserSeed}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.concurrent.Future

final case class UserRegistryManagementServiceImpl(invoker: UserRegistryManagementInvoker, api: UserApi)(implicit
  apiKeyValue: ApiKeyValue
) extends UserRegistryManagementService {
  implicit val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def getUserById(userId: UUID): Future[User] = {
    val request: ApiRequest[User] = api.getUserById(userId)
    invokeAPI(request, "Retrieve User By ID", Some(userId.toString))
  }

  override def getUserByExternalId(externalId: String): Future[User] = {
    val request: ApiRequest[User] = api.getUserByExternalId(EmbeddedExternalId(externalId))
    invokeAPI(request, "Retrieve User By External ID", Some(externalId))
  }

  override def createUser(seed: UserSeed): Future[User] = {
    val request: ApiRequest[User] = api.createUser(seed)
    invokeAPI(request, "Create User", None)
  }

  override def updateUser(seed: UserSeed): Future[User] = {
    val request: ApiRequest[User] = api.updateUser(seed)
    invokeAPI(request, "Update User", None)
  }

  private def invokeAPI[T](request: ApiRequest[T], logMessage: String, entityId: Option[String])(implicit
    m: Manifest[T]
  ): Future[T] =
    invoker
      .invoke(
        request,
        logMessage,
        (logger, msg) => {
          case ex @ ApiError(code, message, _, _, _) if code == 409 =>
            logger.error(s"$msg. code > $code - message > $message", ex)
            Future.failed[T](ResourceConflictError(entityId.getOrElse(replacementEntityId)))
          case ex: ApiError[_]                                      =>
            logger.error(s"$msg. code > ${ex.code} - message > ${ex.message}", ex)
            Future.failed(ex)
          case ex                                                   =>
            logger.error(s"$msg. Error: ${ex.getMessage}", ex)
            Future.failed[T](ex)
        }
      )
}
