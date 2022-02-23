package it.pagopa.interop.partyprocess

import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ApplicationConfigurationSpec
    extends MockFactory
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with SpecHelper {

  override def beforeAll(): Unit = {
    val _ = loadEnvVars()
  }

  "application configuration" should {
    "return proper names and placeholders" in {
      val config = ApplicationConfiguration.onboardingMailPlaceholdersReplacement
      config should contain only (
        ("confirmTokenURL" -> "confirm-value"),
        ("rejectTokenURL"  -> "reject-value"),
      )
    }
  }

}
