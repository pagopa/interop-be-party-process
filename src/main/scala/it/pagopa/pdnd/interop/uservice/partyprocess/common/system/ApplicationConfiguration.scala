package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}

object ApplicationConfiguration {
  lazy val config: Config = ConfigFactory.load()

  def getPartyManagementUrl: String = {
    val partyManagementUrl: String = config.getString("services.party-management")
    s"$partyManagementUrl/pdnd-interop-uservice-party-management/0.0.1"
  }

  def getPartyProxyUrl: String = {
    val partyProxyUrl: String = config.getString("services.party-proxy")
    s"$partyProxyUrl/pdnd-interop-uservice-party-registry-proxy/0.0.1"
  }
}
