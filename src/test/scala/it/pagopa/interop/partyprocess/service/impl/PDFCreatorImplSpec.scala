package it.pagopa.interop.partyprocess.service.impl

import com.typesafe.config.{Config, ConfigFactory}
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.SpecConfig.testDataConfig
import it.pagopa.interop.partyprocess.api.impl.OnboardingSignedRequest
import it.pagopa.interop.partyprocess.model._
import it.pagopa.interop.partyprocess.service.PDFCreator
import it.pagopa.selfcare.commons.utils.crypto.model.SignatureInformation
import it.pagopa.selfcare.commons.utils.crypto.service.PadesSignService
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.scalamock.scalatest.MockFactory
import org.scalatest.Assertion
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.File

class PDFCreatorImplSpec extends AnyWordSpecLike with Matchers with ScalaFutures with MockFactory {

  val config: Config = ConfigFactory
    .parseResourcesAnySyntax("application-test")
    .withFallback(testDataConfig)

  val mockPadesSignService: PadesSignService = mock[PadesSignService]

  val service: PDFCreator = new PDFCreatorImpl(mockPadesSignService)

  val manager: User = User(
    id = null,
    name = "manager",
    surname = "manager",
    taxCode = "taxCode",
    role = PartyRole.MANAGER,
    email = Some("managerEmail"),
    productRole = "admin"
  )

  val user1: User = User(
    id = null,
    name = "user1",
    surname = "user1",
    taxCode = "taxCode1",
    role = PartyRole.DELEGATE,
    email = Some("user1Email"),
    productRole = "admin"
  )

  val institution: PartyManagementDependency.Institution = PartyManagementDependency.Institution(
    id = null,
    externalId = "externalId",
    originId = "originId",
    description = "description",
    digitalAddress = "digitalAddress",
    address = "address",
    zipCode = "zipCode",
    taxCode = "taxCode",
    origin = "origin",
    institutionType = Some("institutionType"),
    attributes = Seq.empty,
    geographicTaxonomies = Seq(PartyManagementDependency.GeographicTaxonomy(code = "GEOCODE", desc = "GEODESC")),
    products = Map.empty
  )

  val baseOnboardingRequest: OnboardingSignedRequest = OnboardingSignedRequest(
    productId = "productId",
    productName = "productName",
    users = Seq.empty,
    contract = OnboardingContract("a", "b"),
    billing = Some(Billing(vatNumber = "VATNUMBER", recipientCode = "RECIPIENTCODE")),
    institutionUpdate = Some(
      InstitutionUpdate(
        institutionType = Some("PA"),
        description = Some("description"),
        digitalAddress = Some("digitalAddress"),
        address = Some("address"),
        zipCode = Some("zipCode"),
        taxCode = Some("taxCode"),
        paymentServiceProvider = Some(
          PaymentServiceProvider(
            abiCode = Some("abiCode"),
            businessRegisterNumber = Some("businessRegisterNumber"),
            legalRegisterName = Some("legalRegisterName"),
            legalRegisterNumber = Some("legalRegisterNumber"),
            vatNumberGroup = Some(true)
          )
        ),
        dataProtectionOfficer =
          Some(DataProtectionOfficer(address = Some("address"), email = Some("email"), pec = Some("pec"))),
        geographicTaxonomyCodes = Seq("geographicTaxonomyCode1")
      )
    ),
    pricingPlan = Some("pricingPlan"),
    applyPagoPaSign = true
  )

  val geoTaxonomies: Seq[GeographicTaxonomy] = Seq(
    GeographicTaxonomy(code = "GEOCODE_REQUEST", desc = "GEODESC_REQUEST")
  )

  val contractTemplate: String = f"institutionTaxCode -> $${institutionTaxCode},<br/>" +
    f"managerName -> $${managerName},<br/>" +
    f"institutionVatNumber -> $${institutionVatNumber},<br/>" +
    f"managerEmail -> $${managerEmail},<br/>" +
    f"institutionRecipientCode -> $${institutionRecipientCode},<br/>" +
    f"manager -> $${manager},<br/>" +
    f"originId -> $${originId},<br/>" +
    f"institutionType -> $${institutionType},<br/>" +
    f"zipCode -> $${zipCode},<br/>" +
    f"managerTaxCode -> $${managerTaxCode},<br/>" +
    f"delegates -> $${delegates},<br/>" +
    f"institutionMail -> $${institutionMail},<br/>" +
    f"address -> $${address},<br/>" +
    f"institutionGeoTaxonomies -> $${institutionGeoTaxonomies},<br/>" +
    f"isPublicServicesManager -> $${isPublicServicesManager},<br/>" +
    f"managerSurname -> $${managerSurname},<br/>" +
    f"pricingPlan -> $${pricingPlan},<br/>" +
    f"institutionName -> $${institutionName}"

