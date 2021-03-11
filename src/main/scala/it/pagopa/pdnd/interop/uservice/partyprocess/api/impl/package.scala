package it.pagopa.pdnd.interop.uservice.partyprocess.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{ErrorResponse, ManagerInfo, OnBoardingRequest}
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat, RootJsonFormat, deserializationError}

import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.util.{Failure, Success, Try}

package object impl extends SprayJsonSupport with DefaultJsonProtocol {

  final val formatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  implicit val uuidFormat: JsonFormat[UUID] =
    new JsonFormat[UUID] {
      override def write(obj: UUID): JsValue = JsString(obj.toString)

      override def read(json: JsValue): UUID = json match {
        case JsString(s) =>
          Try(UUID.fromString(s)) match {
            case Success(result) => result
            case Failure(exception) =>
              deserializationError(s"could not parse $s as UUID", exception)
          }
        case notAJsString =>
          deserializationError(s"expected a String but got a ${notAJsString.compactPrint}")
      }
    }

  implicit val offsetDateTimeFormat: JsonFormat[OffsetDateTime] =
    new JsonFormat[OffsetDateTime] {
      override def write(obj: OffsetDateTime): JsValue = JsString(obj.format(formatter))

      override def read(json: JsValue): OffsetDateTime = json match {
        case JsString(s) =>
          Try(OffsetDateTime.parse(s, formatter)) match {
            case Success(result) => result
            case Failure(exception) =>
              deserializationError(s"could not parse $s as java OffsetDateTime", exception)
          }
        case notAJsString =>
          deserializationError(s"expected a String but got a ${notAJsString.compactPrint}")
      }
    }

  implicit val uriFormat: JsonFormat[URI] =
    new JsonFormat[URI] {
      override def write(obj: URI): JsValue = JsString(obj.toString)

      override def read(json: JsValue): URI = json match {
        case JsString(s) =>
          Try(URI.create(s)) match {
            case Success(result) => result
            case Failure(exception) =>
              deserializationError(s"could not parse $s as URI", exception)
          }
        case notAJsString =>
          deserializationError(s"expected a String but got a ${notAJsString.compactPrint}")
      }
    }

  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse]         = jsonFormat3(ErrorResponse)
  implicit val managerInfoFormat: RootJsonFormat[ManagerInfo]             = jsonFormat3(ManagerInfo)
  implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest] = jsonFormat4(OnBoardingRequest)

}
