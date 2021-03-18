package it.pagopa.pdnd.interop.uservice.partyprocess.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{NewToken, OnBoardingRequest, Problem, TokenRequest}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import java.time.format.DateTimeFormatter

package object impl extends SprayJsonSupport with DefaultJsonProtocol {

  final val formatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  implicit val problemFormat: RootJsonFormat[Problem]                     = jsonFormat3(Problem)
  implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest] = jsonFormat5(OnBoardingRequest)
  implicit val newTokenRequestFormat: RootJsonFormat[NewToken]            = jsonFormat1(NewToken)
  implicit val tokenRequestRequestFormat: RootJsonFormat[TokenRequest]    = jsonFormat3(TokenRequest)

}
