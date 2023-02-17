package it.pagopa.interop.partyprocess.model;

final case class OnboardingContractStorage(fileName: String, filePath: String)

object OnboardingContractStorage {
  def toOnboardingContract(fileName: String, filePath: String): OnboardingContractStorage =
    OnboardingContractStorage(fileName = fileName, filePath = filePath)
}
