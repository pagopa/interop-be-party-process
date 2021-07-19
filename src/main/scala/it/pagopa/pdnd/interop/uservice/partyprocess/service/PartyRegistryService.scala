package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.partyregistryproxy.client.model.{Categories, Institution}

import scala.concurrent.Future

trait PartyRegistryService {
  def getInstitution(institutionId: String): Future[Institution]
  def getCategories: Future[Categories]
  def searchInstitution(text: String, offset: Int, limit: Int): Future[List[Institution]]
}
