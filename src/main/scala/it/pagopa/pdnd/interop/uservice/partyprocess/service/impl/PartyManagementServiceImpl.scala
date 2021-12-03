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
    roles: Seq[PartyRole],
    states: Seq[RelationshipState],
    products: Seq[String],
    productRoles: Seq[String]
  )(bearerToken: String): Future[Relationships] = {
    val request: ApiRequest[Relationships] =
      api.getRelationships(
        from = from,
        to = to,
        roles = roles,
        states = states,
        products = products,
        productRoles = productRoles
      )(BearerToken(bearerToken))
    invoke(request, "Relationships retrieval")
  }

  def getInstitutionRelationships(id: UUID)(bearerToken: String): Future[Relationships] = {
    val request: ApiRequest[Relationships] = api.getRelationships(
      to = Some(id),
      from = None,
      roles = Seq.empty,
      states = Seq.empty,
      products = Seq.empty,
      productRoles = Seq.empty
    )(BearerToken(bearerToken))
    invoke(request, "Relationships retrieval by institution id")
  }

  override def retrieveOrganization(organizationId: UUID)(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] = api.getOrganizationById(organizationId)(BearerToken(bearerToken))
    logger.info(s"Retrieving organization $organizationId")
    logger.info(s"Retrieving organization ${request.toString}")
    invoke(request, "Organization retrieval")
  }

  override def retrieveOrganizationByExternalId(
    externalOrganizationId: String
  )(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] =
      api.getOrganizationByExternalId(externalOrganizationId)(BearerToken(bearerToken))
    logger.info(s"Retrieving organization by external id $externalOrganizationId")
    logger.info(s"Retrieving organization by external id ${request.toString}")
    invoke(request, "Organization retrieval by external id")
  }

  override def createPerson(person: PersonSeed)(bearerToken: String): Future[Person] = {
    val request: ApiRequest[Person] = api.createPerson(person)(BearerToken(bearerToken))
    invoke(request, "Person creation")
  }

  override def createOrganization(organization: OrganizationSeed)(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] = api.createOrganization(organization)(BearerToken(bearerToken))
    invoke(request, "Organization creation")
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
    invoke(request, "Relationship creation")
  }

  override def createToken(relationshipsSeed: RelationshipsSeed, documentHash: String)(
    bearerToken: String
  ): Future[TokenText] = {
    logger.info(s"Creating token for [${relationshipsSeed.items.map(_.toString).mkString(",")}]")
    val tokenSeed: TokenSeed = TokenSeed(id = UUID.randomUUID().toString, relationshipsSeed, documentHash)

    val request = api.createToken(tokenSeed)(BearerToken(bearerToken))
    invoke(request, "Token creation")
  }

  def getToken(tokenId: UUID)(bearerToken: String): Future[TokenInfo] = {
    logger.info(s"Retrieving token for $tokenId")

    val request = api.getToken(tokenId)(BearerToken(bearerToken))
    invoke(request, "Token retrieve")
  }

  override def consumeToken(tokenId: UUID, fileParts: (FileInfo, File))(bearerToken: String): Future[Unit] = {
    logger.info(s"Consuming token $tokenId")

    val request = api.consumeToken(tokenId, fileParts._2)(BearerToken(bearerToken))
    invoke(request, "Token consume")
  }

  override def invalidateToken(tokenId: UUID)(bearerToken: String): Future[Unit] = {
    logger.info(s"Invalidating token $tokenId")

    val request = api.invalidateToken(tokenId)(BearerToken(bearerToken))
    invoke(request, "Token invalidation")
  }

  override def activateRelationship(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    logger.info(s"Activating relationship $relationshipId")

    val request = api.activatePartyRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, "Relationship activation")
  }

  override def suspendRelationship(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    logger.info(s"Suspending relationship $relationshipId")

    val request = api.suspendPartyRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, "Relationship suspension")
  }

  override def getRelationshipById(relationshipId: UUID)(bearerToken: String): Future[Relationship] = {
    logger.info(s"Getting relationship $relationshipId")

    val request = api.getRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, "Relationship retrieval")
  }

  override def deleteRelationshipById(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    logger.info(s"Deleting relationship $relationshipId")

    val request = api.deleteRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, "Relationship deletion")
  }

  private def invoke[T](request: ApiRequest[T], logMessage: String)(implicit m: Manifest[T]): Future[T] =
    invoker
      .execute[T](request)
      .map { response =>
        logger.info(s"$logMessage. Status code: ${response.code.toString}")
        response.content
      }
      .recoverWith {
        case ApiError(code, message, _, _, _) =>
          logger.error(s"$logMessage. code > $code - message > $message")
          Future.failed[T](new RuntimeException(message))
        case ex =>
          logger.error(s"$logMessage. Error: ${ex.getMessage}")
          Future.failed[T](ex)
      }
}
