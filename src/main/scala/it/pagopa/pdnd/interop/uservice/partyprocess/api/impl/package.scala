package it.pagopa.pdnd.interop.uservice.partyprocess.api

import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import it.pagopa.pdnd.interop.commons.utils.SprayCommonFormats.{fileFormat, offsetDateTimeFormat, uuidFormat}

package object impl extends DefaultJsonProtocol {

  implicit val tokenChecksumFormat: RootJsonFormat[TokenChecksum]           = jsonFormat1(TokenChecksum)
  implicit val problemFormat: RootJsonFormat[Problem]                       = jsonFormat3(Problem)
  implicit val userFormat: RootJsonFormat[User]                             = jsonFormat7(User)
  implicit val onboardingContractFormat: RootJsonFormat[OnboardingContract] = jsonFormat2(OnboardingContract)
  implicit val onboardingRequestFormat: RootJsonFormat[OnboardingRequest]   = jsonFormat3(OnboardingRequest)
  implicit val onboardingResponseFormat: RootJsonFormat[OnboardingResponse] = jsonFormat2(OnboardingResponse)
  implicit val personInfoFormat: RootJsonFormat[PersonInfo]                 = jsonFormat3(PersonInfo)
  implicit val attributeDataFormat: RootJsonFormat[Attribute]               = jsonFormat3(Attribute.apply)
  implicit val productInfoDataFormat: RootJsonFormat[ProductInfo]           = jsonFormat3(ProductInfo)
  implicit val onboardingDataFormat: RootJsonFormat[OnboardingData]         = jsonFormat8(OnboardingData)
  implicit val onboardingInfoFormat: RootJsonFormat[OnboardingInfo]         = jsonFormat2(OnboardingInfo)
  implicit val relationshipInfoFormat: RootJsonFormat[RelationshipInfo]     = jsonFormat10(RelationshipInfo)
  implicit val productsFormat: RootJsonFormat[Products]                     = jsonFormat1(Products)

}
