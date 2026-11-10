package com.checkout.payment.gateway.payment.validation;

import com.checkout.payment.gateway.payment.model.PostPaymentRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.YearMonth;

public class FutureExpiryValidator implements ConstraintValidator<FutureExpiry, PostPaymentRequest> {

  @Override
  public boolean isValid(PostPaymentRequest request, ConstraintValidatorContext context) {
    // Field constraints report missing or out-of-range components independently.
    if (request == null || request.expiryMonth() == null || request.expiryYear() == null
        || request.expiryMonth() < 1 || request.expiryMonth() > 12
        || request.expiryYear() < 1 || request.expiryYear() > 9999) {
      return true;
    }
    // Use Bean Validation's ClockProvider so expiry boundaries can be tested deterministically.
    return YearMonth.of(request.expiryYear(), request.expiryMonth())
        .isAfter(YearMonth.now(context.getClockProvider().getClock()));
  }
}
