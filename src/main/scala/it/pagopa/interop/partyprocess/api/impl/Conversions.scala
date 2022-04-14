package it.pagopa.interop.partyprocess.api.impl

import it.pagopa.interop.partymanagement.client.model.{Relationship, RelationshipProduct}
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.model._
import it.pagopa.interop.partyprocess.service.{PartyManagementService, UserRegistryManagementService}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.User
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.{model => UserRegistryManagementDependency}

import scala.concurrent.{ExecutionContext, Future}

object Conversions {
  def roleToDependency(role: PartyRole): PartyManagementDependency.PartyRole =
    role match {
      case PartyRole.MANAGER      => PartyManagementDependency.PartyRole.MANAGER
      case PartyRole.DELEGATE     => PartyManagementDependency.PartyRole.DELEGATE
      case PartyRole.SUB_DELEGATE => PartyManagementDependency.PartyRole.SUB_DELEGATE
      case PartyRole.OPERATOR     => PartyManagementDependency.PartyRole.OPERATOR
    }

  def roleToApi(role: PartyManagementDependency.PartyRole): PartyRole =
    role match {
      case PartyManagementDependency.PartyRole.MANAGER      => PartyRole.MANAGER
      case PartyManagementDependency.PartyRole.DELEGATE     => PartyRole.DELEGATE
      case PartyManagementDependency.PartyRole.SUB_DELEGATE => PartyRole.SUB_DELEGATE
      case PartyManagementDependency.PartyRole.OPERATOR     => PartyRole.OPERATOR
    }

  def relationshipStateToApi(status: PartyManagementDependency.RelationshipState): RelationshipState =
    status match {
      case PartyManagementDependency.RelationshipState.PENDING   => RelationshipState.PENDING
      case PartyManagementDependency.RelationshipState.ACTIVE    => RelationshipState.ACTIVE
      case PartyManagementDependency.RelationshipState.SUSPENDED => RelationshipState.SUSPENDED
      case PartyManagementDependency.RelationshipState.DELETED   => RelationshipState.DELETED
      case PartyManagementDependency.RelationshipState.REJECTED  => RelationshipState.REJECTED
    }

  def relationshipProductToApi(product: RelationshipProduct): ProductInfo = {
    ProductInfo(id = product.id, role = product.role, createdAt = product.createdAt)
  }

  def certificationToApi(certification: UserRegistryManagementDependency.Certification): Certification =
    certification match {
      case UserRegistryManagementDependency.Certification.NONE => Certification.NONE
      case UserRegistryManagementDependency.Certification.SPID => Certification.SPID
    }

  def relationshipToRelationshipsResponse(
    userRegistryManagementService: UserRegistryManagementService,
    partyManagementService: PartyManagementService
  )(relationship: Relationship, bearer: String)(implicit ec: ExecutionContext): Future[RelationshipInfo] = {
    for {
      institution <- partyManagementService.retrieveInstitution(relationship.to)(bearer)
      user        <- userRegistryManagementService.getUserById(relationship.from)
    } yield relationshipToRelationshipInfo(relationship, user, institution)
  }

  def institutionUpdateToApi(institutionUpdate: PartyManagementDependency.InstitutionUpdate): InstitutionUpdate = {
    InstitutionUpdate(
      institutionType = institutionUpdate.institutionType,
      description = institutionUpdate.description,
      digitalAddress = institutionUpdate.digitalAddress,
      address = institutionUpdate.address,
      taxCode = institutionUpdate.taxCode
    )
  }

  def billingToApi(billing: PartyManagementDependency.Billing): Billing = {
    Billing(
      vatNumber = billing.vatNumber,
      recipientCode = billing.recipientCode,
      publicServices = billing.publicServices
    )
  }

  private def relationshipToRelationshipInfo(
    relationship: Relationship,
    user: User,
    institution: PartyManagementDependency.Institution
  ): RelationshipInfo = {
    RelationshipInfo(
      id = relationship.id,
      from = relationship.from,
      to = relationship.to,
      name = user.name,
      surname = user.surname,
      taxCode = user.externalId,
      certification = certificationToApi(user.certification),
      institutionContacts =
        user.extras.email.map(email => institution.institutionId -> Seq(Contact(email = email))).toMap,
      role = roleToApi(relationship.role),
      product = relationshipProductToApi(relationship.product),
      state = relationshipStateToApi(relationship.state),
      createdAt = relationship.createdAt,
      updatedAt = relationship.updatedAt,
      pricingPlan = relationship.pricingPlan,
      institutionUpdate = relationship.institutionUpdate.map(institutionUpdateToApi),
      billing = relationship.billing.map(billingToApi)
    )
  }

}
