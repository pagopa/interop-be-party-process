package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.api.ProcessApiMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{
  OnBoardingInfo,
  OnBoardingRequest,
  OnBoardingResponse,
  Problem
}
import spray.json._

class ProcessApiMarshallerImpl extends ProcessApiMarshaller with SprayJsonSupport with DefaultJsonProtocol {

  override implicit def toEntityMarshallerOnBoardingResponse: ToEntityMarshaller[OnBoardingResponse] =
    sprayJsonMarshaller[OnBoardingResponse]

//  //TODO this implicit is not really used, due usage of HttpEntity.fromFile
//  override implicit def toEntityMarshallerFile: ToEntityMarshaller[File] =
//    Marshaller.withFixedContentType(ContentTypes.`application/octet-stream`) { f =>
//      val source: BufferedSource = scala.io.Source.fromFile(f.getPath)(Codec(StandardCharsets.UTF_8.name))
//      val out: String            = source.mkString
//      source.close()
//      out.getBytes(StandardCharsets.UTF_8.name)
//    }

  override implicit def toEntityMarshallerOnBoardingInfo: ToEntityMarshaller[OnBoardingInfo] =
    sprayJsonMarshaller[OnBoardingInfo]

  override implicit def fromEntityUnmarshallerOnBoardingRequest: FromEntityUnmarshaller[OnBoardingRequest] =
    sprayJsonUnmarshaller[OnBoardingRequest]

  override implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem] = sprayJsonMarshaller[Problem]

}
