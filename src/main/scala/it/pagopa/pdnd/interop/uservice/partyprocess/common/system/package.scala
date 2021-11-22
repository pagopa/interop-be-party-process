package it.pagopa.pdnd.interop.uservice.partyprocess.common

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.{ActorSystem, Scheduler}
import akka.util.Timeout
import akka.{actor => classic}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt

package object system {

  implicit val actorSystem: ActorSystem[Nothing] =
    ActorSystem[Nothing](Behaviors.empty[Nothing], "pdnd-interop-uservice-party-process")

  implicit val executionContext: ExecutionContextExecutor = actorSystem.executionContext

  implicit val classicActorSystem: classic.ActorSystem = actorSystem.toClassic

  implicit val timeout: Timeout = 3.seconds

  implicit val scheduler: Scheduler = actorSystem.scheduler
}
