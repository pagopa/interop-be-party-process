package it.pagopa.pdnd.interop.uservice.partyprocess.service

import akka.http.scaladsl.server.directives.FileInfo
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._

import java.io.File
import java.util.UUID
import scala.concurrent.Future

trait PartyManagementService {
  def retrieveOrganization(organizationId: String): Future[Organization]

  def createPerson(person: PersonSeed): Future[Person]

  def createOrganization(organization: OrganizationSeed): Future[Organization]

  def createRelationship(
    taxCode: String,
    organizationId: String,
    operationRole: String,
    platformRole: String
  ): Future[Unit]

  def retrieveRelationship(
    from: Option[String],
    to: Option[String],
    platformRole: Option[String]
  ): Future[Relationships]

  def getInstitutionRelationships(institutionId: String): Future[Relationships]

  def activateRelationship(relationshipId: UUID): Future[Unit]

  def suspendRelationship(relationshipId: UUID): Future[Unit]

  def createToken(relationshipsSeed: RelationshipsSeed, documentHash: String): Future[TokenText]

  def consumeToken(token: String, fileParts: (FileInfo, File)): Future[Unit]

  def invalidateToken(token: String): Future[Unit]

  def getRelationshipById(relationshipId: UUID): Future[Relationship]
}
