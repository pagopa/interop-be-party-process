/** Party Process Micro Service
  * This service is the party process
  *
  * The version of the OpenAPI document: {{version}}
  * Contact: support@example.com
  *
  * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
  * https://openapi-generator.tech
  * Do not edit the class manually.
  */
package it.pagopa.pdnd.interop.uservice.partyprocess.client.api

import it.pagopa.pdnd.interop.uservice.partyprocess.client.invoker._
import it.pagopa.pdnd.interop.uservice.partyprocess.client.model._

import java.io.File
import java.util.UUID

object ProcessApi {

  def apply(baseUrl: String = "http://localhost/pdnd-interop-uservice-party-process/}") = new ProcessApi(baseUrl)
}

class ProcessApi(baseUrl: String) {

  /** Activate relationship
    *
    * Expected answers:
    *   code 204 :  (Successful operation)
    *   code 400 : Problem (Invalid id supplied)
    *   code 404 : Problem (Not found)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param relationshipId The identifier of the relationship
    */
  def activateRelationship(relationshipId: UUID)(implicit bearerToken: BearerToken): ApiRequest[Unit] =
    ApiRequest[Unit](ApiMethods.POST, baseUrl, "/relationships/{relationshipId}/activate", "application/json")
      .withCredentials(bearerToken)
      .withPathParam("relationshipId", relationshipId)
      .withSuccessResponse[Unit](204)
      .withErrorResponse[Problem](400)
      .withErrorResponse[Problem](404)

  /** Return ok
    *
    * Expected answers:
    *   code 200 :  (successful operation)
    *   code 400 : Problem (Invalid ID supplied)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param token the token containing the onboardind information
    * @param contract
    */
  def confirmOnBoarding(token: String, contract: File)(implicit bearerToken: BearerToken): ApiRequest[Unit] =
    ApiRequest[Unit](ApiMethods.POST, baseUrl, "/onboarding/complete/{token}", "multipart/form-data")
      .withCredentials(bearerToken)
      .withFormParam("contract", contract)
      .withPathParam("token", token)
      .withSuccessResponse[Unit](200)
      .withErrorResponse[Problem](400)

  /** Return ok
    *
    * Expected answers:
    *   code 201 :  (successful operation)
    *   code 400 : Problem (Invalid ID supplied)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param onBoardingRequest
    */
  def createOperators(onBoardingRequest: OnBoardingRequest)(implicit bearerToken: BearerToken): ApiRequest[Unit] =
    ApiRequest[Unit](ApiMethods.POST, baseUrl, "/onboarding/operators", "application/json")
      .withCredentials(bearerToken)
      .withBody(onBoardingRequest)
      .withSuccessResponse[Unit](201)
      .withErrorResponse[Problem](400)

  /** Given a relationship identifier, it relates the corresponding relationship.
    *
    * Expected answers:
    *   code 204 :  (relationship deleted)
    *   code 400 : Problem (Bad request)
    *   code 404 : Problem (Relationship not found)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param relationshipId the identifier of the relationship to be deleted
    */
  def deleteRelationshipById(relationshipId: UUID)(implicit bearerToken: BearerToken): ApiRequest[Unit] =
    ApiRequest[Unit](ApiMethods.DELETE, baseUrl, "/relationships/{relationshipId}", "application/json")
      .withCredentials(bearerToken)
      .withPathParam("relationshipId", relationshipId)
      .withSuccessResponse[Unit](204)
      .withErrorResponse[Problem](400)
      .withErrorResponse[Problem](404)

  /** Return ok
    *
    * Expected answers:
    *   code 200 : OnBoardingInfo (successful operation)
    *   code 400 : Problem (Invalid ID supplied)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param institutionId UUID of an institution you can filter the retrieval with
    */
  def getOnBoardingInfo(
    institutionId: Option[String] = None
  )(implicit bearerToken: BearerToken): ApiRequest[OnBoardingInfo] =
    ApiRequest[OnBoardingInfo](ApiMethods.GET, baseUrl, "/onboarding/info/", "application/json")
      .withCredentials(bearerToken)
      .withQueryParam("institutionId", institutionId)
      .withSuccessResponse[OnBoardingInfo](200)
      .withErrorResponse[Problem](400)

  /** Expected answers:
    *   code 200 : File (Signed onboarding document retrieved)
    *   code 404 : Problem (Document not found)
    *   code 400 : Problem (Bad request)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param relationshipId the relationship id
    */
  def getOnboardingDocument(relationshipId: String)(implicit bearerToken: BearerToken): ApiRequest[File] =
    ApiRequest[File](ApiMethods.GET, baseUrl, "/onboarding/relationship/{relationshipId}/document", "application/json")
      .withCredentials(bearerToken)
      .withPathParam("relationshipId", relationshipId)
      .withSuccessResponse[File](200)
      .withErrorResponse[Problem](404)
      .withErrorResponse[Problem](400)

  /** Gets relationship
    *
    * Expected answers:
    *   code 200 : RelationshipInfo (successful operation)
    *   code 400 : Problem (Invalid id supplied)
    *   code 404 : Problem (Not found)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param relationshipId The identifier of the relationship
    */
  def getRelationship(relationshipId: UUID)(implicit bearerToken: BearerToken): ApiRequest[RelationshipInfo] =
    ApiRequest[RelationshipInfo](ApiMethods.GET, baseUrl, "/relationships/{relationshipId}", "application/json")
      .withCredentials(bearerToken)
      .withPathParam("relationshipId", relationshipId)
      .withSuccessResponse[RelationshipInfo](200)
      .withErrorResponse[Problem](400)
      .withErrorResponse[Problem](404)

