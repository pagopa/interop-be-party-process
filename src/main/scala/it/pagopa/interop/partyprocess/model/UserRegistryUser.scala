package it.pagopa.interop.partyprocess.model

import it.pagopa.userreg.client.model.UserResource

import java.util.UUID

final case class UserRegistryUser(
  id: UUID,
  name: Option[String],
  surname: Option[String],
  taxCode: Option[String],
  email: Option[Map[String, String]] = None
)

object UserRegistryUser {
  def fromUserResource(resource: UserResource): UserRegistryUser =
    UserRegistryUser(
      id = resource.id,
      taxCode = resource.fiscalCode,
      name = resource.name.map(n => n.value),
      surname = resource.familyName.map(s => s.value),
      email = resource.workContacts.map(w => w.transform((_, v) => v.email.map(e => e.value).getOrElse("")))
    )
}
