package it.pagopa.interop.partyprocess.api

import akka.http.scaladsl.model.StatusCode
import it.pagopa.interop.commons.utils.SprayCommonFormats.{offsetDateTimeFormat, uuidFormat}
import it.pagopa.interop.commons.utils.errors.ComponentError
import it.pagopa.interop.partyprocess.model._
import it.pagopa.userreg.client.model.CertifiableFieldResourceOfstring
import it.pagopa.userreg.client.model.CertifiableFieldResourceOfstringEnums.Certification.{
  NONE => CertificationEnumsNone
}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

package object impl extends DefaultJsonProtocol {

  implicit val institutionUpdateFormat: RootJsonFormat[InstitutionUpdate]   = jsonFormat5(InstitutionUpdate)
  implicit val billingFormat: RootJsonFormat[Billing]                       = jsonFormat3(Billing)
  implicit val tokenChecksumFormat: RootJsonFormat[TokenChecksum]           = jsonFormat1(TokenChecksum)
  implicit val problemErrorFormat: RootJsonFormat[ProblemError]             = jsonFormat2(ProblemError)
  implicit val problemFormat: RootJsonFormat[Problem]                       = jsonFormat5(Problem)
  implicit val userFormat: RootJsonFormat[User]                             = jsonFormat8(User)
  implicit val onboardingContractFormat: RootJsonFormat[OnboardingContract] = jsonFormat2(OnboardingContract)

  implicit val onboardingInstitutionRequestFormat: RootJsonFormat[OnboardingInstitutionRequest] =
    jsonFormat6(OnboardingInstitutionRequest)
  implicit val onboardingLegalUsersRequestFormat: RootJsonFormat[OnboardingLegalUsersRequest]   =
    jsonFormat4(OnboardingLegalUsersRequest)
  implicit val onboardingUsersRequestFormat: RootJsonFormat[OnboardingUsersRequest]             =
    jsonFormat2(OnboardingUsersRequest)

  implicit val attributeDataFormat: RootJsonFormat[Attribute]           = jsonFormat3(Attribute)
  implicit val institutionFormat: RootJsonFormat[Institution]           = jsonFormat11(Institution)
  implicit val productInfoDataFormat: RootJsonFormat[ProductInfo]       = jsonFormat3(ProductInfo)
  implicit val onboardingDataFormat: RootJsonFormat[OnboardingData]     = jsonFormat16(OnboardingData)
  implicit val onboardingInfoFormat: RootJsonFormat[OnboardingInfo]     = jsonFormat2(OnboardingInfo)
  implicit val relationshipInfoFormat: RootJsonFormat[RelationshipInfo] = jsonFormat11(RelationshipInfo)
  implicit val productFormat: RootJsonFormat[Product]                   = jsonFormat2(Product)
  implicit val productsFormat: RootJsonFormat[Products]                 = jsonFormat1(Products)

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
