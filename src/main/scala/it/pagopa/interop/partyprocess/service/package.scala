package it.pagopa.interop.partyprocess

import akka.actor.ActorSystem
import it.pagopa._
import it.pagopa.interop._

package object service {

  final val replacementEntityId: String = "UNKNOWN"

  type PartyProxyInvoker             = partyregistryproxy.client.invoker.ApiInvoker
  type PartyManagementInvoker        = partymanagement.client.invoker.ApiInvoker
  type UserRegistryManagementInvoker = userreg.client.invoker.ApiInvoker

  object PartyProxyInvoker {
    def apply()(implicit actorSystem: ActorSystem): PartyProxyInvoker = partyregistryproxy.client.invoker.ApiInvoker()
  }

  object PartyManagementInvoker {
    def apply()(implicit actorSystem: ActorSystem): PartyManagementInvoker =
      partymanagement.client.invoker.ApiInvoker(partymanagement.client.api.EnumsSerializers.all)
  }

  object UserRegistryManagementInvoker {
    def apply()(implicit actorSystem: ActorSystem): UserRegistryManagementInvoker =
      userreg.client.invoker.ApiInvoker(userreg.client.api.EnumsSerializers.all)
  }
}
