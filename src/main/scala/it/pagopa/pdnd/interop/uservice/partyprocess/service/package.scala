package it.pagopa.pdnd.interop.uservice.partyprocess
import akka.actor.ActorSystem
import it.pagopa.pdnd.interop.uservice._

package object service {
  type PartyProxyInvoker             = partyregistryproxy.client.invoker.ApiInvoker
  type PartyManagementInvoker        = partymanagement.client.invoker.ApiInvoker
  type AttributeRegistryInvoker      = attributeregistrymanagement.client.invoker.ApiInvoker
  type AuthorizationProcessInvoker   = authorizationprocess.client.invoker.ApiInvoker
  type UserRegistryManagementInvoker = userregistrymanagement.client.invoker.ApiInvoker

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  object PartyProxyInvoker {
    def apply()(implicit actorSystem: ActorSystem): PartyProxyInvoker = partyregistryproxy.client.invoker.ApiInvoker()
  }

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  object PartyManagementInvoker {
    def apply()(implicit actorSystem: ActorSystem): PartyManagementInvoker =
      partymanagement.client.invoker.ApiInvoker(partymanagement.client.api.EnumsSerializers.all)
  }

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  object AttributeRegistryInvoker {
    def apply()(implicit actorSystem: ActorSystem): AttributeRegistryInvoker =
      attributeregistrymanagement.client.invoker.ApiInvoker(attributeregistrymanagement.client.api.EnumsSerializers.all)
  }

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  object AuthorizationProcessInvoker {
    def apply()(implicit actorSystem: ActorSystem): AuthorizationProcessInvoker =
      authorizationprocess.client.invoker.ApiInvoker(authorizationprocess.client.api.EnumsSerializers.all)
  }

  @SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
  object UserRegistryManagementInvoker {
    def apply()(implicit actorSystem: ActorSystem): UserRegistryManagementInvoker =
      userregistrymanagement.client.invoker.ApiInvoker(userregistrymanagement.client.api.EnumsSerializers.all)
  }
}
