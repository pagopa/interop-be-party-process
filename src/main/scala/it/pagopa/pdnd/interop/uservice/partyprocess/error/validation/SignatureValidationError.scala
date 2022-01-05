package it.pagopa.pdnd.interop.uservice.partyprocess.error.validation

import it.pagopa.pdnd.interop.commons.utils.errors.PDNDError

abstract class SignatureValidationError(val code: String, val msg: String) extends Throwable(msg) with PDNDError
