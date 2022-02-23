package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.partyregistryproxy.client.model.{Category, Institution}

import scala.concurrent.Future

trait PartyRegistryService {
  def getInstitution(institutionId: String)(bearerToken: String): Future[Institution]
  def getCategory(origin: String, code: String)(bearerToken: String): Future[Category]
  def searchInstitution(text: String, offset: Int, limit: Int)(bearerToken: String): Future[List[Institution]]
}
