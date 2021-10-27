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

case class OnBoardingRequest (
  users: Seq[User],
  institutionId: String
) extends ApiModel
