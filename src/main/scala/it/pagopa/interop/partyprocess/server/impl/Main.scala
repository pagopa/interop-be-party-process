package it.pagopa.interop.partyprocess.server.impl

import cats.syntax.all._
import akka.http.scaladsl.Http
import akka.management.scaladsl.AkkaManagement
import it.pagopa.interop.commons.utils.CORSSupport

import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration
import it.pagopa.interop.partyprocess.server.Controller
import kamon.Kamon

import scala.concurrent.Future
import scala.util.{Failure, Success}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import it.pagopa.interop.commons.logging.renderBuildInfo
import com.typesafe.scalalogging.Logger
import buildinfo.BuildInfo
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import akka.actor.typed.DispatcherSelector

object Main extends App with CORSSupport with Dependencies {

  val logger: Logger = Logger(this.getClass)

  System.setProperty("kanela.show-banner", "false")

  val system: ActorSystem[Nothing] = ActorSystem[Nothing](
    Behaviors.setup[Nothing] { context =>
      implicit val actorSystem: ActorSystem[_]        = context.system
      implicit val executionContext: ExecutionContext = actorSystem.executionContext
      val selector: DispatcherSelector                = DispatcherSelector.fromConfig("futures-dispatcher")
      val blockingEc: ExecutionContextExecutor        = actorSystem.dispatchers.lookup(selector)

      Kamon.init()
      AkkaManagement.get(actorSystem.classicSystem).start()
      logger.info(renderBuildInfo(BuildInfo))

      val serverBinding: Future[Http.ServerBinding] = for {
        jwtReader                          <- getJwtValidator()
        fileManager                        <- getFileManager()
        onboardingInitMailTemplate         <- getOnboardingInitMailTemplate(fileManager)
        onboardingCompleteMailTemplate     <- getOnboardingCompleteMailTemplate(fileManager)
        onboardingNotificationMailTemplate <- getOnboardingNotificationMailTemplate(fileManager)
        onboardingRejectMailTemplate       <- getOnboardingRejectMailTemplate(fileManager)
        partyManService  = partyManagementService(blockingEc)
        relService       = relationshipService(partyManService)
        prodService      = productService(partyManService)
        geoTaxonomy      = geoTaxonomyService()
        extApi           = externalApi(partyManService, relService, prodService, geoTaxonomy, jwtReader)
        partyProcService = partyProcessService()
        userreg          = userRegistryManagementService()
        productmng       = productManagementService()
        sigService <- signatureService()
        sigValidationService = signatureValidationService()
        process              = processApi(
          partyManService,
          relService,
          prodService,
          sigService,
          partyProcService,
          userreg,
          productmng,
          geoTaxonomy,
          fileManager,
          onboardingInitMailTemplate,
          onboardingNotificationMailTemplate,
          onboardingRejectMailTemplate,
          jwtReader
        )
        public               = publicApi(
          partyManService,
          userreg,
          productmng,
          sigService,
          sigValidationService,
          onboardingCompleteMailTemplate,
          fileManager
        )
        controller           = new Controller(extApi, healthApi, process, public, validationExceptionToRoute.some)(
          actorSystem.classicSystem
        )
        binding <- Http()
          .newServerAt("0.0.0.0", ApplicationConfiguration.serverPort)
          .bind(corsHandler(controller.routes))
      } yield binding

      serverBinding.onComplete {
        case Success(b) =>
          logger.info(s"Started server at ${b.localAddress.getHostString()}:${b.localAddress.getPort()}")
        case Failure(e) =>
          actorSystem.terminate()
          logger.error("Startup error: ", e)
      }

      Behaviors.empty[Nothing]
    },
    BuildInfo.name
  )

  system.whenTerminated.onComplete { case _ => Kamon.stop() }(scala.concurrent.ExecutionContext.global)

}
