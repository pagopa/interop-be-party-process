package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.{ContentType, HttpEntity, MessageEntity}
import akka.http.scaladsl.server.Directives.{complete, onComplete}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import cats.implicits.toTraverseOps
import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker.ApiError
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.RelationshipEnums.Role.{Delegate, Manager, Operator}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.RelationshipEnums.Status.Pending
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.{
  Organization,
  OrganizationSeed,
  PersonSeed,
  Relationship,
  RelationshipEnums,
  RelationshipSeed,
  RelationshipSeedEnums,
  Relationships,
  RelationshipsSeed,
  Problem => _
}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.ProcessApiService
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.ApplicationConfiguration.productRolesConfiguration
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.utils.{OptionOps, StringOps, TryOps}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{ApplicationConfiguration, Digester}
import it.pagopa.pdnd.interop.uservice.partyprocess.error._
import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.service._
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{
  NONE => CertificationEnumsNone,
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
  authorizationProcessService: AuthorizationProcessService,
  userRegistryManagementService: UserRegistryManagementService,
  mailer: Mailer,
  pdfCreator: PDFCreator,
  fileManager: FileManager
)(implicit ec: ExecutionContext)
    extends ProcessApiService {

  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  /** Code: 200, Message: successful operation, DataType: OnBoardingInfo
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def getOnBoardingInfo(institutionId: Option[String])(implicit
    toEntityMarshallerOnBoardingInfo: ToEntityMarshaller[OnBoardingInfo],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    val result: Future[OnBoardingInfo] = for {
      subjectUUID     <- getCallerSubjectIdentifier(contexts)
      institutionUUID <- institutionId.traverse(_.toFutureUUID)
      user            <- userRegistryManagementService.getUserById(subjectUUID)
      personInfo = PersonInfo(user.name, user.surname, user.externalId)
      relationships <- partyManagementService.retrieveRelationships(Some(subjectUUID), institutionUUID, None)
      organizations <- Future.traverse(relationships.items)(r =>
        getOrganization(r.to).map(o => (o, r.status, r.role, r.products, r.productRole))
      )
      onboardingData = organizations.map { case (o, status, role, products, productRole) =>
        OnboardingData(
          o.institutionId,
          o.description,
          o.digitalAddress,
          status.toString,
          role.toString,
          productRole = productRole,
          relationshipProducts = products,
          attributes = o.attributes,
          institutionProducts = o.products.toSet
        )
      }
    } yield OnBoardingInfo(personInfo, onboardingData)

    onComplete(result) {
      case Success(res) => getOnBoardingInfo200(res)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        getOnBoardingInfo400(errorResponse)

    }
  }

  private[this] def tokenFromContext(context: Seq[(String, String)]): Future[String] =
    Future.fromTry(
      context
        .find(_._1 == "bearer")
        .map(header => header._2)
        .toRight(new RuntimeException("Bearer Token not provided"))
        .toTry
    )

  /** Code: 201, Message: successful operation, DataType: OnBoardingResponse
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingOrganization(onBoardingRequest: OnBoardingRequest)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerOnBoardingResponse: ToEntityMarshaller[OnBoardingResponse],
    contexts: Seq[(String, String)]
  ): Route = {
    val organizationF: Future[Organization] = createOrganization(onBoardingRequest.institutionId).recoverWith {
      case _ => partyManagementService.retrieveOrganizationByExternalId(onBoardingRequest.institutionId)
    }

    val result: Future[OnBoardingResponse] = for {
      organization     <- organizationF
      validUsers       <- verifyUsersByRoles(onBoardingRequest.users, Set(Manager.toString, Delegate.toString))
      personsWithRoles <- Future.traverse(validUsers)(addUser)
      _ <- Future.traverse(personsWithRoles)(pr =>
        partyManagementService.createRelationship(pr._1.id, organization.id, pr._2, pr._3, pr._4)
      )
      relationships = RelationshipsSeed(personsWithRoles.map { case (person, role, product, productRole) =>
        createRelationship(organization.id, person.id, role, product, productRole)
      })
      pdf   <- pdfCreator.create(validUsers, organization)
      token <- partyManagementService.createToken(relationships, pdf._2)
      _ <- mailer.send(
        ApplicationConfiguration.destinationMails,
        pdf._1,
        token.token
      ) //TODO address must be the digital address
      _ = logger.info(s"$token")
    } yield OnBoardingResponse(token.token, pdf._1)

    onComplete(result) {
      case Success(response) =>
        onboardingOrganization201(response)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        onboardingOrganization400(errorResponse)

    }

  }

  /** Code: 201, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def createOperators(
    onBoardingRequest: OnBoardingRequest
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    val result: Future[Unit] = for {
      organization  <- partyManagementService.retrieveOrganizationByExternalId(onBoardingRequest.institutionId)
      relationships <- partyManagementService.retrieveRelationships(None, Some(organization.id), None)
      _             <- existsAManagerActive(relationships)

      validUsers <- verifyUsersByRoles(onBoardingRequest.users, Set(Operator.toString))
      operators  <- Future.traverse(validUsers)(addUser)
      _ <- Future.traverse(operators)(pr =>
        partyManagementService.createRelationship(pr._1.id, organization.id, pr._2, pr._3, pr._4)
      )
      _ = logger.info(s"Operators created ${operators.map(_.toString).mkString(",")}")
    } yield ()

    onComplete(result) {
      case Success(_) => createOperators201
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        createOperators400(errorResponse)
    }
  }

  /** Code: 200, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def confirmOnBoarding(token: String, contract: (FileInfo, File))(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    val result: Future[Unit] = for {
      checksum <- calculateCheckSum(token)
      verified <- verifyChecksum(contract._2, checksum, token)
      _        <- partyManagementService.consumeToken(verified, contract)
    } yield ()

    onComplete(result) {
      case Success(_) => confirmOnBoarding200
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        confirmOnBoarding400(errorResponse)
    }
  }

  /** Code: 200, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def invalidateOnboarding(
    token: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    val result: Future[Unit] = partyManagementService.invalidateToken(token)

    onComplete(result) {
      case Success(_) => invalidateOnboarding200
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        invalidateOnboarding400(errorResponse)

    }
  }

  private def verifyUsersByRoles(users: Seq[User], roles: Set[String]): Future[Seq[User]] = {
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

  private def existsAManagerActive(relationships: Relationships): Future[Unit] = Future.fromTry {
    Either
      .cond(
        relationships.items.exists(rl =>
          rl.role == RelationshipEnums.Role.Manager &&
            rl.status == RelationshipEnums.Status.Active
        ),
        (),
        new RuntimeException("No active legals for this institution ")
      )
      .toTry
  }

  private def addUser(user: User): Future[(UserRegistryUser, String, Set[String], String)] = {
    logger.info(s"Adding user ${user.toString}")
    createPerson(user)
      .recoverWith {
        // TODO Once errors are defined, we should check that error is "person already exists"
        case _ => userRegistryManagementService.getUserByExternalId(user.taxCode)
      }
      .map((_, user.role, user.products, user.productRole))
  }

  private def createRelationship(
    organizationId: UUID,
    personId: UUID,
    role: String,
    products: Set[String],
    productRole: String
  ): RelationshipSeed =
    RelationshipSeed(personId, organizationId, RelationshipSeedEnums.Role.withName(role), products, productRole)

  private def createPerson(user: User): Future[UserRegistryUser] =
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
          // Use can already exists on user registry
          // TODO Once errors are defined, we should check that error is "person already exists"
          case _ => userRegistryManagementService.getUserByExternalId(user.taxCode)
        }
      _ <- partyManagementService.createPerson(PersonSeed(user.id))
    } yield user

  private def getOrganization(institutionId: UUID): Future[Organization] =
    partyManagementService.retrieveOrganization(institutionId)

  private def createOrganization(institutionId: String): Future[Organization] =
    for {
      institution <- partyRegistryService.getInstitution(institutionId)
      categories  <- partyRegistryService.getCategories
      category <- categories.items
        .find(cat => institution.category.contains(cat.code))
        .map(Future.successful)
        .getOrElse(
          Future.failed(new RuntimeException(s"Invalid category ${institution.category.getOrElse("UNKNOWN")}"))
        )
      attributes <- attributeRegistryService.createAttribute("IPA", category.name, category.code)
      _ = logger.info(s"getInstitution ${institution.id}")
      seed = OrganizationSeed(
        institutionId = institution.id,
        description = institution.description,
        digitalAddress = institution.digitalAddress.getOrElse(""), // TODO Must be non optional
        fiscalCode = institution.taxCode.getOrElse(""),
        attributes = attributes.attributes.filter(attr => institution.category == attr.code).map(_.id),
        products = Set.empty
      )
      organization <- partyManagementService.createOrganization(seed)
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
        .cond(Digester.createHash(fileToCheck) == checksum, output, new RuntimeException("Invalid checksum"))
        .toTry
    )
  }

  /*
   currentUserRole is calculated over the relationship of the JWT subject with the required institution id.
   */
  def filterFoundRelationshipsByCurrentUser(
    currentUserId: UUID,
    currentUserRole: String
  )(foundRelationships: Relationships, productRolesFilter: Option[String]): Future[Relationships] = Try {
    val filterRoles = productRolesFilter.getOrElse("").split(",").map(_.trim).toList.filterNot(entry => entry == "")
    val isUserAdmin = productRolesConfiguration.manager.roles.contains(currentUserRole)

    val filteredRelationships = if (!isUserAdmin) {
      foundRelationships.copy(items = foundRelationships.items.filter(r => r.from == currentUserId))
    } else {
      foundRelationships
    }

    filterRoles match {
      case Nil => filteredRelationships
      case _ =>
        filteredRelationships.copy(items = filteredRelationships.items.filter(r => filterRoles.contains(r.productRole)))
    }
  }.toFuture

  /** Code: 200, Message: successful operation, DataType: RelationshipInfo
    * Code: 400, Message: Invalid institution id supplied, DataType: Problem
    */
  override def getUserInstitutionRelationships(institutionId: String, productRoles: Option[String])(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]],
    contexts: Seq[(String, String)]
  ): Route = {
    logger.info(s"Getting relationship for institution $institutionId and current user")
    val result: Future[Seq[RelationshipInfo]] = for {
      subjectUUID     <- getCallerSubjectIdentifier(contexts)
      institutionUUID <- Try { UUID.fromString(institutionId) }.toFuture
      relationships   <- partyManagementService.retrieveRelationships(Some(subjectUUID), Some(institutionUUID), None)
      currentUserRoleInInstitution <- relationships.items.headOption
        .map(_.productRole)
        .toFuture(RelationshipNotFound(institutionUUID, subjectUUID, ""))
      institutionIdRelationships <- partyManagementService.retrieveRelationships(None, Some(institutionUUID), None)
      filteredRelationships <- filterFoundRelationshipsByCurrentUser(subjectUUID, currentUserRoleInInstitution)(
        institutionIdRelationships,
        productRoles
      )
      response = relationshipsToRelationshipsResponse(filteredRelationships)
    } yield response

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
      relationshipUUID <- Try { UUID.fromString(relationshipId) }.toFuture
      relationship     <- partyManagementService.getRelationshipById(relationshipUUID)
      _                <- relationshipMustBeActivable(relationship)
      _                <- partyManagementService.activateRelationship(relationship.id)
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
      relationshipUUID <- Try { UUID.fromString(relationshipId) }.toFuture
      relationship     <- partyManagementService.getRelationshipById(relationshipUUID)
      _                <- relationshipMustBeSuspendable(relationship)
      _                <- partyManagementService.suspendRelationship(relationship.id)
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

  def getOnboardingDocument(relationshipId: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerFile: ToEntityMarshaller[File],
    contexts: Seq[(String, String)]
  ): Route = {
    val result: Future[DocumentDetails] =
      for {
        _              <- tokenFromContext(contexts)
        uuid           <- Try { UUID.fromString(relationshipId) }.toFuture
        relationship   <- partyManagementService.getRelationshipById(uuid)
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
    relationship.status match {
      case RelationshipEnums.Status.Suspended => Future.successful(())
      case status                             => Future.failed(RelationshipNotActivable(relationship.id.toString, status.toString))
    }

  private def relationshipMustBeSuspendable(relationship: Relationship): Future[Unit] =
    relationship.status match {
      case RelationshipEnums.Status.Active => Future.successful(())
      case status                          => Future.failed(RelationshipNotSuspendable(relationship.id.toString, status.toString))
    }

  private def relationshipsToRelationshipsResponse(relationships: Relationships): Seq[RelationshipInfo] = {
    relationships.items.map(relationshipToRelationshipInfo)
  }

  private def relationshipToRelationshipInfo(relationship: Relationship): RelationshipInfo = {
    RelationshipInfo(
      id = relationship.id,
      from = relationship.from,
      role = relationship.role.toString,
      products = relationship.products,
      productRole = relationship.productRole,
      // TODO This conversion is temporary, while we implement a naming convention for enums
      status = relationship.status.toString.toLowerCase
    )
  }

  private def getCallerSubjectIdentifier(contexts: Seq[(String, String)]): Future[UUID] = {
    val subject = for {
      bearer   <- tokenFromContext(contexts)
      validJWT <- authorizationProcessService.validateToken(bearer)
      subjectUUID <- Try {
        UUID.fromString(validJWT.sub)
      }.toFuture
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
      relationshipUUID <- Try { UUID.fromString(relationshipId) }.toFuture
      relationship     <- partyManagementService.getRelationshipById(relationshipUUID)
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
      _                <- getCallerSubjectIdentifier(contexts)
      relationshipUUID <- Try { UUID.fromString(relationshipId) }.toFuture
      _                <- partyManagementService.deleteRelationshipById(relationshipUUID)
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

  /** Code: 200, Message: successful operation, DataType: Institution
    * Code: 404, Message: Organization not found, DataType: Problem
    */
  override def replaceInstitutionProducts(institutionId: String, products: Products)(implicit
    toEntityMarshallerInstitution: ToEntityMarshaller[Institution],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    val result = for {
      _               <- getCallerSubjectIdentifier(contexts)
      institutionUUID <- Try { UUID.fromString(institutionId) }.toFuture
      organization    <- partyManagementService.replaceOrganizationProducts(institutionUUID, products.products)
    } yield Institution(
      id = organization.id,
      institutionId = organization.institutionId,
      code = organization.code,
      description = organization.description,
      digitalAddress = organization.digitalAddress,
      fiscalCode = organization.fiscalCode,
      attributes = organization.attributes,
      products = organization.products
    )

    onComplete(result) {
      case Success(institution) => replaceInstitutionProducts200(institution)
      case Failure(ex: SubjectValidationError) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 401, "Unauthorized")
        complete((401, errorResponse))
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        replaceInstitutionProducts404(errorResponse)
    }
  }

  override def replaceRelationshipProducts(relationshipId: String, products: Products)(implicit
    toEntityMarshallerInstitution: ToEntityMarshaller[RelationshipInfo],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    val result = for {
      _                <- getCallerSubjectIdentifier(contexts)
      relationshipUUID <- Try { UUID.fromString(relationshipId) }.toFuture
      relationship     <- partyManagementService.replaceRelationshipProducts(relationshipUUID, products.products)
    } yield relationshipToRelationshipInfo(relationship)

    onComplete(result) {
      case Success(relationship) => replaceRelationshipProducts200(relationship)
      case Failure(ex: SubjectValidationError) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 401, "Unauthorized")
        complete((401, errorResponse))
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        replaceRelationshipProducts404(errorResponse)
    }
  }

  /** Code: 201, Message: successful operation, DataType: OnBoardingResponse
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def onboardingLegalsOnOrganization(onBoardingRequest: OnBoardingRequest)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerOnBoardingResponse: ToEntityMarshaller[OnBoardingResponse],
    contexts: Seq[(String, String)]
  ): Route = {
    val result: Future[OnBoardingResponse] = for {
      organization <- partyManagementService.retrieveOrganizationByExternalId(onBoardingRequest.institutionId)
      organizationRelationships <- partyManagementService.retrieveRelationships(
        None,
        Some(organization.id),
        productRolesConfiguration.manager.roles.headOption
      )
      _                <- hasAnExistingManager(organizationRelationships)
      validUsers       <- verifyUsersByRoles(onBoardingRequest.users, Set(Manager.toString, Delegate.toString))
      personsWithRoles <- Future.traverse(validUsers)(addUser)
      _ <- Future.traverse(personsWithRoles)(pr =>
        partyManagementService.createRelationship(pr._1.id, organization.id, pr._2, pr._3, pr._4)
      )
      relationships = RelationshipsSeed(personsWithRoles.map { case (person, role, products, productRole) =>
        createRelationship(organization.id, person.id, role, products, productRole)
      })
      pdf   <- pdfCreator.create(validUsers, organization)
      token <- partyManagementService.createToken(relationships, pdf._2)
      _ <- mailer.send(
        ApplicationConfiguration.destinationMails,
        pdf._1,
        token.token
      ) //TODO address must be the digital address
      _ = logger.info(s"$token")
    } yield OnBoardingResponse(token.token, pdf._1)

    onComplete(result) {
      case Success(response) =>
        onboardingLegalsOnOrganization200(response)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        onboardingLegalsOnOrganization400(errorResponse)
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
      _                         <- getCallerSubjectIdentifier(contexts)
      institutionUUID           <- Try { UUID.fromString(institutionId) }.toFuture
      organization              <- partyManagementService.retrieveOrganization(institutionUUID)
      organizationRelationships <- partyManagementService.retrieveRelationships(None, Some(organization.id), None)
      _                         <- existsAManagerActive(organizationRelationships)
    } yield Products(products = organization.products)

    onComplete(result) {
      case Success(institution) => retrieveInstitutionProducts200(institution)
      case Failure(ex: SubjectValidationError) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 401, "Unauthorized")
        complete((401, errorResponse))
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        retrieveInstitutionProducts404(errorResponse)
    }
  }

  //TODO add rejected also
  def hasAnExistingManager(organizationRelationships: Relationships): Future[Boolean] = {
    val noManagers =
      organizationRelationships.items
        .filter(r => r.role == Manager && !Set(Pending.toString).contains(r.status.toString))
        .isEmpty

    if (noManagers)
      Future.failed[Boolean](new RuntimeException("This organization does not have a Manager"))
    else
      Future.successful(true)
  }
}
