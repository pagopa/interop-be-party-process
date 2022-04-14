package it.pagopa.interop.partyprocess.api.converters.partymanagement

import it.pagopa.interop.partymanagement.client.model.{Institution => DependencyInstitution}
import it.pagopa.interop.partyprocess.model.Institution

object InstitutionConverter {
  def dependencyToApi(institution: DependencyInstitution): Institution = {
    Institution(
      id = institution.id,
      institutionId = institution.institutionId,
      description = institution.description,
      digitalAddress = institution.digitalAddress,
      address = institution.address,
      zipCode = institution.zipCode,
      taxCode = institution.taxCode,
      institutionType = institution.institutionType,
      origin = institution.origin,
      attributes = institution.attributes.map(AttributeConverter.dependencyToApi)
    )
  }
}
