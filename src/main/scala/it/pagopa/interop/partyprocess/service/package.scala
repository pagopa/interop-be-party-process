package it.pagopa.interop.partyprocess

import akka.actor.ActorSystem
import it.pagopa.interop._
import it.pagopa._

import scala.concurrent.ExecutionContextExecutor

package object service {

  final val replacementEntityId: String = "UNKNOWN"

  type PartyProxyInvoker             = partyregistryproxy.client.invoker.ApiInvoker
  type PartyManagementInvoker        = partymanagement.client.invoker.ApiInvoker
  type ProductManagementInvoker      = product.client.invoker.ApiInvoker
  type UserRegistryManagementInvoker = userreg.client.invoker.ApiInvoker
  type GeoTaxonomyInvoker            = geotaxonomy.client.invoker.ApiInvoker

  object PartyProxyInvoker {
    def apply()(implicit actorSystem: ActorSystem): PartyProxyInvoker =
      partyregistryproxy.client.invoker.ApiInvoker()
  }

  object PartyManagementInvoker {
    def apply(blockingEc: ExecutionContextExecutor)(implicit actorSystem: ActorSystem): PartyManagementInvoker =
      partymanagement.client.invoker.ApiInvoker(partymanagement.client.api.EnumsSerializers.all, blockingEc)
  }

  object ProductManagementInvoker {
    def apply()(implicit actorSystem: ActorSystem): ProductManagementInvoker =
      product.client.invoker.ApiInvoker(product.client.api.EnumsSerializers.all)
  }

  object UserRegistryManagementInvoker {
    def apply()(implicit actorSystem: ActorSystem): UserRegistryManagementInvoker =
      userreg.client.invoker.ApiInvoker(userreg.client.api.EnumsSerializers.all)
  }

  object GeoTaxonomyInvoker {
    def apply()(implicit actorSystem: ActorSystem): GeoTaxonomyInvoker =
      geotaxonomy.client.invoker.ApiInvoker(product.client.api.EnumsSerializers.all)
  }
}
