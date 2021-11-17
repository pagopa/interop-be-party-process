package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import com.typesafe.config.{Config, ConfigFactory}
import it.pagopa.pdnd.interop.commons.files.service.FileManager
import it.pagopa.pdnd.interop.uservice.partyprocess.SpecConfig.testDataConfig
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
      val fileManager = FileManager.getConcreteImplementation(config.getString("pdnd-interop-commons.storage.type")).get
      val template =
        MailTemplate.get(config.getString("uservice-party-process.mail-template-path"), fileManager).futureValue

      template.subject shouldBe "Procedura di interoperabilità"
      template.body.startsWith("<!DOCTYPE html><meta content='width=device-width' name=viewport>") shouldBe true
    }

  }

}
