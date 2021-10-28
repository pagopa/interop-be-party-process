/**
 * Party Process Micro Service
 * This service is the party process
 *
 * The version of the OpenAPI document: {{version}}
 * Contact: support@example.com
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */
package it.pagopa.pdnd.interop.uservice.partyprocess.client.model

import it.pagopa.pdnd.interop.uservice.partyprocess.client.invoker.ApiModel

case class User (
  name: String,
  surname: String,
  taxCode: String,
  role: String,
  email: Option[UserEnums.Email] = None,
  platformRole: String
) extends ApiModel

object UserEnums {

  type Email = Email.Value
  object Email extends Enumeration {
    val Manager = Value("Manager")
    val Delegate = Value("Delegate")
    val Operator = Value("Operator")
  }

}
