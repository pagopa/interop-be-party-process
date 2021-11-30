package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.{ContentType, HttpEntity, MessageEntity, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, onComplete}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import cats.implicits.toTraverseOps
import it.pagopa.pdnd.interop.commons.files.service.FileManager
import it.pagopa.pdnd.interop.commons.mail.model.PersistedTemplate
import it.pagopa.pdnd.interop.commons.utils.AkkaUtils.getFutureBearer
import it.pagopa.pdnd.interop.commons.utils.Digester
import it.pagopa.pdnd.interop.commons.utils.TypeConversions.{OptionOps, StringOps}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker.ApiError
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.{
  Organization,
  OrganizationSeed,
  PersonSeed,
  Relationship,
  RelationshipProduct,
  RelationshipProductSeed,
  RelationshipSeed,
  Relationships,
  RelationshipsSeed,
  Problem => _
}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.{model => PartyManagementDependency}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.ProcessApiService
import it.pagopa.pdnd.interop.uservice.partyprocess.api.impl.Conversions.{
  relationshipProductToApi,
  relationshipStateToApi,
  roleToApi,
  roleToDependency
}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.ApplicationConfiguration
import it.pagopa.pdnd.interop.uservice.partyprocess.error._
import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.service._
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.Certification.{
  NONE => CertificationEnumsNone
}
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{
  User => UserRegistryUser,
  UserExtras => UserRegistryUserExtras,
  UserSeed => UserRegistryUserSeed
}
import org.slf4j.{Logger, LoggerFactory}
import spray.json._

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.{Base64, UUID}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ProcessApiServiceImpl(
  partyManagementService: PartyManagementService,
  partyRegistryService: PartyRegistryService,
  attributeRegistryService: AttributeRegistryService,
  userRegistryManagementService: UserRegistryManagementService,
  pdfCreator: PDFCreator,
  fileManager: FileManager,
  mailer: MailEngine,
  mailTemplate: PersistedTemplate
)(implicit ec: ExecutionContext)
    extends ProcessApiService {

  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  final val adminPartyRoles: Set[PartyRole] = Set(PartyRole.MANAGER, PartyRole.DELEGATE, PartyRole.SUB_DELEGATE)

  private def sendOnboardingMail(addresses: Seq[String], file: File, token: String): Future[Unit] = {
    val bodyParameters =
      ApplicationConfiguration.onboardingMailPlaceholdersReplacement.map { case (k, placeholder) =>
        (k, s"$placeholder$token")
      }
    mailer.sendMail(mailTemplate)(addresses, file, bodyParameters)
  }

  /** Code: 200, Message: successful operation, DataType: OnboardingInfo
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def getOnboardingInfo(institutionId: Option[String])(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerOnboardingInfo: ToEntityMarshaller[OnboardingInfo],
    contexts: Seq[(String, String)]
  ): Route = {

    val result: Future[OnboardingInfo] = for {
      bearer          <- getFutureBearer(contexts)
      subjectUUID     <- getCallerSubjectIdentifier(bearer)
      institutionUUID <- institutionId.traverse(_.toFutureUUID)
      user            <- userRegistryManagementService.getUserById(subjectUUID)(bearer)
      personInfo = PersonInfo(user.name, user.surname, user.externalId)
      relationships <- partyManagementService.retrieveRelationships(Some(subjectUUID), institutionUUID, None, None)(
        bearer
      )
      onboardingData <- Future.traverse(relationships.items)(getOnboardingData(bearer))
    } yield OnboardingInfo(personInfo, onboardingData)

    onComplete(result) {
      case Success(res) => getOnboardingInfo200(res)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        getOnboardingInfo400(errorResponse)

    }
  }

  private def getOnboardingData(bearer: String)(relationship: Relationship): Future[OnboardingData] = {
    for {
      organization <- getOrganization(relationship.to)(bearer)
      attributes <- Future.traverse(organization.attributes)(id =>
        for {
          uuid      <- id.toFutureUUID
          attribute <- attributeRegistryService.getAttribute(uuid)(bearer)
        } yield attribute
      )
    } yield OnboardingData(
      institutionId = organization.institutionId,
      taxCode = organization.taxCode,
      description = organization.description,
      digitalAddress = organization.digitalAddress,
      state = relationshipStateToApi(relationship.state),
      role = roleToApi(relationship.role),
      productInfo = relationshipProductToApi(relationship.product),
      attributes = attributes.map(attribute =>
        Attribute(id = attribute.id, name = attribute.name, description = attribute.description)
      )
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
    def getOrganization(bearer: String): Future[Organization] =
      createOrganization(onboardingRequest.institutionId)(bearer).recoverWith { case _ =>
        partyManagementService.retrieveOrganizationByExternalId(onboardingRequest.institutionId)(bearer)
      }

    val result: Future[OnboardingResponse] = for {
      bearer           <- getFutureBearer(contexts)
      organization     <- getOrganization(bearer)
      validUsers       <- verifyUsersByRoles(onboardingRequest.users, Set(PartyRole.MANAGER, PartyRole.DELEGATE))
      personsWithRoles <- Future.traverse(validUsers)(addUser(bearer))
      _ <- Future.traverse(personsWithRoles)(pr =>
        partyManagementService.createRelationship(pr._1.id, organization.id, roleToDependency(pr._2), pr._3, pr._4)(
          bearer
        )
      )
      relationships = RelationshipsSeed(personsWithRoles.map { case (person, role, product, productRole) =>
        createRelationship(organization.id, person.id, roleToDependency(role), product, productRole)
      })
      pdf   <- pdfCreator.create(validUsers, organization)
      token <- partyManagementService.createToken(relationships, pdf._2)(bearer)
      _ <- sendOnboardingMail(
        ApplicationConfiguration.destinationMails,
        pdf._1,
        token.token
      ) //TODO address must be the digital address
      _ = logger.info(s"$token")
    } yield OnboardingResponse(token.token, pdf._1)

    onComplete(result) {
      case Success(response) =>
        onboardingOrganization201(response)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        onboardingOrganization400(errorResponse)

    }

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
    val result: Future[OnboardingResponse] = for {
      bearer       <- getFutureBearer(contexts)
      organization <- partyManagementService.retrieveOrganizationByExternalId(onboardingRequest.institutionId)(bearer)
      organizationRelationships <- partyManagementService.retrieveRelationships(
        None,
        Some(organization.id),
        None,
        None
      )(bearer)
      _                <- existsAnOnboardedManager(organizationRelationships)
      validUsers       <- verifyUsersByRoles(onboardingRequest.users, Set(PartyRole.MANAGER, PartyRole.DELEGATE))
      personsWithRoles <- Future.traverse(validUsers)(addUser(bearer))
      _ <- Future.traverse(personsWithRoles)(pr =>
        partyManagementService.createRelationship(pr._1.id, organization.id, roleToDependency(pr._2), pr._3, pr._4)(
          bearer
        )
      )
      relationships = RelationshipsSeed(personsWithRoles.map { case (person, role, products, productRole) =>
        createRelationship(organization.id, person.id, roleToDependency(role), products, productRole)
      })
      pdf   <- pdfCreator.create(validUsers, organization)
      token <- partyManagementService.createToken(relationships, pdf._2)(bearer)
      _ <- sendOnboardingMail(
        ApplicationConfiguration.destinationMails,
        pdf._1,
        token.token
      ) //TODO address must be the digital address
      _ = logger.info(s"$token")
    } yield OnboardingResponse(token.token, pdf._1)

    onComplete(result) {
      case Success(response) =>
        onboardingLegalsOnOrganization200(response)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        onboardingLegalsOnOrganization400(errorResponse)
    }
  }

  /** Code: 200, Message: successful operation, DataType: OnboardingResponse
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingSubDelegatesOnOrganization(onboardingRequest: OnboardingRequest)(implicit
    toEntityMarshallerOnboardingResponse: ToEntityMarshaller[OnboardingResponse],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    val result: Future[Unit] = for {
      bearer        <- getFutureBearer(contexts)
      organization  <- partyManagementService.retrieveOrganizationByExternalId(onboardingRequest.institutionId)(bearer)
      relationships <- partyManagementService.retrieveRelationships(None, Some(organization.id), None, None)(bearer)
      _             <- existsAnOnboardedManager(relationships)
      validUsers    <- verifyUsersByRoles(onboardingRequest.users, Set(PartyRole.SUB_DELEGATE))
      operators     <- Future.traverse(validUsers)(addUser(bearer))
      _ <- Future.traverse(operators)(pr =>
        partyManagementService.createRelationship(pr._1.id, organization.id, roleToDependency(pr._2), pr._3, pr._4)(
          bearer
        )
      )
      _ = logger.info(s"Operators created ${operators.map(_.toString).mkString(",")}")
    } yield ()

    onComplete(result) {
      case Success(_) => onboardingOperators201
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        onboardingOperators400(errorResponse)
    }
  }

  /** Code: 201, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingOperators(
    onboardingRequest: OnboardingRequest
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    val result: Future[Unit] = for {
      bearer        <- getFutureBearer(contexts)
      organization  <- partyManagementService.retrieveOrganizationByExternalId(onboardingRequest.institutionId)(bearer)
      relationships <- partyManagementService.retrieveRelationships(None, Some(organization.id), None, None)(bearer)
      _             <- existsAnOnboardedManager(relationships)

      validUsers <- verifyUsersByRoles(onboardingRequest.users, Set(PartyRole.OPERATOR))
      operators  <- Future.traverse(validUsers)(addUser(bearer))
      _ <- Future.traverse(operators)(pr =>
        partyManagementService.createRelationship(pr._1.id, organization.id, roleToDependency(pr._2), pr._3, pr._4)(
          bearer
        )
      )
      _ = logger.info(s"Operators created ${operators.map(_.toString).mkString(",")}")
    } yield ()

    onComplete(result) {
      case Success(_) => onboardingOperators201
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        onboardingOperators400(errorResponse)
    }
  }

  /** Code: 200, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def confirmOnboarding(token: String, contract: (FileInfo, File))(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    val result: Future[Unit] = for {
      bearer   <- getFutureBearer(contexts)
      checksum <- calculateCheckSum(token)
      verified <- verifyChecksum(contract._2, checksum, token)
      _        <- partyManagementService.consumeToken(verified, contract)(bearer)
    } yield ()

    onComplete(result) {
      case Success(_) => confirmOnboarding200
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        confirmOnboarding400(errorResponse)
    }
  }

  /** Code: 200, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def invalidateOnboarding(
    token: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    val result: Future[Unit] = for {
      bearer <- getFutureBearer(contexts)
      result <- partyManagementService.invalidateToken(token)(bearer)
    } yield result

    onComplete(result) {
      case Success(_) => invalidateOnboarding200
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        invalidateOnboarding400(errorResponse)

    }

  }

  private def verifyUsersByRoles(users: Seq[User], roles: Set[PartyRole]): Future[Seq[User]] = {
    val areValidUsers: Boolean = users.forall(user => roles.contains(user.role))
    Future.fromTry(
      Either
        .cond(
          users.nonEmpty && areValidUsers,
          users,
          new RuntimeException(
            s"Roles ${users.filter(user => !roles.contains(user.role)).mkString(", ")} are not admitted for this operation"
          )
        )
        .toTry
    )
  }

  private def existsAnOnboardedManager(relationships: Relationships): Future[Unit] = Future.fromTry {
    Either
      .cond(
        relationships.items.exists(isAnOnboardedManager),
        (),
        new RuntimeException("No onboarded managers for this institution.")
      )
      .toTry
  }

  private def isAnOnboardedManager(relationship: Relationship): Boolean = {

    relationship.role == PartyManagementDependency.PartyRole.MANAGER &&
    (
      relationship.state != PartyManagementDependency.RelationshipState.PENDING &&
        relationship.state != PartyManagementDependency.RelationshipState.REJECTED
    )

  }

  private def addUser(bearer: String)(user: User): Future[(UserRegistryUser, PartyRole, String, String)] = {
    logger.info(s"Adding user ${user.toString}")
    createPerson(user)(bearer)
      .recoverWith {
        // TODO Once errors are defined, we should check that error is "person already exists"
        case _ => userRegistryManagementService.getUserByExternalId(user.taxCode)(bearer)
      }
      .map((_, user.role, user.product, user.productRole))
  }

  private def createRelationship(
    organizationId: UUID,
    personId: UUID,
    role: PartyManagementDependency.PartyRole,
    product: String,
    productRole: String
  ): RelationshipSeed =
    RelationshipSeed(personId, organizationId, role, RelationshipProductSeed(product, productRole))

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
        )(bearer)
        .recoverWith {
          // Use can already exists on user registry
          // TODO Once errors are defined, we should check that error is "person already exists"
          case _ => userRegistryManagementService.getUserByExternalId(user.taxCode)(bearer)
        }
      _ <- partyManagementService.createPerson(PersonSeed(user.id))(bearer)
    } yield user

  private def getOrganization(institutionId: UUID)(bearer: String): Future[Organization] =
    partyManagementService.retrieveOrganization(institutionId)(bearer)

  private def createOrganization(institutionId: String)(bearer: String): Future[Organization] =
    for {
      institution <- partyRegistryService.getInstitution(institutionId)(bearer)
      categories  <- partyRegistryService.getCategories(bearer)
      category <- categories.items
        .find(cat => institution.category == cat.code)
        .map(Future.successful)
        .getOrElse(Future.failed(new RuntimeException(s"Invalid category ${institution.category}")))
      attributes <- attributeRegistryService.createAttribute("IPA", category.name, category.code)(bearer)
      _ = logger.info(s"getInstitution ${institution.id}")
      seed = OrganizationSeed(
        institutionId = institution.id,
        description = institution.description,
        digitalAddress = institution.digitalAddress, // TODO Must be non optional
        taxCode = institution.taxCode,
        attributes = attributes.attributes.filter(attr => attr.code.contains(institution.category)).map(_.id),
        products = Set.empty
      )
      organization <- partyManagementService.createOrganization(seed)(bearer)
      _ = logger.info(s"createOrganization ${organization.institutionId}")
    } yield organization

  private def calculateCheckSum(token: String): Future[String] = {
    Future.fromTry {
      Try {
        val decoded: Array[Byte] = Base64.getDecoder.decode(token)

        val jsonTxt: String    = new String(decoded, StandardCharsets.UTF_8)
        val chk: TokenChecksum = jsonTxt.parseJson.convertTo[TokenChecksum]
        chk.checksum
      }
    }
  }

  private def verifyChecksum[A](fileToCheck: File, checksum: String, output: A): Future[A] = {
    Future.fromTry(
      Either
        .cond(Digester.createMD5Hash(fileToCheck) == checksum, output, new RuntimeException("Invalid checksum"))
        .toTry
    )
  }

  private def filterFoundRelationshipsByCurrentUser(
    currentUserId: UUID,
    userRelationships: Relationships,
    institutionIdRelationships: Relationships
  )(
    productRolesFilter: Option[String],
    productsFilter: Option[String],
    roles: Option[String],
    states: Option[String]
  ): Relationships = {
    val productRolesFilterList: List[String] = productRolesFilter.getOrElse("").parseCommaSeparated
    val productsFilterList: List[String]     = productsFilter.getOrElse("").parseCommaSeparated
    val rolesFilterList: List[String]        = roles.getOrElse("").parseCommaSeparated
    val statesFilterList: List[String]       = states.getOrElse("").parseCommaSeparated

    val isAdmin: Boolean =
      userRelationships.items
        .map(rl => roleToApi(rl.role))
        .exists(rl => adminPartyRoles.contains(rl))

    val filteredRelationships: Relationships =
      if (isAdmin) institutionIdRelationships
      else
        institutionIdRelationships.copy(items = institutionIdRelationships.items.filter(r => r.from == currentUserId))

    val filteredItems: Seq[Relationship] = filteredRelationships.items
      .filter(r => r.verifyProducts(productsFilterList))
      .filter(r => r.verifyProductRoles(productRolesFilterList))
      .filter(r => r.verifyRole(rolesFilterList))
      .filter(r => r.verifyState(statesFilterList))

    Relationships(filteredItems)
  }

  /** Code: 200, Message: successful operation, DataType: Seq[RelationshipInfo]
    * Code: 400, Message: Invalid institution id supplied, DataType: Problem
    */
  override def getUserInstitutionRelationships(
    institutionId: String,
    products: Option[String],
    productRoles: Option[String],
    roles: Option[String],
    states: Option[String]
  )(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Getting relationship for institution $institutionId and current user")
    val result: Future[Seq[RelationshipInfo]] = for {
      bearer          <- getFutureBearer(contexts)
      subjectUUID     <- getCallerSubjectIdentifier(bearer)
      institutionUUID <- institutionId.toFutureUUID
      institutionIdRelationships <- partyManagementService.retrieveRelationships(
        None,
        Some(institutionUUID),
        None,
        None
      )(bearer)
      userRelationships <- partyManagementService.retrieveRelationships(
        Some(subjectUUID),
        Some(institutionUUID),
        None,
        None
      )(bearer)
      filteredRelationships = filterFoundRelationshipsByCurrentUser(
        subjectUUID,
        userRelationships,
        institutionIdRelationships
      )(productRoles, products, roles, states)
    } yield relationshipsToRelationshipsResponse(filteredRelationships)

    onComplete(result) {
      case Success(relationships) => getUserInstitutionRelationships200(relationships)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
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
    logger.info(s"Activating relationship $relationshipId")
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
        val errorResponse: Problem = Problem(Option(ex.getMessage), 404, "Not found")
        activateRelationship404(errorResponse)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
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
        val errorResponse: Problem = Problem(Option(ex.getMessage), 404, "Not found")
        suspendRelationship404(errorResponse)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        suspendRelationship400(errorResponse)
    }
  }

  override def getOnboardingDocument(relationshipId: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerFile: ToEntityMarshaller[File],
    contexts: Seq[(String, String)]
  ): Route = {
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
        response <- fileManager.get(filePath)
      } yield DocumentDetails(fileName, contentType, response)

    onComplete(result) {
      case Success(document) =>
        val output: MessageEntity = convertToMessageEntity(document)
        complete(output)
      case Failure(ex: ApiError[_]) if ex.code == 400 =>
        getOnboardingDocument400(
          Problem(Option(ex.getMessage), 400, s"Error retrieving document for relationship $relationshipId")
        )
      case Failure(ex: ApiError[_]) if ex.code == 404 =>
        getOnboardingDocument404(
          Problem(Option(ex.getMessage), 404, s"Error retrieving document for relationship $relationshipId")
        )
      case Failure(ex) =>
        complete(
          500,
          Problem(Option(ex.getMessage), 500, s"Error retrieving document for relationship $relationshipId")
        )
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

  private def relationshipsToRelationshipsResponse(relationships: Relationships): Seq[RelationshipInfo] = {
    relationships.items.map(relationshipToRelationshipInfo)
  }

  private def relationshipToRelationshipInfo(relationship: Relationship): RelationshipInfo = {
    RelationshipInfo(
      id = relationship.id,
      from = relationship.from,
      role = roleToApi(relationship.role),
      product = relationshipProductToApi(relationship.product),
      state = relationshipStateToApi(relationship.state),
      createdAt = relationship.createdAt,
      updatedAt = relationship.updatedAt
    )
  }

  private def getCallerSubjectIdentifier(bearer: String): Future[UUID] = {
    val subject = for {
//TODO      validJWT <- add jws validation
//      subjectUUID <- Try {
//        UUID.fromString(validJWT.sub)
//      }.toFuture
      subjectUUID <- bearer.toFutureUUID
    } yield subjectUUID

    subject transform {
      case s @ Success(_) => s
      case Failure(cause) => Failure(SubjectValidationError(cause.getMessage))
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
    logger.info(s"Getting relationship $relationshipId")
    val result: Future[Relationship] = for {
      bearer           <- getFutureBearer(contexts)
      relationshipUUID <- relationshipId.toFutureUUID
      relationship     <- partyManagementService.getRelationshipById(relationshipUUID)(bearer)
    } yield relationship

    onComplete(result) {
      case Success(relationship) => getRelationship200(relationshipToRelationshipInfo(relationship))
      case Failure(ex: RelationshipNotFound) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 404, "Not found")
        getRelationship404(errorResponse)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
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
    val result = for {
      bearer           <- getFutureBearer(contexts)
      _                <- getCallerSubjectIdentifier(bearer)
      relationshipUUID <- relationshipId.toFutureUUID
      _                <- partyManagementService.deleteRelationshipById(relationshipUUID)(bearer)
    } yield ()

    onComplete(result) {
      case Success(_) => deleteRelationshipById204
      case Failure(ex: SubjectValidationError) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 401, "Unauthorized")
        complete((401, errorResponse))
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        deleteRelationshipById404(errorResponse)
    }
  }

  /** Code: 200, Message: successful operation, DataType: Products
    * Code: 404, Message: Institution not found, DataType: Problem
    */
  override def retrieveInstitutionProducts(institutionId: String)(implicit
    toEntityMarshallerProducts: ToEntityMarshaller[Products],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    val result = for {
      bearer          <- getFutureBearer(contexts)
      _               <- getCallerSubjectIdentifier(bearer)
      institutionUUID <- institutionId.toFutureUUID
      organization    <- partyManagementService.retrieveOrganization(institutionUUID)(bearer)
      organizationRelationships <- partyManagementService.retrieveRelationships(
        None,
        Some(organization.id),
        None,
        None
      )(bearer)
    } yield Products(products = extractActiveProducts(organizationRelationships).map(relationshipProductToApi))

    onComplete(result) {
      case Success(institution) if institution.products.isEmpty =>
        val errorResponse: Problem = Problem(None, 404, s"Products not found for institution $institutionId")
        retrieveInstitutionProducts404(errorResponse)
      case Success(institution) => retrieveInstitutionProducts200(institution)
      case Failure(ex: SubjectValidationError) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 401, "Unauthorized")
        complete((401, errorResponse))
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 500, "Something went wrong")
        complete(StatusCodes.InternalServerError, errorResponse)
    }
  }

  private def extractActiveProducts(relationships: Relationships): Seq[RelationshipProduct] = {

    relationships.items.filter(isAnOnboardedManager).map(_.product)
  }

}
