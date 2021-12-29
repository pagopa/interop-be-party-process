package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}

object ApplicationConfiguration {
  lazy val config: Config = ConfigFactory.load()

  def serverPort: Int               = config.getInt("uservice-party-process.port")
  def getPartyManagementUrl: String = config.getString("services.party-management")
  def getPartyProxyUrl: String      = config.getString("services.party-proxy")
  def getUserRegistryURL: String    = config.getString("services.user-registry-management")

  /*
     _________  ________  ________  ________
    |\___   ___\\   __  \|\   ___ \|\   __  \
    \|___ \  \_\ \  \|\  \ \  \_|\ \ \  \|\  \
         \ \  \ \ \  \\\  \ \  \ \\ \ \  \\\  \
          \ \  \ \ \  \\\  \ \  \_\\ \ \  \\\  \
           \ \__\ \ \_______\ \_______\ \_______\
            \|__|  \|_______|\|_______|\|_______|
      TODO THIS IS A TEMPORARY SOLUTION!
      TODO MOVE TO PARTY REGISTRY MOCK
   */
  def destinationMails: Option[Seq[String]] =
    Option(config.getString("uservice-party-process.destination-mails")).map(_.split(",").toSeq)

  def euListOfTrustedListsURL: String = config.getString("uservice-party-process.eu_list_of_trusted_lists_url")
  def euOfficialJournalUrl: String    = config.getString("uservice-party-process.eu_official_journal_url")

  def mailTemplatePath: String   = config.getString("uservice-party-process.mail-template.path")
  def userRegistryApiKey: String = config.getString("uservice-party-process.user-registry-api-key")

  def onboardingMailPlaceholdersReplacement: Map[String, String] = {
    Map(
      config.getString("uservice-party-process.mail-template.onboarding-mail-placeholders.confirm-token.name") -> config
        .getString("uservice-party-process.mail-template.onboarding-mail-placeholders.confirm-token.placeholder"),
      config.getString("uservice-party-process.mail-template.onboarding-mail-placeholders.reject-token.name") -> config
        .getString("uservice-party-process.mail-template.onboarding-mail-placeholders.reject-token.placeholder")
    )
  }
  def onboardingMailUserNamePlaceholder: String =
    config.getString("uservice-party-process.mail-template.onboarding-mail-placeholders.userName")
  def onboardingMailUserSurnamePlaceholder: String =
    config.getString("uservice-party-process.mail-template.onboarding-mail-placeholders.userSurname")
  def onboardingMailTaxCodePlaceholder: String =
    config.getString("uservice-party-process.mail-template.onboarding-mail-placeholders.userTaxCode")
  def onboardingMailProductPlaceholder: String =
    config.getString("uservice-party-process.mail-template.onboarding-mail-placeholders.product")

  def storageContainer: String = config.getString("pdnd-interop-commons.storage.container")
}
