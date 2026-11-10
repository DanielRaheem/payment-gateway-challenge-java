package com.checkout.payment.gateway.payment.model;

import com.checkout.payment.gateway.payment.enums.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** Masked payment details shared by submission and lookup responses. */
public record PaymentResponse(
    @NotNull UUID id,
    @NotNull PaymentStatus status,
    @NotNull @Pattern(regexp = "[0-9]{4}") String cardNumberLastFour,
    @Min(1) @Max(12) int expiryMonth,
    @Min(1) @Max(9999) int expiryYear,
    @NotNull @Pattern(regexp = "GBP|USD|EUR") String currency,
    int amount
) {
  @JsonIgnore
  @AssertTrue(message = "payment status must be Authorized or Declined")
  public boolean isProcessedStatus() {
    return status == null || status == PaymentStatus.AUTHORIZED || status == PaymentStatus.DECLINED;
  }

  @Override
  public String toString() {
    return "PaymentResponse{" +
        "id=" + id +
        ", status=" + status +
        ", cardNumberLastFour='" + cardNumberLastFour + '\'' +
        ", expiryMonth=" + expiryMonth +
        ", expiryYear=" + expiryYear +
        ", currency='" + currency + '\'' +
        ", amount=" + amount +
        '}';
  }
}
