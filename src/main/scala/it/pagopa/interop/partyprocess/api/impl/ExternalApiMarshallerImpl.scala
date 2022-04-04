package it.pagopa.interop.partyprocess.api.impl

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import it.pagopa.interop.partyprocess.api.ExternalApiMarshaller
import it.pagopa.interop.partyprocess.model._
import spray.json._

object ExternalApiMarshallerImpl extends ExternalApiMarshaller with SprayJsonSupport with DefaultJsonProtocol {

  override implicit def toEntityMarshallerInstitution: ToEntityMarshaller[Institution] =
    sprayJsonMarshaller[Institution]

  override implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem] = sprayJsonMarshaller[Problem]

  override implicit def toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]] =
    sprayJsonMarshaller[Seq[RelationshipInfo]]

  override implicit def toEntityMarshallerProducts: ToEntityMarshaller[Products] = sprayJsonMarshaller[Products]
}
