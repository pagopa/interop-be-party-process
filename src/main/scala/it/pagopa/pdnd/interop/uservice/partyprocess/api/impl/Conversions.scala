package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import it.pagopa.pdnd.interop.uservice.partymanagement.client.{model => PartyManagementDependency}

object Conversions {
  def roleToDependency(role: PartyRoleEnum): PartyManagementDependency.PartyRoleEnum =
    role match {
      case MANAGER  => PartyManagementDependency.MANAGER
      case DELEGATE => PartyManagementDependency.DELEGATE
      case OPERATOR => PartyManagementDependency.OPERATOR
    }

  def roleToApi(role: PartyManagementDependency.PartyRoleEnum): PartyRoleEnum =
    role match {
      case PartyManagementDependency.MANAGER  => MANAGER
      case PartyManagementDependency.DELEGATE => DELEGATE
      case PartyManagementDependency.OPERATOR => OPERATOR
    }

  def relationshipStatusToApi(status: PartyManagementDependency.RelationshipStatusEnum): RelationshipStatusEnum =
    status match {
      case PartyManagementDependency.PENDING   => PENDING
      case PartyManagementDependency.ACTIVE    => ACTIVE
      case PartyManagementDependency.SUSPENDED => SUSPENDED
      case PartyManagementDependency.DELETED   => DELETED
      case PartyManagementDependency.REJECTED  => REJECTED
    }

}
