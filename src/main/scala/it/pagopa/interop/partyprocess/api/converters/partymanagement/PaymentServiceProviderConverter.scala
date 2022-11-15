package it.pagopa.interop.partyprocess.api.converters.partymanagement

import it.pagopa.interop.partymanagement.client.model.{PaymentServiceProvider => DependencyPaymentServiceProvider}
import it.pagopa.interop.partyprocess.model.PaymentServiceProvider

object PaymentServiceProviderConverter {
  def dependencyToApi(paymentServiceProvider: DependencyPaymentServiceProvider): PaymentServiceProvider =
    PaymentServiceProvider(
      abiCode = paymentServiceProvider.abiCode,
      businessRegisterNumber = paymentServiceProvider.businessRegisterNumber,
      legalRegisterName = paymentServiceProvider.legalRegisterName,
      legalRegisterNumber = paymentServiceProvider.legalRegisterNumber,
      vatNumberGroup = paymentServiceProvider.vatNumberGroup
    )
}
