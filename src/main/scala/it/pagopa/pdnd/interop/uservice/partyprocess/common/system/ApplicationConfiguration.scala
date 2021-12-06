package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}

object ApplicationConfiguration {
  lazy val config: Config = ConfigFactory.load()

  def serverPort: Int = {
    config.getInt("uservice-party-process.port")
  }

  def getPartyManagementUrl: String   = config.getString("services.party-management")
  def getPartyProxyUrl: String        = config.getString("services.party-proxy")
  def getAttributeRegistryUrl: String = config.getString("services.attribute-registry")

  def getUserRegistryURL: String = config.getString("services.user-registry-management")
  def userRegistryApiKey: String =
    Option(System.getenv("USER_REGISTRY_API_KEY")).getOrElse(throw new RuntimeException("No user registry api key set"))

  def destinationMails: Seq[String] = {
    Option(System.getenv("DESTINATION_MAILS"))
      .map(_.split(",").toSeq)
      .getOrElse(throw new RuntimeException("No destination email set"))
  }

  def lotlUrl: String = config.getString("uservice-party-process.lotl_url")
  def ojUrl: String   = config.getString("uservice-party-process.oj_orl")

  def mailTemplatePath: String = config.getString("uservice-party-process.mail-template.path")

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

}
