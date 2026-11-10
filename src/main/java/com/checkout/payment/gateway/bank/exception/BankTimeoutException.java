package com.checkout.payment.gateway.bank.exception;

import com.checkout.payment.gateway.bank.enums.BankErrorCode;

public class BankTimeoutException extends BankProcessingException {

  public BankTimeoutException(Throwable cause) {
    super(BankErrorCode.BANK_TIMEOUT, cause);
  }

  public BankTimeoutException() {
    this(null);
  }
}
