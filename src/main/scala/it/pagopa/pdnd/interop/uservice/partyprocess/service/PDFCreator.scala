package it.pagopa.pdnd.interop.uservice.partyprocess.service

import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.RelationShips

import java.io.File
import scala.concurrent.Future

trait PDFCreator {
  def create(relationShips: RelationShips): Future[(File, String)]
}
