package com.checkout.payment.gateway.payment.exception;

public class IdempotencyConflictException extends RuntimeException {

  public IdempotencyConflictException() {
    super("Idempotency key has already been used for a different request");
  }
}
