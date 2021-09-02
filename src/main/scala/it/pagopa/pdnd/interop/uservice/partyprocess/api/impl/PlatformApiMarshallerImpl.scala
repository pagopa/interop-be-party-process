package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.api.PlatformApiMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{PlatformRolesResponse, Problem}

class PlatformApiMarshallerImpl extends PlatformApiMarshaller with SprayJsonSupport {

  override implicit def toEntityMarshallerPlatformRolesResponse: ToEntityMarshaller[PlatformRolesResponse] =
    sprayJsonMarshaller[PlatformRolesResponse](jsonFormat3(PlatformRolesResponse))

  override implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem] =
    sprayJsonMarshaller[Problem](jsonFormat3(Problem))
}
