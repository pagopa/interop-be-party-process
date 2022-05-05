package it.pagopa.interop.partyprocess.service.impl

import it.pagopa.interop.partymanagement.client.model.Institution
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.model._
import it.pagopa.interop.partyprocess.service._

import scala.concurrent.{ExecutionContext, Future}

class ProductServiceImpl(partyManagementService: PartyManagementService)(implicit ec: ExecutionContext)
    extends ProductService {
  private final val statesForAllProducts: Seq[PartyManagementDependency.RelationshipState] =
    Seq(
      PartyManagementDependency.RelationshipState.PENDING,
      PartyManagementDependency.RelationshipState.ACTIVE,
      PartyManagementDependency.RelationshipState.SUSPENDED,
      PartyManagementDependency.RelationshipState.DELETED
    )

  private final val statesForActiveProducts: Set[PartyManagementDependency.RelationshipState] =
    Set[PartyManagementDependency.RelationshipState](
      PartyManagementDependency.RelationshipState.ACTIVE,
      PartyManagementDependency.RelationshipState.SUSPENDED,
      PartyManagementDependency.RelationshipState.DELETED
    )

  private final val statesForPendingProducts: Set[PartyManagementDependency.RelationshipState] =
    Set[PartyManagementDependency.RelationshipState](PartyManagementDependency.RelationshipState.PENDING)

  override def retrieveInstitutionProducts(institution: Institution, statesFilter: List[ProductState])(
    bearer: String
  ): Future[Products] = {
    for {
      institutionRelationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq(PartyManagementDependency.PartyRole.MANAGER),
        states = statesForAllProducts,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
    } yield Products(products = extractProducts(institutionRelationships, statesFilter))
  }

  private def extractProducts(
    relationships: PartyManagementDependency.Relationships,
    statesFilter: List[ProductState]
  ): Seq[Product] = {

    val grouped: Seq[(String, Seq[PartyManagementDependency.RelationshipState])] = relationships.items
      .groupBy(rl => rl.product.id)
      .toSeq
      .map { case (product, relationships) => product -> relationships.map(_.state) }

    val allProducts: Seq[Product] = grouped.flatMap {
      case (product, states) if states.exists(st => statesForActiveProducts.contains(st))                     =>
        Some(Product(product, ProductState.ACTIVE))
      case (product, states) if states.nonEmpty && states.forall(st => statesForPendingProducts.contains(st)) =>
        Some(Product(product, ProductState.PENDING))
      case _                                                                                                  => None
    }.distinct

    allProducts.filter(product => statesFilter.isEmpty || statesFilter.contains(product.state))

  }
}
