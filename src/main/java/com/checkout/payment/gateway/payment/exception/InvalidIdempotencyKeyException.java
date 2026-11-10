package com.checkout.payment.gateway.payment.exception;

public class InvalidIdempotencyKeyException extends RuntimeException {

  public InvalidIdempotencyKeyException() {
    super("Idempotency-Key must not be blank");
  }
}