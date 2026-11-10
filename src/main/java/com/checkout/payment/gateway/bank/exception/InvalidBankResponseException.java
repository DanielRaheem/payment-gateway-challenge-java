package com.checkout.payment.gateway.bank.exception;

import com.checkout.payment.gateway.bank.enums.BankErrorCode;

public class InvalidBankResponseException extends BankProcessingException {

  public InvalidBankResponseException(Throwable cause) {
    super(BankErrorCode.BANK_INVALID_RESPONSE, cause);
  }

  public InvalidBankResponseException() {
    this(null);
  }
}
