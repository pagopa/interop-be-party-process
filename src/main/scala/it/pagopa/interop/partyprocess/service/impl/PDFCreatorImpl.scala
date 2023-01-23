package it.pagopa.interop.partyprocess.service.impl

import com.openhtmltopdf.util.XRLog
import com.typesafe.scalalogging.Logger
import it.pagopa.interop.commons.files.service.PDFManager
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.interop.commons.utils.TypeConversions.OptionOps
import it.pagopa.interop.partymanagement.client.model.Institution
import it.pagopa.interop.partyprocess.api.impl.OnboardingSignedRequest
import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration
import it.pagopa.interop.partyprocess.model.{GeographicTaxonomy, PartyRole, User}
import it.pagopa.interop.partyprocess.service.PDFCreator
import it.pagopa.selfcare.commons.utils.crypto.model.SignatureInformation
import it.pagopa.selfcare.commons.utils.crypto.service.PadesSignService

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.Future
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.util.Try

class PDFCreatorImpl(padesSignService: PadesSignService) extends PDFCreator with PDFManager {

  private val logger = Logger.takingImplicit[ContextFieldsToLog](this.getClass)

  // Suppressing openhtmltopdf log
  XRLog.listRegisteredLoggers.asScala.foreach((logger: String) =>
    XRLog.setLevel(logger, java.util.logging.Level.SEVERE)
  )

  def createContract(
    contractTemplate: String,
    manager: User,
    users: Seq[User],
    institution: Institution,
    onboardingRequest: OnboardingSignedRequest,
    geoTaxonomies: Seq[GeographicTaxonomy]
  )(implicit contexts: Seq[(String, String)]): Future[File] =
    Future.fromTry {
      for {
        file <- createTempFile
        data <- onboardingRequest.productId match {
          case "prod-pagopa" if institution.institutionType.getOrElse("").equals("PSP") =>
            setupPSPData(manager, users, institution, onboardingRequest, geoTaxonomies)
          case "prod-io" | "prod-io-premium" => setupProdIOData(manager, users, institution, onboardingRequest, geoTaxonomies)
          case _         => setupData(manager, users, institution, onboardingRequest, geoTaxonomies)
        }
        pdf  <- getPDFAsFile(file.toPath, contractTemplate, data)
        signedPdf = signContract(institution, onboardingRequest, pdf)
      } yield signedPdf
    }

  private def createTempFile: Try[File] = {
    Try {
      val fileTimestamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
      File.createTempFile(s"${fileTimestamp}_${UUID.randomUUID().toString}_contratto_interoperabilita.", ".pdf")
    }
  }

  private def signContract(institution: Institution, onboardingRequest: OnboardingSignedRequest, pdf: File)(implicit
    contexts: Seq[(String, String)]
  ): File = {
    if (ApplicationConfiguration.pagopaSignatureOnboardingEnabled && onboardingRequest.applyPagoPaSign)
      signPdf(pdf, buildSignatureReason(institution, onboardingRequest))
    else {
      if (!ApplicationConfiguration.pagopaSignatureOnboardingEnabled && onboardingRequest.applyPagoPaSign) {
        logger.info("Skipping PagoPA contract pdf sign due to global disabling")
      }
      pdf
    }
  }

  def buildSignatureReason(institution: Institution, onboardingSignedRequest: OnboardingSignedRequest): String = {
    ApplicationConfiguration.pagopaSignatureOnboardingTemplateReason
      .replace("${institutionName}", institution.description)
      .replace("${productName}", onboardingSignedRequest.productName)
  }

  def buildSignatureInfo(signReason: String): SignatureInformation = {
    new SignatureInformation(
      ApplicationConfiguration.pagopaSigner,
      ApplicationConfiguration.pagopaSignerLocation,
      signReason
    )
  }

  def signPdf(pdf: File, signReason: String)(implicit contexts: Seq[(String, String)]): File = {
    if (ApplicationConfiguration.pagopaSignatureEnabled) {
      logger.info("Signing input file {} using reason {}", pdf.getName, signReason)
      val signedPdf = new File(pdf.getAbsolutePath.replace(".pdf", "-signed.pdf"))
      padesSignService.padesSign(pdf, signedPdf, buildSignatureInfo(signReason))
      signedPdf
    } else {
      logger.info("Skipping PagoPA pdf sign due to global disabling")
      pdf
    }
  }

