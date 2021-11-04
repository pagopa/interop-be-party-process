package it.pagopa.pdnd.interop.uservice.partyprocess.model


/**
 * @param person  for example: ''null''
 * @param institutions  for example: ''null''
*/
final case class OnBoardingInfo (
  person: PersonInfo,
  institutions: Seq[OnboardingData]
)

