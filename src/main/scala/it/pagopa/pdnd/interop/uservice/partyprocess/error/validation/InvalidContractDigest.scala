package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

final case class InvalidContractDigest(originalDigest: String, incomingDigest: String)
    extends SignatureValidationError(
      "0101",
      s"Original contract digest $originalDigest is not equal to $incomingDigest"
    )
