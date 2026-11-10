package com.checkout.payment.gateway.payment.model;

public record RejectedPaymentResponse(
    String status,
    String message
) {}