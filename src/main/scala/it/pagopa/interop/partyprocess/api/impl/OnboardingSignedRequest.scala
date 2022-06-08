package it.pagopa.interop.partyprocess.api.impl

import it.pagopa.interop.partyprocess.model._

/**
 * @param users  for example: ''null''
 * @param institutionExternalId  for example: ''null''
 * @param institutionUpdate  for example: ''null''
 * @param pricingPlan pricing plan for example: ''null''
 * @param billing  for example: ''null''
 * @param contract  for example: ''null''
*/
final case class OnboardingSignedRequest(
  productId: String,
  productName: String,
  users: Seq[User],
  institutionUpdate: Option[InstitutionUpdate] = None,
  pricingPlan: Option[String] = None,
  billing: Option[Billing],
  contract: OnboardingContract
)

object OnboardingSignedRequest {
  def fromApi(onboardingRequest: OnboardingInstitutionRequest): OnboardingSignedRequest =
    OnboardingSignedRequest(
      productId = onboardingRequest.productId,
      productName = onboardingRequest.productName,
      users = onboardingRequest.users,
      institutionUpdate = onboardingRequest.institutionUpdate,
      pricingPlan = onboardingRequest.pricingPlan,
      billing = Option(onboardingRequest.billing),
      contract = onboardingRequest.contract
    )

  def fromApi(onboardingRequest: OnboardingLegalUsersRequest): OnboardingSignedRequest =
    OnboardingSignedRequest(
      productId = onboardingRequest.productId,
      productName = onboardingRequest.productName,
      users = onboardingRequest.users,
      institutionUpdate = Option.empty,
      pricingPlan = Option.empty,
      billing = Option.empty,
      contract = onboardingRequest.contract
    )
}
