package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.api.HealthApiMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.model.Problem

class HealthApiMarshallerImpl extends HealthApiMarshaller with SprayJsonSupport {

  override implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem] =
    sprayJsonMarshaller[Problem](jsonFormat5(Problem))
}
