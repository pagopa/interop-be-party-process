package it.pagopa.pdnd.interop.uservice.partyprocess.error

import it.pagopa.pdnd.interop.uservice.partymanagement.client.model.RelationshipState

import java.util.UUID

final case class EmptyOnboardingInfo(uid: UUID, institutionId: Option[String], states: List[RelationshipState])
    extends Throwable(s"No onboarding information found for uid=${uid.toString} institutionId=${institutionId
      .getOrElse("")},states=[${states.mkString(",")}]")
