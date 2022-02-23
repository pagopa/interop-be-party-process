package it.pagopa.interop.partyprocess.service

import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{User, UserSeed}

import java.util.UUID
import scala.concurrent.Future

trait UserRegistryManagementService {
  def getUserById(userId: UUID): Future[User]
  def getUserByExternalId(externalId: String): Future[User]
  def createUser(seed: UserSeed): Future[User]
  def updateUser(seed: UserSeed): Future[User]
}
