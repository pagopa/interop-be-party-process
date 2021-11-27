package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import akka.http.scaladsl.server.directives.FileInfo
import it.pagopa.pdnd.interop.uservice.partymanagement.client.api.PartyApi
import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker.{ApiError, ApiRequest, BearerToken}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{PartyManagementInvoker, PartyManagementService}
import org.slf4j.{Logger, LoggerFactory}

import java.io.File
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

final case class PartyManagementServiceImpl(invoker: PartyManagementInvoker, api: PartyApi)(implicit
  ec: ExecutionContext
) extends PartyManagementService {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def retrieveRelationships(
    from: Option[UUID],
    to: Option[UUID],
    product: Option[String],
    productRole: Option[String]
  )(bearerToken: String): Future[Relationships] = {
    val request: ApiRequest[Relationships] =
      api.getRelationships(from, to, product, productRole)(BearerToken(bearerToken))
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

  def getInstitutionRelationships(id: UUID)(bearerToken: String): Future[Relationships] = {
    val request: ApiRequest[Relationships] = api.getRelationships(to = Some(id))(BearerToken(bearerToken))
    invoker
      .execute[Relationships](request)
      .map { x =>
        logger.info(s"Retrieving relationships for institution $id: ${x.code}")
        logger.info(s"Retrieving relationships for institution $id: ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"ERROR while retrieving relationships for institution $id: ${ex.getMessage}")
        Future.failed[Relationships](ex)
      }
  }

  override def retrieveOrganization(organizationId: UUID)(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] = api.getOrganizationById(organizationId)(BearerToken(bearerToken))
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

  override def retrieveOrganizationByExternalId(
    externalOrganizationId: String
  )(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] =
      api.getOrganizationByExternalId(externalOrganizationId)(BearerToken(bearerToken))
    logger.info(s"Retrieving organization by external id $externalOrganizationId")
    logger.info(s"Retrieving organization by external id ${request.toString}")
    invoker
      .execute[Organization](request)
      .map { x =>
        logger.info(s"Retrieving organization by external id - ERROR: ${x.code}")
        logger.info(s"Retrieving organization by external id - ERROR: ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving organization by external id ${ex.getMessage}")
        Future.failed[Organization](ex)
      }
  }

  override def createPerson(person: PersonSeed)(bearerToken: String): Future[Person] = {
    val request: ApiRequest[Person] = api.createPerson(person)(BearerToken(bearerToken))
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

  override def createOrganization(organization: OrganizationSeed)(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] = api.createOrganization(organization)(BearerToken(bearerToken))
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
    personId: UUID,
    organizationId: UUID,
    role: PartyRole,
    product: String,
    productRole: String
  )(bearerToken: String): Future[Unit] = {
    for {
      _ <- invokeCreateRelationship(personId, organizationId, role, product, productRole)(bearerToken)
    } yield ()
  }

  private def invokeCreateRelationship(
    personId: UUID,
    organizationId: UUID,
    role: PartyRole,
    product: String,
    productRole: String
  )(bearerToken: String): Future[Relationship] = {
    logger.info(
      s"Creating relationship $personId/$organizationId/$role/ with product = $product and productRole = $productRole"
    )
    val partyRelationship: RelationshipSeed =
      RelationshipSeed(
        from = personId,
        to = organizationId,
        role = role,
        product = RelationshipProductSeed(product, productRole)
      )

    val request: ApiRequest[Relationship] = api.createRelationship(partyRelationship)(BearerToken(bearerToken))
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

  override def createToken(relationshipsSeed: RelationshipsSeed, documentHash: String)(
    bearerToken: String
  ): Future[TokenText] = {
    logger.info(s"Creating token for [${relationshipsSeed.items.map(_.toString).mkString(",")}]")
    val tokenSeed: TokenSeed = TokenSeed(seed = UUID.randomUUID().toString, relationshipsSeed, documentHash)

    val request = api.createToken(tokenSeed)(BearerToken(bearerToken))
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

  override def consumeToken(token: String, fileParts: (FileInfo, File))(bearerToken: String): Future[Unit] = {
    logger.info(s"Consuming token $token")

    val request = api.consumeToken(token, fileParts._2)(BearerToken(bearerToken))
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

  override def invalidateToken(token: String)(bearerToken: String): Future[Unit] = {
    logger.info(s"Invalidating token $token")

    val request = api.invalidateToken(token)(BearerToken(bearerToken))
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

  override def activateRelationship(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    logger.info(s"Activating relationship $relationshipId")

    val request = api.activatePartyRelationshipById(relationshipId)(BearerToken(bearerToken))
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

  override def suspendRelationship(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    logger.info(s"Suspending relationship $relationshipId")

    val request = api.suspendPartyRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Relationship suspended ${x.code}")
        x.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) =>
          logger.error(s"Relationship suspension $code")
          logger.error(s"Relationship suspension $message")

          Future.failed[Unit](new RuntimeException(message))
        case ex =>
          logger.error(s"Relationship suspension ${ex.getMessage}")
          Future.failed[Unit](ex)
      }
  }

  override def getRelationshipById(relationshipId: UUID)(bearerToken: String): Future[Relationship] = {
    logger.info(s"Getting relationship $relationshipId")

    val request = api.getRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Relationship retrieved ${x.code}")
        x.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) =>
          logger.error(s"Relationship retrieval error $code")
          logger.error(s"Relationship retrieval error message: $message")

          Future.failed[Relationship](new RuntimeException(message))
        case ex =>
          logger.error(s"Relationship suspension ${ex.getMessage}")
          Future.failed[Relationship](ex)
      }
  }

  override def deleteRelationshipById(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    logger.info(s"Deleting relationship $relationshipId")

    val request = api.deleteRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Relationship deleted ${x.code}")
        x.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) =>
          logger.error(s"Relationship deletion ERROR code > $code")
          logger.error(s"Relationship deletion ERROR message > $message")

          Future.failed[Unit](new RuntimeException(message))
        case ex =>
          logger.error(s"Relationship deletion ERROR message > ${ex.getMessage}")
          Future.failed[Unit](ex)
      }
  }

}
