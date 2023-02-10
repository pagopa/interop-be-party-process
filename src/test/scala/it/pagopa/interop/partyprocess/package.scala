package it.pagopa.interop

import akka.actor.ClassicActorSystemProvider
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import it.pagopa.interop.commons.utils.SprayCommonFormats.uuidFormat
import it.pagopa.interop.partyprocess.api.impl._
import it.pagopa.interop.partyprocess.model._
import spray.json.RootJsonFormat

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

package object partyprocess extends SprayJsonSupport {
  final lazy val url: String =
    s"http://localhost:8088/party-process/${buildinfo.BuildInfo.interfaceVersion}"

  implicit val timeout: Timeout = 3.seconds

  implicit val fromEntityUnmarshallerOnboardingInfo: FromEntityUnmarshaller[OnboardingInfo] =
    sprayJsonUnmarshaller[OnboardingInfo]

  implicit val fromEntityUnmarshallerProducts: FromEntityUnmarshaller[Products] =
    sprayJsonUnmarshaller[Products]

  implicit val fromEntityUnmarshallerRelationshipsResponse: FromEntityUnmarshaller[Seq[RelationshipInfo]] =
    sprayJsonUnmarshaller[Seq[RelationshipInfo]]

  implicit val userFormat: RootJsonFormat[User]                             = jsonFormat7(User)
  implicit val onboardingContractFormat: RootJsonFormat[OnboardingContract] = jsonFormat2(OnboardingContract)

  implicit val onboardingInstitutionRequestFormat: RootJsonFormat[OnboardingInstitutionRequest] =
    jsonFormat10(OnboardingInstitutionRequest)
  implicit val onboardingLegalUsersRequestFormat: RootJsonFormat[OnboardingLegalUsersRequest]   =
    jsonFormat7(OnboardingLegalUsersRequest)
  implicit val onboardingUsersRequestFormat: RootJsonFormat[OnboardingUsersRequest]             =
    jsonFormat3(OnboardingUsersRequest)
  implicit val InstitutionPutRequestFormat: RootJsonFormat[InstitutionPut]                      =
    jsonFormat1(InstitutionPut)

  implicit def fromEntityUnmarshallerOnboardingInstitutionRequest: ToEntityMarshaller[OnboardingInstitutionRequest] =
    sprayJsonMarshaller[OnboardingInstitutionRequest]

  implicit def fromEntityUnmarshallerLegalUsersOnboardingRequest: ToEntityMarshaller[OnboardingLegalUsersRequest] =
    sprayJsonMarshaller[OnboardingLegalUsersRequest]

  implicit def fromEntityUnmarshallerOnboardingUsersRequest: ToEntityMarshaller[OnboardingUsersRequest] =
    sprayJsonMarshaller[OnboardingUsersRequest]

  implicit val fromEntityUnmarshallerInstitution: FromEntityUnmarshaller[Institution] =
    sprayJsonUnmarshaller[Institution]

  implicit val fromEntityUnmarshallerRelationshipInfo: FromEntityUnmarshaller[RelationshipInfo] =
    sprayJsonUnmarshaller[RelationshipInfo]

  implicit val fromEntityUnmarshallerBillingData: FromEntityUnmarshaller[BillingData] =
    sprayJsonUnmarshaller[BillingData]

  implicit val fromEntityUnmarshallerGeoTaxonomiesExt: FromEntityUnmarshaller[GeographicTaxonomyExt] =
    sprayJsonUnmarshaller[GeographicTaxonomyExt]

  def request(data: Source[ByteString, Any], path: String, verb: HttpMethod)(implicit
    system: ClassicActorSystemProvider
  ): HttpResponse = {
    Await.result(
      Http().singleRequest(
        HttpRequest(uri = s"$url/$path", method = verb, entity = HttpEntity(ContentTypes.`application/json`, data))
      ),
      Duration.Inf
    )
  }

}
