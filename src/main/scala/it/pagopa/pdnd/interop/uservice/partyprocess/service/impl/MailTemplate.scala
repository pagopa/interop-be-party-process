package it.pagopa.pdnd.interop.uservice.partyprocess.service.impl

import it.pagopa.pdnd.interop.commons.files.service.FileManager
import it.pagopa.pdnd.interop.commons.mail.model.{PersistedTemplate, PersistedTemplateUnmarshaller}
import it.pagopa.pdnd.interop.commons.utils.TypeConversions.TryOps

import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object MailTemplate {

  def get(templatePath: String, fileManager: FileManager)(implicit ec: ExecutionContext): Future[PersistedTemplate] = {

    for {
      fileStream        <- fileManager.get(templatePath)
      fileString        <- Try { fileStream.toString(StandardCharsets.UTF_8) }.toFuture
      persistedTemplate <- PersistedTemplateUnmarshaller.toPersistedTemplate(fileString).toFuture
    } yield persistedTemplate

  }

}
