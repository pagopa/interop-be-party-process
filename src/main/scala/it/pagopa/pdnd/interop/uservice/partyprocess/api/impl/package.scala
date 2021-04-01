package it.pagopa.pdnd.interop.uservice.partyprocess.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{
  InstitutionInfo,
  NewToken,
  OnBoardingInfo,
  OnBoardingRequest,
  PersonInfo,
  Problem,
  TokenRequest,
  User
}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import java.time.format.DateTimeFormatter

package object impl extends SprayJsonSupport with DefaultJsonProtocol {

  final val formatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  implicit val problemFormat: RootJsonFormat[Problem]                     = jsonFormat3(Problem)
  implicit val userFormat: RootJsonFormat[User]                           = jsonFormat3(User)
  implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest] = jsonFormat4(OnBoardingRequest)
  implicit val personInfoFormat: RootJsonFormat[PersonInfo]               = jsonFormat3(PersonInfo)
  implicit val institutionInfoFormat: RootJsonFormat[InstitutionInfo]     = jsonFormat4(InstitutionInfo)
  implicit val onBoardingInfoFormat: RootJsonFormat[OnBoardingInfo]       = jsonFormat2(OnBoardingInfo)
  implicit val newTokenRequestFormat: RootJsonFormat[NewToken]            = jsonFormat1(NewToken)
  implicit val tokenRequestRequestFormat: RootJsonFormat[TokenRequest]    = jsonFormat3(TokenRequest)

}
