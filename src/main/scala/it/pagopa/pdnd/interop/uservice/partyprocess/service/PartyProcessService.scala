package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.Institution

import scala.concurrent.Future

trait PartyProcessService {
  def getInstitution(institutionId: String): Future[Institution]

  def searchInstitution(text: String, offset: Int, limit: Int): Future[List[Institution]]
}