  private def setupData(
    manager: User,
    users: Seq[User],
    institution: Institution,
    onboardingRequest: OnboardingSignedRequest,
    geoTaxonomies: Seq[GeographicTaxonomy]
  ): Try[Map[String, String]] = {
    for {
      managerEmail <- manager.email.toTry("Manager email not found")
    } yield Map(
      "institutionName"      -> getInstitutionName(institution, onboardingRequest),
      "institutionTaxCode"   -> onboardingRequest.institutionUpdate.flatMap(_.taxCode).getOrElse(institution.taxCode),
      "originId"             -> institution.originId,
      "institutionMail"      -> institution.digitalAddress,
      "managerName"          -> manager.name,
      "managerSurname"       -> manager.surname,
      "managerTaxCode"       -> manager.taxCode,
      "managerEmail"         -> managerEmail,
      "manager"              -> userToText(manager),
      "delegates"            -> delegatesToText(users),
      "institutionType"      -> getInstitutionType(institution, onboardingRequest),
      "address"              -> onboardingRequest.institutionUpdate.flatMap(_.address).getOrElse(institution.address),
      "zipCode"              -> onboardingRequest.institutionUpdate.flatMap(_.zipCode).getOrElse(institution.zipCode),
      "pricingPlan"          -> onboardingRequest.pricingPlan.getOrElse(""),
      "institutionVatNumber" -> onboardingRequest.billing.map(_.vatNumber).getOrElse(""),
      "institutionRecipientCode" -> onboardingRequest.billing.map(_.recipientCode).getOrElse(""),
      "isPublicServicesManager"  -> onboardingRequest.billing
        .flatMap(_.publicServices)
        .map(if (_) "Y" else "N")
        .getOrElse(""),
      "institutionGeoTaxonomies" -> geoTaxonomies.map(_.desc).mkString(", ")
    )

  }

  private def setupPSPData(
    manager: User,
    users: Seq[User],
    institution: Institution,
    onboardingRequest: OnboardingSignedRequest,
    geoTaxonomies: Seq[GeographicTaxonomy]
  ): Try[Map[String, String]] = {
    for {
      managerEmail <- manager.email.toTry("Manager email not found")
    } yield Map(
      "institutionName"      -> getInstitutionName(institution, onboardingRequest),
      "institutionTaxCode"   -> onboardingRequest.institutionUpdate.flatMap(_.taxCode).getOrElse(institution.taxCode),
      "originId"             -> institution.originId,
      "institutionMail"      -> institution.digitalAddress,
      "managerName"          -> manager.name,
      "managerSurname"       -> manager.surname,
      "managerTaxCode"       -> manager.taxCode,
      "managerEmail"         -> managerEmail,
      "manager"              -> userToText(manager),
      "delegates"            -> delegatesToText(users),
      "institutionType"      -> getInstitutionType(institution, onboardingRequest),
      "address"              -> onboardingRequest.institutionUpdate.flatMap(_.address).getOrElse(institution.address),
      "zipCode"              -> onboardingRequest.institutionUpdate.flatMap(_.zipCode).getOrElse(institution.zipCode),
      "pricingPlan"          -> onboardingRequest.pricingPlan.getOrElse(""),
      "institutionVatNumber" -> onboardingRequest.billing.map(_.vatNumber).getOrElse(""),
      "institutionRecipientCode" -> onboardingRequest.billing.map(_.recipientCode).getOrElse(""),
      "isPublicServicesManager"  -> onboardingRequest.billing
        .flatMap(_.publicServices)
        .map(if (_) "Y" else "N")
        .getOrElse(""),
      "institutionGeoTaxonomies" -> geoTaxonomies.map(_.desc).mkString(", "),
      "legalRegisterNumber"      -> institution.paymentServiceProvider.flatMap(_.legalRegisterNumber).getOrElse(""),
      "vatNumberGroup"           -> institution.paymentServiceProvider
        .flatMap(_.vatNumberGroup)
        .map(if (_) "partita iva di gruppo " else "")
        .getOrElse(""),
      "institutionRegister"      -> institution.paymentServiceProvider.flatMap(_.businessRegisterNumber).getOrElse(""),
      "institutionAbi"           -> institution.paymentServiceProvider.flatMap(_.abiCode).getOrElse(""),
      "dataProtectionOfficerAddress" -> institution.dataProtectionOfficer.flatMap(_.address).getOrElse(""),
      "dataProtectionOfficerEmail"   -> institution.dataProtectionOfficer.flatMap(_.email).getOrElse(""),
      "dataProtectionOfficerPec"     -> institution.dataProtectionOfficer.flatMap(_.pec).getOrElse(""),
      "managerPEC"                   -> manager.email.getOrElse("")
    )
  }

