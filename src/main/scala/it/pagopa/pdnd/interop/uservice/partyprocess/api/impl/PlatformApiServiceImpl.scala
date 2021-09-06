package it.pagopa.pdnd.interop.uservice.partyprocess.api.impl

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.server.Route
import it.pagopa.pdnd.interop.uservice.partyprocess.api.PlatformApiService
import it.pagopa.pdnd.interop.uservice.partyprocess.common.system.ApplicationConfiguration.platformRolesConfiguration.{
  manager,
  delegate,
  operator
}
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{PlatformRolesResponse, Problem}

@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
class PlatformApiServiceImpl extends PlatformApiService {

  /** Code: 200, Message: successful operation, DataType: Problem
    */
  override def getPlatformRoles()(implicit
    toEntityMarshallerPlatformRolesResponse: ToEntityMarshaller[PlatformRolesResponse],
    toEntityMarshallerProblem: ToEntityMarshaller[Problem],
    contexts: Seq[(String, String)]
  ): Route = {

    val response = PlatformRolesResponse(
      managerRoles = manager.roles,
      delegateRoles = delegate.roles,
      operatorRoles = operator.roles
    )

    getPlatformRoles200(response)
  }

}
