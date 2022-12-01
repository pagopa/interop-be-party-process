package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.partyprocess.model.{GeographicTaxonomy, GeographicTaxonomyExt}

import scala.concurrent.Future

trait GeoTaxonomyService {
  def getByCode(code: String)(implicit context: Seq[(String, String)]): Future[GeographicTaxonomy]
  def getByCodes(codes: Seq[String])(implicit context: Seq[(String, String)]): Future[Seq[GeographicTaxonomy]]

  def getExtByCode(code: String)(implicit context: Seq[(String, String)]): Future[GeographicTaxonomyExt]
  def getExtByCodes(codes: Seq[String])(implicit context: Seq[(String, String)]): Future[Seq[GeographicTaxonomyExt]]
}
