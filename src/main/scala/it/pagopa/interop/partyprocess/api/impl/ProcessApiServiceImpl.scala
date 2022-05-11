package it.pagopa.interop.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.{ContentType, HttpEntity, MessageEntity, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, onComplete}
import akka.http.scaladsl.server.Route
import cats.implicits._
import com.typesafe.scalalogging.Logger
import it.pagopa.interop.commons.files.service.FileManager
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.interop.commons.mail.model.PersistedTemplate
import it.pagopa.interop.commons.utils.AkkaUtils.{getFutureBearer, getUidFuture}
import it.pagopa.interop.commons.utils.OpenapiUtils._
import it.pagopa.interop.commons.utils.TypeConversions._
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.{ResourceConflictError, ResourceNotFoundError}
import it.pagopa.interop.partymanagement.client.model.Relationships
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.api.ProcessApiService
import it.pagopa.interop.partyprocess.api.converters.partymanagement.InstitutionConverter
import it.pagopa.interop.partyprocess.api.impl.Conversions._
import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration
import it.pagopa.interop.partyprocess.error.PartyProcessErrors._
import it.pagopa.interop.partyprocess.model._
import it.pagopa.interop.partyprocess.service._
import it.pagopa.userreg.client.model.UserId
import org.slf4j.LoggerFactory

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ProcessApiServiceImpl(
  partyManagementService: PartyManagementService,
  partyRegistryService: PartyRegistryService,
  userRegistryManagementService: UserRegistryManagementService,
  pdfCreator: PDFCreator,
  fileManager: FileManager,
  signatureService: SignatureService,
  mailer: MailEngine,
  mailTemplate: PersistedTemplate,
  relationshipService: RelationshipService,
  productService: ProductService
)(implicit ec: ExecutionContext)
    extends ProcessApiService {

  private val logger = Logger.takingImplicit[ContextFieldsToLog](LoggerFactory.getLogger(this.getClass))

  private final val validOnboardingStates: Seq[PartyManagementDependency.RelationshipState] =
    List(
      PartyManagementDependency.RelationshipState.ACTIVE,
      PartyManagementDependency.RelationshipState.DELETED,
      PartyManagementDependency.RelationshipState.SUSPENDED
    )

  private def sendOnboardingMail(
    addresses: Seq[String],
    file: File,
    onboardingMailParameters: Map[String, String]
  ): Future[Unit] = {
    mailer.sendMail(mailTemplate)(addresses, file, onboardingMailParameters)
  }

  /** Code: 204, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    * Code: 404, Message: Not found, DataType: Problem
    */
  override def verifyOnboarding(externalId: String, productId: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Verifying onboarding for institution having externalId {} on product {}", externalId, productId)
    val result: Future[Boolean] = for {
      bearer        <- getFutureBearer(contexts)
      institution   <- partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
      relationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq(PartyManagementDependency.PartyRole.MANAGER),
        states = validOnboardingStates,
        products = Seq(productId),
        productRoles = Seq.empty
      )(bearer)
    } yield relationships.items.nonEmpty

    onComplete(result) {
      case Success(found) if found => verifyOnboarding204
      case Success(_)              =>
        val errorResponse: Problem =
          problemOf(StatusCodes.NotFound, InstitutionNotOnboarded(externalId, productId))
        verifyOnboarding404(errorResponse)
      case Failure(_)              =>
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingVerificationError)
        verifyOnboarding400(errorResponse)

    }

  }

  /** Code: 200, Message: successful operation, DataType: OnboardingInfo
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def getOnboardingInfo(institutionId: Option[String], institutionExternalId: Option[String], states: String)(
    implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerOnboardingInfo: ToEntityMarshaller[OnboardingInfo],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(
      "Getting onboarding info for institution having institutionId {} institutionExternalId {} and states {}",
      institutionId.toString,
      institutionExternalId.toString,
      states
    )
    val defaultStates =
      List(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING)

    val result: Future[OnboardingInfo] = for {
      bearer           <- getFutureBearer(contexts)
      uid              <- getUidFuture(contexts)
      userId           <- uid.toFutureUUID
      institution      <- getInstitutionByOptionIdAndOptionExternalId(institutionId, institutionExternalId)(bearer)
      statesParamArray <- parseArrayParameters(states)
        .traverse(PartyManagementDependency.RelationshipState.fromValue)
        .toFuture
      statesArray = if (statesParamArray.isEmpty) defaultStates else statesParamArray
      relationships  <- partyManagementService.retrieveRelationships(
        from = Some(userId),
        to = institution.map(_.id),
        roles = Seq.empty,
        states = statesArray,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      onboardingData <- Future.traverse(relationships.items)(getOnboardingDataDetails(bearer))
    } yield OnboardingInfo(Option(userId), onboardingData)

    onComplete(result) {
      case Success(res)                       => getOnboardingInfo200(res)
      case Failure(ex: ResourceNotFoundError) =>
        logger.info("No onboarding information found for {}, reason: {}", ex.resourceId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getOnboardingInfo404(errorResponse)
      case Failure(ex)                        =>
        logger.error("Error getting onboarding info , reason: {}", ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, GettingOnboardingInfoError)
        getOnboardingInfo400(errorResponse)

    }
  }

  private def getInstitutionByOptionIdAndOptionExternalId(
    institutionId: Option[String],
    institutionExternalId: Option[String]
  )(bearer: String): Future[Option[PartyManagementDependency.Institution]] = {
    institutionId.fold {
      institutionExternalId.traverse(externalId =>
        partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
      )
    } { institutionId =>
      partyManagementService
        .retrieveInstitution(UUID.fromString(institutionId))(bearer)
        .map(institution =>
          institutionExternalId
            .flatMap(externalId => Option.when(institution.externalId === externalId)(institution))
            .orElse(Option(institution))
        )
    }
  }

  private def getOnboardingDataDetails(
    bearer: String
  ): PartyManagementDependency.Relationship => Future[OnboardingData] =
    relationship => {
      for {
        institution <- partyManagementService.retrieveInstitution(relationship.to)(bearer)
        managers    <- partyManagementService.retrieveRelationships(
          from = None,
          to = Some(institution.id),
          roles = Seq(PartyManagementDependency.PartyRole.MANAGER),
          states = Seq.empty,
          products = Seq(relationship.product.id),
          productRoles = Seq.empty
        )(bearer)
        managerWithBillingData = locateManagerWithBillingData(managers)
      } yield OnboardingData(
        id = institution.id,
        externalId = institution.externalId,
        originId = institution.originId,
        origin = institution.origin,
        institutionType = institution.institutionType,
        taxCode = institution.taxCode,
        description = institution.description,
        digitalAddress = institution.digitalAddress,
        address = institution.address,
        zipCode = institution.zipCode,
        state = relationshipStateToApi(relationship.state),
        role = roleToApi(relationship.role),
        productInfo = relationshipProductToApi(relationship.product),
        billing = managerWithBillingData.fold(Option.empty[Billing])(m => m.billing.map(billingToApi)),
        pricingPlan = managerWithBillingData.fold(Option.empty[String])(m => m.pricingPlan),
        attributes =
          institution.attributes.map(attribute => Attribute(attribute.origin, attribute.code, attribute.description))
      )

    }

  private def locateManagerWithBillingData(managers: Relationships) = {
    managers.items
      .sortWith((b1, b2) =>
        // active as first
        (b1.state != b2.state && b1.state == PartyManagementDependency.RelationshipState.ACTIVE)
        // or last updated
          || b2.updatedAt.getOrElse(b2.createdAt).isBefore(b1.updatedAt.getOrElse(b1.createdAt))
      )
      .find(m => m.state != PartyManagementDependency.RelationshipState.PENDING && m.billing.isDefined)
  }

  /** Code: 204, Message: successful operation
    * Code: 404, Message: Not found, DataType: Problem
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingInstitution(
    onboardingRequest: OnboardingInstitutionRequest
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info("Onboarding institution having externalId {}", onboardingRequest.institutionExternalId)
    val result: Future[Unit] = for {
      bearer                   <- getFutureBearer(contexts)
      uid                      <- getUidFuture(contexts)
      userId                   <- uid.toFutureUUID
      currentUser              <- userRegistryManagementService.getUserById(userId)
      institution              <- createOrGetInstitution(onboardingRequest)(bearer, contexts)
      institutionRelationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product                  <- extractProduct(onboardingRequest.users)
      _                        <- notExistsAnOnboardedManager(institutionRelationships, product)
      currentManager = extractActiveManager(institutionRelationships, product)
      response <- performOnboardingWithSignature(
        OnboardingSignedRequest.fromApi(onboardingRequest),
        currentManager,
        institution,
        currentUser
      )(bearer, contexts)
    } yield response

    onComplete(result) {
      case Success(_)                    => onboardingInstitution204
      case Failure(ex: ContractNotFound) =>
        logger.info(
          "Error while onboarding institution {}, reason: {}",
          onboardingRequest.institutionExternalId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        onboardingInstitution404(errorResponse)
      case Failure(ex)                   =>
        logger.error(
          "Error while onboarding institution {}, reason: {}",
          onboardingRequest.institutionExternalId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingOperationError)
        onboardingInstitution400(errorResponse)
    }

  }

  private def createOrGetInstitution(
    onboardingRequest: OnboardingInstitutionRequest
  )(bearer: String, contexts: Seq[(String, String)]): Future[PartyManagementDependency.Institution] =
    createInstitution(onboardingRequest.institutionExternalId)(bearer, contexts).recoverWith {
      case _: ResourceConflictError =>
        partyManagementService.retrieveInstitutionByExternalId(onboardingRequest.institutionExternalId)(bearer)
      case ex                       =>
        Future.failed(ex)
    }

  /** Code: 204, Message: successful operation
    * Code: 404, Message: Not found, DataType: Problem
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingLegalsOnInstitution(
    onboardingRequest: OnboardingLegalUsersRequest
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info(
      "Onboarding Legals of institution {} and/or externalId {}",
      onboardingRequest.institutionId,
      onboardingRequest.institutionExternalId
    )
    val result: Future[Unit] = for {
      bearer                   <- getFutureBearer(contexts)
      uid                      <- getUidFuture(contexts)
      userId                   <- uid.toFutureUUID
      currentUser              <- userRegistryManagementService.getUserById(userId)
      institutionOption        <- getInstitutionByOptionIdAndOptionExternalId(
        onboardingRequest.institutionId.map(i => i.toString),
        onboardingRequest.institutionExternalId
      )(bearer)
      institution              <- institutionOption.toFuture(
        InstitutionNotFound(
          onboardingRequest.institutionId.map(i => i.toString),
          onboardingRequest.institutionExternalId
        )
      )
      institutionRelationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product                  <- extractProduct(onboardingRequest.users)
      _                        <- existsAnOnboardedManager(institutionRelationships, product)
      activeManager = extractActiveManager(institutionRelationships, product)
      response <- performOnboardingWithSignature(
        OnboardingSignedRequest.fromApi(onboardingRequest),
        activeManager,
        institution,
        currentUser
      )(bearer, contexts)
    } yield response

    onComplete(result) {
      case Success(_)  => onboardingLegalsOnInstitution204
      case Failure(ex) =>
        logger.error(
          "Error while onboarding Legals of institution {} and/or externalId {}, reason: {}",
          onboardingRequest.institutionId,
          onboardingRequest.institutionExternalId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingLegalsError)
        onboardingLegalsOnInstitution400(errorResponse)
    }
  }

  private def extractActiveManager(
    relationships: PartyManagementDependency.Relationships,
    product: String
  ): Option[PartyManagementDependency.Relationship] =
    relationships.items.find(rl =>
      product == rl.product.id && rl.role == PartyManagementDependency.PartyRole.MANAGER && rl.state == PartyManagementDependency.RelationshipState.ACTIVE
    )

  private def getValidManager(manager: Option[PartyManagementDependency.Relationship], users: Seq[User]): Future[User] =
    manager
      .map(getUserFromRelationship)
      .getOrElse(users.find(user => user.role == PartyRole.MANAGER).toFuture(ManagerFoundError))

  private def getUserFromRelationship(relationship: PartyManagementDependency.Relationship): Future[User] =
    userRegistryManagementService
      .getUserById(relationship.from)
      .map(user =>
        User(
          id = user.id,
          name = user.name,
          surname = user.surname,
          taxCode = user.taxCode,
          role = roleToApi(relationship.role),
          email = None,
          product = relationship.product.id,
          productRole = relationship.product.role
        )
      )

  private def performOnboardingWithSignature(
    onboardingRequest: OnboardingSignedRequest,
    activeManager: Option[PartyManagementDependency.Relationship],
    institution: PartyManagementDependency.Institution,
    currentUser: UserRegistryUser
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[Unit] = {
    for {
      validUsers         <- verifyUsersByRoles(onboardingRequest.users, Set(PartyRole.MANAGER, PartyRole.DELEGATE))
      validManager       <- getValidManager(activeManager, validUsers)
      personIdsWithRoles <- Future.traverse(validUsers)(addUser)
      relationships      <- Future.traverse(personIdsWithRoles) { case (personId, role, product, productRole) =>
        role match {
          case PartyRole.MANAGER =>
            createOrGetRelationship(
              personId.id,
              institution.id,
              roleToDependency(role),
              product,
              productRole,
              onboardingRequest.pricingPlan,
              onboardingRequest.institutionUpdate,
              onboardingRequest.billing
            )(bearer)
          case _                 =>
            createOrGetRelationship(
              personId.id,
              institution.id,
              roleToDependency(role),
              product,
              productRole,
              None,
              None,
              None
            )(bearer)
        }
      }
      contractTemplate   <- getFileAsString(onboardingRequest.contract.path)
      pdf                <- pdfCreator.createContract(contractTemplate, validManager, validUsers, institution)
      digest             <- signatureService.createDigest(pdf)
      token              <- partyManagementService.createToken(
        PartyManagementDependency.Relationships(relationships),
        digest,
        onboardingRequest.contract.version,
        onboardingRequest.contract.path
      )(bearer)
      _ = logger.info("Digest {}", digest)
      onboardingMailParameters <- getOnboardingMailParameters(token.token, currentUser, onboardingRequest)
      destinationMails = ApplicationConfiguration.destinationMails.getOrElse(Seq(institution.digitalAddress))
      _ <- sendOnboardingMail(destinationMails, pdf, onboardingMailParameters)
      _ = logger.info(s"$token")
    } yield ()
  }

  private def getOnboardingMailParameters(
    token: String,
    currentUser: UserRegistryUser,
    onboardingRequest: OnboardingSignedRequest
  ): Future[Map[String, String]] = {

    val tokenParameters: Map[String, String] = {
      ApplicationConfiguration.onboardingMailPlaceholdersReplacement.map { case (k, placeholder) =>
        (k, s"$placeholder$token")
      }
    }

    val userParameters: Map[String, String] = Map(
      ApplicationConfiguration.onboardingMailUserNamePlaceholder    -> currentUser.name,
      ApplicationConfiguration.onboardingMailUserSurnamePlaceholder -> currentUser.surname,
      ApplicationConfiguration.onboardingMailTaxCodePlaceholder     -> currentUser.taxCode
    )

    val institutionInfoParameters: Map[String, String] = {
      onboardingRequest.institutionUpdate.fold(Map.empty[String, String]) { iu =>
        Seq(
          ApplicationConfiguration.onboardingMailInstitutionInfoInstitutionTypePlaceholder.some.zip(iu.institutionType),
          ApplicationConfiguration.onboardingMailInstitutionInfoDescriptionPlaceholder.some.zip(iu.description),
          ApplicationConfiguration.onboardingMailInstitutionInfoDigitalAddressPlaceholder.some.zip(iu.digitalAddress),
          ApplicationConfiguration.onboardingMailInstitutionInfoAddressPlaceholder.some.zip(iu.address),
          ApplicationConfiguration.onboardingMailInstitutionInfoTaxCodePlaceholder.some.zip(iu.taxCode)
        ).flatten.toMap
      }
    }

    val billingParameters: Map[String, String] = {
      Seq(
        onboardingRequest.pricingPlan.fold(Map.empty[String, String])(o =>
          Map(ApplicationConfiguration.onboardingMailBillingPricingPlanPlaceholder -> o)
        ),
        onboardingRequest.billing.fold(Map.empty[String, String])(b =>
          Map(
            ApplicationConfiguration.onboardingMailBillingVatNumberPlaceholder     -> b.vatNumber,
            ApplicationConfiguration.onboardingMailBillingRecipientCodePlaceholder -> b.recipientCode
          )
        )
      ).flatten.toMap
    }

    val bodyParameters: Map[String, String] =
      tokenParameters ++ userParameters ++ institutionInfoParameters ++ billingParameters

    extractProduct(onboardingRequest.users).map(product =>
      bodyParameters + (ApplicationConfiguration.onboardingMailProductPlaceholder -> product)
    )

  }

  private def extractProduct(users: Seq[User]): Future[String] = {
    val products: Seq[String] = users.map(_.product).distinct
    if (products.size == 1) Future.successful(products.head)
    else Future.failed(MultipleProductsRequestError(products))
  }

  private def createOrGetRelationship(
    personId: UUID,
    institutionId: UUID,
    role: PartyManagementDependency.PartyRole,
    product: String,
    productRole: String,
    pricingPlan: Option[String],
    institutionUpdate: Option[InstitutionUpdate],
    billing: Option[Billing]
  )(bearer: String): Future[PartyManagementDependency.Relationship] = {
    val relationshipSeed: PartyManagementDependency.RelationshipSeed =
      PartyManagementDependency.RelationshipSeed(
        from = personId,
        to = institutionId,
        role = role,
        product = PartyManagementDependency.RelationshipProductSeed(product, productRole),
        pricingPlan = pricingPlan,
        institutionUpdate = institutionUpdate.map(i =>
          PartyManagementDependency.InstitutionUpdate(
            institutionType = i.institutionType,
            description = i.description,
            digitalAddress = i.digitalAddress,
            address = i.address,
            taxCode = i.taxCode
          )
        ),
        billing = billing.map(b =>
          PartyManagementDependency.Billing(
            vatNumber = b.vatNumber,
            recipientCode = b.recipientCode,
            publicServices = b.publicServices
          )
        )
      )

    partyManagementService
      .createRelationship(relationshipSeed)(bearer)
      .recoverWith {
        case _: ResourceConflictError =>
          for {
            relationships <- partyManagementService.retrieveRelationships(
              from = Some(personId),
              to = Some(institutionId),
              roles = Seq(role),
              states = Seq.empty,
              products = Seq(product),
              productRoles = Seq(productRole)
            )(bearer)
            relationship  <- relationships.items.headOption.toFuture(
              RelationshipNotFound(institutionId, personId, role.toString)
            )
          } yield relationship
        case ex                       => Future.failed(ex)
      }

  }

  /** Code: 201, Message: successful operation, DataType: Seq[RelationshipInfo]
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingSubDelegatesOnInstitution(onboardingRequest: OnboardingUsersRequest)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Onboarding subdelegates on institution {}", onboardingRequest.institutionId)
    val result: Future[Seq[RelationshipInfo]] = for {
      bearer        <- getFutureBearer(contexts)
      institution   <- partyManagementService.retrieveInstitution(onboardingRequest.institutionId)(bearer)
      relationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product       <- extractProduct(onboardingRequest.users)
      _             <- existsAnOnboardedManager(relationships, product)
      result        <- performOnboardingWithoutSignature(onboardingRequest, Set(PartyRole.SUB_DELEGATE), institution)(
        bearer,
        contexts
      )
    } yield result

    onComplete(result) {
      case Success(relationships) => onboardingSubDelegatesOnInstitution200(relationships)
      case Failure(ex)            =>
        logger.error(
          "Error while onboarding subdelegates on institution {}, reason: {}",
          onboardingRequest.institutionId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingSubdelegatesError)
        onboardingSubDelegatesOnInstitution400(errorResponse)
    }
  }

  /** Code: 201, Message: successful operation, DataType: Seq[RelationshipInfo]
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingOperators(onboardingRequest: OnboardingUsersRequest)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Onboarding operators on institution {}", onboardingRequest.institutionId)
    val result: Future[Seq[RelationshipInfo]] = for {
      bearer        <- getFutureBearer(contexts)
      institution   <- partyManagementService.retrieveInstitution(onboardingRequest.institutionId)(bearer)
      relationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product       <- extractProduct(onboardingRequest.users)
      _             <- existsAnOnboardedManager(relationships, product)
      result        <- performOnboardingWithoutSignature(onboardingRequest, Set(PartyRole.OPERATOR), institution)(
        bearer,
        contexts
      )
    } yield result

    onComplete(result) {
      case Success(relationships) => onboardingOperators200(relationships)
      case Failure(ex)            =>
        logger.error(
          "Error while onboarding operators on institution {}, reason: {}",
          onboardingRequest.institutionId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingOperatorsError)
        onboardingOperators400(errorResponse)
    }
  }

  private def performOnboardingWithoutSignature(
    onboardingRequest: OnboardingUsersRequest,
    rolesToCheck: Set[PartyRole],
    institution: PartyManagementDependency.Institution
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[Seq[RelationshipInfo]] = {
    for {
      validUsers    <- verifyUsersByRoles(onboardingRequest.users, rolesToCheck)
      userIds       <- Future.traverse(validUsers)(addUser)
      relationships <- Future.traverse(userIds) { case (userId, role, product, productRole) =>
        val relationshipSeed: PartyManagementDependency.RelationshipSeed =
          PartyManagementDependency.RelationshipSeed(
            from = userId.id,
            to = institution.id,
            role = roleToDependency(role),
            product = PartyManagementDependency.RelationshipProductSeed(product, productRole)
          )
        partyManagementService
          .createRelationship(relationshipSeed)(bearer)
          .map(rl =>
            Conversions
              .relationshipToRelationshipsResponse(rl)
          )
      }
      _ = logger.info(s"Users created ${userIds.map(_.toString).mkString(",")}")
    } yield relationships
  }

  private def verifyUsersByRoles(users: Seq[User], roles: Set[PartyRole]): Future[Seq[User]] = {
    val areValidUsers: Boolean = users.forall(user => roles.contains(user.role))
    Future.fromTry(
      Either
        .cond(users.nonEmpty && areValidUsers, users, RolesNotAdmittedError(users, roles))
        .toTry
    )
  }

  private def existsAnOnboardedManager(
    relationships: PartyManagementDependency.Relationships,
    product: String
  ): Future[Unit] = Future.fromTry {
    Either
      .cond(
        relationships.items.exists(isAnOnboardedManager(product)),
        (),
        ManagerNotFoundError(relationships.items.map(r => r.to.toString), product)
      )
      .toTry
  }

  private def isAnOnboardedManager(product: String): PartyManagementDependency.Relationship => Boolean = relationship =>
    {

      relationship.role == PartyManagementDependency.PartyRole.MANAGER &&
      relationship.product.id == product &&
      (
        relationship.state != PartyManagementDependency.RelationshipState.PENDING &&
          relationship.state != PartyManagementDependency.RelationshipState.REJECTED
      )

    }

  private def notExistsAnOnboardedManager(
    relationships: PartyManagementDependency.Relationships,
    product: String
  ): Future[Unit] =
    Future.fromTry {
      Either
        .cond(relationships.items.forall(isNotAnOnboardedManager(product)), (), ManagerFoundError)
        .toTry
    }

  private def isNotAnOnboardedManager(product: String): PartyManagementDependency.Relationship => Boolean =
    relationship => !isAnOnboardedManager(product)(relationship)

  private def addUser(
    user: User
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[(UserId, PartyRole, String, String)] = {
    logger.info("Adding user {}", user.toString)
    createPerson(user)(bearer)
      .recoverWith {
        case _: ResourceConflictError => Future.successful(UserId(user.id))
        case ex                       => Future.failed(ex)
      }
      .map((_, user.role, user.product, user.productRole))
  }

  private def createPerson(user: User)(bearer: String): Future[UserId] =
    for {
      _ <- partyManagementService.createPerson(PartyManagementDependency.PersonSeed(user.id))(bearer)
    } yield UserId(user.id)

  private def createInstitution(
    externalId: String
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[PartyManagementDependency.Institution] =
    for {
      institution <- partyRegistryService.getInstitution(externalId)(bearer)
      category    <- partyRegistryService.getCategory(institution.origin, institution.category)(bearer)
      _    = logger.info("getInstitution {}", institution.id)
      seed = PartyManagementDependency.InstitutionSeed(
        externalId = externalId,
        originId = institution.originId,
        description = institution.description,
        digitalAddress = institution.digitalAddress,
        taxCode = institution.taxCode,
        attributes = Seq(PartyManagementDependency.Attribute(category.origin, category.code, category.name)),
        products = Set.empty,
        address = institution.address,
        zipCode = institution.zipCode,
        institutionType = Option.empty,
        origin = institution.origin
      )
      institution <- partyManagementService.createInstitution(seed)(bearer)
      _ = logger.info("institution created {}", institution.externalId)
    } yield institution

  /** Code: 204, Message: Successful operation
    * Code: 400, Message: Invalid id supplied, DataType: Problem
    * Code: 404, Message: Not found, DataType: Problem
    */
  override def activateRelationship(
    relationshipId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info("Activating relationship {}", relationshipId)
    val result: Future[Unit] = for {
      bearer           <- getFutureBearer(contexts)
      relationshipUUID <- relationshipId.toFutureUUID
      relationship     <- partyManagementService.getRelationshipById(relationshipUUID)(bearer)
      _                <- relationshipMustBeActivable(relationship)
      _                <- partyManagementService.activateRelationship(relationship.id)(bearer)
    } yield ()

    onComplete(result) {
      case Success(_)                        => activateRelationship204
      case Failure(ex: RelationshipNotFound) =>
        logger.error("Error while activating relationship {}, reason: {}", relationshipId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        activateRelationship404(errorResponse)
      case Failure(ex)                       =>
        logger.error("Error while activating relationship {}, reason: {}", relationshipId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, ActivateRelationshipError)
        activateRelationship400(errorResponse)
    }
  }

  /** Code: 204, Message: Successful operation
    * Code: 400, Message: Invalid id supplied, DataType: Problem
    * Code: 404, Message: Not found, DataType: Problem
    */
  override def suspendRelationship(
    relationshipId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info("Suspending relationship {}", relationshipId)
    val result: Future[Unit] = for {
      bearer           <- getFutureBearer(contexts)
      relationshipUUID <- relationshipId.toFutureUUID
      relationship     <- partyManagementService.getRelationshipById(relationshipUUID)(bearer)
      _                <- relationshipMustBeSuspendable(relationship)
      _                <- partyManagementService.suspendRelationship(relationship.id)(bearer)
    } yield ()

    onComplete(result) {
      case Success(_)                        => suspendRelationship204
      case Failure(ex: RelationshipNotFound) =>
        logger.error("Error while suspending relationship {}, reason: {}", relationshipId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        suspendRelationship404(errorResponse)
      case Failure(ex)                       =>
        logger.error("Error while suspending relationship {}, reason: {}", relationshipId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, SuspendingRelationshipError)
        suspendRelationship400(errorResponse)
    }
  }

  override def getOnboardingDocument(relationshipId: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerFile: ToEntityMarshaller[File],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Getting onboarding document of relationship {}", relationshipId)
    val result: Future[DocumentDetails] =
      for {
        bearer         <- getFutureBearer(contexts)
        uuid           <- relationshipId.toFutureUUID
        relationship   <- partyManagementService.getRelationshipById(uuid)(bearer)
        filePath       <- relationship.filePath.toFuture(RelationshipDocumentNotFound(relationshipId))
        fileName       <- relationship.fileName.toFuture(RelationshipDocumentNotFound(relationshipId))
        contentTypeStr <- relationship.contentType.toFuture(RelationshipDocumentNotFound(relationshipId))
        contentType    <- ContentType
          .parse(contentTypeStr)
          .fold(ex => Future.failed(ContentTypeParsingError(contentTypeStr, ex)), Future.successful)
        response       <- fileManager.get(ApplicationConfiguration.storageContainer)(filePath)
      } yield DocumentDetails(fileName, contentType, response)

    onComplete(result) {
      case Success(document)                         =>
        val output: MessageEntity = convertToMessageEntity(document)
        complete(output)
      case Failure(ex: ResourceNotFoundError)        =>
        logger.error(
          "Error while getting onboarding document of relationship {}, reason: {}",
          relationshipId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getOnboardingDocument404(errorResponse)
      case Failure(ex: RelationshipDocumentNotFound) =>
        logger.error(
          "Error while getting onboarding document of relationship {}, reason: {}",
          relationshipId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getOnboardingDocument404(errorResponse)
      case Failure(ex)                               =>
        logger.error(
          "Error while getting onboarding document of relationship {}, reason: {}",
          relationshipId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, OnboardingDocumentError(relationshipId))
        complete(errorResponse.status, errorResponse)
    }
  }

  def convertToMessageEntity(documentDetails: DocumentDetails): MessageEntity = {
    val randomPath: Path               = Files.createTempDirectory(s"document")
    val temporaryFilePath: String      = s"${randomPath.toString}/${documentDetails.fileName}"
    val file: File                     = new File(temporaryFilePath)
    val outputStream: FileOutputStream = new FileOutputStream(file)
    documentDetails.file.writeTo(outputStream)
    HttpEntity.fromFile(documentDetails.contentType, file)
  }

  private def relationshipMustBeActivable(relationship: PartyManagementDependency.Relationship): Future[Unit] =
    relationship.state match {
      case PartyManagementDependency.RelationshipState.SUSPENDED => Future.successful(())
      case status => Future.failed(RelationshipNotActivable(relationship.id.toString, status.toString))
    }

  private def relationshipMustBeSuspendable(relationship: PartyManagementDependency.Relationship): Future[Unit] =
    relationship.state match {
      case PartyManagementDependency.RelationshipState.ACTIVE => Future.successful(())
      case status => Future.failed(RelationshipNotSuspendable(relationship.id.toString, status.toString))
    }

  /** Code: 200, Message: successful operation, DataType: RelationshipInfo
    * Code: 400, Message: Invalid id supplied, DataType: Problem
    * Code: 404, Message: Not found, DataType: Problem
    */
  override def getRelationship(relationshipId: String)(implicit
    toEntityMarshallerRelationshipInfo: ToEntityMarshaller[RelationshipInfo],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Getting relationship {}", relationshipId)
    val result: Future[RelationshipInfo] = for {
      bearer           <- getFutureBearer(contexts)
      relationshipUUID <- relationshipId.toFutureUUID
      relationship     <- partyManagementService.getRelationshipById(relationshipUUID)(bearer)
      relationshipInfo = Conversions.relationshipToRelationshipsResponse(relationship)
    } yield relationshipInfo

    onComplete(result) {
      case Success(relationshipInfo)         => getRelationship200(relationshipInfo)
      case Failure(ex: RelationshipNotFound) =>
        logger.error("Getting relationship {}, reason: {}", relationshipId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getRelationship404(errorResponse)
      case Failure(ex)                       =>
        logger.error("Getting relationship {}, reason: {}", relationshipId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, GetRelationshipError)
        getRelationship400(errorResponse)
    }
  }

  /** Code: 204, Message: relationship deleted
    * Code: 400, Message: Bad request, DataType: Problem
    * Code: 404, Message: Relationship not found, DataType: Problem
    */
  override def deleteRelationshipById(
    relationshipId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info("Deleting relationship {}", relationshipId)
    val result = for {
      bearer           <- getFutureBearer(contexts)
      _                <- getUidFuture(contexts)
      relationshipUUID <- relationshipId.toFutureUUID
      _                <- partyManagementService.deleteRelationshipById(relationshipUUID)(bearer)
    } yield ()

    onComplete(result) {
      case Success(_)                      => deleteRelationshipById204
      case Failure(ex: UidValidationError) =>
        logger.error("Error while deleting relationship {}, reason: {}", relationshipId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                     =>
        logger.error("Error while deleting relationship {}, reason: {}", relationshipId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, DeleteRelationshipError)
        deleteRelationshipById404(errorResponse)
    }
  }

  private def getFileAsString(filePath: String): Future[String] = for {
    contractTemplateStream <- fileManager.get(ApplicationConfiguration.storageContainer)(filePath)
    fileString             <- Try { contractTemplateStream.toString(StandardCharsets.UTF_8) }.toFuture
  } yield fileString

  /** Code: 200, Message: successful operation, DataType: Institution
    * Code: 400, Message: Invalid id supplied, DataType: Problem
    * Code: 404, Message: Not found, DataType: Problem
    */
  override def getInstitution(id: String)(implicit
    toEntityMarshallerInstitution: ToEntityMarshaller[Institution],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Retrieving institution for id $id")
    val result = for {
      bearer      <- getFutureBearer(contexts)
      _           <- getUidFuture(contexts)
      uuid        <- id.toFutureUUID
      institution <- partyManagementService.retrieveInstitution(uuid)(bearer)
    } yield institution

    onComplete(result) {
      case Success(institution)            => getInstitution200(InstitutionConverter.dependencyToApi(institution))
      case Failure(ex: UidValidationError) =>
        logger.error(s"Error while retrieving institution for id $id - ${ex.getMessage}")
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                     =>
        logger.error(s"Error while retrieving institution for id $id - ${ex.getMessage}")
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, GetProductsError)
        complete(errorResponse.status, errorResponse)
    }
  }

  /**
   * Code: 200, Message: successful operation, DataType: Seq[RelationshipInfo]
   * Code: 400, Message: Invalid institution id supplied, DataType: Problem
   */
  override def getUserInstitutionRelationships(
    institutionId: String,
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
    logger.info("Getting relationship for institution {} and current user", institutionId)
    val productsArray     = parseArrayParameters(products)
    val productRolesArray = parseArrayParameters(productRoles)
    val rolesArray        = parseArrayParameters(roles)
    val statesArray       = parseArrayParameters(states)

    val result: Future[Seq[RelationshipInfo]] = for {
      bearer         <- getFutureBearer(contexts)
      uid            <- getUidFuture(contexts)
      userId         <- uid.toFutureUUID
      institutionUid <- institutionId.toFutureUUID
      institution    <- partyManagementService.retrieveInstitution(institutionUid)(bearer)
      relationships  <- relationshipService.getUserInstitutionRelationships(
        institution,
        productsArray,
        productRolesArray,
        rolesArray,
        statesArray
      )(personId, userId, bearer)
    } yield relationships

    onComplete(result) {
      case Success(relationships) => getUserInstitutionRelationships200(relationships)
      case Failure(ex)            =>
        ex.printStackTrace()
        logger.error(
          "Error while getting relationship for institution {} and current user, reason: {}",
          institutionId,
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
  override def retrieveInstitutionProducts(institutionId: String, states: String)(implicit
    toEntityMarshallerProducts: ToEntityMarshaller[Products],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    logger.info("Retrieving products for institution {}", institutionId)
    val result = for {
      bearer       <- getFutureBearer(contexts)
      _            <- getUidFuture(contexts)
      statesFilter <- parseArrayParameters(states).traverse(par => ProductState.fromValue(par)).toFuture
      institution  <- partyManagementService.retrieveInstitutionByExternalId(institutionId)(bearer)
      products     <- productService.retrieveInstitutionProducts(institution, statesFilter)(bearer)
    } yield products

    onComplete(result) {
      case Success(institution) if institution.products.isEmpty =>
        val errorResponse: Problem =
          problemOf(StatusCodes.NotFound, ProductsNotFoundError(institutionId))
        retrieveInstitutionProducts404(errorResponse)
      case Success(institution)                                 => retrieveInstitutionProducts200(institution)
      case Failure(ex: UidValidationError)                      =>
        logger.error("Error while retrieving products for institution {}, reason: {}", institutionId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                                          =>
        logger.error("Error while retrieving products for institution {}, reason: {}", institutionId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, GetProductsError)
        complete(errorResponse.status, errorResponse)
    }
  }
}
