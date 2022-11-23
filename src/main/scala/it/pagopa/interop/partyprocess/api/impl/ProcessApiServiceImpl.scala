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
import it.pagopa.interop.partymanagement.client.model.Relationship
import it.pagopa.interop.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.interop.partyprocess.api.ProcessApiService
import it.pagopa.interop.partyprocess.api.converters.partymanagement.InstitutionConverter
import it.pagopa.interop.partyprocess.api.impl.Conversions._
import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration
import it.pagopa.interop.partyprocess.error.PartyProcessErrors._
import it.pagopa.interop.partyprocess.model.{InstitutionSeed, User, _}
import it.pagopa.interop.partyprocess.service._
import it.pagopa.interop.partyprocess.utils.LogoUtils
import it.pagopa.userreg.client.model.UserId

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
  productManagementService: ProductManagementService,
  pdfCreator: PDFCreator,
  fileManager: FileManager,
  signatureService: SignatureService,
  mailer: MailEngine,
  mailTemplate: PersistedTemplate,
  mailNotificationTemplate: PersistedTemplate,
  mailRejectTemplate: PersistedTemplate,
  relationshipService: RelationshipService,
  productService: ProductService
)(implicit ec: ExecutionContext)
    extends ProcessApiService {

  private val logger = Logger.takingImplicit[ContextFieldsToLog](this.getClass)
  private val SELC   = "SELC"

  private final val validOnboardingStates: Seq[PartyManagementDependency.RelationshipState] =
    List(
      PartyManagementDependency.RelationshipState.ACTIVE,
      PartyManagementDependency.RelationshipState.DELETED,
      PartyManagementDependency.RelationshipState.SUSPENDED
    )

  private def sendOnboardingMail(
    addresses: Seq[String],
    file: File,
    productName: String,
    onboardingMailParameters: Map[String, String]
  )(implicit contexts: Seq[(String, String)]): Future[Unit] = {
    mailer.sendMail(mailTemplate.copy(subject = s"$productName: Accordo di Adesione"))(
      addresses,
      s"${productName}_accordo_adesione.pdf",
      file,
      onboardingMailParameters
    )("onboarding-contract-email")
  }

  private def sendOnboardingNotificationMail(
    addresses: Seq[String],
    file: File,
    productName: String,
    onboardingMailParameters: Map[String, String]
  )(implicit contexts: Seq[(String, String)]): Future[Unit] = {
    mailer.sendMail(mailNotificationTemplate)(
      addresses,
      s"${productName}_accordo_adesione.pdf",
      file,
      onboardingMailParameters
    )("onboarding-complete-email-notification")
  }

  private def sendOnboardingRejectEmail(emails: Seq[String], onboardingMailParameters: Map[String, String], logo: File)(
    implicit contexts: Seq[(String, String)]
  ): Future[Unit] = {
    mailer.sendMail(mailRejectTemplate)(emails, "pagopa-logo.png", logo, onboardingMailParameters)(
      "onboarding-complete-email"
    )
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
      institutionUuid  <- institutionId.traverse(_.toFutureUUID)
      institution      <- getInstitutionByOptionIdAndOptionExternalId(institutionUuid, institutionExternalId)(bearer)
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
    } yield OnboardingInfo(userId, onboardingData)

    onComplete(result) {
      case Success(res)                       => getOnboardingInfo200(res)
      case Failure(ex: ResourceNotFoundError) =>
        logger.info("No onboarding information found for {}", ex.resourceId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getOnboardingInfo404(errorResponse)
      case Failure(ex)                        =>
        logger.error("Error getting onboarding info", ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, GettingOnboardingInfoError)
        getOnboardingInfo400(errorResponse)

    }
  }

  private def getInstitutionByOptionIdAndOptionExternalId(
    institutionId: Option[UUID],
    institutionExternalId: Option[String]
  )(bearer: String)(implicit contexts: Seq[(String, String)]): Future[Option[PartyManagementDependency.Institution]] = {
    institutionId
      .fold {
        institutionExternalId.traverse(externalId =>
          partyManagementService.retrieveInstitutionByExternalId(externalId)(bearer)
        )
      } { institutionId =>
        partyManagementService
          .retrieveInstitution(institutionId)(bearer)
          .map(institution =>
            institutionExternalId
              .flatMap(externalId => Option.when(institution.externalId === externalId)(institution))
              .orElse(Option(institution))
          )
      }
      .recover {
        case _: ResourceNotFoundError => None
        case ex                       => throw ex
      }
  }

  private def getOnboardingDataDetails(
    bearer: String
  )(implicit contexts: Seq[(String, String)]): PartyManagementDependency.Relationship => Future[OnboardingData] =
    relationship => {
      for {
        institution <- partyManagementService.retrieveInstitution(relationship.to)(bearer)
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
        billing = institution.products.get(relationship.product.id).map(m => billingToApi(m.billing)),
        pricingPlan = institution.products.get(relationship.product.id).flatMap(m => m.pricingPlan),
        attributes =
          institution.attributes.map(attribute => Attribute(attribute.origin, attribute.code, attribute.description))
      )

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
      institutionOption        <- getInstitutionByOptionIdAndOptionExternalId(
        None,
        Option(onboardingRequest.institutionExternalId)
      )(bearer)
      institution              <- institutionOption.toFuture(
        InstitutionNotFound(None, Option(onboardingRequest.institutionExternalId))
      )
      institutionRelationships <- partyManagementService.retrieveRelationships(
        from = None,
        to = Some(institution.id),
        roles = Seq.empty,
        states = Seq.empty,
        products = Seq.empty,
        productRoles = Seq.empty
      )(bearer)
      _                        <- notExistsAnOnboardedManager(institutionRelationships, onboardingRequest.productId)
      currentManager = extractActiveManager(institutionRelationships, onboardingRequest.productId)
      response <- performOnboardingWithSignature(
        OnboardingSignedRequest.fromApi(onboardingRequest),
        currentManager,
        institution,
        currentUser
      )(bearer, contexts)
    } yield response

    onComplete(result) {
      case Success(_)                            => onboardingInstitution204
      case Failure(ex: ContractNotFound)         =>
        logger.info("Error while onboarding institution {}", onboardingRequest.institutionExternalId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        onboardingInstitution404(errorResponse)
      case Failure(ex: InstitutionNotFound)      =>
        logger.error("Institution having externalId {} not found", onboardingRequest.institutionExternalId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        onboardingInstitution404(errorResponse)
      case Failure(ex: OnboardingInvalidUpdates) =>
        logger.error("Invalid institution updates for Institution {}", onboardingRequest.institutionExternalId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, ex)
        onboardingInstitution409(errorResponse)
      case Failure(ManagerFoundError)            =>
        logger.error("Institution already onboarded {}", onboardingRequest.institutionExternalId, ManagerFoundError)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, ManagerFoundError)
        onboardingInstitution400(errorResponse)
      case Failure(ex)                           =>
        logger.error("Error while onboarding institution {}", onboardingRequest.institutionExternalId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, OnboardingOperationError)
        complete(StatusCodes.InternalServerError, errorResponse)
    }

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
        onboardingRequest.institutionId,
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
      _                        <- existsAnOnboardedManager(institutionRelationships, onboardingRequest.productId)
      activeManager = extractActiveManager(institutionRelationships, onboardingRequest.productId)
      response <- performOnboardingWithSignature(
        OnboardingSignedRequest.fromApi(onboardingRequest),
        activeManager,
        institution,
        currentUser
      )(bearer, contexts)
    } yield response

    onComplete(result) {
      case Success(_)                       => onboardingLegalsOnInstitution204
      case Failure(ex: InstitutionNotFound) =>
        logger.error(
          "Error while onboarding Legals of institution {} and/or externalId {} caused by not existent institution",
          onboardingRequest.institutionId,
          onboardingRequest.institutionExternalId,
          ex
        )
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        onboardingLegalsOnInstitution404(errorResponse)
      case Failure(ex)                      =>
        logger.error(
          "Error while onboarding Legals of institution {} and/or externalId {}",
          onboardingRequest.institutionId,
          onboardingRequest.institutionExternalId,
          ex
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

  private def getValidManager(manager: Option[PartyManagementDependency.Relationship], users: Seq[User])(implicit
    context: Seq[(String, String)]
  ): Future[User] =
    manager
      .map(getUserFromRelationship)
      .getOrElse(users.find(user => user.role == PartyRole.MANAGER).toFuture(ManagerFoundError))

  private def getUserFromRelationship(
    relationship: PartyManagementDependency.Relationship
  )(implicit context: Seq[(String, String)]): Future[User] =
    userRegistryManagementService
      .getUserById(relationship.from)
      .map(user =>
        User(
          id = user.id,
          name = user.name.getOrElse(""),
          surname = user.surname.getOrElse(""),
          taxCode = user.taxCode.getOrElse(""),
          role = roleToApi(relationship.role),
          email = None,
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
      _                  <- validateOverridingData(onboardingRequest, institution)
      validUsers         <- verifyUsersByRoles(onboardingRequest.users, Set(PartyRole.MANAGER, PartyRole.DELEGATE))
      validManager       <- getValidManager(activeManager, validUsers)
      personIdsWithRoles <- Future.traverse(validUsers)(u => addUser(u, onboardingRequest.productId))
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
              onboardingRequest.billing,
              institution.origin
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
      pdf    <- pdfCreator.createContract(contractTemplate, validManager, validUsers, institution, onboardingRequest)
      digest <- signatureService.createDigest(pdf)
      token  <- partyManagementService.createToken(
        PartyManagementDependency.Relationships(relationships),
        digest,
        onboardingRequest.contract.version,
        onboardingRequest.contract.path
      )(bearer)
      _ = logger.info("Digest {}", digest)

      onboardingMailParameters <- institution.origin match {
        case SELC => getOnboardingMailNotificationParameters(token.token, currentUser, onboardingRequest)
        case _    => getOnboardingMailParameters(token.token, currentUser, onboardingRequest)
      }
      destinationMails = institution.origin match {
        case SELC => Seq(ApplicationConfiguration.onboardingMailNotificationInstitutionAdminEmailAddress)
        case _    =>
          ApplicationConfiguration.destinationMails.getOrElse(
            Seq(onboardingRequest.institutionUpdate.flatMap(_.digitalAddress).getOrElse(institution.digitalAddress))
          )
      }
      _                        <- institution.origin match {
        case SELC =>
          sendOnboardingNotificationMail(destinationMails, pdf, onboardingRequest.productName, onboardingMailParameters)
        case _    => sendOnboardingMail(destinationMails, pdf, onboardingRequest.productName, onboardingMailParameters)
      }
      _ = logger.info(s"$token for institution: ${institution.id}")
    } yield ()
  }

  private def validateOverridingData(
    onboardingRequest: OnboardingSignedRequest,
    institution: PartyManagementDependency.Institution
  ): Future[Unit] = {
    lazy val areIpaDataMatching: Boolean = onboardingRequest.institutionUpdate.forall(updates =>
      updates.description.forall(_ == institution.description) &&
        updates.taxCode.forall(_ == institution.taxCode) &&
        updates.digitalAddress.forall(_ == institution.digitalAddress) &&
        updates.zipCode.forall(_ == institution.zipCode) &&
        updates.address.forall(_ == institution.address)
    )

    Future
      .failed(OnboardingInvalidUpdates(institution.externalId))
      .unlessA(institution.origin != "IPA" || areIpaDataMatching)
  }

  private def getOnboardingMailNotificationParameters(
    token: String,
    currentUser: UserRegistryUser,
    onboardingRequest: OnboardingSignedRequest
  ): Future[Map[String, String]] = {
    val tokenParameters: Map[String, String] = {
      ApplicationConfiguration.onboardingMailNotificationPlaceholdersReplacement.map { case (k, placeholder) =>
        (k, s"$placeholder$token")
      }
    }

    val productParameters: Map[String, String] = Map(
      ApplicationConfiguration.onboardingMailNotificationProductNamePlaceholder -> onboardingRequest.productName
    )

    val userParameters: Map[String, String] = Map(
      ApplicationConfiguration.onboardingMailNotificationRequesterNamePlaceholder    -> currentUser.name.getOrElse(""),
      ApplicationConfiguration.onboardingMailNotificationRequesterSurnamePlaceholder -> currentUser.surname.getOrElse(
        ""
      )
    )

    val institutionInfoParameters: Map[String, String] = {
      onboardingRequest.institutionUpdate.fold(Map.empty[String, String]) { iu =>
        Seq(
          ApplicationConfiguration.onboardingMailNotificationInstitutionNamePlaceholder.some.zip(iu.description)
        ).flatten.toMap
      }
    }

    Future.successful(tokenParameters ++ productParameters ++ userParameters ++ institutionInfoParameters)
  }

  private def getOnboardingRejectMailParameters(productName: String): Future[Map[String, String]] = {
    val productParameters: Map[String, String] = Map(
      ApplicationConfiguration.onboardingRejectMailProductNamePlaceholder -> productName
    )

    Future.successful(productParameters)
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

    val productParameters: Map[String, String] = Map(
      ApplicationConfiguration.onboardingMailProductIdPlaceholder   -> onboardingRequest.productId,
      ApplicationConfiguration.onboardingMailProductNamePlaceholder -> onboardingRequest.productName
    )

    val userParameters: Map[String, String] = Map(
      ApplicationConfiguration.onboardingMailUserNamePlaceholder    -> currentUser.name.getOrElse(""),
      ApplicationConfiguration.onboardingMailUserSurnamePlaceholder -> currentUser.surname.getOrElse(""),
      ApplicationConfiguration.onboardingMailTaxCodePlaceholder     -> currentUser.taxCode.getOrElse("")
    )

    val institutionInfoParameters: Map[String, String] = {
      onboardingRequest.institutionUpdate.fold(Map.empty[String, String]) { iu =>
        Seq(
          ApplicationConfiguration.onboardingMailInstitutionInfoInstitutionTypePlaceholder.some.zip(iu.institutionType),
          ApplicationConfiguration.onboardingMailInstitutionInfoDescriptionPlaceholder.some.zip(iu.description),
          ApplicationConfiguration.onboardingMailInstitutionInfoDigitalAddressPlaceholder.some.zip(iu.digitalAddress),
          ApplicationConfiguration.onboardingMailInstitutionInfoAddressPlaceholder.some.zip(iu.address),
          ApplicationConfiguration.onboardingMailInstitutionInfoZipCodePlaceholder.some.zip(iu.zipCode),
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

    Future.successful(
      tokenParameters ++ productParameters ++ userParameters ++ institutionInfoParameters ++ billingParameters
    )

  }

  private def createOrGetRelationship(
    personId: UUID,
    institutionId: UUID,
    role: PartyManagementDependency.PartyRole,
    product: String,
    productRole: String,
    pricingPlan: Option[String],
    institutionUpdate: Option[InstitutionUpdate],
    billing: Option[Billing],
    origin: String = SELC
  )(bearer: String)(implicit contexts: Seq[(String, String)]): Future[PartyManagementDependency.Relationship] = {
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
            zipCode = i.zipCode,
            taxCode = i.taxCode,
            paymentServiceProvider = i.paymentServiceProvider.map(p =>
              PartyManagementDependency.PaymentServiceProvider(
                abiCode = p.abiCode,
                businessRegisterNumber = p.businessRegisterNumber,
                legalRegisterName = p.legalRegisterName,
                legalRegisterNumber = p.legalRegisterNumber,
                vatNumberGroup = p.vatNumberGroup
              )
            ),
            dataProtectionOfficer = i.dataProtectionOfficer.map(d =>
              PartyManagementDependency.DataProtectionOfficer(address = d.address, email = d.email, pec = d.pec)
            )
          )
        ),
        billing = billing.map(b =>
          PartyManagementDependency
            .Billing(vatNumber = b.vatNumber, recipientCode = b.recipientCode, publicServices = b.publicServices)
        ),
        state = origin match {
          case SELC => Option(PartyManagementDependency.RelationshipState.TOBEVALIDATED)
          case _    => Option(PartyManagementDependency.RelationshipState.PENDING)
        }
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
      _             <- existsAnOnboardedManager(relationships, onboardingRequest.productId)
      result        <- performOnboardingWithoutSignature(onboardingRequest, Set(PartyRole.SUB_DELEGATE), institution)(
        bearer,
        contexts
      )
    } yield result

    onComplete(result) {
      case Success(relationships) => onboardingSubDelegatesOnInstitution200(relationships)
      case Failure(ex)            =>
        logger.error("Error while onboarding subdelegates on institution {}", onboardingRequest.institutionId, ex)
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
      _             <- existsAnOnboardedManager(relationships, onboardingRequest.productId)
      result        <- performOnboardingWithoutSignature(onboardingRequest, Set(PartyRole.OPERATOR), institution)(
        bearer,
        contexts
      )
    } yield result

    onComplete(result) {
      case Success(relationships) => onboardingOperators200(relationships)
      case Failure(ex)            =>
        logger.error("Error while onboarding operators on institution {}", onboardingRequest.institutionId, ex)
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
      userIds       <- Future.traverse(validUsers)(u => addUser(u, onboardingRequest.productId))
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
          relationship.state != PartyManagementDependency.RelationshipState.REJECTED &&
          relationship.state != PartyManagementDependency.RelationshipState.TOBEVALIDATED
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

  private def addUser(user: User, productId: String)(implicit
    bearer: String,
    contexts: Seq[(String, String)]
  ): Future[(UserId, PartyRole, String, String)] = {
    logger.info("Adding user {}", user.toString)
    createPerson(user)(bearer)
      .recoverWith {
        case _: ResourceConflictError => Future.successful(UserId(user.id))
        case ex                       => Future.failed(ex)
      }
      .map((_, user.role, productId, user.productRole))
  }

  private def createPerson(user: User)(bearer: String)(implicit contexts: Seq[(String, String)]): Future[UserId] =
    for {
      _ <- partyManagementService.createPerson(PartyManagementDependency.PersonSeed(user.id))(bearer)
    } yield UserId(user.id)

  private def createInstitutionInner(
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
        address = institution.address,
        zipCode = institution.zipCode,
        institutionType = Option.empty,
        origin = institution.origin
      )
      institution <- partyManagementService.createInstitution(seed)(bearer)
      _ = logger.info("institution created {}", institution.externalId)
    } yield institution

  private def createInstitutionInnerRaw(externalId: String, institutionSeed: InstitutionSeed)(implicit
    bearer: String,
    contexts: Seq[(String, String)]
  ): Future[PartyManagementDependency.Institution] = {
    val seed = PartyManagementDependency.InstitutionSeed(
      externalId = externalId,
      originId = s"${institutionSeed.institutionType.getOrElse("SELC")}_${externalId}",
      description = institutionSeed.description,
      digitalAddress = institutionSeed.digitalAddress,
      taxCode = institutionSeed.taxCode,
      attributes =
        institutionSeed.attributes.map(a => PartyManagementDependency.Attribute(a.origin, a.code, a.description)),
      address = institutionSeed.address,
      zipCode = institutionSeed.zipCode,
      institutionType = institutionSeed.institutionType,
      origin = SELC,
      paymentServiceProvider = institutionSeed.paymentServiceProvider.map(p =>
        PartyManagementDependency.PaymentServiceProvider(
          abiCode = p.abiCode,
          businessRegisterNumber = p.businessRegisterNumber,
          legalRegisterName = p.legalRegisterName,
          legalRegisterNumber = p.legalRegisterNumber,
          vatNumberGroup = p.vatNumberGroup
        )
      ),
      dataProtectionOfficer = institutionSeed.dataProtectionOfficer.map(d =>
        PartyManagementDependency.DataProtectionOfficer(address = d.address, email = d.email, pec = d.pec)
      )
    )

    for {
      institution <- partyManagementService.createInstitution(seed)(bearer)
      _ = logger.info("institution created {}", institution.externalId)
    } yield institution
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
      case Success(_)                        => activateRelationship204
      case Failure(ex: RelationshipNotFound) =>
        logger.error("Error while activating relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        activateRelationship404(errorResponse)
      case Failure(ex)                       =>
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
      case Success(_)                        => suspendRelationship204
      case Failure(ex: RelationshipNotFound) =>
        logger.error("Error while suspending relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        suspendRelationship404(errorResponse)
      case Failure(ex)                       =>
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
        logger.error("Error while getting onboarding document of relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getOnboardingDocument404(errorResponse)
      case Failure(ex: RelationshipDocumentNotFound) =>
        logger.error("Error while getting onboarding document of relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getOnboardingDocument404(errorResponse)
      case Failure(ex)                               =>
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
        logger.error("Getting relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        getRelationship404(errorResponse)
      case Failure(ex)                       =>
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
      relationshipUUID <- relationshipId.toFutureUUID
      _                <- partyManagementService.deleteRelationshipById(relationshipUUID)(bearer)
    } yield ()

    onComplete(result) {
      case Success(_)                      => deleteRelationshipById204
      case Failure(ex: UidValidationError) =>
        logger.error("Error while deleting relationship {}", relationshipId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                     =>
        logger.error("Error while deleting relationship {}", relationshipId, ex)
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
      uuid        <- id.toFutureUUID
      institution <- partyManagementService.retrieveInstitution(uuid)(bearer)
    } yield institution

    onComplete(result) {
      case Success(institution)               => getInstitution200(InstitutionConverter.dependencyToApi(institution))
      case Failure(ex: UidValidationError)    =>
        logger.error(s"Error while retrieving institution for id $id", ex)
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex: ResourceNotFoundError) =>
        logger.info(s"Cannot find institution having id $id")
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                        =>
        logger.error(s"Error while retrieving institution for id $id", ex)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, GetInstitutionError(id))
        complete(errorResponse.status, errorResponse)
    }
  }

  /**
   * Code: 201, Message: successful operation, DataType: Institution
   * Code: 404, Message: Invalid externalId supplied, DataType: Problem
   * Code: 409, Message: institution having externalId already exists, DataType: Problem
   */
  override def createInstitution(externalId: String)(implicit
    toEntityMarshallerInstitution: ToEntityMarshaller[Institution],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Creating institution having external id $externalId")
    val result = for {
      bearer      <- getFutureBearer(contexts)
      institution <- createInstitutionInner(externalId)(bearer, contexts)
    } yield institution

    onComplete(result) {
      case Success(institution)               => createInstitution201(InstitutionConverter.dependencyToApi(institution))
      case Failure(ex: ResourceNotFoundError) =>
        logger.error(s"Institution having externalId $externalId not exists in registry", ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, CreateInstitutionNotFound)
        complete(errorResponse.status, errorResponse)
      case Failure(ex: ResourceConflictError) =>
        logger.error(s"Institution having externalId $externalId already exists", ex)
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, CreateInstitutionConflict)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                        =>
        logger.error(s"Error while creating institution having external id $externalId", ex)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, CreateInstitutionError)
        complete(errorResponse.status, errorResponse)
    }
  }

  /**
    * Code: 201, Message: successful operation, DataType: Institution
    * Code: 404, Message: Invalid externalId supplied, DataType: Problem
    * Code: 409, Message: institution having externalId already exists, DataType: Problem
    */
  override def createInstitutionRaw(externalId: String, institutionSeed: InstitutionSeed)(implicit
    toEntityMarshallerInstitution: ToEntityMarshaller[Institution],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Creating institution having external id $externalId")
    val result = for {
      bearer      <- getFutureBearer(contexts)
      institution <- createInstitutionInnerRaw(externalId, institutionSeed)(bearer, contexts)
    } yield institution

    onComplete(result) {
      case Success(institution) => createInstitutionRaw200(InstitutionConverter.dependencyToApi(institution))
      case Failure(ex: ResourceNotFoundError) =>
        logger.error(s"Institution having externalId $externalId not exists in registry", ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, CreateInstitutionNotFound)
        complete(errorResponse.status, errorResponse)
      case Failure(ex: ResourceConflictError) =>
        logger.error(s"Institution having externalId $externalId already exists", ex)
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, CreateInstitutionConflict)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                        =>
        logger.error(s"Error while creating institution having external id $externalId", ex)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, CreateInstitutionError)
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
        logger.error("Error while getting relationship for institution {} and current user", institutionId, ex)
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
      bearer         <- getFutureBearer(contexts)
      statesFilter   <- parseArrayParameters(states).traverse(par => ProductState.fromValue(par)).toFuture
      institutionUid <- institutionId.toFutureUUID
      institution    <- partyManagementService.retrieveInstitution(institutionUid)(bearer)
      products       <- productService.retrieveInstitutionProducts(institution, statesFilter)(bearer)
    } yield products

    onComplete(result) {
      case Success(institution) if institution.products.isEmpty =>
        val errorResponse: Problem =
          problemOf(StatusCodes.NotFound, ProductsNotFoundError(institutionId))
        retrieveInstitutionProducts404(errorResponse)
      case Success(institution)                                 => retrieveInstitutionProducts200(institution)
      case Failure(ex: UidValidationError)                      =>
        logger.error("Error while retrieving products for institution {}", institutionId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.Unauthorized, ex)
        complete(errorResponse.status, errorResponse)
      case Failure(ex)                                          =>
        logger.error("Error while retrieving products for institution {}", institutionId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, GetProductsError)
        complete(errorResponse.status, errorResponse)
    }
  }

  override def onboardingApprove(
    tokenId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info(s"Onboarding Approve having tokenId $tokenId")

    def createUser(relationships: Seq[Relationship], institutionInternalId: String, u: UserRegistryUser) = {
      val email = u.email match {
        case Some(value) => value.getOrElse(institutionInternalId, "")
        case _           => ""
      }

      User(
        id = u.id,
        taxCode = u.taxCode.getOrElse(""),
        name = u.name.getOrElse(""),
        surname = u.surname.getOrElse(""),
        email = Option(email),
        role = roleToApi(relationships.filter(_.from == u.id).head.role),
        productRole = relationships.filter(_.from == u.id).head.product.role
      )
    }

    val result: Future[Unit] = for {
      bearer      <- getFutureBearer(contexts)
      tokenIdUUID <- tokenId.toFutureUUID
      token       <- partyManagementService.verifyToken(tokenIdUUID)
      uid         <- getUidFuture(contexts)
      userId      <- uid.toFutureUUID
      currentUser <- userRegistryManagementService.getUserById(userId)

      managerLegals = token.legals.filter(_.role == PartyManagementDependency.PartyRole.MANAGER)

      managerRegistryUsers <- Future.traverse(managerLegals)(legal =>
        userRegistryManagementService.getUserWithEmailById(legal.partyId)
      )
      managerRelationships <- Future.traverse(managerLegals)(relationship =>
        partyManagementService.getRelationshipById(relationship.relationshipId)(bearer)
      )
      _                    <- Future.traverse(managerRelationships)(managerRelationship =>
        getValidManager(Option(managerRelationship), Seq())
      )
      institutions         <- Future.traverse(managerRelationships)(legalUser =>
        partyManagementService.retrieveInstitution(legalUser.to)(bearer)
      )

      institutionInternalId = institutions.headOption.map(_.id.toString).getOrElse("")
      institutionExternalId = institutions.headOption.map(_.externalId.toString).getOrElse("")

      institution <- institutions.headOption.toFuture(
        InstitutionNotFound(Option(institutionInternalId), Option(institutionExternalId))
      )

      productId    = managerRelationships.head.product.id
      managerUsers = managerRegistryUsers.map(u => createUser(managerRelationships, institutionInternalId, u))
      managerUser         <- managerUsers.headOption.toFuture(ManagerFoundError)
      manager             <- managerRegistryUsers.headOption.toFuture(ManagerFoundError)
      managerRelationship <- managerRelationships.find(_.from == manager.id).toFuture(ManagerFoundError)

      onboardingProduct <- productManagementService.getProductById(productId)

      otherUsersLegals = token.legals.filter(_.role != PartyManagementDependency.PartyRole.MANAGER)
      otherUsersRelationships <- Future.traverse(otherUsersLegals)(legal =>
        partyManagementService.getRelationshipById(legal.relationshipId)(bearer)
      )
      otherRegistryUsers      <- Future.traverse(otherUsersLegals)(legal =>
        userRegistryManagementService.getUserWithEmailById(legal.partyId)
      )
      otherUsers = otherRegistryUsers.map(u => createUser(otherUsersRelationships, institutionInternalId, u))

      institutionUpdate = managerRelationship.institutionUpdate.as(
        managerRelationship.institutionUpdate
          .map(i =>
            InstitutionUpdate(
              institutionType = i.institutionType,
              description = i.description,
              digitalAddress = i.digitalAddress,
              address = i.address,
              zipCode = i.zipCode,
              taxCode = i.taxCode,
              paymentServiceProvider = i.paymentServiceProvider.map(p =>
                PaymentServiceProvider(
                  abiCode = p.abiCode,
                  businessRegisterNumber = p.businessRegisterNumber,
                  legalRegisterName = p.legalRegisterName,
                  legalRegisterNumber = p.legalRegisterNumber,
                  vatNumberGroup = p.vatNumberGroup
                )
              ),
              dataProtectionOfficer = i.dataProtectionOfficer.map(d =>
                DataProtectionOfficer(address = d.address, email = d.email, pec = d.pec)
              )
            )
          )
          .get
      )

      onboardingRequest = OnboardingSignedRequest(
        productId = managerRelationship.product.id,
        productName = onboardingProduct.name,
        users = managerUsers ++ otherUsers,
        institutionUpdate = institutionUpdate,
        pricingPlan = managerRelationship.pricingPlan,
        billing = managerRelationship.billing.map(b =>
          Billing(vatNumber = b.vatNumber, recipientCode = b.recipientCode, publicServices = b.publicServices)
        ),
        contract =
          OnboardingContract(version = onboardingProduct.version, path = onboardingProduct.contractTemplatePath)
      )

      contractTemplate <- getFileAsString(onboardingRequest.contract.path)
      pdf              <- pdfCreator.createContract(
        contractTemplate,
        managerUser,
        managerUsers ++ otherUsers,
        institution,
        onboardingRequest
      )

      // Enabling Users relationship
      _                <- Future.traverse(managerLegals)(managerLegal =>
        partyManagementService.enableRelationship(managerLegal.relationshipId)(bearer)
      )
      _                <- Future.traverse(otherUsersLegals)(otherUsersLegal =>
        partyManagementService.enableRelationship(otherUsersLegal.relationshipId)(bearer)
      )
      // Update contract's digest
      digest           <- signatureService.createDigest(pdf)
      _                <- partyManagementService.updateTokenDigest(tokenIdUUID, digest)(bearer)

      onboardingMailParameters <- getOnboardingMailParameters(tokenId, currentUser, onboardingRequest)

      destinationMails =
        ApplicationConfiguration.destinationMails.getOrElse(
          Seq(
            onboardingRequest.institutionUpdate
              .flatMap(_.digitalAddress)
              .getOrElse(institutions.headOption.map(_.digitalAddress).getOrElse(""))
          )
        )

      _ <- sendOnboardingMail(destinationMails, pdf, onboardingRequest.productName, onboardingMailParameters)

    } yield ()

    onComplete(result) {
      case Success(_)                            => onboardingApprove204
      case Failure(ex: ContractNotFound)         =>
        logger.info(s"Error while enable onbarding with $tokenId", ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        onboardingApprove404(errorResponse)
      case Failure(ex: InstitutionNotFound)      =>
        logger.error(s"Token $tokenId not found", ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        onboardingApprove404(errorResponse)
      case Failure(ex: OnboardingInvalidUpdates) =>
        logger.error(s"Invalid institution updates for Token $tokenId", ex)
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, ex)
        onboardingApprove409(errorResponse)
      case Failure(ManagerFoundError)            =>
        logger.error(s"Institution already onboarded for Token $tokenId", ManagerFoundError)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, ManagerFoundError)
        onboardingApprove400(errorResponse)
      case Failure(ex)                           =>
        logger.error(s"Error while updating Token $tokenId", ex)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, OnboardingOperationError)
        complete(StatusCodes.InternalServerError, errorResponse)
    }
  }

  override def onboardingReject(
    tokenId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info(s"Onboarding Reject having tokenId $tokenId")

    val result: Future[Unit] = for {
      bearer      <- getFutureBearer(contexts)
      tokenIdUUID <- tokenId.toFutureUUID
      token       <- partyManagementService.verifyToken(tokenIdUUID)
      uid         <- getUidFuture(contexts)

      managerLegals = token.legals.filter(_.role == PartyManagementDependency.PartyRole.MANAGER)
      managerRelationships <- Future.traverse(managerLegals)(relationship =>
        partyManagementService.getRelationshipById(relationship.relationshipId)(bearer)
      )

      institutions <- Future.traverse(managerRelationships)(legalUser =>
        partyManagementService.retrieveInstitution(legalUser.to)(bearer)
      )

      destinationMails =
        ApplicationConfiguration.destinationMails.getOrElse(
          Seq(
            institutions.headOption
              .map(_.digitalAddress)
              .getOrElse(ApplicationConfiguration.institutionAlternativeEmail)
          )
        )

      productId = managerRelationships.head.product.id
      onboardingProduct <- productManagementService.getProductById(productId)
      mailParameters    <- getOnboardingRejectMailParameters(onboardingProduct.name)
      logo              <- LogoUtils.getLogoFile(fileManager, ApplicationConfiguration.emailLogoPath)(ec)
      result            <- partyManagementService.invalidateToken(token.id)
      _                 <- sendOnboardingRejectEmail(destinationMails, mailParameters, logo)

    } yield result

    onComplete(result) {
      case Success(_)                            => onboardingReject204
      case Failure(ex: ContractNotFound)         =>
        logger.info(s"Error while enable onbarding with $tokenId", ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        onboardingReject404(errorResponse)
      case Failure(ex: InstitutionNotFound)      =>
        logger.error(s"Token $tokenId not found", ex)
        val errorResponse: Problem = problemOf(StatusCodes.NotFound, ex)
        onboardingReject404(errorResponse)
      case Failure(ex: OnboardingInvalidUpdates) =>
        logger.error(s"Invalid institution updates for Token $tokenId", ex)
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, ex)
        onboardingReject409(errorResponse)
      case Failure(ManagerFoundError)            =>
        logger.error(s"Institution already onboarded for Token $tokenId", ManagerFoundError)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, ManagerFoundError)
        onboardingReject400(errorResponse)
      case Failure(ex)                           =>
        logger.error(s"Error while updating Token $tokenId", ex)
        val errorResponse: Problem = problemOf(StatusCodes.InternalServerError, OnboardingOperationError)
        complete(StatusCodes.InternalServerError, errorResponse)
    }
  }
}
