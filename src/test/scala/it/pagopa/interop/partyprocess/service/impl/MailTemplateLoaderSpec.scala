package it.pagopa.interop.partyprocess.service.impl

import com.typesafe.config.{Config, ConfigFactory}
import it.pagopa.interop.commons.files.service.FileManager
import it.pagopa.interop.partyprocess.SpecConfig.testDataConfig
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext.Implicits.global

class MailTemplateLoaderSpec extends AnyWordSpecLike with Matchers with ScalaFutures {

  val config: Config = ConfigFactory
    .parseResourcesAnySyntax("application-test")
    .withFallback(testDataConfig)

  System.setProperty("SMTP_SERVER", "localhost")

  "a mail template file" should {
    "be properly loaded" in {
      val fileManager = FileManager.getConcreteImplementation(config.getString("interop-commons.storage.type")).get
      val template    =
        MailTemplate
          .get(config.getString("party-process.mail-template.onboarding-mail-placeholders.path"), fileManager)
          .futureValue

      template.subject shouldBe "Procedura di interoperabilità"
      template.body.startsWith("<!DOCTYPE html><meta content='width=device-width' name=viewport>") shouldBe true
    }

  }
}
