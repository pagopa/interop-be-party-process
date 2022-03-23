package it.pagopa.interop.partyprocess.service.impl

import akka.http.scaladsl.server.directives.FileInfo
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.{ResourceConflictError, ResourceNotFoundError}
import it.pagopa.interop.partymanagement.client.api.{PartyApi, PublicApi}
import it.pagopa.interop.partymanagement.client.invoker.{ApiError, ApiRequest, BearerToken}
import it.pagopa.interop.partymanagement.client.model._
import it.pagopa.interop.partyprocess.service.{PartyManagementInvoker, PartyManagementService, replacementEntityId}
import org.slf4j.{Logger, LoggerFactory}

import java.io.File
import java.util.UUID
import scala.concurrent.Future

final case class PartyManagementServiceImpl(invoker: PartyManagementInvoker, api: PartyApi, publicApi: PublicApi)
    extends PartyManagementService {
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
    invoke(request, s"Organization retrieval $organizationId", Some(organizationId.toString))
  }

  override def retrieveOrganizationByExternalId(
    externalOrganizationId: String
  )(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] =
      api.getOrganizationByExternalId(externalOrganizationId)(BearerToken(bearerToken))
    invoke(request, s"Organization retrieval by external id $externalOrganizationId", Some(externalOrganizationId))
  }

  override def createPerson(person: PersonSeed)(bearerToken: String): Future[Person] = {
    val request: ApiRequest[Person] = api.createPerson(person)(BearerToken(bearerToken))
    invoke(request, s"Person creation with id ${person.id.toString}", Some(person.id.toString))
  }

  override def createOrganization(organization: OrganizationSeed)(bearerToken: String): Future[Organization] = {
    val request: ApiRequest[Organization] = api.createOrganization(organization)(BearerToken(bearerToken))
    invoke(
      request,
      s"Organization creation with institution id ${organization.institutionId}",
      Some(organization.institutionId)
    )
  }

  override def createRelationship(relationshipSeed: RelationshipSeed)(bearerToken: String): Future[Relationship] =
    invokeCreateRelationship(relationshipSeed)(bearerToken)

  private def invokeCreateRelationship(
    relationshipSeed: RelationshipSeed
  )(bearerToken: String): Future[Relationship] = {

    val logMessage =
      s"Creating relationship ${relationshipSeed.from}/${relationshipSeed.to}/${relationshipSeed.role.toString}/ " +
        s"with product = ${relationshipSeed.product.id} and productRole = ${relationshipSeed.product.role}"

    val request: ApiRequest[Relationship] = api.createRelationship(relationshipSeed)(BearerToken(bearerToken))
    invoke(request, logMessage, None)
  }

  override def createToken(
    relationships: Relationships,
    documentHash: String,
    contractVersion: String,
    contractPath: String
  )(bearerToken: String): Future[TokenText] = {
    val tokenSeed: TokenSeed = TokenSeed(
      id = UUID.randomUUID().toString,
      relationships,
      documentHash,
      OnboardingContractInfo(contractVersion, contractPath)
    )

    val request = api.createToken(tokenSeed)(BearerToken(bearerToken))
    invoke(request, s"Creating token for [${relationships.items.map(_.toString).mkString(",")}]", Some(tokenSeed.id))
  }

  def verifyToken(tokenId: UUID): Future[TokenInfo] = {
    val request = publicApi.verifyToken(tokenId)
    invoke(request, s"Token retrieve with id $tokenId", Some(tokenId.toString))
  }

  override def consumeToken(tokenId: UUID, fileParts: (FileInfo, File)): Future[Unit] = {
    val request = publicApi.consumeToken(tokenId, fileParts._2)
    invoke(request, s"Consuming token $tokenId", Some(tokenId.toString))
  }

  override def invalidateToken(tokenId: UUID): Future[Unit] = {
    val request = publicApi.invalidateToken(tokenId)
    invoke(request, s"Invalidating token $tokenId", Some(tokenId.toString))
  }

  override def activateRelationship(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    val request = api.activatePartyRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, s"Activating relationship $relationshipId", Some(relationshipId.toString))
  }

  override def suspendRelationship(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    val request = api.suspendPartyRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, s"Suspending relationship $relationshipId", Some(relationshipId.toString))
  }

  override def getRelationshipById(relationshipId: UUID)(bearerToken: String): Future[Relationship] = {
    val request = api.getRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, s"Getting relationship $relationshipId", Some(relationshipId.toString))
  }

  override def deleteRelationshipById(relationshipId: UUID)(bearerToken: String): Future[Unit] = {
    val request = api.deleteRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, s"Deleting relationship $relationshipId", Some(relationshipId.toString))
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
        case ex @ ApiError(code, message, _, _, _)                =>
          logger.error(s"$msg. code > $code - message > $message", ex)
          Future.failed[T](new RuntimeException(message))
        case ex                                                   =>
          logger.error(s"$msg. Error: ${ex.getMessage}", ex)
          Future.failed[T](ex)
      }
    )
}
