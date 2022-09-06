package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.partyprocess.model.UserRegistryUser
import it.pagopa.userreg.client.model.UserId

import java.util.UUID
import scala.concurrent.Future

trait UserRegistryManagementService {
  def getUserById(userId: UUID)(implicit context: Seq[(String, String)]): Future[UserRegistryUser]
  def getUserWithEmailById(userId: UUID)(implicit context: Seq[(String, String)]): Future[UserRegistryUser]
  def getUserByExternalId(externalId: String)(implicit context: Seq[(String, String)]): Future[UserRegistryUser]
  def getUserIdByExternalId(externalId: String)(implicit context: Seq[(String, String)]): Future[UserId]
}
