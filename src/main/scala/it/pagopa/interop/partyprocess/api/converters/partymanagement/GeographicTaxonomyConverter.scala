package it.pagopa.interop.partyprocess.api.converters.partymanagement

import it.pagopa.interop.partymanagement.client.model.{GeographicTaxonomy => DependencyGeographicTaxonomy}
import it.pagopa.interop.partyprocess.model.GeographicTaxonomy

object GeographicTaxonomyConverter {
  def dependencyToApi(geographicTaxonomy: DependencyGeographicTaxonomy): GeographicTaxonomy =
    GeographicTaxonomy(code = geographicTaxonomy.code, desc = geographicTaxonomy.desc)

  def dependencyFromApi(geographicTaxonomy: GeographicTaxonomy): DependencyGeographicTaxonomy =
    DependencyGeographicTaxonomy(code = geographicTaxonomy.code, desc = geographicTaxonomy.desc)
}
