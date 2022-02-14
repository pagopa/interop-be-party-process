package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.onComplete
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import com.typesafe.scalalogging.Logger
import it.pagopa.pdnd.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.pdnd.interop.commons.utils.TypeConversions._
import it.pagopa.pdnd.interop.commons.utils.errors.GenericComponentErrors.ResourceConflictError
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.{Problem => _}
import it.pagopa.pdnd.interop.uservice.partyprocess.api.PublicApiService
import it.pagopa.pdnd.interop.uservice.partyprocess.error.PartyProcessErrors._
import it.pagopa.pdnd.interop.uservice.partyprocess.model._
import it.pagopa.pdnd.interop.uservice.partyprocess.service._
import org.slf4j.LoggerFactory

import java.io.File
import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class PublicApiServiceImpl(
  partyManagementService: PartyManagementService,
  userRegistryManagementService: UserRegistryManagementService,
  signatureService: SignatureService,
  signatureValidationService: SignatureValidationService
)(implicit ec: ExecutionContext)
    extends PublicApiService {

  private val logger = Logger.takingImplicit[ContextFieldsToLog](LoggerFactory.getLogger(this.getClass))

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
  ): Route = {
    logger.info("Confirm onboarding of token identified with {}", tokenId)
    val result: Future[Unit] = for {
      tokenIdUUID <- tokenId.toFutureUUID
      token       <- partyManagementService.verifyToken(tokenIdUUID)
      legalUsers  <- Future.traverse(token.legals)(legal => userRegistryManagementService.getUserById(legal.partyId))
      validator   <- signatureService.createDocumentValidator(Files.readAllBytes(contract._2.toPath))
      _ <- SignatureValidationService.validateSignature(
        signatureValidationService.isDocumentSigned(validator),
        signatureValidationService.verifySignature(validator),
        signatureValidationService.verifySignatureForm(validator),
        signatureValidationService.verifyDigest(validator, token.checksum),
        signatureValidationService.verifyManagerTaxCode(validator, legalUsers)
      )
      _ <- partyManagementService.consumeToken(token.id, contract)
    } yield ()

    onComplete(result) {
      case Success(_) => confirmOnboarding200
      case Failure(InvalidSignature(signatureValidationErrors)) =>
        logger.error(
          "Error while confirming onboarding of token identified with {} - {}",
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
      case Failure(ex: ResourceConflictError) =>
        logger.error("Error while confirming onboarding of token identified with {} - {}", tokenId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, ex)
        confirmOnboarding409(errorResponse)
      case Failure(ex) =>
        logger.error("Error while confirming onboarding of token identified with {} - {}", tokenId, ex)
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
      case Success(_) => invalidateOnboarding200
      case Failure(ex: ResourceConflictError) =>
        logger.error("Error while confirming onboarding of token identified with {} - {}", tokenId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.Conflict, ex)
        confirmOnboarding409(errorResponse)
      case Failure(ex) =>
        logger.error("Error while invalidating onboarding for token identified with {}", tokenId, ex)
        val errorResponse: Problem = problemOf(StatusCodes.BadRequest, InvalidateOnboardingError)
        invalidateOnboarding400(errorResponse)

    }

  }

}
