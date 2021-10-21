package it.pagopa.pdnd.interop.uservice.partyprocess.error

import akka.http.scaladsl.model.ErrorInfo

final case class ContentTypeParsingError(contentType: String, errors: List[ErrorInfo]) extends Throwable {
  override def getMessage: String = {
    val errorTxt: String = errors.map(_.formatPretty).mkString("\n")
    s"Error trying to parse content type ${contentType}, reason:\n$errorTxt"
  }
}
