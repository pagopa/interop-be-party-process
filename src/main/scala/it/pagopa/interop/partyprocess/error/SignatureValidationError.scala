package it.pagopa.interop.partyprocess.error

import it.pagopa.interop.commons.utils.errors.ComponentError

abstract class SignatureValidationError(val cod: String, val message: String) extends ComponentError(cod, message)
