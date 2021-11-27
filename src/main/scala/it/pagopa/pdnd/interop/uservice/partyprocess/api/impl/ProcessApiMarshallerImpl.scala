package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.api.ProcessApiMarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import spray.json._

import java.io.File
import java.nio.charset.StandardCharsets
import scala.io.{BufferedSource, Codec}

class ProcessApiMarshallerImpl extends ProcessApiMarshaller with SprayJsonSupport with DefaultJsonProtocol {

  override implicit def toEntityMarshallerOnBoardingResponse: ToEntityMarshaller[OnBoardingResponse] =
    sprayJsonMarshaller[OnBoardingResponse]

  override implicit def toEntityMarshallerFile: ToEntityMarshaller[File] =
    Marshaller.withFixedContentType(ContentTypes.`application/octet-stream`) { f =>
      val source: BufferedSource = scala.io.Source.fromFile(f.getPath)(Codec(StandardCharsets.UTF_8.name))
      val out: String            = source.mkString
      source.close()
      out.getBytes(StandardCharsets.UTF_8.name)
    }

  override implicit def toEntityMarshallerOnBoardingInfo: ToEntityMarshaller[OnBoardingInfo] =
    sprayJsonMarshaller[OnBoardingInfo]

  override implicit def fromEntityUnmarshallerOnBoardingRequest: FromEntityUnmarshaller[OnBoardingRequest] =
    sprayJsonUnmarshaller[OnBoardingRequest]

  override implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem] = sprayJsonMarshaller[Problem]

  override implicit def toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]] =
    sprayJsonMarshaller[Seq[RelationshipInfo]]

  override implicit def toEntityMarshallerRelationshipInfo: ToEntityMarshaller[RelationshipInfo] =
    sprayJsonMarshaller[RelationshipInfo]

  override implicit def toEntityMarshallerProducts: ToEntityMarshaller[Products] = sprayJsonMarshaller[Products]
}
