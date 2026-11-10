package com.checkout.payment.gateway.bank.enums;

/** Stable gateway error codes; these do not represent payment outcomes. */
public enum BankErrorCode {
  BANK_UNAVAILABLE("Bank service is unavailable"),
  BANK_TIMEOUT("Bank request timed out; payment outcome is unknown"),
  BANK_INVALID_RESPONSE("Bank returned an invalid response");

  private final String message;

  BankErrorCode(String message) {
    this.message = message;
  }

  public String getMessage() {
    return message;
  }
}
