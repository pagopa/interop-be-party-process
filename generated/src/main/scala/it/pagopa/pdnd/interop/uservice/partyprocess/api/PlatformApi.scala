package it.pagopa.pdnd.interop.uservice.partyprocess.api

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directive1, Route}
import akka.http.scaladsl.marshalling.ToEntityMarshaller
    import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
    import akka.http.scaladsl.unmarshalling.FromStringUnmarshaller
import it.pagopa.pdnd.interop.uservice.partyprocess.server.AkkaHttpHelper._
import it.pagopa.pdnd.interop.uservice.partyprocess.model.Problem
import it.pagopa.pdnd.interop.uservice.partyprocess.model.ProductRolesResponse


    class PlatformApi(
    platformService: PlatformApiService,
    platformMarshaller: PlatformApiMarshaller,
    wrappingDirective: Directive1[Seq[(String, String)]]
    ) {
    
    import platformMarshaller._

    lazy val route: Route =
        path("platform" / "roles") { 
        get { wrappingDirective { implicit contexts =>  
            platformService.getProductRoles()
        }
        }
        }
    }


    trait PlatformApiService {
          def getProductRoles200(responseProductRolesResponse: ProductRolesResponse)(implicit toEntityMarshallerProductRolesResponse: ToEntityMarshaller[ProductRolesResponse]): Route =
            complete((200, responseProductRolesResponse))
  def getProductRoles400(responseProblem: Problem)(implicit toEntityMarshallerProblem: ToEntityMarshaller[Problem]): Route =
            complete((400, responseProblem))
        /**
           * Code: 200, Message: Available platform roles&#39; bindings., DataType: ProductRolesResponse
   * Code: 400, Message: Bad Request, DataType: Problem
        */
        def getProductRoles()
            (implicit toEntityMarshallerProductRolesResponse: ToEntityMarshaller[ProductRolesResponse], toEntityMarshallerProblem: ToEntityMarshaller[Problem], contexts: Seq[(String, String)]): Route

    }

        trait PlatformApiMarshaller {
        
        
          implicit def toEntityMarshallerProductRolesResponse: ToEntityMarshaller[ProductRolesResponse]

  implicit def toEntityMarshallerProblem: ToEntityMarshaller[Problem]

        }

