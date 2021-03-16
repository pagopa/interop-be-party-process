package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.{
  Organization,
  OrganizationSeed,
  Person,
  PersonSeed,
  Role,
  TokenText
}

import java.util.UUID
import scala.concurrent.Future

trait PartyManagementService {
  def getPerson(taxCode: String): Future[Person]
  def getOrganization(organizationId: String): Future[Organization]
  def createPerson(person: PersonSeed): Future[Person]
  def createOrganization(organization: OrganizationSeed): Future[Organization]
  def createRelationShip(taxCode: String, organizationId: String, role: Role): Future[Unit]
  def createToken(from: UUID, to: UUID, role: Role): Future[TokenText]
  def consumeToken(token: String): Future[Unit]
  def invalidateToken(token: String): Future[Unit]
}
