package com.checkout.payment.gateway.observability;

/** Published custom meter names; changes must also account for dashboards and configuration. */
public final class MetricNames {

  public static final String BANK_REQUESTS = "gateway.bank.requests";
  public static final String PAYMENT_ATTEMPTS = "gateway.payment.attempts";
  public static final String PAYMENT_ERRORS = "gateway.payment.errors";
  public static final String IDEMPOTENCY_REQUESTS = "gateway.idempotency.requests";
  public static final String IDEMPOTENCY_ENTRIES = "gateway.idempotency.entries";

  private MetricNames() {
  }
}
