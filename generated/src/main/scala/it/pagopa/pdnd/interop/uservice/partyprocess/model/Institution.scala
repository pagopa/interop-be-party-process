package it.pagopa.pdnd.interop.uservice.partyprocess.model

import java.util.UUID

/**
 * @param id  for example: ''97c0f418-bcb3-48d4-825a-fe8b29ae68e5''
 * @param institutionId DN for example: ''aoo=c_f205,o=c_f205,c=it''
 * @param code an accessory code (e.g. codice ipa) for example: ''null''
 * @param description  for example: ''AGENCY X''
 * @param digitalAddress  for example: ''email@pec.mail.org''
 * @param fiscalCode organization fiscal code for example: ''null''
 * @param attributes  for example: ''null''
 * @param products  for example: ''null''
*/
final case class Institution (
  id: UUID,
  institutionId: String,
  code: Option[String],
  description: String,
  digitalAddress: String,
  fiscalCode: String,
  attributes: Seq[String],
  products: Set[String]
)

