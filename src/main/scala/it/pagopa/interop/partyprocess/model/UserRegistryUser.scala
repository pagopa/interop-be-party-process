package it.pagopa.interop.partyprocess.model

import it.pagopa.userreg.client.model.{UserResource}

import java.util.UUID

final case class UserRegistryUser(
  id: UUID,
  name: String,
  surname: String,
  taxCode: String,
  email: Map[String, String] = Map()
)

object UserRegistryUser {
  def fromUserResource(resource: UserResource): UserRegistryUser =
    UserRegistryUser(
      id = resource.id,
      taxCode = resource.fiscalCode.orNull,
      name = resource.name.map(n => n.value).orNull,
      surname = resource.familyName.map(s => s.value).orNull,
      email = resource.workContacts.map(w => w.transform((_, v) => v.email.map(e => e.value).orNull)).orNull
    )
}
