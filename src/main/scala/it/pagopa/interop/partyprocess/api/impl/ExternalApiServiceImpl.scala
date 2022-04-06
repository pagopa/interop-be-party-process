package it.pagopa.interop.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, onComplete}
import akka.http.scaladsl.server.Route
import cats.implicits._
import com.typesafe.scalalogging.Logger
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.interop.commons.utils.AkkaUtils.{getFutureBearer, getUidFuture}
import it.pagopa.interop.commons.utils.OpenapiUtils._
import it.pagopa.interop.commons.utils.TypeConversions._
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.api.ExternalApiService
import it.pagopa.interop.partyprocess.api.converters.partymanagement.InstitutionConverter
import it.pagopa.interop.partyprocess.api.impl.Conversions._
import it.pagopa.interop.partyprocess.error.PartyProcessErrors._
import it.pagopa.interop.partyprocess.model._
import it.pagopa.interop.partyprocess.service._
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class ExternalApiServiceImpl(
  partyManagementService: PartyManagementService,
  userRegistryManagementService: UserRegistryManagementService
)(implicit ec: ExecutionContext)
    extends ExternalApiService {

  private val logger = Logger.takingImplicit[ContextFieldsToLog](LoggerFactory.getLogger(this.getClass))

  private final val adminPartyRoles: Set[PartyRole] = Set(PartyRole.MANAGER, PartyRole.DELEGATE, PartyRole.SUB_DELEGATE)

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

  /**
   * Code: 200, Message: successful operation, DataType: Institution
   * Code: 400, Message: Invalid id supplied, DataType: Problem
   * Code: 404, Message: Not found, DataType: Problem
   */
  override def getInstitutionByExternalId(externalId: String)(implicit
    toEntityMarshallerInstitution: ToEntityMarshaller[Institution],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Retrieving institution for institutionId $externalId")
    val result = for {
      bearer      <- getFutureBearer(contexts)
      _           <- getUidFuture(contexts)
      institution <- partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
    } yield institution

    onComplete(result) {
      case Success(institution) => getInstitutionByExternalId200(InstitutionConverter.dependencyToApi(institution))
      case Failure(ex: UidValidationError) =>
        logger.error(s"Error while retrieving institution for institutionId $externalId - ${ex.getMessage}")
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                     =>
        logger.error(s"Error while retrieving institution $externalId - ${ex.getMessage}")
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, GetProductsError)
        complete(errorResponse.status, errorResponse)
    }
  }

  /**
   * Code: 200, Message: successful operation, DataType: Seq[RelationshipInfo]
   * Code: 400, Message: Invalid institution id supplied, DataType: Problem
   */
  override def getUserInstitutionRelationships(
    externalId: String,
    personId: Option[String],
    roles: String,
    states: String,
    products: String,
    productRoles: String
  )(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Getting relationship for institution {} and current user", externalId)
    val productsArray     = parseArrayParameters(products)
    val productRolesArray = parseArrayParameters(productRoles)
    val rolesArray        = parseArrayParameters(roles)
    val statesArray       = parseArrayParameters(states)

    val result: Future[Seq[RelationshipInfo]] = for {
      bearer                     <- getFutureBearer(contexts)
      uid                        <- getUidFuture(contexts)
      userId                     <- uid.toFutureUUID
      institution                <- partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
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
      relationships <- filteredRelationships.items.traverse(rl =>
        Conversions.relationshipToRelationshipsResponse(userRegistryManagementService, partyManagementService)(
          rl,
          bearer
        )
      )
    } yield relationships

    onComplete(result) {
      case Success(relationships) => getUserInstitutionRelationships200(relationships)
      case Failure(ex)            =>
        ex.printStackTrace()
        logger.error(
          "Error while getting relationship for institution {} and current user, reason: {}",
          externalId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, RetrievingUserRelationshipsError)
        getUserInstitutionRelationships400(errorResponse)
    }
  }

  /**
   * Code: 200, Message: successful operation, DataType: Products
   * Code: 404, Message: Institution not found, DataType: Problem
   */
  override def retrieveInstitutionProducts(externalId: String, states: String)(implicit
    toEntityMarshallerProducts: ToEntityMarshaller[Products],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    logger.info("Retrieving products for institution {}", externalId)
    val result = for {
      bearer                   <- getFutureBearer(contexts)
      _                        <- getUidFuture(contexts)
      statesFilter             <- parseArrayParameters(states).traverse(par => ProductState.fromValue(par)).toFuture
      institution              <- partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
      institutionRelationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq(PartyManagementDependency.PartyRole.MANAGER),
        states = statesForAllProducts,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
    } yield Products(products = extractProducts(institutionRelationships, statesFilter))

    onComplete(result) {
      case Success(institution) if institution.products.isEmpty =>
        val errorResponse: Problem =
          problemOf(StatusCodes.NotFound, ProductsNotFoundError(externalId))
        retrieveInstitutionProducts404(errorResponse)
      case Success(institution)                                 => retrieveInstitutionProducts200(institution)
      case Failure(ex: UidValidationError)                      =>
        logger.error("Error while retrieving products for institution {}, reason: {}", externalId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                                          =>
        logger.error("Error while retrieving products for institution {}, reason: {}", externalId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, GetProductsError)
        complete(errorResponse.status, errorResponse)
    }
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
