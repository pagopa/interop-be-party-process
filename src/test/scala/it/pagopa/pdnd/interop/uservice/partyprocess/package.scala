package it.pagopa.pdnd.interop.uservice

import akka.actor.ClassicActorSystemProvider
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.scaladsl.Source
import akka.util.ByteString
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl._
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{OnBoardingInfo, OnBoardingRequest, RelationshipInfo, User}
import spray.json.RootJsonFormat

import scala.concurrent.Await
import scala.concurrent.duration.Duration

package object partyprocess extends SprayJsonSupport {
  final lazy val url: String =
    s"http://localhost:8088/pdnd-interop-uservice-party-process/${buildinfo.BuildInfo.interfaceVersion}"

  implicit val fromEntityUnmarshallerOnBoardingInfo: FromEntityUnmarshaller[OnBoardingInfo] =
    sprayJsonUnmarshaller[OnBoardingInfo]

  implicit val fromEntityUnmarshallerRelationshipsResponse: FromEntityUnmarshaller[Seq[RelationshipInfo]] =
    sprayJsonUnmarshaller[Seq[RelationshipInfo]]

  implicit val userFormat: RootJsonFormat[User]                           = jsonFormat7(User)
  implicit val onBoardingRequestFormat: RootJsonFormat[OnBoardingRequest] = jsonFormat2(OnBoardingRequest)

  implicit def fromEntityUnmarshallerOnBoardingRequest: ToEntityMarshaller[OnBoardingRequest] =
    sprayJsonMarshaller[OnBoardingRequest]

  final val authorization: Seq[Authorization] = Seq(headers.Authorization(OAuth2BearerToken("token")))
  def request(data: Source[ByteString, Any], path: String, verb: HttpMethod)(implicit
    system: ClassicActorSystemProvider
  ): HttpResponse = {
    Await.result(
      Http().singleRequest(
        HttpRequest(
          uri = s"$url/$path",
          method = verb,
          entity = HttpEntity(ContentTypes.`application/json`, data),
          headers = authorization
        )
      ),
      Duration.Inf
    )
  }

}
