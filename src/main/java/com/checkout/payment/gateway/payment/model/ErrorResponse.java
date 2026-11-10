package com.checkout.payment.gateway.payment.model;

public record ErrorResponse(String message) {
  @Override
  public String toString() {
    return "ErrorResponse{message='" + message + "'}";
  }
}
