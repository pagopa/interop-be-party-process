package it.pagopa.pdnd.interop.uservice.partyprocess.error

import it.pagopa.pdnd.interop.commons.utils.errors.ComponentError

abstract class SignatureValidationError(val cod: String, val message: String) extends ComponentError(cod, message)
