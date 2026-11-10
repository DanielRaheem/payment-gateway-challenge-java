package com.checkout.payment.gateway.payment.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FutureExpiryValidator.class)
public @interface FutureExpiry {
  String message() default "expiry month and year must be later than the current month";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
