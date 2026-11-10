package com.checkout.payment.gateway.bank.exception;

import com.checkout.payment.gateway.bank.enums.BankErrorCode;

/** Internal failure contract shared by the bank client and gateway exception handler. */
public class BankProcessingException extends RuntimeException {

  private final BankErrorCode code;

  public BankProcessingException(BankErrorCode code, Throwable cause) {
    super(code.getMessage(), cause);
    this.code = code;
  }

  public BankProcessingException(BankErrorCode code) {
    this(code, null);
  }

  public BankErrorCode getCode() {
    return code;
  }
}
