package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

/** Represents the sealed data types for modeling platform roles
  */
trait ProductRoles {
  val roles: Seq[String]
  def validateProductRoleMapping(productRole: String): Either[Throwable, String] = {
    Either.cond(
      roles.contains(productRole),
      productRole,
      new RuntimeException(s"Invalid platform role => $productRole not supported for ${this.getClass.getSimpleName}")
    )
  }
}

final case class ManagerRoles(roles: Seq[String])  extends ProductRoles
final case class DelegateRoles(roles: Seq[String]) extends ProductRoles
final case class OperatorRoles(roles: Seq[String]) extends ProductRoles
