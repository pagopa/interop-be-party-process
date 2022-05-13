package it.pagopa.interop.partyprocess.service.impl

import akka.http.scaladsl.server.directives.FileInfo
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.{ResourceConflictError, ResourceNotFoundError}
import it.pagopa.interop.partymanagement.client.api.{ExternalApi, PartyApi, PublicApi}
import it.pagopa.interop.partymanagement.client.invoker.{ApiError, ApiRequest, BearerToken}
import it.pagopa.interop.partymanagement.client.model._
import it.pagopa.interop.partyprocess.service.{PartyManagementInvoker, PartyManagementService, replacementEntityId}
import com.typesafe.scalalogging.Logger
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}

import java.io.File
import java.util.UUID
import scala.concurrent.Future

final case class PartyManagementServiceImpl(
  invoker: PartyManagementInvoker,
  partyApi: PartyApi,
  externalApi: ExternalApi,
  publicApi: PublicApi
) extends PartyManagementService {
  implicit val logger = Logger.takingImplicit[ContextFieldsToLog](this.getClass())

  override def retrieveRelationships(
    from: Option[UUID],
    to: Option[UUID],
    roles: Seq[PartyRole],
    states: Seq[RelationshipState],
    products: Seq[String],
    productRoles: Seq[String]
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Relationships] = {
    val request: ApiRequest[Relationships] =
      partyApi.getRelationships(
        from = from,
        to = to,
        roles = roles,
        states = states,
        products = products,
        productRoles = productRoles
      )(BearerToken(bearerToken))
    invoke(request, "Relationships retrieval", None)
  }

  override def getInstitutionRelationships(
    id: UUID
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Relationships] = {
    val request: ApiRequest[Relationships] = partyApi.getRelationships(
      to = Some(id),
      from = None,
      roles = Seq.empty,
      states = Seq.empty,
      products = Seq.empty,
      productRoles = Seq.empty
    )(BearerToken(bearerToken))
    invoke(request, "Relationships retrieval by institution id", None)
  }

  override def retrieveInstitution(
    institutionId: UUID
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Institution] = {
    val request: ApiRequest[Institution] = partyApi.getInstitutionById(institutionId)(BearerToken(bearerToken))
    invoke(request, s"Institution retrieval $institutionId", Some(institutionId.toString))
  }

  override def retrieveInstitutionByExternalId(
    externalInstitutionId: String
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Institution] = {
    val request: ApiRequest[Institution] =
      externalApi.getInstitutionByExternalId(externalInstitutionId)(BearerToken(bearerToken))
    invoke(request, s"Institution retrieval by external id $externalInstitutionId", Some(externalInstitutionId))
  }

  override def createPerson(
    person: PersonSeed
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Person] = {
    val request: ApiRequest[Person] = partyApi.createPerson(person)(BearerToken(bearerToken))
    invoke(request, s"Person creation with id ${person.id.toString}", Some(person.id.toString))
  }

  override def createInstitution(
    institution: InstitutionSeed
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Institution] = {
    val request: ApiRequest[Institution] = partyApi.createInstitution(institution)(BearerToken(bearerToken))
    invoke(
      request,
      s"Institution creation with external institution id ${institution.externalId}",
      Some(institution.externalId)
    )
  }

  override def createRelationship(relationshipSeed: RelationshipSeed)(bearerToken: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Relationship] =
    invokeCreateRelationship(relationshipSeed)(bearerToken)

  private def invokeCreateRelationship(
    relationshipSeed: RelationshipSeed
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Relationship] = {

    val logMessage =
      s"Creating relationship ${relationshipSeed.from}/${relationshipSeed.to}/${relationshipSeed.role.toString}/ " +
        s"with product = ${relationshipSeed.product.id} and productRole = ${relationshipSeed.product.role}"

    val request: ApiRequest[Relationship] = partyApi.createRelationship(relationshipSeed)(BearerToken(bearerToken))
    invoke(request, logMessage, None)
  }

  override def createToken(
    relationships: Relationships,
    documentHash: String,
    contractVersion: String,
    contractPath: String
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[TokenText] = {
    val tokenSeed: TokenSeed = TokenSeed(
      id = UUID.randomUUID().toString,
      relationships,
      documentHash,
      OnboardingContractInfo(contractVersion, contractPath)
    )

    val request = partyApi.createToken(tokenSeed)(BearerToken(bearerToken))
    invoke(request, s"Creating token for [${relationships.items.map(_.toString).mkString(",")}]", Some(tokenSeed.id))
  }

  override def verifyToken(tokenId: UUID)(implicit contexts: Seq[(String, String)]): Future[TokenInfo] = {
    val request = publicApi.verifyToken(tokenId)
    invoke(request, s"Token retrieve with id $tokenId", Some(tokenId.toString))
  }

  override def consumeToken(tokenId: UUID, fileParts: (FileInfo, File))(implicit
    contexts: Seq[(String, String)]
  ): Future[Unit] = {
    val request = publicApi.consumeToken(tokenId, fileParts._2)
    invoke(request, s"Consuming token $tokenId", Some(tokenId.toString))
  }

  override def invalidateToken(tokenId: UUID)(implicit contexts: Seq[(String, String)]): Future[Unit] = {
    val request = publicApi.invalidateToken(tokenId)
    invoke(request, s"Invalidating token $tokenId", Some(tokenId.toString))
  }

  override def activateRelationship(
    relationshipId: UUID
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Unit] = {
    val request = partyApi.activatePartyRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, s"Activating relationship $relationshipId", Some(relationshipId.toString))
  }

  override def suspendRelationship(
    relationshipId: UUID
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Unit] = {
    val request = partyApi.suspendPartyRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, s"Suspending relationship $relationshipId", Some(relationshipId.toString))
  }

  override def getRelationshipById(
    relationshipId: UUID
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Relationship] = {
    val request = partyApi.getRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, s"Getting relationship $relationshipId", Some(relationshipId.toString))
  }

  override def deleteRelationshipById(
    relationshipId: UUID
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Unit] = {
    val request = partyApi.deleteRelationshipById(relationshipId)(BearerToken(bearerToken))
    invoke(request, s"Deleting relationship $relationshipId", Some(relationshipId.toString))
  }

  private def invoke[T](request: ApiRequest[T], logMessage: String, entityId: Option[String])(implicit
    m: Manifest[T],
    contexts: Seq[(String, String)]
  ): Future[T] =
    invoker.invoke(
      request,
      logMessage,
      (contextFieldsToLog, logger, msg) => {
        case ex @ ApiError(code, message, _, _, _) if code == 409 =>
          logger.error(s"$msg. code > $code - message > $message", ex)(contextFieldsToLog)
          Future.failed[T](ResourceConflictError(entityId.getOrElse(replacementEntityId)))
        case ex @ ApiError(code, message, _, _, _) if code == 404 =>
          logger.error(s"$msg. code > $code - message > $message", ex)(contextFieldsToLog)
          Future.failed[T](ResourceNotFoundError(entityId.getOrElse(replacementEntityId)))
        case ex @ ApiError(code, message, _, _, _)                =>
          logger.error(s"$msg. code > $code - message > $message", ex)(contextFieldsToLog)
          Future.failed[T](new RuntimeException(message))
        case ex                                                   =>
          logger.error(s"$msg. Error: ${ex.getMessage}", ex)(contextFieldsToLog)
          Future.failed[T](ex)
      }
    )
}
