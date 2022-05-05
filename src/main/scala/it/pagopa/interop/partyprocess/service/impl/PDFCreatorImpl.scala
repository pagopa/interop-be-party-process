package it.pagopa.interop.partyprocess.service.impl

import com.openhtmltopdf.util.XRLog
import it.pagopa.interop.commons.files.service.PDFManager
import it.pagopa.interop.commons.utils.TypeConversions.OptionOps
import it.pagopa.interop.partymanagement.client.model.Institution
import it.pagopa.interop.partyprocess.model.{PartyRole, User}
import it.pagopa.interop.partyprocess.service.PDFCreator

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.Future
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.util.Try

object PDFCreatorImpl extends PDFCreator with PDFManager {

  // Suppressing openhtmltopdf log
  XRLog.listRegisteredLoggers.asScala.foreach((logger: String) =>
    XRLog.setLevel(logger, java.util.logging.Level.SEVERE)
  )

  def createContract(
    contractTemplate: String,
    manager: User,
    users: Seq[User],
    institution: Institution
  ): Future[File] =
    Future.fromTry {
      for {
        file <- createTempFile
        data <- setupData(manager, users, institution)
        pdf  <- getPDFAsFile(file.toPath, contractTemplate, data)
      } yield pdf

    }

  private def createTempFile: Try[File] = {
    Try {
      val fileTimestamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
      File.createTempFile(s"${fileTimestamp}_${UUID.randomUUID().toString}_contratto_interoperabilita.", ".pdf")
    }
  }

  private def setupData(manager: User, users: Seq[User], institution: Institution): Try[Map[String, String]] = {
    for {
      managerEmail <- manager.email.toTry("Manager email not found")
    } yield Map(
      "institutionName"    -> institution.description,
      "institutionTaxCode" -> institution.taxCode,
      "originId"           -> institution.originId,
      "institutionMail"    -> institution.digitalAddress,
      "managerName"        -> manager.name,
      "managerSurname"     -> manager.surname,
      "managerTaxCode"     -> manager.taxCode,
      "managerEmail"       -> managerEmail,
      "manager"            -> userToText(manager),
      "delegates"          -> delegatesToText(users)
    )

  }

  def delegatesToText(users: Seq[User]): String = {
    val delegates: Seq[User] = users.filter(_.role == PartyRole.DELEGATE)

    delegates
      .map { delegate =>
        s"""
           |<p class="c141"><span class="c6">Nome e Cognome: ${userToText(delegate)}&nbsp;</span></p>
           |<p class="c158"><span class="c6">Codice Fiscale: ${delegate.taxCode}</span></p>
           |<p class="c24"><span class="c6">Amm.ne/Ente/Societ&agrave;: </span></p>
           |<p class="c229"><span class="c6">Qualifica/Posizione: </span></p>
           |<p class="c255"><span class="c6">e-mail: ${delegate.email}&nbsp;</span></p>
           |<p class="c74"><span class="c6">PEC: &nbsp;</span></p>
           |""".stripMargin
      }
      .mkString("\n")
  }

  private def userToText(user: User): String = {
    s"${user.name} ${user.surname}"
  }
}
