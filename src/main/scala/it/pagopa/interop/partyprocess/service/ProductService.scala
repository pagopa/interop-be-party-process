package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.partymanagement.client.model.Institution
import it.pagopa.interop.partyprocess.model.{ProductState, Products}

import scala.concurrent.Future

trait ProductService {
  def retrieveInstitutionProducts(institution: Institution, statesFilter: List[ProductState])(bearer: String)(implicit
    contexts: Seq[(String, String)]
  ): Future[Products]
}
