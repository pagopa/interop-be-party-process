package it.pagopa.interop.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.{ContentType, HttpEntity, MessageEntity, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, onComplete}
import akka.http.scaladsl.server.Route
import cats.implicits.toTraverseOps
import com.typesafe.scalalogging.Logger
import it.pagopa.interop.partyprocess.error.PartyProcessErrors.{
  ActivateRelationshipError,
  ContractNotFound,
  DeleteRelationshipError,
  GetProductsError,
  GetRelationshipError,
  GettingOnboardingInfoError,
  ManagerFoundError,
  ManagerNotFoundError,
  OnboardingLegalsError,
  OnboardingOperationError,
  OnboardingOperatorsError,
  OnboardingSubdelegatesError,
  OnboardingVerificationError,
  RelationshipDocumentNotFound,
  RelationshipNotFound,
  RetrievingUserRelationshipsError,
  SuspendingRelationshipError,
  UidValidationError
}
import it.pagopa.interop.commons.files.service.FileManager
import it.pagopa.interop.commons.jwt.service.JWTReader
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.interop.commons.mail.model.PersistedTemplate
import it.pagopa.interop.commons.utils.AkkaUtils.getFutureBearer
import it.pagopa.interop.commons.utils.OpenapiUtils._
import it.pagopa.interop.commons.utils.TypeConversions._
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.{ResourceConflictError, ResourceNotFoundError}
import it.pagopa.interop.partymanagement.client.model.{
  Organization,
  OrganizationSeed,
  PersonSeed,
  Relationship,
  RelationshipProductSeed,
  RelationshipSeed,
  Relationships,
  Attribute => PartyManagementAttribute,
  Problem => _
}
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.api.ProcessApiService
import it.pagopa.interop.partyprocess.api.impl.Conversions._
import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration
import it.pagopa.interop.partyprocess.error.PartyProcessErrors._
import it.pagopa.interop.partyprocess.model._
import it.pagopa.interop.partyprocess.service._
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.Certification.{
  NONE => CertificationEnumsNone
}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{
  User => UserRegistryUser,
  UserExtras => UserRegistryUserExtras,
  UserSeed => UserRegistryUserSeed
}
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
  jwtReader: JWTReader
)(implicit ec: ExecutionContext)
    extends ProcessApiService {

  private val logger = Logger.takingImplicit[ContextFieldsToLog](LoggerFactory.getLogger(this.getClass))

  private final val adminPartyRoles: Set[PartyRole] = Set(PartyRole.MANAGER, PartyRole.DELEGATE, PartyRole.SUB_DELEGATE)

  private final val validOnboardingStates: Seq[PartyManagementDependency.RelationshipState] =
    List(
      PartyManagementDependency.RelationshipState.ACTIVE,
      PartyManagementDependency.RelationshipState.DELETED,
      PartyManagementDependency.RelationshipState.SUSPENDED
    )

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
  override def verifyOnboarding(institutionId: String, productId: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Verifying onboarding for institution {} on product {}", institutionId, productId)
    val result: Future[Boolean] = for {
      bearer       <- getFutureBearer(contexts)
      organization <- partyManagementService.retrieveOrganizationByExternalId(institutionId)(bearer)
      relationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(organization.id),
        roles = Seq(PartyManagementDependency.PartyRole.MANAGER),
        states = validOnboardingStates,
        products = Seq(productId),
        productRoles = Seq.empty
      )(bearer)
    } yield relationships.items.nonEmpty

    onComplete(result) {
      case Success(found) if found => verifyOnboarding204
      case Success(_) =>
        val errorResponse: Problem =
          problemOf(StatusCodes.NotFound, InstitutionNotOnboarded(institutionId, productId))
        verifyOnboarding404(errorResponse)
      case Failure(_) =>
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingVerificationError)
        verifyOnboarding400(errorResponse)

    }

  }

  /** Code: 200, Message: successful operation, DataType: OnboardingInfo
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def getOnboardingInfo(institutionId: Option[String], states: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerOnboardingInfo: ToEntityMarshaller[OnboardingInfo],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Getting onboarding info for institution {} and states {}", institutionId.toString, states)
    val defaultStates =
      List(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING)

    val result: Future[OnboardingInfo] = for {
      bearer <- getFutureBearer(contexts)
      uid    <- getCallerUserIdentifier(bearer)
      organization <- institutionId.traverse(externalId =>
        partyManagementService.retrieveOrganizationByExternalId(externalId)(bearer)
      )
      statesParamArray <- parseArrayParameters(states)
        .traverse(PartyManagementDependency.RelationshipState.fromValue)
        .toFuture
      statesArray = if (statesParamArray.isEmpty) defaultStates else statesParamArray
      user <- userRegistryManagementService.getUserById(uid)
      relationships <- partyManagementService.retrieveRelationships(
        from = Some(uid),
        to = organization.map(_.id),
        roles = Seq.empty,
        states = statesArray,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      personInfo = PersonInfo(
        name = user.name,
        surname = user.surname,
        taxCode = user.externalId,
        certification = certificationToApi(user.certification),
        institutionContacts = relationships.items
          .flatMap(rl => user.extras.email.map(email => rl.to.toString -> Seq(Contact(email = email))))
          .toMap
      )
      onboardingData <- Future.traverse(relationships.items)(getOnboardingData(bearer))
    } yield OnboardingInfo(personInfo, onboardingData)

    onComplete(result) {
      case Success(res) => getOnboardingInfo200(res)
      case Failure(ex: ResourceNotFoundError) =>
        logger.info("No onboarding information found for {}", ex.resourceId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getOnboardingInfo404(errorResponse)
      case Failure(ex) =>
        logger.error("Error getting onboarding info ", ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, GettingOnboardingInfoError)
        getOnboardingInfo400(errorResponse)

    }
  }

  private def getOnboardingData(bearer: String)(relationship: Relationship): Future[OnboardingData] = {
    for {
      organization <- partyManagementService.retrieveOrganization(relationship.to)(bearer)
    } yield OnboardingData(
      institutionId = organization.institutionId,
      taxCode = organization.taxCode,
      description = organization.description,
      digitalAddress = organization.digitalAddress,
      address = organization.address,
      zipCode = organization.zipCode,
      state = relationshipStateToApi(relationship.state),
      role = roleToApi(relationship.role),
      productInfo = relationshipProductToApi(relationship.product),
      attributes =
        organization.attributes.map(attribute => Attribute(attribute.origin, attribute.code, attribute.description))
    )

  }

  /** Code: 201, Message: successful operation, DataType: OnboardingResponse
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    * Code: 409, Message: Document validation failed, DataType: Problem
    */
  override def onboardingOrganization(onboardingRequest: OnboardingRequest)(implicit
    toEntityMarshallerOnboardingResponse: ToEntityMarshaller[OnboardingResponse],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Onboarding organization {}", onboardingRequest.institutionId)
    val result: Future[OnboardingResponse] = for {
      bearer       <- getFutureBearer(contexts)
      uid          <- getCallerUserIdentifier(bearer)
      currentUser  <- userRegistryManagementService.getUserById(uid)
      organization <- createOrGetOrganization(onboardingRequest)(bearer, contexts)
      relationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(organization.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product  <- extractProduct(onboardingRequest)
      _        <- notExistsAnOnboardedManager(relationships, product)
      response <- performOnboardingWithSignature(onboardingRequest, organization, currentUser)(bearer, contexts)
    } yield response

    onComplete(result) {
      case Success(response) =>
        onboardingOrganization200(response)
      case Failure(ex: ContractNotFound) =>
        logger.error("Error while onboarding organization {}", onboardingRequest.institutionId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        onboardingOrganization404(errorResponse)
      case Failure(ex) =>
        logger.error("Error while onboarding organization {}", onboardingRequest.institutionId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingOperationError)
        onboardingOrganization400(errorResponse)
    }

  }

  private def createOrGetOrganization(
    onboardingRequest: OnboardingRequest
  )(bearer: String, contexts: Seq[(String, String)]): Future[Organization] =
    createOrganization(onboardingRequest.institutionId)(bearer, contexts).recoverWith {
      case _: ResourceConflictError =>
        partyManagementService.retrieveOrganizationByExternalId(onboardingRequest.institutionId)(bearer)
      case ex =>
        Future.failed(ex)
    }

  /** Code: 200, Message: successful operation, DataType: OnboardingResponse
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    * Code: 409, Message: Document validation failed, DataType: Problem
    */
  override def onboardingLegalsOnOrganization(onboardingRequest: OnboardingRequest)(implicit
    toEntityMarshallerOnboardingResponse: ToEntityMarshaller[OnboardingResponse],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Onboarding Legals of organization {}", onboardingRequest.institutionId)
    val result: Future[OnboardingResponse] = for {
      bearer       <- getFutureBearer(contexts)
      uid          <- getCallerUserIdentifier(bearer)
      currentUser  <- userRegistryManagementService.getUserById(uid)
      organization <- partyManagementService.retrieveOrganizationByExternalId(onboardingRequest.institutionId)(bearer)
      organizationRelationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(organization.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product  <- extractProduct(onboardingRequest)
      _        <- existsAnOnboardedManager(organizationRelationships, product)
      response <- performOnboardingWithSignature(onboardingRequest, organization, currentUser)(bearer, contexts)
    } yield response

    onComplete(result) {
      case Success(response) =>
        onboardingLegalsOnOrganization200(response)
      case Failure(ex) =>
        logger.error("Error while onboarding Legals of organization {}", onboardingRequest.institutionId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingLegalsError)
        onboardingLegalsOnOrganization400(errorResponse)
    }
  }

  private def performOnboardingWithSignature(
    onboardingRequest: OnboardingRequest,
    organization: Organization,
    currentUser: UserRegistryUser
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[OnboardingResponse] = {
    for {
      validUsers       <- verifyUsersByRoles(onboardingRequest.users, Set(PartyRole.MANAGER, PartyRole.DELEGATE))
      personsWithRoles <- Future.traverse(validUsers)(addUser)
      relationships <- Future.traverse(personsWithRoles) { case (person, role, product, productRole) =>
        createOrGetRelationship(person.id, organization.id, roleToDependency(role), product, productRole)(bearer)
      }
      contract         <- onboardingRequest.contract.toFuture(ContractNotFound(onboardingRequest.institutionId))
      contractTemplate <- getFileAsString(contract.path)
      pdf              <- pdfCreator.createContract(contractTemplate, validUsers, organization)
      digest           <- signatureService.createDigest(pdf)
      token <- partyManagementService.createToken(
        Relationships(relationships),
        digest,
        contract.version,
        contract.path
      )(bearer)
      _ = logger.info("Digest {}", digest)
      onboardingMailParameters <- getOnboardingMailParameters(token.token, currentUser, onboardingRequest)
      destinationMails = ApplicationConfiguration.destinationMails.getOrElse(Seq(organization.digitalAddress))
      _ <- sendOnboardingMail(destinationMails, pdf, onboardingMailParameters)
      _ = logger.info(s"$token")
    } yield OnboardingResponse(token.token, pdf)
  }

  private def getOnboardingMailParameters(
    token: String,
    currentUser: UserRegistryUser,
    onboardingRequest: OnboardingRequest
  ): Future[Map[String, String]] = {

    val tokenParameters: Map[String, String] = {
      ApplicationConfiguration.onboardingMailPlaceholdersReplacement.map { case (k, placeholder) =>
        (k, s"$placeholder$token")
      }
    }

    val userParameters: Map[String, String] = Map(
      ApplicationConfiguration.onboardingMailUserNamePlaceholder    -> currentUser.name,
      ApplicationConfiguration.onboardingMailUserSurnamePlaceholder -> currentUser.surname,
      ApplicationConfiguration.onboardingMailTaxCodePlaceholder     -> currentUser.externalId
    )

    val bodyParameters: Map[String, String] = tokenParameters ++ userParameters

    extractProduct(onboardingRequest).map(product =>
      bodyParameters + (ApplicationConfiguration.onboardingMailProductPlaceholder -> product)
    )

  }

  private def extractProduct(onboardingRequest: OnboardingRequest): Future[String] = {
    val products: Seq[String] = onboardingRequest.users.map(_.product).distinct
    if (products.size == 1) Future.successful(products.head)
    else Future.failed(MultipleProductsRequestError(products))
  }

  private def createOrGetRelationship(
    personId: UUID,
    organizationId: UUID,
    role: PartyManagementDependency.PartyRole,
    product: String,
    productRole: String
  )(bearer: String): Future[Relationship] = {
    val relationshipSeed: RelationshipSeed =
      RelationshipSeed(
        from = personId,
        to = organizationId,
        role = role,
        product = RelationshipProductSeed(product, productRole)
      )

    partyManagementService
      .createRelationship(relationshipSeed)(bearer)
      .recoverWith {
        case _: ResourceConflictError =>
          for {
            relationships <- partyManagementService.retrieveRelationships(
              from = Some(personId),
              to = Some(organizationId),
              roles = Seq(role),
              states = Seq.empty,
              products = Seq(product),
              productRoles = Seq(productRole)
            )(bearer)
            relationship <- relationships.items.headOption.toFuture(
              RelationshipNotFound(organizationId, personId, role.toString)
            )
          } yield relationship
        case ex => Future.failed(ex)
      }

  }

  /** Code: 201, Message: successful operation, DataType: Seq[RelationshipInfo]
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingSubDelegatesOnOrganization(onboardingRequest: OnboardingRequest)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Onboarding subdelegates on organization {}", onboardingRequest.institutionId)
    val result: Future[Seq[RelationshipInfo]] = for {
      bearer       <- getFutureBearer(contexts)
      organization <- partyManagementService.retrieveOrganizationByExternalId(onboardingRequest.institutionId)(bearer)
      relationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(organization.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product <- extractProduct(onboardingRequest)
      _       <- existsAnOnboardedManager(relationships, product)
      result <- performOnboardingWithoutSignature(onboardingRequest, Set(PartyRole.SUB_DELEGATE), organization)(
        bearer,
        contexts
      )
    } yield result

    onComplete(result) {
      case Success(relationships) => onboardingSubDelegatesOnOrganization200(relationships)
      case Failure(ex) =>
        logger.error("Error while onboarding subdelegates on organization {}", onboardingRequest.institutionId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingSubdelegatesError)
        onboardingSubDelegatesOnOrganization400(errorResponse)
    }
  }

  /** Code: 201, Message: successful operation, DataType: Seq[RelationshipInfo]
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingOperators(onboardingRequest: OnboardingRequest)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Onboarding operators on organization {}", onboardingRequest.institutionId)
    val result: Future[Seq[RelationshipInfo]] = for {
      bearer       <- getFutureBearer(contexts)
      organization <- partyManagementService.retrieveOrganizationByExternalId(onboardingRequest.institutionId)(bearer)
      relationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(organization.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product <- extractProduct(onboardingRequest)
      _       <- existsAnOnboardedManager(relationships, product)
      result <- performOnboardingWithoutSignature(onboardingRequest, Set(PartyRole.OPERATOR), organization)(
        bearer,
        contexts
      )
    } yield result

    onComplete(result) {
      case Success(relationships) => onboardingOperators200(relationships)
      case Failure(ex) =>
        logger.error("Error while onboarding operators on organization {}", onboardingRequest.institutionId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingOperatorsError)
        onboardingOperators400(errorResponse)
    }
  }

  private def performOnboardingWithoutSignature(
    onboardingRequest: OnboardingRequest,
    rolesToCheck: Set[PartyRole],
    organization: Organization
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[Seq[RelationshipInfo]] = {
    for {
      validUsers <- verifyUsersByRoles(onboardingRequest.users, rolesToCheck)
      users      <- Future.traverse(validUsers)(user => addUser(user))
      relationships <- Future.traverse(users) { case (user, role, product, productRole) =>
        val relationshipSeed: RelationshipSeed =
          RelationshipSeed(
            from = user.id,
            to = organization.id,
            role = roleToDependency(role),
            product = RelationshipProductSeed(product, productRole)
          )
        partyManagementService
          .createRelationship(relationshipSeed)(bearer)
          .map(rl => relationshipToRelationshipInfo(user, rl))
      }
      _ = logger.info(s"Users created ${users.map(_.toString).mkString(",")}")
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

  private def existsAnOnboardedManager(relationships: Relationships, product: String): Future[Unit] = Future.fromTry {
    Either
      .cond(relationships.items.exists(isAnOnboardedManager(product)), (), ManagerNotFoundError)
      .toTry
  }

  private def isAnOnboardedManager(product: String): Relationship => Boolean = relationship => {

    relationship.role == PartyManagementDependency.PartyRole.MANAGER &&
      relationship.product.id == product &&
      (
        relationship.state != PartyManagementDependency.RelationshipState.PENDING &&
          relationship.state != PartyManagementDependency.RelationshipState.REJECTED
      )

  }

  private def notExistsAnOnboardedManager(relationships: Relationships, product: String): Future[Unit] =
    Future.fromTry {
      Either
        .cond(relationships.items.forall(isNotAnOnboardedManager(product)), (), ManagerFoundError)
        .toTry
    }

  private def isNotAnOnboardedManager(product: String): Relationship => Boolean = relationship =>
    !isAnOnboardedManager(product)(relationship)

  private def addUser(
    user: User
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[(UserRegistryUser, PartyRole, String, String)] = {
    logger.info("Adding user {}", user.toString)
    createPerson(user)(bearer)
      .recoverWith {
        case _: ResourceConflictError => userRegistryManagementService.getUserByExternalId(user.taxCode)
        case ex                       => Future.failed(ex)
      }
      .map((_, user.role, user.product, user.productRole))
  }

  private def createPerson(user: User)(bearer: String): Future[UserRegistryUser] =
    for {
      user <- userRegistryManagementService
        .createUser(
          UserRegistryUserSeed(
            externalId = user.taxCode,
            name = user.name,
            surname = user.surname,
            certification = CertificationEnumsNone,
            extras = UserRegistryUserExtras(email = user.email, birthDate = None)
          )
        )
        .recoverWith {
          case _: ResourceConflictError => userRegistryManagementService.getUserByExternalId(user.taxCode)
          case ex                       => Future.failed(ex)
        }
      _ <- partyManagementService.createPerson(PersonSeed(user.id))(bearer)
    } yield user

  private def createOrganization(
    institutionId: String
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[Organization] =
    for {
      institution <- partyRegistryService.getInstitution(institutionId)(bearer)
      category    <- partyRegistryService.getCategory(institution.origin, institution.category)(bearer)
      _ = logger.info("getInstitution {}", institution.id)
      seed = OrganizationSeed(
        institutionId = institution.id,
        description = institution.description,
        digitalAddress = institution.digitalAddress,
        taxCode = institution.taxCode,
        attributes = Seq(PartyManagementAttribute(category.origin, category.code, category.name)),
        products = Set.empty,
        address = institution.address,
        zipCode = institution.zipCode
      )
      organization <- partyManagementService.createOrganization(seed)(bearer)
      _ = logger.info("organization created {}", organization.institutionId)
    } yield organization

  private def filterFoundRelationshipsByCurrentUser(
    currentUserId: UUID,
    userAdminRelationships: Relationships,
    institutionIdRelationships: Relationships
  ): Relationships = {
    val isAdmin: Boolean                     = userAdminRelationships.items.nonEmpty
    val userRelationships: Seq[Relationship] = institutionIdRelationships.items.filter(_.from == currentUserId)

    val filteredRelationships: Seq[Relationship] =
      if (isAdmin) institutionIdRelationships.items
      else userRelationships

    Relationships(filteredRelationships)
  }

  /** Code: 200, Message: successful operation, DataType: Seq[RelationshipInfo]
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
      bearer          <- getFutureBearer(contexts)
      uid             <- getCallerUserIdentifier(bearer)
      organization    <- partyManagementService.retrieveOrganizationByExternalId(institutionId)(bearer)
      rolesEnumArray  <- rolesArray.traverse(PartyManagementDependency.PartyRole.fromValue).toFuture
      statesEnumArray <- statesArray.traverse(PartyManagementDependency.RelationshipState.fromValue).toFuture
      userAdminRelationships <- partyManagementService.retrieveRelationships(
        from = Some(uid),
        to = Some(organization.id),
        roles = adminPartyRoles.map(roleToDependency).toSeq,
        states =
          Seq(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING),
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      from <- personId.traverse(_.toFutureUUID)
      institutionIdRelationships <- partyManagementService.retrieveRelationships(
        from = from,
        to = Some(organization.id),
        roles = rolesEnumArray,
        states = statesEnumArray,
        products = productsArray,
        productRoles = productRolesArray
      )(bearer)
      filteredRelationships = filterFoundRelationshipsByCurrentUser(
        uid,
        userAdminRelationships,
        institutionIdRelationships
      )
      relationships <- filteredRelationships.items.traverse(relationshipToRelationshipsResponse)
    } yield relationships

    onComplete(result) {
      case Success(relationships) => getUserInstitutionRelationships200(relationships)
      case Failure(ex) =>
        logger.error("Error while getting relationship for institution {} and current user", institutionId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, RetrievingUserRelationshipsError)
        getUserInstitutionRelationships400(errorResponse)
    }
  }

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
      case Success(_) => activateRelationship204
      case Failure(ex: RelationshipNotFound) =>
        logger.error("Error while activating relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        activateRelationship404(errorResponse)
      case Failure(ex) =>
        logger.error("Error while activating relationship {}", relationshipId, ex)
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
      case Success(_) => suspendRelationship204
      case Failure(ex: RelationshipNotFound) =>
        logger.error("Error while suspending relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        suspendRelationship404(errorResponse)
      case Failure(ex) =>
        logger.error("Error while suspending relationship {}", relationshipId, ex)
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
        contentType <- ContentType
          .parse(contentTypeStr)
          .fold(ex => Future.failed(ContentTypeParsingError(contentTypeStr, ex)), Future.successful)
        response <- fileManager.get(ApplicationConfiguration.storageContainer)(filePath)
      } yield DocumentDetails(fileName, contentType, response)

    onComplete(result) {
      case Success(document) =>
        val output: MessageEntity = convertToMessageEntity(document)
        complete(output)
      case Failure(ex: ResourceNotFoundError) =>
        logger.error("Error while getting onboarding document of relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getOnboardingDocument404(errorResponse)
      case Failure(ex: RelationshipDocumentNotFound) =>
        logger.error("Error while getting onboarding document of relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getOnboardingDocument404(errorResponse)
      case Failure(ex) =>
        logger.error("Error while getting onboarding document of relationship {}", relationshipId, ex)
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

  private def relationshipMustBeActivable(relationship: Relationship): Future[Unit] =
    relationship.state match {
      case PartyManagementDependency.RelationshipState.SUSPENDED => Future.successful(())
      case status                                                => Future.failed(RelationshipNotActivable(relationship.id.toString, status.toString))
    }

  private def relationshipMustBeSuspendable(relationship: Relationship): Future[Unit] =
    relationship.state match {
      case PartyManagementDependency.RelationshipState.ACTIVE => Future.successful(())
      case status                                             => Future.failed(RelationshipNotSuspendable(relationship.id.toString, status.toString))
    }

  private def relationshipToRelationshipsResponse(relationship: Relationship): Future[RelationshipInfo] = {
    for {
      user <- userRegistryManagementService.getUserById(relationship.from)
    } yield relationshipToRelationshipInfo(user, relationship)

  }

  private def relationshipToRelationshipInfo(user: UserRegistryUser, relationship: Relationship): RelationshipInfo = {
    RelationshipInfo(
      id = relationship.id,
      from = relationship.from,
      name = user.name,
      surname = user.surname,
      taxCode = user.externalId,
      certification = certificationToApi(user.certification),
      institutionContacts =
        user.extras.email.map(email => relationship.to.toString -> Seq(Contact(email = email))).toMap,
      role = roleToApi(relationship.role),
      product = relationshipProductToApi(relationship.product),
      state = relationshipStateToApi(relationship.state),
      createdAt = relationship.createdAt,
      updatedAt = relationship.updatedAt
    )
  }

  private def getCallerUserIdentifier(bearer: String): Future[UUID] = {
    val subject = for {
      claims <- jwtReader.getClaims(bearer).toFuture
      uidTxt <- Option(claims.getStringClaim(uidClaim)).toFuture(ClaimNotFound(uidClaim))
      uid    <- uidTxt.toFutureUUID
    } yield uid

    subject transform {
      case s @ Success(_) => s
      case Failure(cause) => Failure(UidValidationError(cause.getMessage))
    }
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
      relationshipInfo <- relationshipToRelationshipsResponse(relationship)
    } yield relationshipInfo

    onComplete(result) {
      case Success(relationshipInfo) => getRelationship200(relationshipInfo)
      case Failure(ex: RelationshipNotFound) =>
        logger.error("Getting relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getRelationship404(errorResponse)
      case Failure(ex) =>
        logger.error("Getting relationship {}", relationshipId, ex)
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
      _                <- getCallerUserIdentifier(bearer)
      relationshipUUID <- relationshipId.toFutureUUID
      _                <- partyManagementService.deleteRelationshipById(relationshipUUID)(bearer)
    } yield ()

    onComplete(result) {
      case Success(_) => deleteRelationshipById204
      case Failure(ex: UidValidationError) =>
        logger.error("Error while deleting relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex) =>
        logger.error("Error while deleting relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, DeleteRelationshipError)
        deleteRelationshipById404(errorResponse)
    }
  }

  /** Code: 200, Message: successful operation, DataType: Products
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
      _            <- getCallerUserIdentifier(bearer)
      statesFilter <- parseArrayParameters(states).traverse(par => ProductState.fromValue(par)).toFuture
      organization <- partyManagementService.retrieveOrganizationByExternalId(institutionId)(bearer)
      organizationRelationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(organization.id),
        roles = Seq(PartyManagementDependency.PartyRole.MANAGER),
        states = statesForAllProducts,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
    } yield Products(products = extractProducts(organizationRelationships, statesFilter))

    onComplete(result) {
      case Success(institution) if institution.products.isEmpty =>
        val errorResponse: Problem =
          problemOf(StatusCodes.NotFound, ProductsNotFoundError(institutionId))
        retrieveInstitutionProducts404(errorResponse)
      case Success(institution) => retrieveInstitutionProducts200(institution)
      case Failure(ex: UidValidationError) =>
        logger.error("Error while retrieving products for institution {}", institutionId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex) =>
        logger.error("Error while retrieving products for institution {}", institutionId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, GetProductsError)
        complete(errorResponse.status, errorResponse)
    }
  }

  private def extractProducts(relationships: Relationships, statesFilter: List[ProductState]): Seq[Product] = {

    val grouped: Seq[(String, Seq[PartyManagementDependency.RelationshipState])] = relationships.items
      .groupBy(rl => rl.product.id)
      .toSeq
      .map { case (product, relationships) => product -> relationships.map(_.state) }

    val allProducts: Seq[Product] = grouped.flatMap {
      case (product, states) if states.exists(st => statesForActiveProducts.contains(st)) =>
        Some(Product(product, ProductState.ACTIVE))
      case (product, states) if states.nonEmpty && states.forall(st => statesForPendingProducts.contains(st)) =>
        Some(Product(product, ProductState.PENDING))
      case _ => None
    }.distinct

    allProducts.filter(product => statesFilter.isEmpty || statesFilter.contains(product.state))

  }

  private def getFileAsString(filePath: String): Future[String] = for {
    contractTemplateStream <- fileManager.get(ApplicationConfiguration.storageContainer)(filePath)
    fileString             <- Try { contractTemplateStream.toString(StandardCharsets.UTF_8) }.toFuture
  } yield fileString

}
