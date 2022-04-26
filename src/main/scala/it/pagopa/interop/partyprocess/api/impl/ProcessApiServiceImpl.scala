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
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.api.ProcessApiService
import it.pagopa.interop.partyprocess.api.converters.partymanagement.InstitutionConverter
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
  mailTemplate: PersistedTemplate
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
  override def getOnboardingInfo(externalId: Option[String], states: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerOnboardingInfo: ToEntityMarshaller[OnboardingInfo],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(
      "Getting onboarding info for institution having externalId {} and states {}",
      externalId.toString,
      states
    )
    val defaultStates =
      List(PartyManagementDependency.RelationshipState.ACTIVE, PartyManagementDependency.RelationshipState.PENDING)

    val result: Future[OnboardingInfo] = for {
      bearer           <- getFutureBearer(contexts)
      uid              <- getUidFuture(contexts)
      userId           <- uid.toFutureUUID
      institution      <- externalId.traverse(externalId =>
        partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
      )
      statesParamArray <- parseArrayParameters(states)
        .traverse(PartyManagementDependency.RelationshipState.fromValue)
        .toFuture
      statesArray = if (statesParamArray.isEmpty) defaultStates else statesParamArray
      user                  <- userRegistryManagementService.getUserById(userId)
      relationships         <- partyManagementService.retrieveRelationships(
        from = Some(userId),
        to = institution.map(_.id),
        roles = Seq.empty,
        states = statesArray,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      onboardingDataDetails <- Future.traverse(relationships.items)(getOnboardingDataDetails(bearer)(user))
      contacts       = onboardingDataDetails.flatMap(_._1).toMap
      onboardingData = onboardingDataDetails.map(_._2)
      personInfo     = PersonInfo(
        name = user.name,
        surname = user.surname,
        taxCode = user.externalId,
        certification = certificationToApi(user.certification),
        institutionContacts = contacts
      )
    } yield OnboardingInfo(personInfo, onboardingData)

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

  private def getOnboardingDataDetails(bearer: String)(
    user: UserRegistryUser
  ): PartyManagementDependency.Relationship => Future[(Option[(String, Seq[Contact])], OnboardingData)] =
    relationship => {
      for {
        institution <- partyManagementService.retrieveInstitution(relationship.to)(bearer)
      } yield (
        user.extras.email.map(email => institution.externalId -> Seq(Contact(email = email))),
        OnboardingData(
          id = institution.id,
          externalId = institution.externalId,
          originId = institution.originId,
          origin = institution.origin,
          taxCode = institution.taxCode,
          description = institution.description,
          digitalAddress = institution.digitalAddress,
          address = institution.address,
          zipCode = institution.zipCode,
          state = relationshipStateToApi(relationship.state),
          role = roleToApi(relationship.role),
          productInfo = relationshipProductToApi(relationship.product),
          attributes =
            institution.attributes.map(attribute => Attribute(attribute.origin, attribute.code, attribute.description))
        )
      )

    }

  /** Code: 204, Message: successful operation
    * Code: 404, Message: Not found, DataType: Problem
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingInstitution(
    onboardingRequest: OnboardingRequest
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info("Onboarding institution having externalId {}", onboardingRequest.externalId)
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
      product                  <- extractProduct(onboardingRequest)
      _                        <- notExistsAnOnboardedManager(institutionRelationships, product)
      currentManager = extractActiveManager(institutionRelationships, product)
      response <- performOnboardingWithSignature(onboardingRequest, currentManager, institution, currentUser)(
        bearer,
        contexts
      )
    } yield response

    onComplete(result) {
      case Success(_)                    => onboardingInstitution204
      case Failure(ex: ContractNotFound) =>
        logger.info("Error while onboarding institution {}, reason: {}", onboardingRequest.externalId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        onboardingInstitution404(errorResponse)
      case Failure(ex)                   =>
        logger.error("Error while onboarding institution {}, reason: {}", onboardingRequest.externalId, ex.getMessage)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingOperationError)
        onboardingInstitution400(errorResponse)
    }

  }

  private def createOrGetInstitution(
    onboardingRequest: OnboardingRequest
  )(bearer: String, contexts: Seq[(String, String)]): Future[PartyManagementDependency.Institution] =
    createInstitution(onboardingRequest.externalId)(bearer, contexts).recoverWith {
      case _: ResourceConflictError =>
        partyManagementService.retrieveInstitutionByExternalId(onboardingRequest.externalId)(bearer)
      case ex                       =>
        Future.failed(ex)
    }

  /** Code: 204, Message: successful operation
    * Code: 404, Message: Not found, DataType: Problem
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingLegalsOnInstitution(
    onboardingRequest: OnboardingRequest
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info("Onboarding Legals of institution {}", onboardingRequest.externalId)
    val result: Future[Unit] = for {
      bearer      <- getFutureBearer(contexts)
      uid         <- getUidFuture(contexts)
      userId      <- uid.toFutureUUID
      currentUser <- userRegistryManagementService.getUserById(userId)
      institution <- partyManagementService.retrieveInstitutionByExternalId(onboardingRequest.externalId)(bearer)
      institutionRelationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product                  <- extractProduct(onboardingRequest)
      _                        <- existsAnOnboardedManager(institutionRelationships, product)
      activeManager = extractActiveManager(institutionRelationships, product)
      response <- performOnboardingWithSignature(onboardingRequest, activeManager, institution, currentUser)(
        bearer,
        contexts
      )
    } yield response

    onComplete(result) {
      case Success(_)  => onboardingLegalsOnInstitution204
      case Failure(ex) =>
        logger.error(
          "Error while onboarding Legals of institution {}, reason: {}",
          onboardingRequest.externalId,
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
          name = user.name,
          surname = user.surname,
          taxCode = user.externalId,
          role = roleToApi(relationship.role),
          email = None,
          product = relationship.product.id,
          productRole = relationship.product.role
        )
      )

  private def performOnboardingWithSignature(
    onboardingRequest: OnboardingRequest,
    activeManager: Option[PartyManagementDependency.Relationship],
    institution: PartyManagementDependency.Institution,
    currentUser: UserRegistryUser
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[Unit] = {
    for {
      validUsers       <- verifyUsersByRoles(onboardingRequest.users, Set(PartyRole.MANAGER, PartyRole.DELEGATE))
      validManager     <- getValidManager(activeManager, validUsers)
      personsWithRoles <- Future.traverse(validUsers)(addUser)
      relationships    <- Future.traverse(personsWithRoles) { case (person, role, product, productRole) =>
        role match {
          case PartyRole.MANAGER =>
            createOrGetRelationship(
              person.id,
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
              person.id,
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
      contract         <- onboardingRequest.contract.toFuture(ContractNotFound(onboardingRequest.externalId))
      contractTemplate <- getFileAsString(contract.path)
      pdf              <- pdfCreator.createContract(contractTemplate, validManager, validUsers, institution)
      digest           <- signatureService.createDigest(pdf)
      token            <- partyManagementService.createToken(
        PartyManagementDependency.Relationships(relationships),
        digest,
        contract.version,
        contract.path
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
  override def onboardingSubDelegatesOnInstitution(onboardingRequest: OnboardingRequest)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info("Onboarding subdelegates on institution {}", onboardingRequest.externalId)
    val result: Future[Seq[RelationshipInfo]] = for {
      bearer        <- getFutureBearer(contexts)
      institution   <- partyManagementService.retrieveInstitutionByExternalId(onboardingRequest.externalId)(bearer)
      relationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product       <- extractProduct(onboardingRequest)
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
          onboardingRequest.externalId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingSubdelegatesError)
        onboardingSubDelegatesOnInstitution400(errorResponse)
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
    logger.info("Onboarding operators on institution {}", onboardingRequest.externalId)
    val result: Future[Seq[RelationshipInfo]] = for {
      bearer        <- getFutureBearer(contexts)
      institution   <- partyManagementService.retrieveInstitutionByExternalId(onboardingRequest.externalId)(bearer)
      relationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      product       <- extractProduct(onboardingRequest)
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
          onboardingRequest.externalId,
          ex.getMessage
        )
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, OnboardingOperatorsError)
        onboardingOperators400(errorResponse)
    }
  }

  private def performOnboardingWithoutSignature(
    onboardingRequest: OnboardingRequest,
    rolesToCheck: Set[PartyRole],
    institution: PartyManagementDependency.Institution
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[Seq[RelationshipInfo]] = {
    for {
      validUsers    <- verifyUsersByRoles(onboardingRequest.users, rolesToCheck)
      users         <- Future.traverse(validUsers)(user => addUser(user))
      relationships <- Future.traverse(users) { case (user, role, product, productRole) =>
        val relationshipSeed: PartyManagementDependency.RelationshipSeed =
          PartyManagementDependency.RelationshipSeed(
            from = user.id,
            to = institution.id,
            role = roleToDependency(role),
            product = PartyManagementDependency.RelationshipProductSeed(product, productRole)
          )
        partyManagementService
          .createRelationship(relationshipSeed)(bearer)
          .flatMap(rl =>
            Conversions
              .relationshipToRelationshipsResponse(userRegistryManagementService, partyManagementService)(rl, bearer)
          )
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

  private def existsAnOnboardedManager(
    relationships: PartyManagementDependency.Relationships,
    product: String
  ): Future[Unit] = Future.fromTry {
    Either
      .cond(relationships.items.exists(isAnOnboardedManager(product)), (), ManagerNotFoundError)
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
      _    <- partyManagementService.createPerson(PartyManagementDependency.PersonSeed(user.id))(bearer)
    } yield user

  private def createInstitution(
    externalId: String
  )(implicit bearer: String, contexts: Seq[(String, String)]): Future[PartyManagementDependency.Institution] =
    for {
      institution <- partyRegistryService.getInstitution(externalId)(bearer)
      category    <- partyRegistryService.getCategory(institution.origin, institution.category)(bearer)
      _    = logger.info("getInstitution {}", institution.id)
      seed = PartyManagementDependency.InstitutionSeed(
        externalId = externalId,
        originId = institution.id,
        description = institution.description,
        digitalAddress = institution.digitalAddress,
        taxCode = institution.taxCode,
        attributes = Seq(PartyManagementDependency.Attribute(category.origin, category.code, category.name)),
        products = Set.empty,
        address = institution.address,
        zipCode = institution.zipCode,
        institutionType = "TBD",
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
      relationshipInfo <- Conversions.relationshipToRelationshipsResponse(
        userRegistryManagementService,
        partyManagementService
      )(relationship, bearer)
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

}
