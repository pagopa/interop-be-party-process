package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.RelationshipProduct
import it.pagopa.pdnd.interop.uservice.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.pdnd.interop.uservice.partyprocess.model._

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
    ProductInfo(id = product.id, role = product.role, timestamp = product.timestamp)
  }

}
