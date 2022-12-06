package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.partyprocess.model.{GeographicTaxonomy}

import scala.concurrent.Future

trait GeoTaxonomyService {
  def getByCode(code: String)(implicit context: Seq[(String, String)]): Future[GeographicTaxonomy]
}
