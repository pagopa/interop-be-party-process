package it.pagopa.pdnd.interop.uservice.partyprocess.model

import java.io.File

/** @param token  for example: ''null''
  * @param document  for example: ''null''
  */
final case class OnBoardingResponse(token: String, document: File)
