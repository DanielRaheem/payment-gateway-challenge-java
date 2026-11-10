package com.checkout.payment.gateway.bank.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Outbound payload for the bank simulator's POST /payments endpoint. */
public record BankPaymentRequest(
    @JsonProperty("card_number") String cardNumber,
    @JsonProperty("expiry_date") String expiryDate,
    String currency,
    int amount,
    String cvv
) {

  @Override
  public String toString() {
    return "BankPaymentRequest{" +
        "cardNumber=REDACTED" +
        ", expiryDate='" + expiryDate + '\'' +
        ", currency='" + currency + '\'' +
        ", amount=" + amount +
        ", cvv=REDACTED" +
        '}';
  }
}
