package it.pagopa.interop.partyprocess.api.converters.partymanagement

import it.pagopa.interop.partymanagement.client.model.{Attribute => DependencyAttribute}
import it.pagopa.interop.partyprocess.model.Attribute
object AttributeConverter {

  def dependencyToApi(attribute: DependencyAttribute): Attribute = {
    Attribute(origin = attribute.origin, code = attribute.code, description = attribute.description)

  }
}
