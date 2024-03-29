package it.pagopa.interop.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

object ApplicationConfiguration {
  val config: Config = ConfigFactory.load()

  val serverPort: Int               = config.getInt("party-process.port")
  val getPartyManagementUrl: String = config.getString("party-process.services.party-management")
  val getPartyProxyUrl: String      = config.getString("party-process.services.party-proxy")
  val getUserRegistryURL: String    = config.getString("party-process.services.user-registry-management")
  val getProductURL: String         = config.getString("party-process.services.product-management")
  val getGeoTaxonomyURL: String     = config.getString("party-process.services.geo-taxonomy")

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

  val selfcareUrl: String = config.getString("party-process.eu_official_journal_url")

  val onboardingCompleteMailTemplatePath: String       =
    config.getString("party-process.mail-template.onboarding-complete-mail-placeholders.path")
  val onboardingCompleteProductName: String            =
    config.getString("party-process.mail-template.onboarding-complete-mail-placeholders.productName")
  val onboardingCompleteSelfcareUrlPlaceholder: String =
    config.getString("party-process.mail-template.onboarding-complete-mail-placeholders.selfcare.placeholder")
  val onboardingCompleteSelfcareUrlName: String        =
    config.getString("party-process.mail-template.onboarding-complete-mail-placeholders.selfcare.name")

  val emailLogoPath: String      = config.getString("party-process.logo.path")
  val mailTemplatePath: String   = config.getString("party-process.mail-template.onboarding-mail-placeholders.path")
  val userRegistryApiKey: String = config.getString("party-process.user-registry-api-key")
  val externalApiKey: String     = config.getString("party-process.external-api-key")
  val externalApiUser: String    = config.getString("party-process.external-api-user")

  val sendEmailToInstitution: Boolean     =
    config.getBoolean("party-process.mail-template.onboarding_send_email_to_institution")
  val institutionAlternativeEmail: String =
    config.getString("party-process.mail-template.onboarding_institution_alternative_email")

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
  val onboardingMailProductIdPlaceholder: String                 =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.productId")
  val onboardingMailProductNamePlaceholder: String               =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.productName")

  val onboardingMailInstitutionInfoInstitutionTypePlaceholder: String =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.institutionType")
  val onboardingMailInstitutionInfoDescriptionPlaceholder: String     =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.description")
  val onboardingMailInstitutionInfoDigitalAddressPlaceholder: String  =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.digitalAddress")
  val onboardingMailInstitutionInfoAddressPlaceholder: String         =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.address")
  val onboardingMailInstitutionInfoZipCodePlaceholder: String         =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.zipCode")
  val onboardingMailInstitutionInfoTaxCodePlaceholder: String         =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.institution-info.taxCode")

  val onboardingMailBillingPricingPlanPlaceholder: String   =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.billing.pricingPlan")
  val onboardingMailBillingVatNumberPlaceholder: String     =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.billing.vatNumber")
  val onboardingMailBillingRecipientCodePlaceholder: String =
    config.getString("party-process.mail-template.onboarding-mail-placeholders.billing.recipientCode")

  val onboardingMailNotificationPlaceholdersReplacement: Map[String, String] = {
    Map(
      config
        .getString("party-process.mail-template.onboarding-notification-mail-placeholders.confirm-token.name") -> config
        .getString("party-process.mail-template.onboarding-notification-mail-placeholders.confirm-token.placeholder")
    )
  }
  val onboardingMailNotificationProductNamePlaceholder: String               =
    config.getString("party-process.mail-template.onboarding-notification-mail-placeholders.productName")
  val onboardingMailNotificationRequesterNamePlaceholder: String             =
    config.getString("party-process.mail-template.onboarding-notification-mail-placeholders.requesterName")
  val onboardingMailNotificationRequesterSurnamePlaceholder: String          =
    config.getString("party-process.mail-template.onboarding-notification-mail-placeholders.requesterSurname")
  val onboardingMailNotificationInstitutionNamePlaceholder: String           =
    config.getString("party-process.mail-template.onboarding-notification-mail-placeholders.institutionName")
  val onboardingMailNotificationInstitutionAdminEmailAddress: String         =
    config.getString("party-process.mail-template.onboarding-notification-mail-placeholders.adminEmail")
  val onboardingNotificationMailTemplatePath: String                         =
    config.getString("party-process.mail-template.onboarding-notification-mail-placeholders.path")
  val onboardingNotificationMailInstitutionGeoTaxonomies: String             =
    config.getString("party-process.mail-template.onboarding-notification-mail-placeholders.institutionGeoTaxonomies")

  val onboardingRejectMailProductNamePlaceholder: String   =
    config.getString("party-process.mail-template.onboarding-reject-mail-placeholders.productName")
  val onboardingRejectMailTemplatePath: String             =
    config.getString("party-process.mail-template.onboarding-reject-mail-placeholders.path")
  val onboardingRejectMailOnboardingUrlPlaceholder: String =
    config.getString("party-process.mail-template.onboarding-reject-mail-placeholders.onboardingUrlPlaceholder")
  val onboardingRejectMailOnboardingUrlValue: String       =
    config.getString("party-process.mail-template.onboarding-reject-mail-placeholders.onboardingUrlValue")

  val onboardingAutoCompleteMailTemplatePath: String =
    config.getString("party-process.mail-template.onboarding-auto-complete.path")

  val storageContainer: String = config.getString("party-process.storage.container")

  val jwtAudience: Set[String] = config.getString("party-process.jwt.audience").split(",").toSet.filter(_.nonEmpty)

  val confirmTokenTimeout: FiniteDuration =
    Duration.fromNanos(config.getDuration("party-process.confirm-token-timeout").toNanos)

  val arubaServiceUrl: String        = config.getString("party-process.aruba.serviceUrl")
  val arubaTypeOtpAuth: String       = config.getString("party-process.aruba.typeOtpAuth")
  val arubaOtpPwd: String            = config.getString("party-process.aruba.otpPwd")
  val arubaUser: String              = config.getString("party-process.aruba.user")
  val arubaDelegatedUser: String     = config.getString("party-process.aruba.delegatedUser")
  val arubaDelegatedPassword: String = config.getString("party-process.aruba.delegatedPassword")
  val arubaDelegatedDomain: String   = config.getString("party-process.aruba.delegatedDomain")

  val pagopaSignatureEnabled: Boolean = config.getBoolean("party-process.pagopaSignature.enabled")
  val pagopaSigner: String            = config.getString("party-process.pagopaSignature.signer")
  val pagopaSignerLocation: String    = config.getString("party-process.pagopaSignature.location")

  val pagopaSignatureOnboardingEnabled: Boolean       =
    config.getBoolean("party-process.pagopaSignature.apply.onboarding.enabled")
  val pagopaSignatureOnboardingTemplateReason: String =
    config.getString("party-process.pagopaSignature.apply.onboarding.templateReason")
}
