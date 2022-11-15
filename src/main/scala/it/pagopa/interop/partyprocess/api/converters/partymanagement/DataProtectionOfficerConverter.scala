package it.pagopa.interop.partyprocess.api.converters.partymanagement

import it.pagopa.interop.partymanagement.client.model.{DataProtectionOfficer => DependencyDataProtectionOfficer}
import it.pagopa.interop.partyprocess.model.DataProtectionOfficer

object DataProtectionOfficerConverter {
  def dependencyToApi(dataProtectionOfficer: DependencyDataProtectionOfficer): DataProtectionOfficer =
    DataProtectionOfficer(
      address = dataProtectionOfficer.address,
      email = dataProtectionOfficer.email,
      pec = dataProtectionOfficer.pec
    )
}
