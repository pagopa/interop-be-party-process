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
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.ResourceNotFoundError
import it.pagopa.interop.partyprocess.api.ExternalApiService
import it.pagopa.interop.partyprocess.api.converters.partymanagement.InstitutionConverter
import it.pagopa.interop.partyprocess.error.PartyProcessErrors._
import it.pagopa.interop.partyprocess.model._
import it.pagopa.interop.partyprocess.service._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class ExternalApiServiceImpl(
  partyManagementService: PartyManagementService,
  relationshipService: RelationshipService,
  productService: ProductService
)(implicit ec: ExecutionContext)
    extends ExternalApiService {

  private val logger = Logger.takingImplicit[ContextFieldsToLog](this.getClass)

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
    logger.info(s"Retrieving institution for externalId $externalId")
    val result = for {
      bearer      <- getFutureBearer(contexts)
      _           <- getUidFuture(contexts)
      institution <- partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
    } yield institution

    onComplete(result) {
      case Success(institution) => getInstitutionByExternalId200(InstitutionConverter.dependencyToApi(institution))
      case Failure(ex: UidValidationError)    =>
        logger.error(s"Error while retrieving institution for externalId $externalId", ex)
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex: ResourceNotFoundError) =>
        logger.info(s"Cannot find institution having externalId $externalId")
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                        =>
        logger.error(s"Error while retrieving institution $externalId", ex)
        val errorResponse: Problem =
          problemOf(StatusCodes.InternalServerError, GetInstitutionByExternalIdError(externalId))
        complete(errorResponse.status, errorResponse)
    }
  }

  /**
   * Code: 200, Message: successful operation, DataType: Seq[RelationshipInfo]
   * Code: 400, Message: Invalid institution id supplied, DataType: Problem
   */
  override def getUserInstitutionRelationshipsByExternalId(
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
    logger.info("Getting relationship for institution having externalId {} and current user", externalId)
    val productsArray     = parseArrayParameters(products)
    val productRolesArray = parseArrayParameters(productRoles)
    val rolesArray        = parseArrayParameters(roles)
    val statesArray       = parseArrayParameters(states)

    val result: Future[Seq[RelationshipInfo]] = for {
      bearer        <- getFutureBearer(contexts)
      uid           <- getUidFuture(contexts)
      userId        <- uid.toFutureUUID
      institution   <- partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
      relationships <- relationshipService.getUserInstitutionRelationships(
        institution,
        productsArray,
        productRolesArray,
        rolesArray,
        statesArray
      )(personId, userId, bearer)
    } yield relationships

    onComplete(result) {
      case Success(relationships) => getUserInstitutionRelationshipsByExternalId200(relationships)
      case Failure(ex)            =>
        logger.error(
          "Error while getting relationship for institution having externalId {} and current user",
          externalId,
          ex
        )
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, RetrievingUserRelationshipsError)
        getUserInstitutionRelationshipsByExternalId400(errorResponse)
    }
  }

  /**
   * Code: 200, Message: successful operation, DataType: Products
   * Code: 404, Message: Institution not found, DataType: Problem
   */
  override def retrieveInstitutionProductsByExternalId(externalId: String, states: String)(implicit
    toEntityMarshallerProducts: ToEntityMarshaller[Products],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    logger.info("Retrieving products for institution having externalId {}", externalId)
    val result = for {
      bearer       <- getFutureBearer(contexts)
      _            <- getUidFuture(contexts)
      statesFilter <- parseArrayParameters(states).traverse(par => ProductState.fromValue(par)).toFuture
      institution  <- partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
      products     <- productService.retrieveInstitutionProducts(institution, statesFilter)(bearer)
    } yield products

    onComplete(result) {
      case Success(institution) if institution.products.isEmpty =>
        val errorResponse: Problem =
          problemOf(StatusCodes.NotFound, ProductsNotFoundError(externalId))
        retrieveInstitutionProductsByExternalId404(errorResponse)
      case Success(institution)            => retrieveInstitutionProductsByExternalId200(institution)
      case Failure(ex: UidValidationError) =>
        logger.error("Error while retrieving products for institution having externalId {}", externalId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                     =>
        logger.error("Error while retrieving products for institution having externalId {}", externalId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, GetProductsError)
        complete(errorResponse.status, errorResponse)
    }
  }

  /**
   * Code: 200, Message: successful operation, DataType: RelationshipInfo
   * Code: 404, Message: There is not a relationship between an ACTIVE manager and the institution/product, DataType: Problem
   * Code: 400, Message: Invalid institution id supplied, DataType: Problem
   */
  override def getManagerInstitutionByExternalId(externalId: String, productId: String)(implicit
    toEntityMarshallerRelationshipInfo: ToEntityMarshaller[RelationshipInfo],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Getting manager for institution having externalId {}", externalId)
    val result: Future[Option[RelationshipInfo]] = for {
      bearer      <- getFutureBearer(contexts)
      institution <- partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
      manager     <- relationshipService.getInstitutionActiveManager(institution, productId)(bearer)
    } yield manager

    onComplete(result) {
      case Success(manager) if manager.isDefined =>
        getManagerInstitutionByExternalId200(manager.get)
      case Success(_)                            =>
        getManagerInstitutionByExternalId404(
          problemOf(StatusCodes.NotFound, GetInstitutionManagerNotFound(externalId, productId))
        )
      case Failure(ex)                           =>
        logger.error("Error while retrieving institution having externalId {}", externalId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, GetInstitutionManagerError(externalId))
        getUserInstitutionRelationshipsByExternalId400(errorResponse)
    }
  }

  /**
   * Code: 200, Message: successful operation, DataType: BillingData
   * Code: 404, Message: There is not confirmed billing data for the institution/product, DataType: Problem
   * Code: 400, Message: Invalid institution id supplied, DataType: Problem
   */
  override def getBillingInstitutionByExternalId(externalId: String, productId: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerBillingData: ToEntityMarshaller[BillingData],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Retrieving billing data for institution having externalId $externalId and productId $productId")
    val result = for {
      bearer      <- getFutureBearer(contexts)
      _           <- getUidFuture(contexts)
      institution <- partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
    } yield institution

    onComplete(result) {
      case Success(institution)              =>
        institution.products
          .get(productId)
          .map(p => getBillingInstitutionByExternalId200(Conversions.institutionBillingToBillingData(institution, p)))
          .getOrElse({
            logger.info(s"The institution having externalId $externalId has not billing data for product $productId")
            getBillingInstitutionByExternalId404(
              problemOf(StatusCodes.NotFound, GetInstitutionBillingNotFound(externalId, productId))
            )
          })
      case Failure(_: ResourceNotFoundError) =>
        logger.info(s"Cannot find institution having externalId $externalId")
        getBillingInstitutionByExternalId404(
          problemOf(StatusCodes.NotFound, GetInstitutionBillingNotFound(externalId, productId))
        )
      case Failure(ex)                       =>
        logger.error("Error while retrieving institution having externalId {}", externalId, ex)
        getBillingInstitutionByExternalId400(problemOf(StatusCodes.BadRequest, GetInstitutionBillingError(externalId)))
    }
  }
}
