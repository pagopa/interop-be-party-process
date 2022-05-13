package it.pagopa.interop.partyprocess.service.impl

import cats.implicits._
import it.pagopa.interop.commons.utils.TypeConversions._
import it.pagopa.interop.partymanagement.client.model.Institution
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.api.impl.Conversions
import it.pagopa.interop.partyprocess.api.impl.Conversions._
import it.pagopa.interop.partyprocess.model._
import it.pagopa.interop.partyprocess.service._

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class RelationshipServiceImpl(partyManagementService: PartyManagementService)(implicit ec: ExecutionContext)
    extends RelationshipService {
  private final val adminPartyRoles: Set[PartyRole] = Set(PartyRole.MANAGER, PartyRole.DELEGATE, PartyRole.SUB_DELEGATE)

  override def getUserInstitutionRelationships(
    institution: Institution,
    productsArray: List[String],
    productRolesArray: List[String],
    rolesArray: List[String],
    statesArray: List[String]
  )(personId: Option[String], userId: UUID, bearer: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Seq[RelationshipInfo]] = {
    for {
      rolesEnumArray             <- rolesArray.traverse(PartyManagementDependency.PartyRole.fromValue).toFuture
      statesEnumArray            <- statesArray.traverse(PartyManagementDependency.RelationshipState.fromValue).toFuture
      userAdminRelationships     <- partyManagementService.retrieveRelationships(
        from = Some(userId),
        to = Some(institution.id),
        roles = adminPartyRoles.map(roleToDependency).toSeq,
        states =
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      from                       <- personId.traverse(_.toFutureUUID)
      institutionIdRelationships <- partyManagementService.retrieveRelationships(
        from = from,
        to = Some(institution.id),
        roles = rolesEnumArray,
        states = statesEnumArray,
        products = productsArray,
        productRoles = productRolesArray
      )(bearer)
      filteredRelationships = filterFoundRelationshipsByCurrentUser(
        userId,
        userAdminRelationships,
        institutionIdRelationships
      )
      relationships         = filteredRelationships.items.map(rl => Conversions.relationshipToRelationshipsResponse(rl))
    } yield relationships
  }

  private def filterFoundRelationshipsByCurrentUser(
    currentUserId: UUID,
    userAdminRelationships: PartyManagementDependency.Relationships,
    institutionIdRelationships: PartyManagementDependency.Relationships
  ): PartyManagementDependency.Relationships = {
    val isAdmin: Boolean                                               = userAdminRelationships.items.nonEmpty
    val userRelationships: Seq[PartyManagementDependency.Relationship] =
      institutionIdRelationships.items.filter(_.from == currentUserId)

    val filteredRelationships: Seq[PartyManagementDependency.Relationship] =
      if (isAdmin) institutionIdRelationships.items
      else userRelationships

    PartyManagementDependency.Relationships(filteredRelationships)
  }
}
