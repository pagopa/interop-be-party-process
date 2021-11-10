package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.api.PlatformApiMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{ProductRolesResponse, Problem}

class PlatformApiMarshallerImpl extends PlatformApiMarshaller with SprayJsonSupport {

  override implicit def toEntityMarshallerProductRolesResponse: ToEntityMarshaller[ProductRolesResponse] =
    sprayJsonMarshaller[ProductRolesResponse](jsonFormat3(ProductRolesResponse))

  override implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem] =
    sprayJsonMarshaller[Problem](jsonFormat3(Problem))
}
