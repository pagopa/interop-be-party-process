package it.pagopa.interop.partyprocess.service.impl

import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.ResourceConflictError
import it.pagopa.interop.partyprocess.model.UserRegistryUser
import it.pagopa.interop.partyprocess.service.{
  UserRegistryManagementInvoker,
  UserRegistryManagementService,
  replacementEntityId
}
import it.pagopa.userreg.client.api.UserApi
import it.pagopa.userreg.client.invoker.{ApiError, ApiKeyValue, ApiRequest}
import it.pagopa.userreg.client.model.{UserId, UserResource, UserSearchDto}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

final case class UserRegistryManagementServiceImpl(invoker: UserRegistryManagementInvoker, api: UserApi)(implicit
  apiKeyValue: ApiKeyValue
) extends UserRegistryManagementService {
  implicit val logger: Logger = LoggerFactory.getLogger(this.getClass)

  private val userFields2fetch: Seq[String] = Seq("name,familyName,fiscalCode")

  override def getUserById(userId: UUID): Future[UserRegistryUser] = {
    val request: ApiRequest[UserResource] = api.findByIdUsingGET(userId, userFields2fetch)
    invokeAPI(request, "Retrieve User By ID", Some(userId.toString))
      .map(u => UserRegistryUser.fromUserResource(u))
  }

  override def getUserByExternalId(externalId: String): Future[UserRegistryUser] = {
    val request: ApiRequest[UserResource] =
      api.searchUsingPOST(userFields2fetch, Option(UserSearchDto(fiscalCode = externalId)))
    invokeAPI(request, "Retrieve User By External ID", Some(externalId))
      .map(u => UserRegistryUser.fromUserResource(u))
  }
  override def getUserIdByExternalId(externalId: String): Future[UserId]         = {
    val request: ApiRequest[UserResource] = api.searchUsingPOST(Seq(), Option(UserSearchDto(fiscalCode = externalId)))
    invokeAPI(request, "Retrieve User By External ID", Some(externalId))
      .map(u => UserId(u.id))
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
