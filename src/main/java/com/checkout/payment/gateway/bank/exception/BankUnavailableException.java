package com.checkout.payment.gateway.bank.exception;

import com.checkout.payment.gateway.bank.enums.BankErrorCode;

public class BankUnavailableException extends BankProcessingException {

  public BankUnavailableException(Throwable cause) {
    super(BankErrorCode.BANK_UNAVAILABLE, cause);
  }

  public BankUnavailableException() {
    this(null);
  }
}
