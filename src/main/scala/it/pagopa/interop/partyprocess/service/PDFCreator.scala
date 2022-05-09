package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.partymanagement.client.model.Institution
import it.pagopa.interop.partyprocess.model.User

import java.io.File
import scala.concurrent.Future

trait PDFCreator {
  def createContract(contractTemplate: String, manager: User, users: Seq[User], organization: Institution): Future[File]
}
