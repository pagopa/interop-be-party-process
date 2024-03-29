package it.pagopa.interop.partyprocess.api.impl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import it.pagopa.interop.partyprocess.api.ProcessApiMarshaller
import it.pagopa.interop.partyprocess.model._
import spray.json._

import java.io.File
import java.nio.charset.StandardCharsets
import scala.io.{BufferedSource, Codec}

object ProcessApiMarshallerImpl extends ProcessApiMarshaller with SprayJsonSupport with DefaultJsonProtocol {

  override implicit def toEntityMarshallerFile: ToEntityMarshaller[File] =
    Marshaller.withFixedContentType(ContentTypes.`application/octet-stream`) { f =>
      val source: BufferedSource = scala.io.Source.fromFile(f.getPath)(Codec(StandardCharsets.UTF_8.name))
      val out: String            = source.mkString
      source.close()
      out.getBytes(StandardCharsets.UTF_8.name)
    }

  override implicit def toEntityMarshallerInstitution: ToEntityMarshaller[Institution] =
    sprayJsonMarshaller[Institution]

  // override implicit def toEntityMarshallerInstitutionSeed: ToEntityMarshaller[InstitutionSeed] = sprayJsonMarshaller[InstitutionSeed]

  override implicit def toEntityMarshallerOnboardingInfo: ToEntityMarshaller[OnboardingInfo] =
    sprayJsonMarshaller[OnboardingInfo]

  override implicit def fromEntityUnmarshallerOnboardingInstitutionRequest
    : FromEntityUnmarshaller[OnboardingInstitutionRequest] =
    sprayJsonUnmarshaller[OnboardingInstitutionRequest]

  override implicit def fromEntityUnmarshallerOnboardingLegalUsersRequest
    : FromEntityUnmarshaller[OnboardingLegalUsersRequest] =
    sprayJsonUnmarshaller[OnboardingLegalUsersRequest]

  override implicit def fromEntityUnmarshallerOnboardingUsersRequest: FromEntityUnmarshaller[OnboardingUsersRequest] =
    sprayJsonUnmarshaller[OnboardingUsersRequest]

  override implicit def fromEntityUnmarshallerInstitutionSeed: FromEntityUnmarshaller[InstitutionSeed] =
    sprayJsonUnmarshaller[InstitutionSeed]

  override implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem] = sprayJsonMarshaller[Problem]

  override implicit def toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]] =
    sprayJsonMarshaller[Seq[RelationshipInfo]]

  override implicit def toEntityMarshallerRelationshipInfo: ToEntityMarshaller[RelationshipInfo] =
    sprayJsonMarshaller[RelationshipInfo]

  override implicit def toEntityMarshallerProducts: ToEntityMarshaller[Products] = sprayJsonMarshaller[Products]

  override implicit def toEntityMarshallerGeographicTaxonomyExtarray: ToEntityMarshaller[Seq[GeographicTaxonomyExt]] =
    sprayJsonMarshaller[Seq[GeographicTaxonomyExt]]

  override implicit def fromEntityUnmarshallerInstitutionPut: FromEntityUnmarshaller[InstitutionPut] =
    sprayJsonUnmarshaller[InstitutionPut]
}
