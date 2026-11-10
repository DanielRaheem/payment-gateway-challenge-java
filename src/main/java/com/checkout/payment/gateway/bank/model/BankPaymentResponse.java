package com.checkout.payment.gateway.bank.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Bank authorization outcome; the authorization code is not the gateway payment ID. */
public record BankPaymentResponse(
    // Null distinguishes a missing outcome from an explicit decline.
    Boolean authorized,
    @JsonProperty("authorization_code") String authorizationCode
) {

  @Override
  public String toString() {
    return "BankPaymentResponse{" +
        "authorized=" + authorized +
        ", authorizationCode='" + authorizationCode + '\'' +
        '}';
  }
}
