package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.partyprocess.service.{
  UserRegistryManagementInvoker,
  UserRegistryManagementService
}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.api.UserApi
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.invoker.ApiRequest
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{User, UserSeed}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.StringPlusAny",
    "org.wartremover.warts.ImplicitParameter",
    "org.wartremover.warts.ToString"
  )
)
final case class UserRegistryManagementServiceImpl(invoker: UserRegistryManagementInvoker, api: UserApi)(implicit
  ec: ExecutionContext
) extends UserRegistryManagementService {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def getUserById(userId: UUID): Future[User] = {
    val request: ApiRequest[User] = api.getUserById(userId)
    logger.info(s"getUserById ${request.toString}")
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Retrieving user ${x.code}")
        logger.info(s"Retrieving user ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving user ERROR: ${ex.getMessage}")
        Future.failed[User](ex)
      }
  }

  override def upsertUser(externalId: String, name: String, surname: String, email: String): Future[User] = {
    val seed: UserSeed            = UserSeed(externalId = externalId, name = name, surname = surname, email = email)
    val request: ApiRequest[User] = api.upsertUser(seed)
    logger.info(s"upsertUser ${request.toString}")
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Upserting user ${x.code}")
        logger.info(s"Upserting user ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Upserting user ERROR: ${ex.getMessage}")
        Future.failed[User](ex)
      }
  }
}
