package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import scala.concurrent.Future
import scala.util.Try

package object utils {
  implicit class EitherOps[A](val either: Either[Throwable, A]) extends AnyVal {
    def toFuture: Future[A] = either.fold(e => Future.failed(e), a => Future.successful(a))
  }

  implicit class TryOps[A](val tryOp: Try[A]) extends AnyVal {
    def toFuture: Future[A] = tryOp.fold(e => Future.failed(e), a => Future.successful(a))
  }

  implicit class OptionOps[A](val option: Option[A]) extends AnyVal {
    def toFuture(e: Throwable): Future[A] = option.fold[Future[A]](Future.failed(e))(Future.successful)
  }
}
