package com.checkout.payment.gateway.payment.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PaymentStatus {
  AUTHORIZED("Authorized"),
  DECLINED("Declined"),
  REJECTED("Rejected");

  private final String name;

  PaymentStatus(String name) {
    this.name = name;
  }

  // API JSON uses the display name ("Authorized"), rather than the enum constant ("AUTHORIZED").
  @JsonValue
  public String getName() {
    return this.name;
  }
}
