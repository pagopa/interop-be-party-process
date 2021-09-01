package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}
import scala.jdk.CollectionConverters._

/** Represents the data structure holding all the admittable platform roles as defined through app configuration.
  * @param manager - the admittable manager platform roles
  * @param delegate - the amittable delegate platform roles
  * @param operator - the admittable operator platform roles
  */
final case class PlatformRolesConfiguration(manager: ManagerRoles, delegate: DelegateRoles, operator: OperatorRoles)

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object ApplicationConfiguration {
  lazy val config: Config = ConfigFactory.load()

  def getPartyManagementUrl: String = {
    val partyManagementUrl: String = config.getString("services.party-management")
    s"$partyManagementUrl/pdnd-interop-uservice-party-management/0.0.1"
  }

  def getPartyProxyUrl: String = {
    val partyProxyUrl: String = config.getString("services.party-proxy")
    s"$partyProxyUrl/pdnd-interop-uservice-party-registry-proxy/0.0.1"
  }

  def getAttributeRegistryUrl: String = {
    val partyProxyUrl: String = config.getString("services.attribute-registry")
    s"$partyProxyUrl/pdnd-interop-uservice-attribute-registry-management/0.0.1"
  }

  def destinationMail: String = {
    Option(System.getenv("DESTINATION_MAIL")).getOrElse(throw new RuntimeException("No destination email set"))
  }

  /** Return the data structure containing all the platform roles currently defined for this deployment.
    * <br/>
    * In order to customize the roles for your needs, you MUST define the following environment variables:
    * <br/>
    * <br/>
    * <ul>
    *   <li>MANAGER_PLATFORM_ROLES</li>
    *   <li>DELEGATE_PLATFORM_ROLES</li>
    *   <li>OPERATOR_PLATFORM_ROLES</li>
    *  </ul>
    *
    *  <hr>
    *    <br/>
    *    <br/>
    *   E.g.:
    *  <ul>
    *   <li>export MANAGER_PLATFORM_ROLES = '"admin"'</li>
    *   <li>export DELEGATE_PLATFORM_ROLES = '"delegate", "superuser"'</li>
    *   <li>export OPERATOR_PLATFORM_ROLES = '"api", "security"'</li>
    *  </ul>
    */
  lazy val platformRolesConfiguration: PlatformRolesConfiguration = {
    PlatformRolesConfiguration(
      manager = ManagerRoles(config.getStringList("uservice-party-process.platform.roles.manager").asScala.toSeq),
      delegate = DelegateRoles(config.getStringList("uservice-party-process.platform.roles.manager").asScala.toSeq),
      operator = OperatorRoles(config.getStringList("uservice-party-process.platform.roles.manager").asScala.toSeq)
    )
  }

}
