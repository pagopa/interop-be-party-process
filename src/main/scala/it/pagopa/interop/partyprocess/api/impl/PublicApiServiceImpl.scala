package it.pagopa.interop.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{onComplete, withRequestTimeout}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import com.typesafe.scalalogging.Logger
import it.pagopa.interop.commons.files.service.FileManager
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.interop.commons.mail.model.PersistedTemplate
import it.pagopa.interop.commons.utils.TypeConversions._
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.{GenericClientError, ResourceConflictError}
import it.pagopa.interop.partymanagement.client.model.{PartyRole => PartyMgmtRole, Problem => _}
import it.pagopa.interop.partyprocess.api.PublicApiService
import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration
import it.pagopa.interop.partyprocess.error.PartyProcessErrors._
import it.pagopa.interop.partyprocess.model._
import it.pagopa.interop.partyprocess.service._

import java.io.{ByteArrayOutputStream, File, FileOutputStream}
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class PublicApiServiceImpl(
  partyManagementService: PartyManagementService,
  userRegistryManagementService: UserRegistryManagementService,
  productManagementService: ProductManagementService,
  signatureService: SignatureService,
  signatureValidationService: SignatureValidationService,
  mailer: MailEngine,
  mailTemplate: PersistedTemplate,
  fileManager: FileManager
)(implicit ec: ExecutionContext)
    extends PublicApiService {

  private val logger = Logger.takingImplicit[ContextFieldsToLog](this.getClass)

  /** Code: 200, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    * Code: 409, Message: Document validation failed
    *
    * These are the error code used in the document validation process:
    *
    *  * 002-100: document validation fails
    *  * 002-101: original document digest differs from incoming document one
    *  * 002-102: the signature is invalid
    *  * 002-103: signature form is not CAdES
    *  * 002-104: signature tax code is not equal to document one
    *  * 002-105: signature tax code has an invalid format
    *  * 002-106: signature tax code is not present
    *
    *  DataType: Problem
    */
  override def confirmOnboarding(tokenId: String, contract: (FileInfo, File))(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = withRequestTimeout(ApplicationConfiguration.confirmTokenTimeout) {
    logger.info("Confirm onboarding of token identified with {}", tokenId)

    val result: Future[Unit] = for {
      tokenIdUUID <- tokenId.toFutureUUID
      token       <- partyManagementService.verifyToken(tokenIdUUID)
      legalsRelationships = token.legals.filter(_.role == PartyMgmtRole.MANAGER)
      legalUsers    <- Future.traverse(legalsRelationships)(legal =>
        userRegistryManagementService.getUserWithEmailById(legal.partyId)
      )
      institutionId <- Future.traverse(legalsRelationships)(legalUser =>
        partyManagementService.getInstitutionId(legalUser.relationshipId)
      )
      institutionEmail =
        if (ApplicationConfiguration.sendEmailToInstitution) institutionId.headOption.map(_.digitalAddress)
        else Some(ApplicationConfiguration.institutionAlternativeEmail)

      institutionInternalId = institutionId.headOption.map(_.to.toString)
      legalUserWithEmails   = legalUsers.filter(_.email.get(institutionInternalId.getOrElse("")).nonEmpty)
      legalEmails           = legalUserWithEmails.map(u => u.email.get(institutionInternalId.getOrElse("")))

      validator <- signatureService.createDocumentValidator(Files.readAllBytes(contract._2.toPath))
      _         <- SignatureValidationService.validateSignature(signatureValidationService.isDocumentSigned(validator))
      _ <- SignatureValidationService.validateSignature(signatureValidationService.verifyOriginalDocument(validator))
      reports                  <- signatureValidationService.validateDocument(validator)
      _                        <- SignatureValidationService.validateSignature(
        signatureValidationService.verifySignatureForm(validator),
        signatureValidationService.verifySignature(reports),
        signatureValidationService.verifyDigest(validator, token.checksum),
        signatureValidationService.verifyManagerTaxCode(reports, legalUsers)
      )
      logo                     <- getLogoFile(ApplicationConfiguration.emailLogoPath)
      product                  <- productManagementService.getProductById(institutionId.head.product)
      onboardingMailParameters <- getOnboardingMailParameters(product.name)
      emails = legalEmails ++ institutionEmail.toSeq
      _ <- sendOnboardingCompleteEmail(emails, onboardingMailParameters, logo)
      _ <- partyManagementService.consumeToken(token.id, contract)
    } yield ()

    onComplete(result) {
      case Success(_)                                           =>
        confirmOnboarding204
      case Failure(InvalidSignature(signatureValidationErrors)) =>
        logger.error(
          "Error while confirming onboarding of token identified with {} - {}, reason: {}",
          tokenId,
          signatureValidationErrors.mkString(", ")
        )
        val errorResponse: Problem = Problem(
          `type` = defaultProblemType,
          status = StatusCodes.Conflict.intValue,
          title = StatusCodes.Conflict.defaultMessage,
          errors = signatureValidationErrors.map(signatureValidationError =>
            ProblemError(
              code = s"$serviceErrorCodePrefix-${signatureValidationError.code}",
              detail = signatureValidationError.msg
            )
          )
        )
        confirmOnboarding409(errorResponse)
      case Failure(ex: ResourceConflictError)                   =>
        logger.error("Error while confirming onboarding of token identified with {}", tokenId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, ex)
        confirmOnboarding409(errorResponse)
      case Failure(ex)                                          =>
        logger.error("Error while confirming onboarding of token identified with {}", tokenId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, ConfirmOnboardingError)
        confirmOnboarding400(errorResponse)
    }
  }

  /** Code: 200, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  override def invalidateOnboarding(
    tokenId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route = {
    logger.info("Invalidating onboarding for token identified with {}", tokenId)
    val result: Future[Unit] = for {
      tokenIdUUID <- tokenId.toFutureUUID
      token       <- partyManagementService.verifyToken(tokenIdUUID)
      result      <- partyManagementService.invalidateToken(token.id)
    } yield result

    onComplete(result) {
      case Success(_)                         => invalidateOnboarding204
      case Failure(ex: ResourceConflictError) =>
        logger.error("Error while confirming onboarding of token identified with {}", tokenId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, ex)
        confirmOnboarding409(errorResponse)
      case Failure(ex)                        =>
        logger.error("Error while invalidating onboarding for token identified with {}", tokenId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, InvalidateOnboardingError)
        invalidateOnboarding400(errorResponse)
    }
  }

  /**
    * Code: 200, Message: successful operation, DataType: TokenId
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    * Code: 404, Message: Token not found, DataType: Problem
    * Code: 409, Message: Token already consumed, DataType: Problem
    */
  override def verifyToken(tokenId: String)(implicit
    toEntityMarshallerTokenId: ToEntityMarshaller[TokenId],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {
    val result: Future[TokenId] = for {
      tokenIdUUID <- tokenId.toFutureUUID
      token       <- partyManagementService.verifyToken(tokenIdUUID)
    } yield TokenId(id = token.id)

    onComplete(result) {
      case Success(tokenId)                => verifyToken200(tokenId)
      case Failure(ex: GenericClientError) =>
        logger.error(s"Token not found", ex)
        verifyToken404(problemOf(StatusCodes.NotFound, ex))

      /*case Failure(ex: TokenAlreadyConsumed)    =>
        logger.error(s"Token already consumed", ex)
        verifyToken409(problemOf(StatusCodes.Conflict, ex))
      case Failure(ex: GetRelationshipNotFound) =>
        logger.error(s"Missing token relationships", ex)
        verifyToken400(problemOf(StatusCodes.BadRequest, ex))
        */
      case Failure(ex)                          =>
        logger.error(s"Verifying token failed", ex)
        //complete(problemOf(StatusCodes.InternalServerError, TokenVerificationFatalError(tokenId, ex.getMessage)))
        verifyToken400(problemOf(StatusCodes.InternalServerError, TokenVerificationFatalError(tokenId, ex.getMessage)))
    }
  }

  private def sendOnboardingCompleteEmail(
    legalEmails: Seq[String],
    onboardingMailParameters: Map[String, String],
    logo: File
  )(implicit contexts: Seq[(String, String)]): Future[Unit] = {
    mailer.sendMail(mailTemplate)(legalEmails, "pagopa-logo.png", logo, onboardingMailParameters)(
      "onboarding-complete-email"
    )
  }

  private def getOnboardingMailParameters(productName: String): Future[Map[String, String]] = {

    val productParameters: Map[String, String] = Map(
      ApplicationConfiguration.onboardingCompleteProductName -> productName
    )

    val selfcareParameters: Map[String, String] = Map(
      ApplicationConfiguration.onboardingCompleteSelfcareUrlName ->
        ApplicationConfiguration.onboardingCompleteSelfcareUrlPlaceholder
    )

    Future.successful(productParameters ++ selfcareParameters)
  }

  private def getLogoFile(filePath: String): Future[File] = for {
    logoStream <- fileManager.get(ApplicationConfiguration.storageContainer)(filePath)
    file       <- Try { createTempFile }.toFuture
    _          <- Try { getLogoAsFile(file.get, logoStream) }.toFuture
  } yield file.get

  private def createTempFile: Try[File] = {
    Try {
      val fileTimestamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
      File.createTempFile(s"${fileTimestamp}_${UUID.randomUUID().toString}_logo.", ".png")
    }
  }

  private def getLogoAsFile(destination: File, logo: ByteArrayOutputStream): Try[File] = {
    Try {
      val fos = new FileOutputStream(destination)
      logo.writeTo(fos)
      fos.close()
      destination
    }
  }
}
