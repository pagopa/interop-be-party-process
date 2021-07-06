package it.pagopa.pdnd.interop.uservice.partyprocess
import akka.actor.ActorSystem
import it.pagopa.pdnd.interop.uservice._
import it.pagopa.pdnd.interop.uservice.partymanagement.client.api.EnumsSerializers

package object service {
  type PartyProxyInvoker      = partyregistryproxy.client.invoker.ApiInvoker
  type PartyManagementInvoker = partymanagement.client.invoker.ApiInvoker

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  object PartyProxyInvoker {
    def apply()(implicit actorSystem: ActorSystem): PartyProxyInvoker = partyregistryproxy.client.invoker.ApiInvoker()
  }
  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  object PartyManagementInvoker {
    def apply()(implicit actorSystem: ActorSystem): PartyManagementInvoker =
      partymanagement.client.invoker.ApiInvoker(EnumsSerializers.all)
  }
}