  private def setupProdIOData(
    manager: User,
    users: Seq[User],
    institution: Institution,
    onboardingRequest: OnboardingSignedRequest,
    geoTaxonomies: Seq[GeographicTaxonomy]
  ): Try[Map[String, String]] = {
    for {
      managerEmail <- manager.email.toTry("Manager email not found")
    } yield Map(
      "institutionName"      -> getInstitutionName(institution, onboardingRequest),
      "institutionTaxCode"   -> onboardingRequest.institutionUpdate.flatMap(_.taxCode).getOrElse(institution.taxCode),
      "originId"             -> institution.originId,
      "institutionMail"      -> institution.digitalAddress,
      "managerName"          -> manager.name,
      "managerSurname"       -> manager.surname,
      "managerTaxCode"       -> manager.taxCode,
      "managerEmail"         -> managerEmail,
      "manager"              -> userToText(manager),
      "delegates"            -> delegatesToText(users),
      "institutionType"      -> getInstitutionType(institution, onboardingRequest),
      "address"              -> onboardingRequest.institutionUpdate.flatMap(_.address).getOrElse(institution.address),
      "zipCode"              -> onboardingRequest.institutionUpdate.flatMap(_.zipCode).getOrElse(institution.zipCode),
      "institutionTypeCode"  -> getInstitutionTypeCode(institution, onboardingRequest),
      "pricingPlan"          -> (onboardingRequest.pricingPlan.getOrElse("") match {
        case "FA"                                                 => "FAST"
        case _ if (onboardingRequest.productId.equals("prod-io")) => "BASE"
        case _                                                    => "PREMIUM"
      }).mkString,
      "institutionVatNumber" -> onboardingRequest.billing.map(_.vatNumber).getOrElse(""),
      "institutionRecipientCode"      -> onboardingRequest.billing.map(_.recipientCode).getOrElse(""),
      "isPublicServicesManager"       -> onboardingRequest.billing
        .flatMap(_.publicServices)
        .map(if (_) "Y" else "N")
        .getOrElse(""),
      "institutionGeoTaxonomies"      -> geoTaxonomies.map(_.desc).mkString(", "),
      "originIdLabelValue"            -> (if (institution.origin.equals("IPA"))
                                 s"""
                                    |<li class="c19 c39 li-bullet-0"><span class="c1">codice di iscrizione all&rsquo;Indice delle Pubbliche Amministrazioni e dei gestori di pubblici servizi (I.P.A.) <span class="c3">${institution.originId}</span> </span><span class="c1"></span></li>
                                    |""".stripMargin
                               else "").mkString,
      "institutionRegisterLabelValue" -> institution.paymentServiceProvider
        .flatMap(_.businessRegisterNumber)
        .map(number =>
          if (!number.isEmpty)
            s"""
               |<li class="c19 c39 li-bullet-0"><span class="c1">codice di iscrizione all&rsquo;Indice delle Pubbliche Amministrazioni e dei gestori di pubblici servizi (I.P.A.) <span class="c3">$number</span> </span><span class="c1"></span></li>
               |""".stripMargin
          else ""
        )
        .getOrElse(""),
      "GPSinstitutionName"            -> (if (getInstitutionTypeCode(institution, onboardingRequest).equals("GSP"))
                                 getInstitutionName(institution, onboardingRequest)
                               else "_______________").mkString,
      "GPSmanagerName"    -> (if (getInstitutionTypeCode(institution, onboardingRequest).equals("GSP")) manager.name
                           else "_______________").mkString,
      "GPSmanagerSurname" -> (if (getInstitutionTypeCode(institution, onboardingRequest).equals("GSP")) manager.surname
                              else "_______________").mkString,
      "GPSmanagerTaxCode" -> (if (getInstitutionTypeCode(institution, onboardingRequest).equals("GSP")) manager.taxCode
                              else "_______________").mkString
    )
  }

  private def getInstitutionName(institution: Institution, onboardingRequest: OnboardingSignedRequest) = {
    onboardingRequest.institutionUpdate
      .flatMap(_.description)
      .getOrElse(institution.description)
  }

  private def getInstitutionType(institution: Institution, onboardingRequest: OnboardingSignedRequest) = {
    onboardingRequest.institutionUpdate
      .flatMap(_.institutionType)
      .orElse(institution.institutionType)
      .map(transcodeInstitutionType)
      .getOrElse("")
  }

  private def getInstitutionTypeCode(institution: Institution, onboardingRequest: OnboardingSignedRequest) = {
    onboardingRequest.institutionUpdate
      .flatMap(_.institutionType)
      .orElse(institution.institutionType)
      .getOrElse("")
  }

  def delegatesToText(users: Seq[User]): String = {
    val delegates: Seq[User] = users.filter(_.role == PartyRole.DELEGATE)

    delegates
      .map { delegate =>
        s"""
           |<p class="c141"><span class="c6">Nome e Cognome: ${userToText(delegate)}&nbsp;</span></p>
           |<p class="c141"><span class="c6">Codice Fiscale: ${delegate.taxCode}</span></p>
           |<p class="c141"><span class="c6">Amm.ne/Ente/Societ&agrave;: </span></p>
           |<p class="c141"><span class="c6">Qualifica/Posizione: </span></p>
           |<p class="c141"><span class="c6">e-mail: ${delegate.email.getOrElse("")}&nbsp;</span></p>
           |<p class="c141"><span class="c6">PEC: &nbsp;</span></p>
           |""".stripMargin
      }
      .mkString("\n")
  }

  private def userToText(user: User): String = {
    s"${user.name} ${user.surname}"
  }

  private def transcodeInstitutionType(institutionType: String): String = {
    institutionType.toUpperCase match {
      case "PA"  => "Pubblica Amministrazione"
      case "GSP" => "Gestore di servizi pubblici"
      case "SCP" => "Società a controllo pubblico"
      case "PT"  => "Partner tecnologico"
      case "PSP" => "Prestatori Servizi di Pagamento"
    }
  }
}
