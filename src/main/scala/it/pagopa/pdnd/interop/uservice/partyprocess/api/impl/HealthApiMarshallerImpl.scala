package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.api.HealthApiMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.model.ErrorResponse

class HealthApiMarshallerImpl extends HealthApiMarshaller {

  override implicit def toEntityMarshallerErrorResponse: ToEntityMarshaller[ErrorResponse] =
    sprayJsonMarshaller[ErrorResponse](jsonFormat3(ErrorResponse))
}
