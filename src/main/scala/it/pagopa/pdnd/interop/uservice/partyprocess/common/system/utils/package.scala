package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import scala.concurrent.Future

package object utils {
  implicit class EitherOps[A](val either: Either[Throwable, A]) extends AnyVal {
    def toFuture: Future[A] = either.fold(e => Future.failed(e), a => Future.successful(a))
  }
}
