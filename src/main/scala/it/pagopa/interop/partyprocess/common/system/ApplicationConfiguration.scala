package it.pagopa.interop.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}

import scala.util.Try

object ApplicationConfiguration {
  val config: Config = ConfigFactory.load()

  val serverPort: Int               = config.getInt("party-process.port")
  val getPartyManagementUrl: String = config.getString("party-process.services.party-management")
  val getPartyProxyUrl: String      = config.getString("party-process.services.party-proxy")
  val getUserRegistryURL: String    = config.getString("party-process.services.user-registry-management")

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
  val destinationMails: Option[Seq[String]] =
    Try(config.getString("party-process.destination-mails")).toOption.map(_.split(",").toSeq)

  val signatureValidationEnabled: Boolean =
    config.getBoolean("party-process.signature-validation-enabled")

  val euListOfTrustedListsURL: String = config.getString("party-process.eu_list_of_trusted_lists_url")
  val euOfficialJournalUrl: String    = config.getString("party-process.eu_official_journal_url")

  val mailTemplatePath: String   = config.getString("party-process.mail-template.path")
  val userRegistryApiKey: String = config.getString("party-process.user-registry-api-key")

  val onboardingMailPlaceholdersReplacement: Map[String, String] = {
    Map(
      config.getString("party-process.mail-template.onboarding-mail-placeholders.confirm-token.name") -> config
        .getString("party-process.mail-template.onboarding-mail-placeholders.confirm-token.placeholder"),
      config.getString("party-process.mail-template.onboarding-mail-placeholders.reject-token.name")  -> config
        .getString("party-process.mail-template.onboarding-mail-placeholders.reject-token.placeholder")
    )
  }
  val onboardingMailUserNamePlaceholder: String                  =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.userName")
  val onboardingMailUserSurnamePlaceholder: String               =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.userSurname")
  val onboardingMailTaxCodePlaceholder: String                   =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.userTaxCode")
  val onboardingMailProductPlaceholder: String                   =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.product")

  val storageContainer: String = config.getString("party-process.storage.container")

  val interopAudience: Set[String] =
    config.getString("party-process.jwt.audience").split(",").toSet.filter(_.nonEmpty)

  require(interopAudience.nonEmpty, "Audience cannot be empty")

}
