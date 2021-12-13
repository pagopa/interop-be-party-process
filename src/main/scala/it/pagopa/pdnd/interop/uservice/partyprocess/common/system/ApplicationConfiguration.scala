package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}

object ApplicationConfiguration {
  lazy val config: Config = ConfigFactory.load()

  def serverPort: Int                 = config.getInt("uservice-party-process.port")
  def getPartyManagementUrl: String   = config.getString("services.party-management")
  def getPartyProxyUrl: String        = config.getString("services.party-proxy")
  def getAttributeRegistryUrl: String = config.getString("services.attribute-registry")
  def getUserRegistryURL: String      = config.getString("services.user-registry-management")

  def destinationMails: Option[Seq[String]] =
    Option(System.getenv("DESTINATION_MAILS")).map(_.split(",").toSeq)

  def mailTemplatePath: String   = config.getString("uservice-party-process.mail-template.path")
  def userRegistryApiKey: String = config.getString("uservice-party-process.user-registry-api-key")

  def onboardingMailPlaceholdersReplacement: Map[String, String] = {
    Map(
      config.getString("uservice-party-process.mail-template.confirm-token.name") -> config.getString(
        "uservice-party-process.mail-template.confirm-token.placeholder"
      ),
      config.getString("uservice-party-process.mail-template.reject-token.name") -> config.getString(
        "uservice-party-process.mail-template.reject-token.placeholder"
      )
    )
  }

  def storageContainer: String = config.getString("pdnd-interop-commons.storage.container")
}
