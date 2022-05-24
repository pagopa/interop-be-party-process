package it.pagopa.interop.partyprocess.server.impl

import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

// Enabled in application.conf
class HealthCheck() extends (() => Future[Boolean]) {

  private val log = Logger(this.getClass())

  override def apply(): Future[Boolean] = {
    log.trace("HealthCheck called")
    Future.successful(true)
  }
}

class LiveCheck() extends (() => Future[Boolean]) {

  private val log = Logger(this.getClass())

  override def apply(): Future[Boolean] = {
    log.trace("LiveCheck called")
    Future.successful(true)
  }
}
