package it.pagopa.interop.partyprocess

import it.pagopa.interop.partymanagement.client.model.Organization
import it.pagopa.interop.partyprocess.model.{PartyRole, User}
import it.pagopa.interop.partyprocess.service.impl.PDFCreatorImpl
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.File
import java.util.UUID
import scala.io.Source

//IGNORING THIS TO AVOID TEMPORARY FILES POLLUTION
class PDFCreationSpec extends AnyWordSpecLike with Matchers with ScalaFutures {

  val path: String = getClass.getResource(s"/template-html.txt").getPath
  val file: File   = new File(path)

  "PDFBox should edit the pdf" should {

    "test reading" ignore {

      val htmlTemplateSource = Source.fromFile(file)
      val htmlTemplate       = htmlTemplateSource.mkString

      val org = Organization(
        id = UUID.randomUUID(),
        institutionId = "Comune di Milano",
        description = "Comune di Milano",
        digitalAddress = "prova.test@test.it",
        taxCode = "1234",
        attributes = Seq.empty,
        address = "",
        zipCode = ""
      )

      val users = Seq(
        User(
          name = "Mario",
          surname = "Rossi",
          taxCode = "MRRSSSSSSS",
          role = PartyRole.DELEGATE,
          email = Some("mario@rossi.it"),
          product = "TEST",
          productRole = "Delegate"
        ),
        User(
          name = "Gianni",
          surname = "Brera",
          taxCode = "MRRSSSSSSS",
          role = PartyRole.SUB_DELEGATE,
          email = Some("mario@rossi.it"),
          product = "TEST",
          productRole = "Subdelegate"
        ),
        User(
          name = "Mario",
          surname = "Sconcerti",
          taxCode = "MRRSSSSSSS",
          role = PartyRole.DELEGATE,
          email = Some("mario@rossi.it"),
          product = "TEST",
          productRole = "Delegate"
        ),
        User(
          name = "Fabio",
          surname = "Cannavaro",
          taxCode = "MRRSSSSSSS",
          role = PartyRole.MANAGER,
          email = Some("mario@rossi.it"),
          product = "TEST",
          productRole = "Manager"
        )
      )

      println(PDFCreatorImpl.createContract(htmlTemplate, users, org).futureValue.getPath)
      htmlTemplateSource.close()

    }

  }

}
