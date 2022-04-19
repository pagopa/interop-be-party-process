package it.pagopa.interop.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}

import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Try

object ApplicationConfiguration {
  lazy val config: Config = ConfigFactory.load()

  lazy val serverPort: Int               = config.getInt("party-process.port")
  lazy val getPartyManagementUrl: String = config.getString("party-process.services.party-management")
  lazy val getPartyProxyUrl: String      = config.getString("party-process.services.party-proxy")
  lazy val getUserRegistryURL: String    = config.getString("party-process.services.user-registry-management")

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
  lazy val destinationMails: Option[Seq[String]] =
    Try(config.getString("party-process.destination-mails")).toOption.map(_.split(",").toSeq)

  lazy val signatureValidationEnabled: Boolean =
    config.getBoolean("party-process.signature-validation-enabled")

  lazy val euListOfTrustedListsURL: String = config.getString("party-process.eu_list_of_trusted_lists_url")
  lazy val euOfficialJournalUrl: String    = config.getString("party-process.eu_official_journal_url")

  lazy val mailTemplatePath: String   = config.getString("party-process.mail-template.path")
  lazy val userRegistryApiKey: String = config.getString("party-process.user-registry-api-key")

  lazy val onboardingMailPlaceholdersReplacement: Map[String, String] = {
    Map(
      config.getString("party-process.mail-template.onboarding-mail-placeholders.confirm-token.name") -> config
        .getString("party-process.mail-template.onboarding-mail-placeholders.confirm-token.placeholder"),
      config.getString("party-process.mail-template.onboarding-mail-placeholders.reject-token.name")  -> config
        .getString("party-process.mail-template.onboarding-mail-placeholders.reject-token.placeholder")
    )
  }
  lazy val onboardingMailUserNamePlaceholder: String                  =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.userName")
  lazy val onboardingMailUserSurnamePlaceholder: String               =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.userSurname")
  lazy val onboardingMailTaxCodePlaceholder: String                   =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.userTaxCode")
  lazy val onboardingMailProductPlaceholder: String                   =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.product")

  lazy val onboardingMailInstitutionInfoInstitutionTypePlaceholder: String =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.institutionType")
  lazy val onboardingMailInstitutionInfoDescriptionPlaceholder: String     =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.description")
  lazy val onboardingMailInstitutionInfoDigitalAddressPlaceholder: String  =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.digitalAddress")
  lazy val onboardingMailInstitutionInfoAddressPlaceholder: String         =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.address")
  lazy val onboardingMailInstitutionInfoTaxCodePlaceholder: String         =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.taxCode")

  lazy val onboardingMailBillingPricingPlanPlaceholder: String   =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.billing.pricingPlan")
  lazy val onboardingMailBillingVatNumberPlaceholder: String     =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.billing.vatNumber")
  lazy val onboardingMailBillingRecipientCodePlaceholder: String =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.billing.recipientCode")

  lazy val storageContainer: String = config.getString("party-process.storage.container")

  lazy val jwtAudience: Set[String] = config.getStringList("party-process.jwt.audience").asScala.toSet
}
