package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}

/** Represents the data structure holding all the admittable platform roles as defined through app configuration.
  * @param manager - the admittable manager platform roles
  * @param delegate - the amittable delegate platform roles
  * @param operator - the admittable operator platform roles
  */
final case class PlatformRolesConfiguration(manager: ManagerRoles, delegate: DelegateRoles, operator: OperatorRoles)

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object ApplicationConfiguration {
  lazy val config: Config = ConfigFactory.load()

  def serverPort: Int = {
    config.getInt("uservice-party-process.port")
  }

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

  def destinationMails: Seq[String] = {
    Option(System.getenv("DESTINATION_MAILS"))
      .map(_.split(",").toSeq)
      .getOrElse(throw new RuntimeException("No destination email set"))
  }

  /** Returns the data structure containing all the platform roles currently defined for this deployment.
    * <br/>
    * In order to customize the roles for your needs, you MUST define the following environment variables:
    * <br/>
    * <br/>
    * <ul>
    *   <li>MANAGER_PLATFORM_ROLES</li>
    *   <li>DELEGATE_PLATFORM_ROLES</li>
    *   <li>OPERATOR_PLATFORM_ROLES</li>
    *  </ul>
    * <br>
    * They MUST be populated with comma separated strings
    *  <hr>
    *    <br/>
    *    <br/>
    *   E.g.:
    *  <ul>
    *   <li>export MANAGER_PLATFORM_ROLES=admin</li>
    *   <li>export DELEGATE_PLATFORM_ROLES=delegate, superuser</li>
    *   <li>export OPERATOR_PLATFORM_ROLES=api, security</li>
    *  </ul>
    */
  lazy val platformRolesConfiguration: PlatformRolesConfiguration = {
    PlatformRolesConfiguration(
      manager = ManagerRoles(sequencedParameter("uservice-party-process.platform.roles.manager")),
      delegate = DelegateRoles(sequencedParameter("uservice-party-process.platform.roles.delegate")),
      operator = OperatorRoles(sequencedParameter("uservice-party-process.platform.roles.operator"))
    )
  }

  private def sequencedParameter(parameterName: String) = config.getString(parameterName).split(",").map(_.trim).toSeq

}
