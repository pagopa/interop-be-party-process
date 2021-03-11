package it.pagopa.pdnd.interop.uservice.partyprocess.server.impl

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.directives.SecurityDirectives
import akka.management.scaladsl.AkkaManagement
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.{
  HealthApiMarshallerImpl,
  HealthServiceApiImpl,
  ProcessApiMarshallerImpl,
  ProcessApiServiceImpl
}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.{HealthApi, ProcessApi}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{Authenticator, classicActorSystem, executionContext}
import it.pagopa.pdnd.interop.uservice.partyprocess.server.Controller
import kamon.Kamon

import scala.concurrent.Future

object Main extends App {

  Kamon.init()

  val processApi: ProcessApi = new ProcessApi(
    new ProcessApiServiceImpl(),
    new ProcessApiMarshallerImpl(),
    SecurityDirectives.authenticateBasic("SecurityRealm", Authenticator)
  )

  val healthApi: HealthApi = new HealthApi(
    new HealthServiceApiImpl(),
    new HealthApiMarshallerImpl(),
    SecurityDirectives.authenticateBasic("SecurityRealm", Authenticator)
  )

  locally {
    val _ = AkkaManagement.get(classicActorSystem).start()

  }

  val controller: Controller = new Controller(healthApi, processApi)

  val bindingFuture: Future[Http.ServerBinding] =
    Http().newServerAt("0.0.0.0", 8089).bind(controller.routes)

}
