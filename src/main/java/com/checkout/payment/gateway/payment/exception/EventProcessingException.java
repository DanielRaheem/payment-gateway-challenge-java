package com.checkout.payment.gateway.payment.exception;

public class EventProcessingException extends RuntimeException{
  public EventProcessingException(String message) {
    super(message);
  }
}
