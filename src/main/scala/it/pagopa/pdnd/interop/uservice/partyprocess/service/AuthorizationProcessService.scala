package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.authorizationprocess.client.model.ValidJWT

import scala.concurrent.Future

trait AuthorizationProcessService {
  def validateToken(bearerToken: String): Future[ValidJWT]
}
