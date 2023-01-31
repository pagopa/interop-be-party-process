package it.pagopa.interop.partyprocess.api.converters.partymanagement

import it.pagopa.interop.partymanagement.client.model.{Institution => DependencyInstitution}
import it.pagopa.interop.partyprocess.model.Institution

object InstitutionConverter {
  def dependencyToApi(institution: DependencyInstitution): Institution = {
    Institution(
      id = institution.id,
      externalId = institution.externalId,
      originId = institution.originId,
      description = institution.description,
      digitalAddress = institution.digitalAddress,
      address = institution.address,
      zipCode = institution.zipCode,
      taxCode = institution.taxCode,
      institutionType = institution.institutionType,
      origin = institution.origin,
      attributes = institution.attributes.map(AttributeConverter.dependencyToApi),
      paymentServiceProvider = institution.paymentServiceProvider.map(PaymentServiceProviderConverter.dependencyToApi),
      dataProtectionOfficer = institution.dataProtectionOfficer.map(DataProtectionOfficerConverter.dependencyToApi),
      geographicTaxonomies = institution.geographicTaxonomies.map(GeographicTaxonomyConverter.dependencyToApi),
      rea = institution.rea,
      shareCapital = institution.shareCapital,
      businessRegisterPlace = institution.businessRegisterPlace,
      supportEmail = institution.supportEmail,
      supportPhone = institution.supportEmail,
      imported = institution.imported
    )
  }
}
