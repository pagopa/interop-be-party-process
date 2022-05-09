package it.pagopa.interop.partyprocess.service

import akka.http.scaladsl.server.directives.FileInfo
import it.pagopa.interop.partymanagement.client.model._

import java.io.File
import java.util.UUID
import scala.concurrent.Future

trait PartyManagementService {
  def deleteRelationshipById(relationshipUUID: UUID)(bearerToken: String): Future[Unit]

  def retrieveOrganization(organizationId: UUID)(bearerToken: String): Future[Institution]
  def retrieveOrganizationByExternalId(externalOrganizationId: String)(bearerToken: String): Future[Institution]

  def createPerson(person: PersonSeed)(bearerToken: String): Future[Person]

  def createOrganization(organization: InstitutionSeed)(bearerToken: String): Future[Institution]

  def createRelationship(relationshipSeed: RelationshipSeed)(bearerToken: String): Future[Relationship]

  def retrieveRelationships(
    from: Option[UUID],
    to: Option[UUID],
    roles: Seq[PartyRole],
    states: Seq[RelationshipState],
    products: Seq[String],
    productRoles: Seq[String]
  )(bearerToken: String): Future[Relationships]

  def getInstitutionRelationships(id: UUID)(bearerToken: String): Future[Relationships]

  def activateRelationship(relationshipId: UUID)(bearerToken: String): Future[Unit]

  def suspendRelationship(relationshipId: UUID)(bearerToken: String): Future[Unit]

  def createToken(relationships: Relationships, documentHash: String, contractVersion: String, contractPath: String)(
    bearerToken: String
  ): Future[TokenText]

  def verifyToken(tokenId: UUID): Future[TokenInfo]

  def consumeToken(tokenId: UUID, fileParts: (FileInfo, File)): Future[Unit]

  def invalidateToken(tokenId: UUID): Future[Unit]

  def getRelationshipById(relationshipId: UUID)(bearerToken: String): Future[Relationship]

}
