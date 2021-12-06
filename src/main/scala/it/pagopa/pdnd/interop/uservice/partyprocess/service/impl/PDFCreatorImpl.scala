package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.commons.files.service.PDFManager
import it.pagopa.pdnd.interop.commons.utils.TypeConversions.OptionOps
import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.Organization
import it.pagopa.pdnd.interop.uservice.partyprocess.model.{PartyRole, User}
import it.pagopa.pdnd.interop.uservice.partyprocess.service.PDFCreator

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.Future
import scala.util.Try

object PDFCreatorImpl extends PDFCreator with PDFManager {

  def createContract(contractTemplate: String, users: Seq[User], organization: Organization): Future[File] =
    Future.fromTry {
      for {
        file <- createTempFile
        data <- setupData(users, organization)
        pdf  <- getPDFAsFile(file.toPath, contractTemplate, data)
      } yield pdf

    }

  private def createTempFile: Try[File] = {
    Try {
      val fileTimestamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
      File.createTempFile(s"${fileTimestamp}_${UUID.randomUUID().toString}_contratto_interoperabilita.", ".pdf")
    }
  }

  private def setupData(users: Seq[User], organization: Organization): Try[Map[String, String]] = {
    for {
      manager <- users.find(u => u.role == PartyRole.MANAGER).toTry("Manager not found")
    } yield Map(
      "institutionName" -> organization.description,
      "institutionMail" -> organization.digitalAddress,
      "manager"         -> managerToText(manager),
      "users"           -> users.map(userToText).mkString
    )
  }

  private def userToText(user: User): String = {
    s"${user.name} ${user.surname}, Codice fiscale: ${user.taxCode}, Ruolo: <strong>${user.role}</strong><br/>"
  }

  private def managerToText(user: User): String = {
    s"${user.name} ${user.surname}, Codice fiscale: ${user.taxCode}"
  }
}
