package it.pagopa.pdnd.interop.uservice.partyprocess.model


/**
 * = PlatformRolesResponse =
 *
 * This payload contains the currently defined bindings between roles and platform roles.
 *
 * @param managerRoles binding between manager and its platform roles for example: ''null''
 * @param delegateRoles binding between delegate and its platform roles for example: ''null''
 * @param operatorRoles binding between operator and its platform roles for example: ''null''
*/
final case class PlatformRolesResponse (
  managerRoles: Seq[String],
  delegateRoles: Seq[String],
  operatorRoles: Seq[String]
)

