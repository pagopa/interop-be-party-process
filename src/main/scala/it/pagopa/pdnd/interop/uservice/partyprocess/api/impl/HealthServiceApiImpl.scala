package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.server.Route
import it.pagopa.pdnd.interop.uservice.partyprocess.api.HealthApiService
import it.pagopa.pdnd.interop.uservice.partyprocess.model.Problem

class HealthServiceApiImpl extends HealthApiService {

  override def getStatus()(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem]): Route = getStatus200(
    Problem(None, 200, "OK")
  )

}
