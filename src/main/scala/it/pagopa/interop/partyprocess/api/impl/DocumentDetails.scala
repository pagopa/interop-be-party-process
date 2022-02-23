package it.pagopa.interop.partyprocess.api.impl

import akka.http.scaladsl.model.ContentType

import java.io.ByteArrayOutputStream

final case class DocumentDetails(fileName: String, contentType: ContentType, file: ByteArrayOutputStream)
