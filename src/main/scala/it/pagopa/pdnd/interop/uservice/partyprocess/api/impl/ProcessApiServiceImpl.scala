package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.{ContentType, HttpEntity, MessageEntity}
import akka.http.scaladsl.server.Directives.{complete, onComplete}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import it.pagopa.pdnd.interop.uservice.partymanagement.client.invoker.ApiError
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.RelationshipEnums.Role.{Delegate, Manager, Operator}
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.{
  Organization,
  OrganizationSeed,
  Relationship,
  RelationshipEnums,
  RelationshipSeed,
  RelationshipSeedEnums,
  Relationships,
  RelationshipsSeed,
  Problem => _
}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.ProcessApiService
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.ApplicationConfiguration.platformRolesConfiguration
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.utils.{OptionOps, TryOps}
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.{ApplicationConfiguration, Digester}
import it.pagopa.pdnd.interop.uservice.partyprocess.error._
import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.service._
import it.pagopa.pdnd.interop.uservice.userregistrymanagement.client.model.{User => UserRegistryUser}
import org.slf4j.{Logger, LoggerFactory}
import spray.json._

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.{Base64, UUID}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.StringPlusAny",
    "org.wartremover.warts.ImplicitParameter",
    "org.wartremover.warts.ToString",
    "org.wartremover.warts.Equals"
  )
)
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
  override def getOnBoardingInfo()(implicit
    toEntityMarshallerOnBoardingInfo: ToEntityMarshaller[OnBoardingInfo],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    val result: Future[OnBoardingInfo] = for {
      subjectUUID <- getCallerSubjectIdentifier(contexts)
      user        <- userRegistryManagementService.getUserById(subjectUUID)
      personInfo = PersonInfo(user.name, user.surname, user.externalId)
      relationships <- partyManagementService.retrieveRelationships(Some(subjectUUID), None, None)
      organizations <- Future.traverse(relationships.items)(r =>
        getOrganization(r.to).map(o => (o, r.status, r.role, r.platformRole))
      )
      institutionsInfo = organizations.map { case (o, status, role, platformRole) =>
        InstitutionInfo(
          o.institutionId,
          o.description,
          o.digitalAddress,
          status.toString,
          role.toString,
          platformRole,
          attributes = o.attributes
        )
      }
    } yield OnBoardingInfo(personInfo, institutionsInfo)

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
  override def createLegals(onBoardingRequest: OnBoardingRequest)(implicit
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
      personsWithRoles <- Future.traverse(validUsers)(user => addUser(user))
      _ <- Future.traverse(personsWithRoles)(pr =>
        partyManagementService.createRelationship(pr._1.id, organization.id, pr._2, pr._3)
      )
      relationships = RelationshipsSeed(personsWithRoles.map { case (person, role, platformRole) =>
        createRelationship(organization.id, person.id, role, platformRole)
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
        createLegals201(response)
      //        complete(StatusCodes.Created, HttpEntity.fromFile(ContentTypes.`application/octet-stream`, file))
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        createLegals400(errorResponse)

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
      operators  <- Future.traverse(validUsers)(user => addUser(user))
      _ <- Future.traverse(operators)(pr =>
        partyManagementService.createRelationship(pr._1.id, organization.id, pr._2, pr._3)
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

  //TODO add the recover with using the latest user registry version endpoints.
  private def addUser(user: User): Future[(UserRegistryUser, String, String)] = {
    logger.info(s"Adding user ${user.toString}")
    createPerson(user).map(p => (p, user.role, user.platformRole))
  }

  private def createRelationship(
    organizationId: UUID,
    personId: UUID,
    role: String,
    platformRole: String
  ): RelationshipSeed =
    RelationshipSeed(personId, organizationId, RelationshipSeedEnums.Role.withName(role), platformRole)

  private def createPerson(user: User): Future[UserRegistryUser] = {
    userRegistryManagementService.upsertUser(
      externalId = user.taxCode,
      name = user.name,
      surname = user.surname,
      email = user.email.getOrElse("")
    )
  }

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
        attributes = attributes.attributes.filter(attr => institution.category == attr.code).map(_.id)
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
  )(foundRelationships: Relationships, platformRolesFilter: Option[String]): Future[Relationships] = Try {
    val filterRoles = platformRolesFilter.getOrElse("").split(",").map(_.trim).toList.filterNot(entry => entry == "")
    val isUserAdmin = platformRolesConfiguration.manager.roles.contains(currentUserRole)

    val filteredRelationships = if (!isUserAdmin) {
      foundRelationships.copy(items = foundRelationships.items.filter(r => r.from == currentUserId))
    } else {
      foundRelationships
    }

    filterRoles match {
      case Nil => filteredRelationships
      case _ =>
        filteredRelationships.copy(items =
          filteredRelationships.items.filter(r => filterRoles.contains(r.platformRole))
        )
    }
  }.toFuture

  /** Code: 200, Message: successful operation, DataType: RelationshipInfo
    * Code: 400, Message: Invalid institution id supplied, DataType: Problem
    */
  override def getUserInstitutionRelationships(institutionId: String, platformRoles: Option[String])(implicit
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
        .map(_.platformRole)
        .toFuture(RelationshipNotFound(institutionUUID, subjectUUID, ""))
      institutionIdRelationships <- partyManagementService.retrieveRelationships(None, Some(institutionUUID), None)
      filteredRelationships <- filterFoundRelationshipsByCurrentUser(subjectUUID, currentUserRoleInInstitution)(
        institutionIdRelationships,
        platformRoles
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
      platformRole = relationship.platformRole,
      // TODO This conversion is temporary, while we implement a naming convention for enums
      status = relationship.status.toString.toLowerCase
    )
  }

  private def getCallerSubjectIdentifier(contexts: Seq[(String, String)]): Future[UUID] = {
    for {
      bearer   <- tokenFromContext(contexts)
      validJWT <- authorizationProcessService.validateToken(bearer)
      subjectUUID <- Try {
        UUID.fromString(validJWT.sub)
      }.toFuture
    } yield subjectUUID
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
  override def deleteInstitutionRelationshipById(institutionId: String, relationshipId: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    val result = for {
      institutionUUID  <- Try { UUID.fromString(institutionId) }.toFuture
      relationshipUUID <- Try { UUID.fromString(relationshipId) }.toFuture
      relationships    <- partyManagementService.retrieveRelationships(None, Some(institutionUUID), None)
      _ <- relationships.items
        .find(r => r.id == relationshipUUID)
        .toFuture(RelationshipNotFoundInInstitution(institutionId = institutionUUID, relationshipId = relationshipUUID))
      _ <- partyManagementService.deleteRelationshipById(relationshipUUID)

    } yield ()

    onComplete(result) {
      case Success(_) => deleteInstitutionRelationshipById204
      case Failure(ex: RelationshipNotFoundInInstitution) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 404, "Not found")
        deleteInstitutionRelationshipById404(errorResponse)
      case Failure(ex) =>
        val errorResponse: Problem = Problem(Option(ex.getMessage), 400, "some error")
        deleteInstitutionRelationshipById400(errorResponse)
    }
  }
}