  /** Return ok
    *
    * Expected answers:
    *   code 200 : Seq[RelationshipInfo] (successful operation)
    *   code 400 : Problem (Invalid institution id supplied)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param institutionId The identifier of the institution
    * @param productRoles comma separated sequence of platform roles to filter the response with
    */
  def getUserInstitutionRelationships(institutionId: UUID, productRoles: Option[String] = None)(implicit
    bearerToken: BearerToken
  ): ApiRequest[Seq[RelationshipInfo]] =
    ApiRequest[Seq[RelationshipInfo]](
      ApiMethods.GET,
      baseUrl,
      "/institutions/{institutionId}/relationships",
      "application/json"
    )
      .withCredentials(bearerToken)
      .withQueryParam("productRoles", productRoles)
      .withPathParam("institutionId", institutionId)
      .withSuccessResponse[Seq[RelationshipInfo]](200)
      .withErrorResponse[Problem](400)

  /** Return ok
    *
    * Expected answers:
    *   code 200 :  (successful operation)
    *   code 400 : Problem (Invalid ID supplied)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param token The token to invalidate
    */
  def invalidateOnboarding(token: String)(implicit bearerToken: BearerToken): ApiRequest[Unit] =
    ApiRequest[Unit](ApiMethods.DELETE, baseUrl, "/onboarding/complete/{token}", "application/json")
      .withCredentials(bearerToken)
      .withPathParam("token", token)
      .withSuccessResponse[Unit](200)
      .withErrorResponse[Problem](400)

  /** it performs the onboarding of a new organization on the platform
    *
    * Expected answers:
    *   code 201 : OnBoardingResponse (successful operation)
    *   code 400 : Problem (Invalid ID supplied)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param onBoardingRequest
    */
  def onboardingOrganization(
    onBoardingRequest: OnBoardingRequest
  )(implicit bearerToken: BearerToken): ApiRequest[OnBoardingResponse] =
    ApiRequest[OnBoardingResponse](ApiMethods.POST, baseUrl, "/onboarding/organization", "application/json")
      .withCredentials(bearerToken)
      .withBody(onBoardingRequest)
      .withSuccessResponse[OnBoardingResponse](201)
      .withErrorResponse[Problem](400)

  /** creates users entry on already onboarded institution
    *
    * Expected answers:
    *   code 200 : OnBoardingResponse (successful operation)
    *   code 400 : Problem (Invalid ID supplied)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param onBoardingRequest
    */
  def onboardingUsersOnOrganization(
    onBoardingRequest: OnBoardingRequest
  )(implicit bearerToken: BearerToken): ApiRequest[OnBoardingResponse] =
    ApiRequest[OnBoardingResponse](ApiMethods.POST, baseUrl, "/onboarding/users", "application/json")
      .withCredentials(bearerToken)
      .withBody(onBoardingRequest)
      .withSuccessResponse[OnBoardingResponse](200)
      .withErrorResponse[Problem](400)

  /** replaces institution's products with the set passed as payload
    *
    * Expected answers:
    *   code 200 : Institution (successful operation)
    *   code 404 : Problem (Institution not found)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param institutionId The identifier of the institution
    * @param products
    */
  def replaceInstitutionProducts(institutionId: UUID, products: Products)(implicit
    bearerToken: BearerToken
  ): ApiRequest[Institution] =
    ApiRequest[Institution](ApiMethods.POST, baseUrl, "/institutions/{institutionId}/products", "application/json")
      .withCredentials(bearerToken)
      .withBody(products)
      .withPathParam("institutionId", institutionId)
      .withSuccessResponse[Institution](200)
      .withErrorResponse[Problem](404)

  /** replaces relationships's products with the set passed as payload
    *
    * Expected answers:
    *   code 200 : RelationshipInfo (successful operation)
    *   code 404 : Problem (Relationship not found)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param relationshipId The identifier of the relationship
    * @param products
    */
  def replaceRelationshipProducts(relationshipId: UUID, products: Products)(implicit
    bearerToken: BearerToken
  ): ApiRequest[RelationshipInfo] =
    ApiRequest[RelationshipInfo](
      ApiMethods.POST,
      baseUrl,
      "/relationships/{relationshipId}/products",
      "application/json"
    )
      .withCredentials(bearerToken)
      .withBody(products)
      .withPathParam("relationshipId", relationshipId)
      .withSuccessResponse[RelationshipInfo](200)
      .withErrorResponse[Problem](404)

  /** Suspend relationship
    *
    * Expected answers:
    *   code 204 :  (Successful operation)
    *   code 400 : Problem (Invalid id supplied)
    *   code 404 : Problem (Not found)
    *
    * Available security schemes:
    *   bearerAuth (http)
    *
    * @param relationshipId The identifier of the relationship
    */
  def suspendRelationship(relationshipId: UUID)(implicit bearerToken: BearerToken): ApiRequest[Unit] =
    ApiRequest[Unit](ApiMethods.POST, baseUrl, "/relationships/{relationshipId}/suspend", "application/json")
      .withCredentials(bearerToken)
      .withPathParam("relationshipId", relationshipId)
      .withSuccessResponse[Unit](204)
      .withErrorResponse[Problem](400)
      .withErrorResponse[Problem](404)

}
