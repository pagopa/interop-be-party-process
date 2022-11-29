package it.pagopa.interop.partyprocess.api.impl

import it.pagopa.interop.partymanagement.client.model.{
  InstitutionProduct,
  Relationship,
  RelationshipProduct,
  GeographicTaxonomy => DependencyGeographicTaxonomy
}
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.model._

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
      case PartyManagementDependency.RelationshipState.PENDING       => RelationshipState.PENDING
      case PartyManagementDependency.RelationshipState.ACTIVE        => RelationshipState.ACTIVE
      case PartyManagementDependency.RelationshipState.SUSPENDED     => RelationshipState.SUSPENDED
      case PartyManagementDependency.RelationshipState.DELETED       => RelationshipState.DELETED
      case PartyManagementDependency.RelationshipState.REJECTED      => RelationshipState.REJECTED
      case PartyManagementDependency.RelationshipState.TOBEVALIDATED => RelationshipState.TOBEVALIDATED
    }

  def relationshipProductToApi(product: RelationshipProduct): ProductInfo = {
    ProductInfo(id = product.id, role = product.role, createdAt = product.createdAt)
  }

  def relationshipToRelationshipsResponse(relationship: Relationship): RelationshipInfo = {
    relationshipToRelationshipInfo(relationship)
  }

  def institutionUpdateToApi(institutionUpdate: PartyManagementDependency.InstitutionUpdate): InstitutionUpdate = {
    InstitutionUpdate(
      institutionType = institutionUpdate.institutionType,
      description = institutionUpdate.description,
      digitalAddress = institutionUpdate.digitalAddress,
      address = institutionUpdate.address,
      zipCode = institutionUpdate.zipCode,
      taxCode = institutionUpdate.taxCode,
      geographicTaxonomies = institutionUpdate.geographicTaxonomies.map(geographicTaxonomyConverter)
    )
  }

  def billingToApi(billing: PartyManagementDependency.Billing): Billing = {
    Billing(
      vatNumber = billing.vatNumber,
      recipientCode = billing.recipientCode,
      publicServices = billing.publicServices
    )
  }

  private def relationshipToRelationshipInfo(relationship: Relationship): RelationshipInfo = {
    RelationshipInfo(
      id = relationship.id,
      from = relationship.from,
      to = relationship.to,
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

  def institutionBillingToBillingData(
    institution: PartyManagementDependency.Institution,
    institutionProduct: InstitutionProduct
  ): BillingData = {
    BillingData(
      institutionId = institution.id,
      externalId = institution.externalId,
      origin = institution.origin,
      originId = institution.originId,
      description = institution.description,
      taxCode = institution.taxCode,
      digitalAddress = institution.digitalAddress,
      address = institution.address,
      zipCode = institution.zipCode,
      institutionType = institution.institutionType,
      pricingPlan = institutionProduct.pricingPlan,
      billing = billingToApi(institutionProduct.billing)
    )
  }

  private def geographicTaxonomyConverter(geographicTaxonomy: DependencyGeographicTaxonomy): GeographicTaxonomy = {
    GeographicTaxonomy(code = geographicTaxonomy.code, desc = geographicTaxonomy.desc)
  }

}
