package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._

import scala.concurrent.Future

trait PartyManagementService {
  def retrievePerson(taxCode: String): Future[Person]

  def retrieveOrganization(organizationId: String): Future[Organization]

  def createPerson(person: PersonSeed): Future[Person]

  def createOrganization(organization: OrganizationSeed): Future[Organization]

  def createRelationship(
    taxCode: String,
    organizationId: String,
    operationRole: String,
    platformRole: String
  ): Future[Unit]

  def retrieveRelationship(from: Option[String], to: Option[String]): Future[Relationships]

  def getInstitutionRelationships(institutionId: String): Future[Relationships]

  def createToken(relationshipsSeed: RelationshipsSeed, documentHash: String): Future[TokenText]

  def consumeToken(token: String): Future[Unit]

  def invalidateToken(token: String): Future[Unit]
}
