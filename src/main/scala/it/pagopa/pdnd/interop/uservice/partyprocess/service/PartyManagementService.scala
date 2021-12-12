package it.pagopa.pdnd.interop.uservice.partyprocess.service

import akka.http.scaladsl.server.directives.FileInfo
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._

import java.io.File
import java.util.UUID
import scala.concurrent.Future

trait PartyManagementService {
  def deleteRelationshipById(relationshipUUID: UUID)(bearerToken: String): Future[Unit]

  def retrieveOrganization(organizationId: UUID)(bearerToken: String): Future[Organization]
  def retrieveOrganizationByExternalId(externalOrganizationId: String)(bearerToken: String): Future[Organization]

  def createPerson(person: PersonSeed)(bearerToken: String): Future[Person]

  def createOrganization(organization: OrganizationSeed)(bearerToken: String): Future[Organization]

  def createRelationship(relationshipSeed: RelationshipSeed)(bearerToken: String): Future[RelationshipSeed]

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

  def createToken(
    relationshipsSeed: RelationshipsSeed,
    documentHash: String,
    contractVersion: String,
    contractPath: String
  )(bearerToken: String): Future[TokenText]

  def getToken(tokenId: UUID)(bearerToken: String): Future[TokenInfo]

  def consumeToken(tokenId: UUID, fileParts: (FileInfo, File))(bearerToken: String): Future[Unit]

  def invalidateToken(tokenId: UUID)(bearerToken: String): Future[Unit]

  def getRelationshipById(relationshipId: UUID)(bearerToken: String): Future[Relationship]

}
