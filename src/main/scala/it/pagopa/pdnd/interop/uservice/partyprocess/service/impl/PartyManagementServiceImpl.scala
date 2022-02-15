package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import akka.http.scaladsl.server.directives.FileInfo
import it.pagopa.pdnd.interop.commons.utils.errors.GenericComponentErrors.{ResourceConflictError, ResourceNotFoundError}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.api.{PartyApi, PublicApi}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker.{ApiError, ApiRequest, BearerToken}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.service.{PartyManagementInvoker, PartyManagementService, replacementEntityId}
import org.slf4j.{Logger, LoggerFactory}

import java.io.File
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

final case class PartyManagementServiceImpl(invoker: PartyManagementInvoker, api: PartyApi, publicApi: PublicApi)(
  implicit ec: ExecutionContext
) extends PartyManagementService {
  implicit val logger: Logger = LoggerFactory.getLogger(this.getClass)

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
    invoke(request, "Relationships retrieval", None)
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
    invoke(request, "Relationships retrieval by institution id", None)
  }

  override def retrieveOrganization(organizationId: UUID)(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] = api.getOrganizationById(organizationId)(BearerToken(bearerToken))
    logger.info(s"Retrieving organization $organizationId")
    logger.info(s"Retrieving organization ${request.toString}")
    invoke(request, "Organization retrieval", Some(organizationId.toString))
  }

  override def retrieveOrganizationByExternalId(
    externalOrganizationId: String
  )(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] =
      api.getOrganizationByExternalId(externalOrganizationId)(BearerToken(bearerToken))
    logger.info(s"Retrieving organization by external id $externalOrganizationId")
    logger.info(s"Retrieving organization by external id ${request.toString}")
    invoke(request, "Organization retrieval by external id", Some(externalOrganizationId))
  }

  override def createPerson(person: PersonSeed)(bearerToken: String): Future[Person] = {
    val request: ApiRequest[Person] = api.createPerson(person)(BearerToken(bearerToken))
    invoke(request, "Person creation", Some(person.id.toString))
  }

  override def createOrganization(organization: OrganizationSeed)(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] = api.createOrganization(organization)(BearerToken(bearerToken))
    invoke(request, "Organization creation", Some(organization.institutionId))
  }

  override def createRelationship(relationshipSeed: RelationshipSeed)(bearerToken: String): Future[Relationship] = {
    for {
      relationship <- invokeCreateRelationship(relationshipSeed)(bearerToken)
    } yield relationship
  }

  private def invokeCreateRelationship(
    relationshipSeed: RelationshipSeed
  )(bearerToken: String): Future[Relationship] = {

    logger.info(
      s"Creating relationship ${relationshipSeed.from}/${relationshipSeed.to}/${relationshipSeed.role.toString}/ " +
        s"with product = ${relationshipSeed.product.id} and productRole = ${relationshipSeed.product.role}"
    )

    val request: ApiRequest[Relationship] = api.createRelationship(relationshipSeed)(BearerToken(bearerToken))
    invoke(request, "Relationship creation", None)
  }

  override def createToken(
    relationships: Relationships,
    documentHash: String,
    contractVersion: String,
    contractPath: String
  )(bearerToken: String): Future[TokenText] = {
    logger.info(s"Creating token for [${relationships.items.map(_.toString).mkString(",")}]")
    val tokenSeed: TokenSeed = TokenSeed(
      id = UUID.randomUUID().toString,
      relationships,
      documentHash,
      OnboardingContractInfo(contractVersion, contractPath)
    )

    val request = api.createToken(tokenSeed)(BearerToken(bearerToken))
    invoke(request, "Token creation", Some(tokenSeed.id))
  }

  def verifyToken(tokenId: UUID): Future[TokenInfo] = {
    logger.info(s"Retrieving token for $tokenId")

    val request = publicApi.verifyToken(tokenId)
    invoke(request, "Token retrieve", Some(tokenId.toString))
  }

  override def consumeToken(tokenId: UUID, fileParts: (FileInfo, File)): Future[Unit] = {
    logger.info(s"Consuming token $tokenId")

    val request = publicApi.consumeToken(tokenId, fileParts._2)
    invoke(request, "Token consume", Some(tokenId.toString))
  }

  override def invalidateToken(tokenId: UUID): Future[Unit] = {
    logger.info(s"Invalidating token $tokenId")

    val request = publicApi.invalidateToken(tokenId)
    invoke(request, "Token invalidation", Some(tokenId.toString))
  }

  override def activateRelationship(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    logger.info(s"Activating relationship $relationshipId")

    val request = api.activatePartyRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, "Relationship activation", Some(relationshipId.toString))
  }

  override def suspendRelationship(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    logger.info(s"Suspending relationship $relationshipId")

    val request = api.suspendPartyRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, "Relationship suspension", Some(relationshipId.toString))
  }

  override def getRelationshipById(relationshipId: UUID)(bearerToken: String): Future[Relationship] = {
    logger.info(s"Getting relationship $relationshipId")

    val request = api.getRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, "Relationship retrieval", Some(relationshipId.toString))
  }

  override def deleteRelationshipById(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    logger.info(s"Deleting relationship $relationshipId")

    val request = api.deleteRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, "Relationship deletion", Some(relationshipId.toString))
  }

  private def invoke[T](request: ApiRequest[T], logMessage: String, entityId: Option[String])(implicit
    m: Manifest[T]
  ): Future[T] =
    invoker.invoke(
      request,
      logMessage,
      (logger, msg) => {
        case ex @ ApiError(code, message, _, _, _) if code == 409 =>
          logger.error(s"$msg. code > $code - message > $message", ex)
          Future.failed[T](ResourceConflictError(entityId.getOrElse(replacementEntityId)))
        case ex @ ApiError(code, message, _, _, _) if code == 404 =>
          logger.error(s"$msg. code > $code - message > $message", ex)
          Future.failed[T](ResourceNotFoundError(entityId.getOrElse(replacementEntityId)))
        case ex @ ApiError(code, message, _, _, _) =>
          logger.error(s"$msg. code > $code - message > $message", ex)
          Future.failed[T](new RuntimeException(message))
        case ex =>
          logger.error(s"$msg. Error: ${ex.getMessage}", ex)
          Future.failed[T](ex)
      }
    )
}
