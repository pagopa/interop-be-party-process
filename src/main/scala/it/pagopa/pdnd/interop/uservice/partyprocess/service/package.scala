package it.pagopa.pdnd.interop.uservice.partyprocess
import akka.actor.ActorSystem
import it.pagopa.pdnd.interop.uservice._

package object service {

  final val unknown: String = "UNKNOWN"

  type PartyProxyInvoker             = partyregistryproxy.client.invoker.ApiInvoker
  type PartyManagementInvoker        = partymanagement.client.invoker.ApiInvoker
  type UserRegistryManagementInvoker = userregistrymanagement.client.invoker.ApiInvoker

  object PartyProxyInvoker {
    def apply()(implicit actorSystem: ActorSystem): PartyProxyInvoker = partyregistryproxy.client.invoker.ApiInvoker()
  }

  object PartyManagementInvoker {
    def apply()(implicit actorSystem: ActorSystem): PartyManagementInvoker =
      partymanagement.client.invoker.ApiInvoker(partymanagement.client.api.EnumsSerializers.all)
  }

  object UserRegistryManagementInvoker {
    def apply()(implicit actorSystem: ActorSystem): UserRegistryManagementInvoker =
      userregistrymanagement.client.invoker.ApiInvoker(userregistrymanagement.client.api.EnumsSerializers.all)
  }
}
