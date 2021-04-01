package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.api.ProcessApiMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{
  NewToken,
  OnBoardingInfo,
  OnBoardingRequest,
  Problem,
  TokenRequest
}
import spray.json._

class ProcessApiMarshallerImpl extends ProcessApiMarshaller with SprayJsonSupport with DefaultJsonProtocol {

  override implicit def toEntityMarshallerOnBoardingInfo: ToEntityMarshaller[OnBoardingInfo] =
    sprayJsonMarshaller[OnBoardingInfo]

  override implicit def fromEntityUnmarshallerOnBoardingRequest: FromEntityUnmarshaller[OnBoardingRequest] =
    sprayJsonUnmarshaller[OnBoardingRequest]

  override implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem] = sprayJsonMarshaller[Problem]

  override implicit def fromEntityUnmarshallerTokenRequest: FromEntityUnmarshaller[TokenRequest] =
    sprayJsonUnmarshaller[TokenRequest]

  override implicit def toEntityMarshallerNewToken: ToEntityMarshaller[NewToken] = sprayJsonMarshaller[NewToken]
}
