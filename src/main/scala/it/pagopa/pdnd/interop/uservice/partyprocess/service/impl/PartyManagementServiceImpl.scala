package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.partymanagement.client.api.PartyApi
import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker.{ApiError, ApiRequest}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.RelationshipSeedEnums.Role.{
  Delegate,
  Manager,
  Operator
}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{PartyManagementInvoker, PartyManagementService}
import org.slf4j.{Logger, LoggerFactory}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.ApplicationConfiguration.platformRolesConfiguration._
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.utils.{EitherOps, TryOps}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@SuppressWarnings(
  Array(
    "org.wartremover.warts.StringPlusAny",
    "org.wartremover.warts.ImplicitParameter",
    "org.wartremover.warts.ToString"
  )
)
final case class PartyManagementServiceImpl(invoker: PartyManagementInvoker, api: PartyApi)(implicit
  ec: ExecutionContext
) extends PartyManagementService {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def retrievePerson(taxCode: String): Future[Person] = {
    val request: ApiRequest[Person] = api.getPerson(taxCode)
    invoker
      .execute[Person](request)
      .map { x =>
        logger.info(s"Retrieving person ${x.code}")
        logger.info(s"Retrieving person ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving person ${ex.getMessage}")
        Future.failed[Person](ex)
      }
  }

  override def retrieveRelationship(
    from: Option[String],
    to: Option[String],
    platformRole: Option[String]
  ): Future[Relationships] = {
    val request: ApiRequest[Relationships] = api.getRelationships(from, to, platformRole)
    invoker
      .execute[Relationships](request)
      .map { x =>
        logger.info(s"Retrieving relationships ${x.code}")
        logger.info(s"Retrieving relationships ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving relationships ${ex.getMessage}")
        Future.failed[Relationships](ex)
      }
  }

  def getInstitutionRelationships(institutionId: String): Future[Relationships] = {
    val request: ApiRequest[Relationships] = api.getRelationships(to = Some(institutionId))
    invoker
      .execute[Relationships](request)
      .map { x =>
        logger.info(s"Retrieving relationships for institution $institutionId: ${x.code}")
        logger.info(s"Retrieving relationships for institution $institutionId: ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"ERROR while retrieving relationships for institution $institutionId: ${ex.getMessage}")
        Future.failed[Relationships](ex)
      }
  }

  override def retrieveOrganization(organizationId: String): Future[Organization] = {
    val request: ApiRequest[Organization] = api.getOrganization(organizationId)
    logger.info(s"Retrieving organization $organizationId")
    logger.info(s"Retrieving organization ${request.toString}")
    invoker
      .execute[Organization](request)
      .map { x =>
        logger.info(s"Retrieving organization ${x.code}")
        logger.info(s"Retrieving organization ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving organization ${ex.getMessage}")
        Future.failed[Organization](ex)
      }
  }

  override def createPerson(person: PersonSeed): Future[Person] = {
    val request: ApiRequest[Person] = api.createPerson(person)
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Create person ${x.code}")
        logger.info(s"Create person ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Create person ${ex.getMessage}")
        Future.failed[Person](ex)
      }
  }

  override def createOrganization(organization: OrganizationSeed): Future[Organization] = {
    val request: ApiRequest[Organization] = api.createOrganization(organization)
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Create organization ${x.code}")
        logger.info(s"Create organization ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Create organization ${ex.getMessage}")
        Future.failed[Organization](ex)
      }
  }

  override def createRelationship(
    taxCode: String,
    organizationId: String,
    role: String,
    platformRole: String
  ): Future[Unit] = {
    for {
      role <- Try { RelationshipSeedEnums.Role.withName(role) }.toFuture
      _    <- isPlatformRoleValid(role = role, platformRole = platformRole).toFuture
      _    <- invokeCreateRelationship(taxCode, organizationId, role, platformRole)
    } yield ()
  }

  private def invokeCreateRelationship(
    taxCode: String,
    organizationId: String,
    role: RelationshipSeedEnums.Role,
    platformRole: String
  ): Future[Relationship] = {
    logger.info(s"Creating relationship $taxCode/$organizationId/$role/ with platformRole = $platformRole")
    val partyRelationship: RelationshipSeed =
      RelationshipSeed(from = taxCode, to = organizationId, role = role, platformRole = platformRole)

    val request: ApiRequest[Relationship] = api.createRelationship(partyRelationship)
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Create relationship ${x.code}")
        logger.info(s"Create relationship ${x.content}")
        x.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) =>
          logger.error(s"Create relationship $code")
          logger.error(s"Create relationship $message")

          Future.failed[Relationship](new RuntimeException(message))
        case ex =>
          logger.error(s"Create relationship ! ${ex.getMessage}")
          Future.failed[Relationship](ex)
      }
  }

  private def isPlatformRoleValid(role: RelationshipSeedEnums.Role, platformRole: String): Either[Throwable, String] = {
    logger.info(s"Checking if the platformRole '$platformRole' is valid for a '$role'")
    role match {
      case Manager  => manager.validatePlatformRoleMapping(platformRole)
      case Delegate => delegate.validatePlatformRoleMapping(platformRole)
      case Operator => operator.validatePlatformRoleMapping(platformRole)
    }
  }

  override def createToken(relationshipsSeed: RelationshipsSeed, documentHash: String): Future[TokenText] = {
    logger.info(s"Creating token for [${relationshipsSeed.items.map(_.toString).mkString(",")}]")
    val tokenSeed: TokenSeed = TokenSeed(seed = UUID.randomUUID().toString, relationshipsSeed, documentHash)

    val request = api.createToken(tokenSeed)
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Create token ${x.code}")
        logger.info(s"Create token ${x.content}")
        x.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) =>
          logger.error(s"Create token $code")
          logger.error(s"Create token $message")

          Future.failed[TokenText](new RuntimeException(message))
        case ex =>
          logger.error(s"Create token ! ${ex.getMessage}")
          Future.failed[TokenText](ex)
      }

  }

  override def consumeToken(token: String): Future[Unit] = {
    logger.info(s"Consuming token $token")

    val request = api.consumeToken(token)
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Token consumed ${x.code}")
        logger.info(s"Token consumed ${x.content}")
        x.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) =>
          logger.error(s"Token consumed $code")
          logger.error(s"Token consumed $message")

          Future.failed[Unit](new RuntimeException(message))
        case ex =>
          logger.error(s"Token consumed ! ${ex.getMessage}")
          Future.failed[Unit](ex)
      }

  }

  override def invalidateToken(token: String): Future[Unit] = {
    logger.info(s"Invalidating token $token")

    val request = api.invalidateToken(token)
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Token invalidated ${x.code}")
        logger.info(s"Token invalidated ${x.content}")
        x.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) =>
          logger.error(s"Token invalidated $code")
          logger.error(s"Token invalidated $message")

          Future.failed[Unit](new RuntimeException(message))
        case ex =>
          logger.error(s"Token invalidated ! ${ex.getMessage}")
          Future.failed[Unit](ex)
      }
  }

  override def activateRelationship(relationshipId: UUID): Future[Unit] = {
    logger.info(s"Activating relationship $relationshipId")

    val request = api.activatePartyRelationshipById(relationshipId)
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Relationship activated ${x.code}")
        x.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) =>
          logger.error(s"Relationship activation $code")
          logger.error(s"Relationship activation $message")

          Future.failed[Unit](new RuntimeException(message))
        case ex =>
          logger.error(s"Relationship activation ${ex.getMessage}")
          Future.failed[Unit](ex)
      }
  }
}
