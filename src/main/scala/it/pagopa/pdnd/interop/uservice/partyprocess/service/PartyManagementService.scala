package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.partymanagement.client.model._

import scala.concurrent.Future

trait PartyManagementService {
  def retrievePerson(taxCode: String): Future[Person]
  def retrieveOrganization(organizationId: String): Future[Organization]
  def createPerson(person: PersonSeed): Future[Person]
  def createOrganization(organization: OrganizationSeed): Future[Organization]
  def createRelationShip(taxCode: String, organizationId: String, role: String): Future[Unit]
  def retrieveRelationship(from: String): Future[RelationShips]
  def createToken(manager: TokenUser, delegate: TokenUser): Future[TokenText]
  def consumeToken(token: String): Future[Unit]
  def invalidateToken(token: String): Future[Unit]
}
