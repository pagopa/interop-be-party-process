package it.pagopa.pdnd.interop.uservice.partyprocess.model


/**
 * @param users  for example: ''null''
 * @param institutionId  for example: ''null''
*/
final case class OnBoardingRequest (
  users: Seq[User],
  institutionId: String
)

