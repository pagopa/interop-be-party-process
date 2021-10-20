package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.User

import java.util.UUID
import scala.concurrent.Future

trait UserRegistryManagementService {
  def getUserById(userId: UUID): Future[User]
}
