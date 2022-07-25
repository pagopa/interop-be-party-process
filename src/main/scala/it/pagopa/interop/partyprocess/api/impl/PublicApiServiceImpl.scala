package it.pagopa.interop.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{onComplete, withRequestTimeout}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import com.typesafe.scalalogging.Logger
import it.pagopa.interop.commons.logging.{CanLogContextFields, ContextFieldsToLog}
import it.pagopa.interop.commons.utils.AkkaUtils.getFutureBearer
import it.pagopa.interop.commons.utils.TypeConversions._
import it.pagopa.interop.commons.utils.errors.GenericComponentErrors.ResourceConflictError
import it.pagopa.interop.partymanagement.client.model.{Problem => _}
import it.pagopa.interop.partymanagement.client.model.{PartyRole => PartyMgmtRole}
import it.pagopa.interop.partyprocess.api.PublicApiService
import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration
import it.pagopa.interop.partyprocess.error.PartyProcessErrors._
import it.pagopa.interop.partyprocess.model._
import it.pagopa.interop.partyprocess.service._

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
      bearer        <- getFutureBearer(contexts)
      tokenIdUUID   <- tokenId.toFutureUUID
      token         <- partyManagementService.verifyToken(tokenIdUUID)
      relationships <- Future.traverse(token.legals)(legal =>
        partyManagementService.getRelationshipById(legal.relationshipId)(bearer)
      )
      legalsRelationships = relationships.filter(_.role == PartyMgmtRole.MANAGER)
      legalUsers <- Future.traverse(legalsRelationships)(legal => userRegistryManagementService.getUserById(legal.from))
      validator  <- signatureService.createDocumentValidator(Files.readAllBytes(contract._2.toPath))
      _          <- SignatureValidationService.validateSignature(signatureValidationService.isDocumentSigned(validator))
      reports    <- signatureValidationService.validateDocument(validator)
      _          <- SignatureValidationService.validateSignature(
        signatureValidationService.verifySignature(reports),
        signatureValidationService.verifyDigest(validator, token.checksum),
        signatureValidationService.verifyManagerTaxCode(reports, legalUsers)
      )
      _          <- partyManagementService.consumeToken(token.id, contract)
    } yield ()

    onComplete(result) {
      case Success(_)                                           => confirmOnboarding204
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

}
