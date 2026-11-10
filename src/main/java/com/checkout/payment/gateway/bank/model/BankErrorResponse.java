package com.checkout.payment.gateway.bank.model;

import com.checkout.payment.gateway.bank.enums.BankErrorCode;

/** Public bank-failure contract: no payment status, ID, or upstream response details. */
public record BankErrorResponse(BankErrorCode code, String message) {

  @Override
  public String toString() {
    return "BankErrorResponse{code=" + code + ", message='" + message + "'}";
  }
}
