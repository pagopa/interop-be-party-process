package it.pagopa.interop.partyprocess.api.impl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import it.pagopa.interop.partyprocess.api.PublicApiMarshaller
import it.pagopa.interop.partyprocess.model._
import spray.json._

object PublicApiMarshallerImpl extends PublicApiMarshaller with SprayJsonSupport with DefaultJsonProtocol {

  override implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem] = sprayJsonMarshaller[Problem]

}
