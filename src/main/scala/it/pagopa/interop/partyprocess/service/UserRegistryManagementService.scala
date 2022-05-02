package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.partyprocess.model.UserRegistryUser
import it.pagopa.userreg.client.model.{SaveUserDto, UserId, UserResource}

import java.util.UUID
import scala.concurrent.Future

trait UserRegistryManagementService {
  def getUserById(userId: UUID): Future[UserRegistryUser]
  def getUserByExternalId(externalId: String): Future[UserRegistryUser]
  def getUserIdByExternalId(externalId: String): Future[UserId]
  def createUser(seed: SaveUserDto): Future[UserId]
  def updateUser(seed: SaveUserDto): Future[UserId]
}
