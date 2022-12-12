package it.pagopa.interop.partyprocess.error

import akka.http.scaladsl.model.ErrorInfo
import it.pagopa.interop.commons.utils.errors.ComponentError
import it.pagopa.interop.partyprocess.model.{PartyRole, User}

import java.util.UUID

object PartyProcessErrors {

  final case class ClaimNotFound(claim: String) extends ComponentError("0001", s"Claim $claim not found")

  final case class ContentTypeParsingError(contentType: String, errors: List[ErrorInfo])
      extends ComponentError(
        "0002",
        s"Error trying to parse content type $contentType, reason:\n${errors.map(_.formatPretty).mkString("\n")}"
      )

  final case class ContractNotFound(externalId: String)
      extends ComponentError("0003", s"Contract not found for institution having externalId $externalId")

  final case class InstitutionNotOnboarded(externalId: String, productId: String)
      extends ComponentError(
        "0004",
        s"Institution having externalId $externalId is not onboarded for product $productId"
      )

  final case class InvalidSignature(signatureValidationErrors: List[SignatureValidationError])
      extends ComponentError("0005", s"Signature not valid ${signatureValidationErrors.mkString("\n")}")

  final case class RelationshipDocumentNotFound(relationshipId: String)
      extends ComponentError("0006", s"Relationship document not found for relationship $relationshipId")

  final case class RelationshipNotActivable(relationshipId: String, status: String)
      extends ComponentError("0007", s"Relationship $relationshipId is in status $status and cannot be activated")

  final case class RelationshipNotFound(institutionId: UUID, userId: UUID, role: String)
      extends ComponentError(
        "0008",
        s"Relationship not found for Institution ${institutionId.toString} User ${userId.toString} Role $role"
      )

  final case class RelationshipNotFoundInInstitution(institutionId: UUID, relationshipId: UUID)
      extends ComponentError(
        "0009",
        s"Relationship ${relationshipId.toString} not found for Institution ${institutionId.toString}"
      )

  final case class RelationshipNotSuspendable(relationshipId: String, status: String)
      extends ComponentError("0010", s"Relationship $relationshipId is in status $status and cannot be suspended")

  final case class UidValidationError(message: String)
      extends ComponentError("0013", s"Error while uid validation: $message")

  final case class MultipleProductsRequestError(products: Seq[String])
      extends ComponentError(
        "0014",
        s"Multi products request is forbidden: Products in request: ${products.mkString(",")} "
      )

  final case object OnboardingVerificationError extends ComponentError("0015", "Error while verifying onboarding")
  final case object GettingOnboardingInfoError  extends ComponentError("0016", "Error while getting onboarding info")
  final case object OnboardingOperationError
      extends ComponentError("0017", "Error while performing onboarding operation")
  final case object OnboardingLegalsError       extends ComponentError("0018", "Error while onboarding legals")
  final case object OnboardingSubdelegatesError extends ComponentError("0019", "Error while onboarding subdelegates")
  final case object OnboardingOperatorsError    extends ComponentError("0020", "Error while onboarding operators")
  final case object ConfirmOnboardingError      extends ComponentError("0021", "Error while confirming onboarding")
  final case object InvalidateOnboardingError   extends ComponentError("0022", "Error while invalidating onboarding")
  final case object RetrievingUserRelationshipsError
      extends ComponentError("0023", "Error while retrieving user relationships")
  final case object ActivateRelationshipError   extends ComponentError("0024", "Error while activating relationship")
  final case object SuspendingRelationshipError extends ComponentError("0025", "Error while suspending relationship")
  final case object BadRequestError             extends ComponentError("0026", "Bad request error")
  final case class OnboardingDocumentError(relationshipId: String)
      extends ComponentError("0027", s"Error retrieving document for relationship $relationshipId")
  final case object GetRelationshipError        extends ComponentError("0028", "Error while getting relationship")
  final case object DeleteRelationshipError     extends ComponentError("0029", "Error while deleting relationship")

  final case class ProductsNotFoundError(externalId: String)
      extends ComponentError("0030", s"Products not found for institution having externalId $externalId")

  final case object GetProductsError extends ComponentError("0031", "Error while getting products")

  final case object ManagerFoundError extends ComponentError("0032", "Onboarded managers found for this institution")
  final case class ManagerNotFoundError(institutionIds: Seq[String], product: String)
      extends ComponentError(
        "0033",
        s"No onboarded managers for at least one of the institutions $institutionIds and product $product"
      )

  final case class RolesNotAdmittedError(users: Seq[User], roles: Set[PartyRole])
      extends ComponentError(
        "0034",
        s"Roles ${users.filter(user => !roles.contains(user.role)).mkString(", ")} are not admitted for this operation"
      )

  final case class InvalidCategoryError(category: String) extends ComponentError("0035", s"Invalid category $category")

  final case class InstitutionNotFound(institutionId: Option[String], externalInstitutionId: Option[String])
      extends ComponentError(
        "0036",
        s"Cannot find Institution using institutionId $institutionId and externalInstitutionId $externalInstitutionId"
      )

  final case object CreateInstitutionError extends ComponentError("0037", "Error while creating requested institution")
  final case object CreateInstitutionConflict extends ComponentError("0038", "Institution already exists")
  final case object CreateInstitutionNotFound
      extends ComponentError("0039", "Institution doesn't exist in party-registry")

  final case class GetInstitutionError(institutionId: String)
      extends ComponentError("0040", s"Error while retrieving institution having id $institutionId")
  final case class GetInstitutionByExternalIdError(externalId: String)
      extends ComponentError("0041", s"Error while retrieving institution having externalId $externalId")

  final case class GetInstitutionManagerError(externalId: String)
      extends ComponentError("0042", s"Error while retrieving institution having externalId $externalId")
  final case class GetInstitutionManagerNotFound(externalId: String, productId: String)
      extends ComponentError(
        "0043",
        s"Cannot find active manager for institution having externalId $externalId and product $productId"
      )

  final case class GetInstitutionBillingError(externalId: String)
      extends ComponentError("0044", s"Error while retrieving institution having externalId $externalId")
  final case class GetInstitutionBillingNotFound(externalId: String, productId: String)
      extends ComponentError(
        "0045",
        s"Cannot find billing data for institution having externalId $externalId and product $productId"
      )

  final case class OnboardingInvalidUpdates(externalId: String)
      extends ComponentError("0046", s"Cannot perform data overrides on institution having external id $externalId")

  final case class TokenVerificationFatalError(tokenId: String, error: String)
      extends ComponentError("0048", s"Error on retrieve $tokenId: $error")

  final case class GeoTaxonomyCodeNotFound(geoTaxonomyCode: String, error: String)
      extends ComponentError("0049", s"Error on retrieve geographic taxonomy code $geoTaxonomyCode: $error")

  final case object GeoTaxonomyCodeError
      extends ComponentError("0050", "Error while retrieving institution geographic taxonomy")
}
