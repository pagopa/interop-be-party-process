package it.pagopa.interop.partyprocess.model

import it.pagopa.interop.partyprocess.model.PartyRole.{DELEGATE, MANAGER, OPERATOR, SUB_DELEGATE}

object PartyRoleAPIConverter {

  def fromClientValue(value: String): PartyRole =
    value match {
      case "MANAGER"      => MANAGER
      case "DELEGATE"     => DELEGATE
      case "SUB_DELEGATE" => SUB_DELEGATE
      case _              => OPERATOR
    }

}
