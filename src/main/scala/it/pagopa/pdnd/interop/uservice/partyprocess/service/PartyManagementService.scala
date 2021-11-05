package it.pagopa.pdnd.interop.uservice.partyprocess.service

import akka.http.scaladsl.server.directives.FileInfo
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._

import java.io.File
import java.util.UUID
import scala.concurrent.Future

trait PartyManagementService {
  def deleteRelationshipById(relationshipUUID: UUID): Future[Unit]

  def retrieveOrganization(organizationId: UUID): Future[Organization]
  def retrieveOrganizationByExternalId(externalOrganizationId: String): Future[Organization]

  def createPerson(person: PersonSeed): Future[Person]

  def createOrganization(organization: OrganizationSeed): Future[Organization]

  def createRelationship(
    id: UUID,
    organizationId: UUID,
    operationRole: String,
    products: Set[String],
    productRole: String
  ): Future[Unit]

  def retrieveRelationships(from: Option[UUID], to: Option[UUID], productRole: Option[String]): Future[Relationships]

  def getInstitutionRelationships(id: UUID): Future[Relationships]

  def activateRelationship(relationshipId: UUID): Future[Unit]

  def suspendRelationship(relationshipId: UUID): Future[Unit]

  def createToken(relationshipsSeed: RelationshipsSeed, documentHash: String): Future[TokenText]

  def consumeToken(token: String, fileParts: (FileInfo, File)): Future[Unit]

  def invalidateToken(token: String): Future[Unit]

  def getRelationshipById(relationshipId: UUID): Future[Relationship]

  def replaceOrganizationProducts(institutionId: UUID, products: Set[String]): Future[Organization]

  def replaceRelationshipProducts(relationshipUUID: UUID, products: Set[String]): Future[Relationship]
}
