package it.pagopa.pdnd.interop.uservice.partyprocess.api

import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import it.pagopa.pdnd.interop.commons.utils.SprayCommonFormats.{fileFormat, uuidFormat, offsetDateTimeFormat}

package object impl extends DefaultJsonProtocol {

  implicit val tokenChecksumFormat: RootJsonFormat[TokenChecksum]           = jsonFormat1(TokenChecksum)
  implicit val problemFormat: RootJsonFormat[Problem]                       = jsonFormat3(Problem)
  implicit val userFormat: RootJsonFormat[User]                             = jsonFormat7(User)
  implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest]   = jsonFormat2(OnBoardingRequest)
  implicit val onBoardingResponseFormat: RootJsonFormat[OnBoardingResponse] = jsonFormat2(OnBoardingResponse)
  implicit val personInfoFormat: RootJsonFormat[PersonInfo]                 = jsonFormat3(PersonInfo)
  implicit val attributeDataFormat: RootJsonFormat[Attribute]               = jsonFormat3(Attribute.apply)
  implicit val productInfoDataFormat: RootJsonFormat[ProductInfo]           = jsonFormat3(ProductInfo)
  implicit val onboardingDataFormat: RootJsonFormat[OnboardingData]         = jsonFormat8(OnboardingData)
  implicit val onBoardingInfoFormat: RootJsonFormat[OnBoardingInfo]         = jsonFormat2(OnBoardingInfo)
  implicit val relationshipInfoFormat: RootJsonFormat[RelationshipInfo]     = jsonFormat5(RelationshipInfo)
  implicit val productsFormat: RootJsonFormat[Products]                     = jsonFormat1(Products)

}
