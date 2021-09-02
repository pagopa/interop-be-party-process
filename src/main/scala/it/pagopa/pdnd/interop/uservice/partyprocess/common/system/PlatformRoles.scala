package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

/** Represents the sealed data types for modeling platform roles
  */
trait PlatformRoles {
  val roles: Seq[String]
  def hasPlatformRoleMapping(platformRole: String): Either[Throwable, String] = {
    Either.cond(
      roles.contains(platformRole),
      platformRole,
      new RuntimeException(s"Invalid platform role => $platformRole not supported for ${this.getClass.getSimpleName}")
    )
  }
}

final case class ManagerRoles(roles: Seq[String])  extends PlatformRoles
final case class DelegateRoles(roles: Seq[String]) extends PlatformRoles
final case class OperatorRoles(roles: Seq[String]) extends PlatformRoles
