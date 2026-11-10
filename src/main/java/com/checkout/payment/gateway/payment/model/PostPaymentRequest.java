package com.checkout.payment.gateway.payment.model;

import com.checkout.payment.gateway.payment.validation.FutureExpiry;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;

@FutureExpiry
public record PostPaymentRequest(
    @NotNull @Pattern(regexp = "[0-9]{14,19}")
    @JsonProperty("card_number") String cardNumber,

    @NotNull @Min(1) @Max(12)
    @JsonProperty("expiry_month") Integer expiryMonth,

    @NotNull @Min(1) @Max(9999)
    @JsonProperty("expiry_year") Integer expiryYear,

    @NotNull @Pattern(regexp = "GBP|USD|EUR") String currency,

    // Amount is in minor units; the assessment does not require a positive-only range.
    @NotNull Integer amount,

    @NotNull @Pattern(regexp = "[0-9]{3,4}") String cvv
) implements Serializable {

  @Override
  public String toString() {
    return "PostPaymentRequest{" +
        "cardNumber=REDACTED" +
        ", expiryMonth=" + expiryMonth +
        ", expiryYear=" + expiryYear +
        ", currency='" + currency + '\'' +
        ", amount=" + amount +
        ", cvv=REDACTED" +
        '}';
  }
}
