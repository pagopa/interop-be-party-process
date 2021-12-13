package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

final case class InvalidContractDigest(originalDigest: String, incomingDigest: String) extends ValidationError {

  override def getMessage: String = s"Original contract digest $originalDigest is not equal to $incomingDigest"

  override def getErrorCode: String = "0101"

}
