package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.server.Route
import it.pagopa.pdnd.interop.uservice.partyprocess.api.PlatformApiService
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.ApplicationConfiguration.productRolesConfiguration.{
  manager,
  delegate,
  operator
}
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{ProductRolesResponse, Problem}

class PlatformApiServiceImpl extends PlatformApiService {

  /** Code: 200, Message: successful operation, DataType: Problem
    */
  override def getProductRoles()(implicit
    toEntityMarshallerProductRolesResponse: ToEntityMarshaller[ProductRolesResponse],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    val response =
      ProductRolesResponse(managerRoles = manager.roles, delegateRoles = delegate.roles, operatorRoles = operator.roles)

    getProductRoles200(response)
  }

}
