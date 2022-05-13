package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.partymanagement.client.model.Institution
import it.pagopa.interop.partyprocess.model.RelationshipInfo

import java.util.UUID
import scala.concurrent.Future

trait RelationshipService {
  def getUserInstitutionRelationships(
    institution: Institution,
    productsArray: List[String],
    productRolesArray: List[String],
    rolesArray: List[String],
    statesArray: List[String]
  )(personId: Option[String], userId: UUID, bearer: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Seq[RelationshipInfo]]
}
