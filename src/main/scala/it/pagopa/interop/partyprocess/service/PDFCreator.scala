package it.pagopa.interop.partyprocess.service

import it.pagopa.interop.partymanagement.client.model.Institution
import it.pagopa.interop.partyprocess.api.impl.OnboardingSignedRequest
import it.pagopa.interop.partyprocess.model.{GeographicTaxonomy, User}

import java.io.File
import scala.concurrent.Future

trait PDFCreator {
  def createContract(
    contractTemplate: String,
    manager: User,
    users: Seq[User],
    institution: Institution,
    onboardingRequest: OnboardingSignedRequest,
    geoTaxonomies: Seq[GeographicTaxonomy]
  )(implicit contexts: Seq[(String, String)]): Future[File]
}
