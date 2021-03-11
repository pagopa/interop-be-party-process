package it.pagopa.pdnd.interop.uservice.partyprocess.common

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.http.scaladsl.server.Directives.Authenticator
import akka.http.scaladsl.server.directives.Credentials
import akka.util.Timeout
import akka.{actor => classic}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt

package object system {

  implicit val actorSystem: ActorSystem[Nothing] =
    ActorSystem[Nothing](Behaviors.empty, "pdnd-interop-uservice-party-management")

  implicit val executionContext: ExecutionContextExecutor = actorSystem.executionContext

  implicit val classicActorSystem: classic.ActorSystem = actorSystem.toClassic

  implicit val timeout: Timeout = 3.seconds

  implicit val scheduler: Scheduler = actorSystem.scheduler

  object Authenticator extends Authenticator[Unit] {
    override def apply(credentials: Credentials): Option[Unit] = Some(())
  }
}
