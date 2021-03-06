package it.pagopa.interop.partyprocess.api.impl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import it.pagopa.interop.partyprocess.api.HealthApiMarshaller
import it.pagopa.interop.partyprocess.model.Problem

object HealthApiMarshallerImpl extends HealthApiMarshaller with SprayJsonSupport {

  override implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem] =
    sprayJsonMarshaller[Problem](jsonFormat5(Problem))
}
