package com.checkout.payment.gateway.payment.repository;

import com.checkout.payment.gateway.payment.model.PaymentResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentsRepository {

  // Thread-safe single-entry operations; data still lasts only for this application instance.
  private final ConcurrentMap<UUID, PaymentResponse> payments = new ConcurrentHashMap<>();

  public void add(PaymentResponse payment) {
    payments.put(payment.id(), payment);
  }

  public Optional<PaymentResponse> get(UUID id) {
    return Optional.ofNullable(payments.get(id));
  }

}
