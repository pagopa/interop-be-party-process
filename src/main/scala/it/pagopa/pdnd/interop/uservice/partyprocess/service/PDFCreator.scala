package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.Organization
import it.pagopa.pdnd.interop.uservice.partyprocess.model.User

import java.io.File
import scala.concurrent.Future

trait PDFCreator {
  def createContract(contractTemplate: String, users: Seq[User], organization: Organization): Future[File]
}
