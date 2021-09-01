package it.pagopa.pdnd.interop.uservice.partyprocess.api

import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat, RootJsonFormat, deserializationError}

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.format.DateTimeFormatter
import java.util.{Base64, UUID}
import scala.util.{Failure, Success, Try}

package object impl extends DefaultJsonProtocol {

  implicit val fileFormat: JsonFormat[File] =
    new JsonFormat[File] {
      override def write(obj: File): JsValue = {
        val source = new FileInputStream(obj)
        val bytes  = source.readAllBytes()
        val base64 = Base64.getEncoder.encodeToString(bytes)
        source.close()
        JsString(base64)
      }

      override def read(json: JsValue): File = json match {
        case JsString(s) =>
          Try {
            val file = Files.createTempFile(UUID.randomUUID().toString, ".pdf").toFile
            val pw   = new PrintWriter(file, StandardCharsets.UTF_8)
            pw.write(s)
            pw.close()
            file
          } match {
            case Success(result) => result
            case Failure(exception) =>
              deserializationError(s"could not parse $s as File", exception)
          }
        case notAJsString =>
          deserializationError(s"expected a String but got a ${notAJsString.compactPrint}")
      }
    }
  final val formatter: DateTimeFormatter                                    = DateTimeFormatter.ISO_OFFSET_DATE_TIME
  implicit val tokenChecksumFormat: RootJsonFormat[TokenChecksum]           = jsonFormat1(TokenChecksum)
  implicit val problemFormat: RootJsonFormat[Problem]                       = jsonFormat3(Problem)
  implicit val userFormat: RootJsonFormat[User]                             = jsonFormat5(User)
  implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest]   = jsonFormat2(OnBoardingRequest)
  implicit val onBoardingResponseFormat: RootJsonFormat[OnBoardingResponse] = jsonFormat2(OnBoardingResponse)
  implicit val personInfoFormat: RootJsonFormat[PersonInfo]                 = jsonFormat3(PersonInfo)
  implicit val institutionInfoFormat: RootJsonFormat[InstitutionInfo]       = jsonFormat5(InstitutionInfo)
  implicit val onBoardingInfoFormat: RootJsonFormat[OnBoardingInfo]         = jsonFormat2(OnBoardingInfo)
  implicit val tokenRequestRequestFormat: RootJsonFormat[TokenRequest]      = jsonFormat2(TokenRequest)

}
