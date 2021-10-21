package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}

/** Represents the data structure holding all the admittable platform roles as defined through app configuration.
  * @param manager - the admittable manager platform roles
  * @param delegate - the amittable delegate platform roles
  * @param operator - the admittable operator platform roles
  */
final case class PlatformRolesConfiguration(manager: ManagerRoles, delegate: DelegateRoles, operator: OperatorRoles)

case class StorageAccountInfo(applicationId: String, applicationSecret: String, endpoint: String, container: String)

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object ApplicationConfiguration {
  lazy val config: Config = ConfigFactory.load()

  def serverPort: Int = {
    config.getInt("uservice-party-process.port")
  }

  def getPartyManagementUrl: String = config.getString("services.party-management")

  def getPartyProxyUrl: String = config.getString("services.party-proxy")

  def getAttributeRegistryUrl: String    = config.getString("services.attribute-registry")
  def getAuthorizationProcessURL: String = config.getString("services.authorization-process")
  def getUserRegistryURL: String         = config.getString("services.user-registry-management")

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

  def runtimeFileManager: String = {
    config.getString("uservice-party-process.storage.type")
  }

  def storageAccountInfo = {
    StorageAccountInfo(
      applicationId = config.getString("uservice-party-process.storage.application.id"),
      applicationSecret = config.getString("uservice-party-process.storage.application.secret"),
      endpoint = config.getString("uservice-party-process.storage.endpoint"),
      container = config.getString("uservice-party-process.storage.container")
    )
  }
}
