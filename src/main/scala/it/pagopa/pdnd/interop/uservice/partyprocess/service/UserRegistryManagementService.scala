package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{User, UserSeed}

import java.util.UUID
import scala.concurrent.Future

trait UserRegistryManagementService {
  def getUserById(userId: UUID)(bearerToken: String): Future[User]
  def getUserByExternalId(externalId: String)(bearerToken: String): Future[User]
  def createUser(seed: UserSeed)(bearerToken: String): Future[User]
  def updateUser(seed: UserSeed)(bearerToken: String): Future[User]
}
