package it.pagopa.interop.partyprocess.service.impl

import it.pagopa.interop.commons.files.service.FileManager
import it.pagopa.interop.commons.mail.model.{PersistedTemplate, PersistedTemplateUnmarshaller}
import it.pagopa.interop.commons.utils.TypeConversions.TryOps
import it.pagopa.interop.partyprocess.common.system.ApplicationConfiguration

import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object MailTemplate {

  def get(templatePath: String, fileManager: FileManager)(implicit ec: ExecutionContext): Future[PersistedTemplate] = {

    for {
      fileStream        <- fileManager.get(ApplicationConfiguration.storageContainer)(templatePath)
      fileString        <- Try { fileStream.toString(StandardCharsets.UTF_8) }.toFuture
      persistedTemplate <- PersistedTemplateUnmarshaller.toPersistedTemplate(fileString).toFuture
    } yield persistedTemplate

  }

}
