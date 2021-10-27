package it.pagopa.pdnd.interop.uservice.partyprocess.api

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directive1, Route}
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.unmarshalling.FromStringUnmarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.server.AkkaHttpHelper._
import it.pagopa.pdnd.interop.uservice.partyprocess.server.StringDirectives
import it.pagopa.pdnd.interop.uservice.partyprocess.server.MultipartDirectives
import it.pagopa.pdnd.interop.uservice.partyprocess.server.FileField
import it.pagopa.pdnd.interop.uservice.partyprocess.server.PartsAndFiles
import java.io.File
import it.pagopa.pdnd.interop.uservice.partyprocess.model.OnBoardingInfo
import it.pagopa.pdnd.interop.uservice.partyprocess.model.OnBoardingRequest
import it.pagopa.pdnd.interop.uservice.partyprocess.model.OnBoardingResponse
import it.pagopa.pdnd.interop.uservice.partyprocess.model.Problem
import it.pagopa.pdnd.interop.uservice.partyprocess.model.RelationshipInfo
import java.util.UUID
import scala.util.Try
import akka.http.scaladsl.server.MalformedRequestContentRejection
import akka.http.scaladsl.server.directives.FileInfo

class ProcessApi(
  processService: ProcessApiService,
  processMarshaller: ProcessApiMarshaller,
  wrappingDirective: Directive1[Seq[(String, String)]]
) extends MultipartDirectives
    with StringDirectives {

  import processMarshaller._

  lazy val route: Route =
    path("relationships" / Segment / "activate") { (relationshipId) =>
      post {
        wrappingDirective { implicit contexts =>
          processService.activateRelationship(relationshipId = relationshipId)
        }
      }
    } ~
      path("onboarding" / "complete" / Segment) { (token) =>
        post {
          wrappingDirective { implicit contexts =>
            formAndFiles(FileField("contract")) { partsAndFiles =>
              val routes: Try[Route] = for {
                contract <- optToTry(partsAndFiles.files.get("contract"), s"File contract missing")
              } yield {
                implicit val vp: StringValueProvider = partsAndFiles.form ++ contexts.toMap
                processService.confirmOnBoarding(token = token, contract = contract)
              }

              routes.fold[Route](t => reject(MalformedRequestContentRejection("Missing file.", t)), identity)
            }
          }
        }
      } ~
      path("onboarding" / "legals") {
        post {
          wrappingDirective { implicit contexts =>
            entity(as[OnBoardingRequest]) { onBoardingRequest =>
              processService.createLegals(onBoardingRequest = onBoardingRequest)
            }
          }
        }
      } ~
      path("onboarding" / "operators") {
        post {
          wrappingDirective { implicit contexts =>
            entity(as[OnBoardingRequest]) { onBoardingRequest =>
              processService.createOperators(onBoardingRequest = onBoardingRequest)
            }
          }
        }
      } ~
      path("onboarding" / "info") {
        get {
          wrappingDirective { implicit contexts =>
            processService.getOnBoardingInfo()
          }
        }
      } ~
      path("onboarding" / "relationship" / Segment / "document") { (relationshipId) =>
        get {
          wrappingDirective { implicit contexts =>
            processService.getOnboardingDocument(relationshipId = relationshipId)
          }
        }
      } ~
      path("relationships" / Segment) { (relationshipId) =>
        get {
          wrappingDirective { implicit contexts =>
            processService.getRelationship(relationshipId = relationshipId)
          }
        }
      } ~
      path("institutions" / Segment / "relationships") { (institutionId) =>
        get {
          wrappingDirective { implicit contexts =>
            parameters("platformRoles".as[String].?) { (platformRoles) =>
              processService.getUserInstitutionRelationships(
                institutionId = institutionId,
                platformRoles = platformRoles
              )
            }
          }
        }
      } ~
      path("onboarding" / "complete" / Segment) { (token) =>
        delete {
          wrappingDirective { implicit contexts =>
            processService.invalidateOnboarding(token = token)
          }
        }
      } ~
      path("relationships" / Segment / "suspend") { (relationshipId) =>
        post {
          wrappingDirective { implicit contexts =>
            processService.suspendRelationship(relationshipId = relationshipId)
          }
        }
      }
}

