package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.uservice.partymanagement.client.api.PartyApi
import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker.{ApiError, ApiRequest}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{PartyManagementInvoker, PartyManagementService}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

final case class PartyManagementServiceImpl(invoker: PartyManagementInvoker, api: PartyApi)(implicit
  ec: ExecutionContext
) extends PartyManagementService {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def getPerson(taxCode: String): Future[Person] = {
    val request: ApiRequest[Person] = api.getPerson(taxCode)
    invoker
      .execute[Person](request)
      .map { x =>
        logger.info(s"Retrieving person ${x.code}")
        logger.info(s"Retrieving organization ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Retrieving person ${ex.getMessage}")
        Future.failed(ex)
      }
  }

  override def getOrganization(organizationId: String): Future[Organization] = {
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
        Future.failed(ex)
      }
  }

  override def createPerson(person: PersonSeed): Future[Person] = {
    val request: ApiRequest[Person] = api.createPerson(person)
    invoker.execute(request).map(_.content)
  }

  override def createOrganization(organization: OrganizationSeed): Future[Organization] = {
    val request: ApiRequest[Organization] = api.createOrganization(organization)
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Create Organization ${x.code}")
        logger.info(s"Create Organization ${x.content}")
        x.content
      }
      .recoverWith { case ex =>
        logger.error(s"Create Organization ${ex.getMessage}")
        Future.failed(ex)
      }
  }

  override def createRelationShip(taxCode: String, organizationId: String, role: Role): Future[Unit] = {
    logger.info(s"Creating relationShip $taxCode/$organizationId/${role.toString}")
    val partyRelationShip: PartyRelationShip = PartyRelationShip(from = taxCode, to = organizationId, role = role)
    val request: ApiRequest[Unit]            = api.createRelationShip(partyRelationShip)
    invoker
      .execute(request)
      .map { x =>
        logger.info(s"Create relationShip ${x.code}")
        logger.info(s"Create relationShip ${x.content}")
        x.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) =>
          logger.error(s"Create relationShip $code")
          logger.error(s"Create relationShip $message")

          Future.failed(new RuntimeException(message))
        case ex =>
          logger.error(s"Create relationShip ! ${ex.getMessage}")
          Future.failed(ex)
      }

  }

  override def createToken(from: UUID, to: UUID, role: Role): Future[TokenText] = {
    logger.info(s"Creating Token ${from.toString}/${to.toString}/${role.toString}")

    val tokenSeed: TokenSeed =
      TokenSeed(from = from.toString, to = to.toString, role = role, seed = UUID.randomUUID().toString)

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

          Future.failed(new RuntimeException(message))
        case ex =>
          logger.error(s"Create token ! ${ex.getMessage}")
          Future.failed(ex)
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

          Future.failed(new RuntimeException(message))
        case ex =>
          logger.error(s"Token consumed ! ${ex.getMessage}")
          Future.failed(ex)
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

          Future.failed(new RuntimeException(message))
        case ex =>
          logger.error(s"Token invalidated ! ${ex.getMessage}")
          Future.failed(ex)
      }

  }
}
