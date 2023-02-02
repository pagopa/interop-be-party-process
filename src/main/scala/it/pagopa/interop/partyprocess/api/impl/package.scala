package it.pagopa.interop.partyprocess.api

import spray.json.{JsString, JsValue, JsonFormat, deserializationError}
import akka.http.scaladsl.model.StatusCode
import it.pagopa.interop.commons.utils.SprayCommonFormats.{offsetDateTimeFormat, uuidFormat}
import it.pagopa.interop.commons.utils.errors.ComponentError
import it.pagopa.interop.partyprocess.model._
import it.pagopa.userreg.client.model.CertifiableFieldResourceOfstring
import it.pagopa.userreg.client.model.CertifiableFieldResourceOfstringEnums.Certification.{
  NONE => CertificationEnumsNone
}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.util.{Failure, Success, Try}

package object impl extends DefaultJsonProtocol {

  private lazy val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_DATE

  /** Defines the JsonFormat for <code>LocalDate</code> data type
    */
  implicit val localDateFormat: JsonFormat[LocalDate] =
    new JsonFormat[LocalDate] {
      override def write(obj: LocalDate): JsValue = JsString(obj.format(dateFormatter))

      override def read(json: JsValue): LocalDate = json match {
        case JsString(s)  =>
          Try { LocalDate.parse(s, dateFormatter) } match {
            case Success(result)    => result
            case Failure(exception) =>
              deserializationError(s"could not parse $s as java OffsetDateTime", exception)
          }
        case notAJsString =>
          deserializationError(s"expected a String but got a ${notAJsString.compactPrint}")
      }
    }

  implicit val paymentServiceProviderDataFormat: RootJsonFormat[PaymentServiceProvider] = jsonFormat5(
    PaymentServiceProvider
  )
  implicit val dataProtectionOfficerDataFormat: RootJsonFormat[DataProtectionOfficer]   = jsonFormat3(
    DataProtectionOfficer
  )
  implicit val geographicTaxonomyExtFormat: RootJsonFormat[GeographicTaxonomyExt] = jsonFormat10(GeographicTaxonomyExt)
  implicit val geographicTaxonomyFormat: RootJsonFormat[GeographicTaxonomy]       = jsonFormat2(GeographicTaxonomy)
  implicit val institutionUpdateFormat: RootJsonFormat[InstitutionUpdate]         = jsonFormat15(InstitutionUpdate)
  implicit val billingFormat: RootJsonFormat[Billing]                             = jsonFormat3(Billing)
  implicit val tokenChecksumFormat: RootJsonFormat[TokenChecksum]                 = jsonFormat1(TokenChecksum)
  implicit val problemErrorFormat: RootJsonFormat[ProblemError]                   = jsonFormat2(ProblemError)
  implicit val problemFormat: RootJsonFormat[Problem]                             = jsonFormat5(Problem)
  implicit val userFormat: RootJsonFormat[User]                                   = jsonFormat7(User)
  implicit val onboardingContractFormat: RootJsonFormat[OnboardingContract]       = jsonFormat2(OnboardingContract)

  implicit val onboardingInstitutionRequestFormat: RootJsonFormat[OnboardingInstitutionRequest] =
    jsonFormat9(OnboardingInstitutionRequest)
  implicit val onboardingLegalUsersRequestFormat: RootJsonFormat[OnboardingLegalUsersRequest]   =
    jsonFormat7(OnboardingLegalUsersRequest)
  implicit val onboardingUsersRequestFormat: RootJsonFormat[OnboardingUsersRequest]             =
    jsonFormat3(OnboardingUsersRequest)

  implicit val attributeDataFormat: RootJsonFormat[Attribute]           = jsonFormat3(Attribute)
  implicit val institutionFormat: RootJsonFormat[Institution]           = jsonFormat20(Institution)
  implicit val institutionSeedFormat: RootJsonFormat[InstitutionSeed]   = jsonFormat15(InstitutionSeed)
  implicit val productInfoDataFormat: RootJsonFormat[ProductInfo]       = jsonFormat3(ProductInfo)
  implicit val onboardingDataFormat: RootJsonFormat[OnboardingData]     = jsonFormat22(OnboardingData)
  implicit val onboardingInfoFormat: RootJsonFormat[OnboardingInfo]     = jsonFormat2(OnboardingInfo)
  implicit val relationshipInfoFormat: RootJsonFormat[RelationshipInfo] = jsonFormat11(RelationshipInfo)
  implicit val productFormat: RootJsonFormat[Product]                   = jsonFormat2(Product)
  implicit val productsFormat: RootJsonFormat[Products]                 = jsonFormat1(Products)
  implicit val billingDataFormat: RootJsonFormat[BillingData]           = jsonFormat12(BillingData)
  implicit val institutionPutFormat: RootJsonFormat[InstitutionPut]     = jsonFormat1(InstitutionPut)

  implicit val tokenIdFormat: RootJsonFormat[TokenId] = jsonFormat1(TokenId)

  final val serviceErrorCodePrefix: String = "002"
  final val defaultProblemType: String     = "about:blank"
  final val uidClaim: String               = "uid"

  def problemOf(httpError: StatusCode, error: ComponentError, defaultMessage: String = "Unknown error"): Problem =
    Problem(
      `type` = defaultProblemType,
      status = httpError.intValue,
      title = httpError.defaultMessage,
      errors = Seq(
        ProblemError(
          code = s"$serviceErrorCodePrefix-${error.code}",
          detail = Option(error.getMessage).getOrElse(defaultMessage)
        )
      )
    )

  def notCertifiedString(value: String): CertifiableFieldResourceOfstring =
    CertifiableFieldResourceOfstring(value = value, certification = CertificationEnumsNone)
}
