package it.pagopa.interop.partyprocess.api

import akka.http.scaladsl.model.StatusCode
import it.pagopa.interop.commons.utils.SprayCommonFormats.{fileFormat, offsetDateTimeFormat, uuidFormat}
import it.pagopa.interop.commons.utils.errors.ComponentError
import it.pagopa.interop.partyprocess.model._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

package object impl extends DefaultJsonProtocol {

  implicit val tokenChecksumFormat: RootJsonFormat[TokenChecksum]           = jsonFormat1(TokenChecksum)
  implicit val problemErrorFormat: RootJsonFormat[ProblemError]             = jsonFormat2(ProblemError)
  implicit val problemFormat: RootJsonFormat[Problem]                       = jsonFormat5(Problem)
  implicit val userFormat: RootJsonFormat[User]                             = jsonFormat7(User)
  implicit val onboardingContractFormat: RootJsonFormat[OnboardingContract] = jsonFormat2(OnboardingContract)
  implicit val onboardingRequestFormat: RootJsonFormat[OnboardingRequest]   = jsonFormat3(OnboardingRequest)
  implicit val onboardingResponseFormat: RootJsonFormat[OnboardingResponse] = jsonFormat2(OnboardingResponse)
  implicit val contactFormat: RootJsonFormat[Contact]                       = jsonFormat1(Contact)
  implicit val personInfoFormat: RootJsonFormat[PersonInfo]                 = jsonFormat5(PersonInfo)
  implicit val attributeDataFormat: RootJsonFormat[Attribute]               = jsonFormat3(Attribute.apply)
  implicit val productInfoDataFormat: RootJsonFormat[ProductInfo]           = jsonFormat3(ProductInfo)
  implicit val onboardingDataFormat: RootJsonFormat[OnboardingData]         = jsonFormat10(OnboardingData)
  implicit val onboardingInfoFormat: RootJsonFormat[OnboardingInfo]         = jsonFormat2(OnboardingInfo)
  implicit val relationshipInfoFormat: RootJsonFormat[RelationshipInfo]     = jsonFormat10(RelationshipInfo)
  implicit val productFormat: RootJsonFormat[Product]                       = jsonFormat2(Product)
  implicit val productsFormat: RootJsonFormat[Products]                     = jsonFormat1(Products)

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
}