trait ProcessApiService {
  def activateRelationship204: Route =
    complete((204, "Successful operation"))
  def activateRelationship400(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((400, responseProblem))
  def activateRelationship404(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((404, responseProblem))

  /** Code: 204, Message: Successful operation
    * Code: 400, Message: Invalid id supplied, DataType: Problem
    * Code: 404, Message: Not found, DataType: Problem
    */
  def activateRelationship(
    relationshipId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route

  def confirmOnBoarding200: Route =
    complete((200, "successful operation"))
  def confirmOnBoarding400(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((400, responseProblem))

  /** Code: 200, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  def confirmOnBoarding(token: String, contract: (FileInfo, File))(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route

  def createLegals201(responseOnBoardingResponse: OnBoardingResponse)(implicit
    toEntityMarshallerOnBoardingResponse: ToEntityMarshaller[OnBoardingResponse]
  ): Route =
    complete((201, responseOnBoardingResponse))
  def createLegals400(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((400, responseProblem))

  /** Code: 201, Message: successful operation, DataType: OnBoardingResponse
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  def createLegals(onBoardingRequest: OnBoardingRequest)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerOnBoardingResponse: ToEntityMarshaller[OnBoardingResponse],
    contexts: Seq[(String, String)]
  ): Route

  def createOperators201: Route =
    complete((201, "successful operation"))
  def createOperators400(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((400, responseProblem))

  /** Code: 201, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  def createOperators(
    onBoardingRequest: OnBoardingRequest
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route

  def getOnBoardingInfo200(responseOnBoardingInfo: OnBoardingInfo)(implicit
    toEntityMarshallerOnBoardingInfo: ToEntityMarshaller[OnBoardingInfo]
  ): Route =
    complete((200, responseOnBoardingInfo))
  def getOnBoardingInfo400(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((400, responseProblem))

  /** Code: 200, Message: successful operation, DataType: OnBoardingInfo
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  def getOnBoardingInfo()(implicit
    toEntityMarshallerOnBoardingInfo: ToEntityMarshaller[OnBoardingInfo],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route

  def getOnboardingDocument200(responseFile: File)(implicit toEntityMarshallerFile: ToEntityMarshaller[File]): Route =
    complete((200, responseFile))
  def getOnboardingDocument404(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((404, responseProblem))
  def getOnboardingDocument400(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((400, responseProblem))

  /** Code: 200, Message: Signed onboarding document retrieved, DataType: File
    * Code: 404, Message: Document not found, DataType: Problem
    * Code: 400, Message: Bad request, DataType: Problem
    */
  def getOnboardingDocument(relationshipId: String)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerFile: ToEntityMarshaller[File],
    contexts: Seq[(String, String)]
  ): Route

  def getRelationship200(responseRelationshipInfo: RelationshipInfo)(implicit
    toEntityMarshallerRelationshipInfo: ToEntityMarshaller[RelationshipInfo]
  ): Route =
    complete((200, responseRelationshipInfo))
  def getRelationship400(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((400, responseProblem))
  def getRelationship404(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((404, responseProblem))

  /** Code: 200, Message: successful operation, DataType: RelationshipInfo
    * Code: 400, Message: Invalid id supplied, DataType: Problem
    * Code: 404, Message: Not found, DataType: Problem
    */
  def getRelationship(relationshipId: String)(implicit
    toEntityMarshallerRelationshipInfo: ToEntityMarshaller[RelationshipInfo],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route

  def getUserInstitutionRelationships200(responseRelationshipInfoarray: Seq[RelationshipInfo])(implicit
    toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]]
  ): Route =
    complete((200, responseRelationshipInfoarray))
  def getUserInstitutionRelationships400(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((400, responseProblem))

  /** Code: 200, Message: successful operation, DataType: Seq[RelationshipInfo]
    * Code: 400, Message: Invalid institution id supplied, DataType: Problem
    */
  def getUserInstitutionRelationships(institutionId: String, platformRoles: Option[String])(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]],
    contexts: Seq[(String, String)]
  ): Route

  def invalidateOnboarding200: Route =
    complete((200, "successful operation"))
  def invalidateOnboarding400(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((400, responseProblem))

  /** Code: 200, Message: successful operation
    * Code: 400, Message: Invalid ID supplied, DataType: Problem
    */
  def invalidateOnboarding(
    token: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route

  def suspendRelationship204: Route =
    complete((204, "Successful operation"))
  def suspendRelationship400(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((400, responseProblem))
  def suspendRelationship404(responseProblem: Problem)(implicit
    toEntityMarshallerProblem: ToEntityMarshaller[Problem]
  ): Route =
    complete((404, responseProblem))

  /** Code: 204, Message: Successful operation
    * Code: 400, Message: Invalid id supplied, DataType: Problem
    * Code: 404, Message: Not found, DataType: Problem
    */
  def suspendRelationship(
    relationshipId: String
  )(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route

}

trait ProcessApiMarshaller {
  implicit def fromEntityUnmarshallerOnBoardingRequest: FromEntityUnmarshaller[OnBoardingRequest]

  implicit def toEntityMarshallerOnBoardingInfo: ToEntityMarshaller[OnBoardingInfo]

  implicit def toEntityMarshallerRelationshipInfo: ToEntityMarshaller[RelationshipInfo]

  implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem]

  implicit def toEntityMarshallerRelationshipInfoarray: ToEntityMarshaller[Seq[RelationshipInfo]]

  implicit def toEntityMarshallerFile: ToEntityMarshaller[File]

  implicit def toEntityMarshallerOnBoardingResponse: ToEntityMarshaller[OnBoardingResponse]

}
