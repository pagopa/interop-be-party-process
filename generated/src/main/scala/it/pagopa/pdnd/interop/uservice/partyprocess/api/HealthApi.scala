package it.pagopa.pdnd.interop.uservice.partyprocess.api

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directive1, Route}
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.unmarshalling.FromStringUnmarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.server.AkkaHttpHelper._
import it.pagopa.pdnd.interop.uservice.partyprocess.model.Problem

class HealthApi(
  healthService: HealthApiService,
  healthMarshaller: HealthApiMarshaller,
  wrappingDirective: Directive1[Seq[(String, String)]]
) {

  import healthMarshaller._

  lazy val route: Route =
    path("status") {
      get {
        wrappingDirective { implicit contexts =>
          healthService.getStatus()
        }
      }
    }
}

trait HealthApiService {
  def getStatus200(responseProblem: Problem)(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem]): Route =
    complete((200, responseProblem))

  /** Code: 200, Message: successful operation, DataType: Problem
    */
  def getStatus()(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route

}

trait HealthApiMarshaller {

  implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem]

}