  val expectedSignatureInformation: SignatureInformation =
    new SignatureInformation("PagoPaSigner", "Rome", "Onboarding institution description into product productName")

  "A request" should {
    "be signed if configured" in {
      (mockPadesSignService
        .padesSign(_: File, _: File, _: SignatureInformation))
        .expects(*, *, *)
        .onCall((inputPdf: File, signedPdf: File, signatureInformation: SignatureInformation) => {
          signedPdf.getAbsolutePath shouldBe inputPdf.getAbsolutePath.replace(".pdf", "-signed.pdf")
          signatureInformation shouldBe expectedSignatureInformation
          inputPdf.renameTo(signedPdf)
          ()
        })

      val pdf: File = service
        .createContract(contractTemplate, manager, Seq(user1), institution, baseOnboardingRequest, geoTaxonomies)(
          Seq.empty
        )
        .value
        .get
        .get

      pdf.getName.endsWith("-signed.pdf") shouldBe true
      checkPdfContent(pdf)
    }

    "be NOT to be signed if NOT configured" in {
      (mockPadesSignService
        .padesSign(_: File, _: File, _: SignatureInformation))
        .expects(*, *, *)
        .never()

      service.createContract(
        contractTemplate,
        manager,
        Seq(user1),
        institution,
        baseOnboardingRequest.copy(applyPagoPaSign = false),
        geoTaxonomies
      )(Seq.empty)
    }

    "be NOT to be signed if globally disabled" in {
      ConfigFactory
        .parseString("party-process.pagopaSignature.enabled=false")
        .withFallback(testDataConfig)

      (mockPadesSignService
        .padesSign(_: File, _: File, _: SignatureInformation))
        .expects(*, *, *)
        .never()

      service.createContract(contractTemplate, manager, Seq(user1), institution, baseOnboardingRequest, geoTaxonomies)(
        Seq.empty
      )
    }

    "be NOT to be signed if contract sign is globally disabled" in {
      ConfigFactory
        .parseString(
          "party-process.pagopaSignature.enabled=true\n" +
            "party-process.pagopaSignature.apply.onboarding.enabled=false"
        )
        .withFallback(testDataConfig)

      (mockPadesSignService
        .padesSign(_: File, _: File, _: SignatureInformation))
        .expects(*, *, *)
        .never()

      service.createContract(contractTemplate, manager, Seq(user1), institution, baseOnboardingRequest, geoTaxonomies)(
        Seq.empty
      )
    }

  }

  def getPdfText(pdfFile: File): String = {
    val doc: PDDocument = PDDocument.load(pdfFile)
    new PDFTextStripper().getText(doc)
  }

  def checkPdfContent(pdf: File): Assertion = {
    getPdfText(pdf)
      .replaceAll("user1 user1.*", "user1 user1")
      .replaceAll("user1Email.*", "user1Email")
      .replaceAll("PEC:.*", "PEC:")
      .trim.split("\n").toSeq shouldBe "institutionTaxCode -> taxCode,\nmanagerName -> manager,\ninstitutionVatNumber -> VATNUMBER,\nmanagerEmail -> managerEmail,\ninstitutionRecipientCode -> RECIPIENTCODE,\nmanager -> manager manager,\noriginId -> originId,\ninstitutionType -> Pubblica Amministrazione,\nzipCode -> zipCode,\nmanagerTaxCode -> taxCode,\ndelegates ->\nNome e Cognome: user1 user1\nCodice Fiscale: taxCode1\nAmm.ne/Ente/Società:\nQualifica/Posizione:\ne-mail: user1Email\nPEC:\n,\ninstitutionMail -> digitalAddress,\naddress -> address,\ninstitutionGeoTaxonomies -> GEODESC_REQUEST,\nisPublicServicesManager -> ,\nmanagerSurname -> manager,\npricingPlan -> pricingPlan,\ninstitutionName -> description".split("\n").toSeq
  }
}
