package com.checkout.payment.gateway.payment.model;

public record ApiErrorResponse(
    String code,
    String message
) {}