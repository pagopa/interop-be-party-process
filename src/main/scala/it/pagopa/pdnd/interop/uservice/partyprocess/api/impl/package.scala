package it.pagopa.pdnd.interop.uservice.partyprocess.api

import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import it.pagopa.pdnd.interop.commons.utils.SprayCommonFormats.{fileFormat, offsetDateTimeFormat, uuidFormat}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.Relationship

package object impl extends DefaultJsonProtocol {

  implicit val tokenChecksumFormat: RootJsonFormat[TokenChecksum]           = jsonFormat1(TokenChecksum)
  implicit val problemFormat: RootJsonFormat[Problem]                       = jsonFormat3(Problem)
  implicit val userFormat: RootJsonFormat[User]                             = jsonFormat7(User)
  implicit val onBoardingRequestFormat: RootJsonFormat[OnboardingRequest]   = jsonFormat2(OnboardingRequest)
  implicit val onBoardingResponseFormat: RootJsonFormat[OnboardingResponse] = jsonFormat2(OnboardingResponse)
  implicit val personInfoFormat: RootJsonFormat[PersonInfo]                 = jsonFormat3(PersonInfo)
  implicit val attributeDataFormat: RootJsonFormat[Attribute]               = jsonFormat3(Attribute.apply)
  implicit val productInfoDataFormat: RootJsonFormat[ProductInfo]           = jsonFormat3(ProductInfo)
  implicit val onboardingDataFormat: RootJsonFormat[OnboardingData]         = jsonFormat8(OnboardingData)
  implicit val onBoardingInfoFormat: RootJsonFormat[OnboardingInfo]         = jsonFormat2(OnboardingInfo)
  implicit val relationshipInfoFormat: RootJsonFormat[RelationshipInfo]     = jsonFormat5(RelationshipInfo)
  implicit val productsFormat: RootJsonFormat[Products]                     = jsonFormat1(Products)

  implicit case class RelationshipOps(relationship: Relationship) extends AnyVal {
    def verifyProducts(products: List[String]): Boolean = {
      products.isEmpty || products.contains(relationship.product.id)
    }
    def verifyProductRoles(productRoles: List[String]): Boolean = {
      productRoles.isEmpty || productRoles.contains(relationship.product.role)
    }
    def verifyRole(roles: List[String]): Boolean = {
      roles.isEmpty || roles.contains(relationship.role.toString)
    }
    def verifyState(states: List[String]): Boolean = {
      states.isEmpty || states.contains(relationship.state.toString)
    }
  }

}
