package it.pagopa.interop.partyprocess.service

import akka.http.scaladsl.server.directives.FileInfo
import it.pagopa.interop.partymanagement.client.model._

import java.io.File
import java.util.UUID
import scala.concurrent.Future

trait PartyManagementService {
  def deleteRelationshipById(relationshipUUID: UUID)(bearerToken: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Unit]

  def retrieveInstitution(institutionId: UUID)(bearerToken: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Institution]
  def retrieveInstitutionByExternalId(externalInstitutionId: String)(bearerToken: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Institution]

  def createPerson(person: PersonSeed)(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Person]

  def createInstitution(institution: InstitutionSeed)(bearerToken: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Institution]

  def createRelationship(relationshipSeed: RelationshipSeed)(bearerToken: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Relationship]

  def retrieveRelationships(
    from: Option[UUID],
    to: Option[UUID],
    roles: Seq[PartyRole],
    states: Seq[RelationshipState],
    products: Seq[String],
    productRoles: Seq[String]
  )(bearerToken: String)(implicit contexts: Seq[(String, String)]): Future[Relationships]

  def getInstitutionRelationships(id: UUID)(bearerToken: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Relationships]

  def activateRelationship(relationshipId: UUID)(bearerToken: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Unit]

  def suspendRelationship(relationshipId: UUID)(bearerToken: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Unit]

  def createToken(relationships: Relationships, documentHash: String, contractVersion: String, contractPath: String)(
    bearerToken: String
  )(implicit contexts: Seq[(String, String)]): Future[TokenText]

  def verifyToken(tokenId: UUID)(implicit contexts: Seq[(String, String)]): Future[TokenInfo]

  def consumeToken(tokenId: UUID, fileParts: (FileInfo, File))(implicit contexts: Seq[(String, String)]): Future[Unit]

  def invalidateToken(tokenId: UUID)(implicit contexts: Seq[(String, String)]): Future[Unit]

  def getRelationshipById(relationshipId: UUID)(bearerToken: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Relationship]

  def getInstitutionId(relationshipId: UUID)(implicit contexts: Seq[(String, String)]): Future[InstitutionId]
}
