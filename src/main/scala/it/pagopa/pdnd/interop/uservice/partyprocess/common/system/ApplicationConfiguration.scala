package it.pagopa.pdnd.interop.uservice.partyprocess.common.system

import com.typesafe.config.{Config, ConfigFactory}

/** Represents the data structure holding all the admittable platform roles as defined through app configuration.
  * @param manager - the admittable manager platform roles
  * @param delegate - the amittable delegate platform roles
  * @param operator - the admittable operator platform roles
  */
final case class ProductRolesConfiguration(manager: ManagerRoles, delegate: DelegateRoles, operator: OperatorRoles)

object ApplicationConfiguration {
  lazy val config: Config = ConfigFactory.load()

  def serverPort: Int = {
    config.getInt("uservice-party-process.port")
  }

  def getPartyManagementUrl: String = config.getString("services.party-management")

  def getPartyProxyUrl: String = config.getString("services.party-proxy")

  def getAttributeRegistryUrl: String = config.getString("services.attribute-registry")
  def getUserRegistryURL: String      = config.getString("services.user-registry-management")

  def destinationMails: Seq[String] = {
    Option(System.getenv("DESTINATION_MAILS"))
      .map(_.split(",").toSeq)
      .getOrElse(throw new RuntimeException("No destination email set"))
  }

  def mailTemplatePath: String = config.getString("uservice-party-process.mail-template.path")

  def onboardingMailPlaceholdersReplacement: Map[String, String] = {
    Map(
      config.getString("uservice-party-process.mail-template.confirm-token.name") -> config.getString(
        "uservice-party-process.mail-template.confirm-token.placeholder"
      ),
      config.getString("uservice-party-process.mail-template.reject-token.name") -> config.getString(
        "uservice-party-process.mail-template.reject-token.placeholder"
      )
    )
  }

  /** Returns the data structure containing all the platform roles currently defined for this deployment.
    * <br/>
    * In order to customize the roles for your needs, you MUST define the following environment variables:
    * <br/>
    * <br/>
    * <ul>
    *   <li>MANAGER_PRODUCT_ROLES</li>
    *   <li>DELEGATE_PRODUCT_ROLES</li>
    *   <li>OPERATOR_PRODUCT_ROLES</li>
    *  </ul>
    * <br>
    * They MUST be populated with comma separated strings
    *  <hr>
    *    <br/>
    *    <br/>
    *   E.g.:
    *  <ul>
    *   <li>export MANAGER_PRODUCT_ROLES=admin</li>
    *   <li>export DELEGATE_PRODUCT_ROLES=delegate, superuser</li>
    *   <li>export OPERATOR_PRODUCT_ROLES=api, security</li>
    *  </ul>
    */
  lazy val productRolesConfiguration: ProductRolesConfiguration = {
    ProductRolesConfiguration(
      manager = ManagerRoles(sequencedParameter("uservice-party-process.product.roles.manager")),
      delegate = DelegateRoles(sequencedParameter("uservice-party-process.product.roles.delegate")),
      operator = OperatorRoles(sequencedParameter("uservice-party-process.product.roles.operator"))
    )
  }

  private def sequencedParameter(parameterName: String) = config.getString(parameterName).split(",").map(_.trim).toSeq

}
