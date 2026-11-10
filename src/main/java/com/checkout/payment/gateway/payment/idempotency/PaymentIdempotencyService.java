package com.checkout.payment.gateway.payment.idempotency;

import com.checkout.payment.gateway.observability.MetricNames;
import com.checkout.payment.gateway.bank.enums.BankErrorCode;
import com.checkout.payment.gateway.bank.exception.BankProcessingException;
import com.checkout.payment.gateway.bank.exception.BankTimeoutException;
import com.checkout.payment.gateway.bank.exception.BankUnavailableException;
import com.checkout.payment.gateway.bank.exception.InvalidBankResponseException;
import com.checkout.payment.gateway.payment.exception.IdempotencyConflictException;
import com.checkout.payment.gateway.payment.exception.InvalidIdempotencyKeyException;
import com.checkout.payment.gateway.payment.model.PostPaymentRequest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** In-memory, single-instance coordination. Entries last until application restart. */
@Service
public class PaymentIdempotencyService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentIdempotencyService.class);

  private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final PaymentRequestFingerprint fingerprints;
  private final MeterRegistry meters;

  public PaymentIdempotencyService(PaymentRequestFingerprint fingerprints, MeterRegistry meters) {
    this.fingerprints = fingerprints;
    this.meters = meters;
    Gauge.builder(MetricNames.IDEMPOTENCY_ENTRIES, entries, ConcurrentMap::size)
        .description("Reserved keys, including in-flight and failed attempts")
        .register(meters);
  }

  public UUID execute(String key, PostPaymentRequest request, Supplier<UUID> processPayment) {
    if (key == null) {
      recordDecision("NO_KEY");
      return processPayment.get();
    }
    if (key.isBlank()) {
      throw new InvalidIdempotencyKeyException();
    }

    String fingerprint = fingerprints.create(request);
    Entry candidate = new Entry(fingerprint, new CompletableFuture<>());
    Entry existing = entries.putIfAbsent(key, candidate);
    if (existing != null) {
      if (!existing.fingerprint().equals(fingerprint)) {
        recordDecision("CONFLICT");
        throw new IdempotencyConflictException();
      }
      recordDecision("REPLAY");
      return existing.result().join().paymentIdOrThrow();
    }

    // Only the reserving caller executes the bank operation. No map lock spans the call.
    recordDecision("NEW");
    return processOnce(candidate, processPayment);
  }

  private void recordDecision(String decision) {
    meters.counter(MetricNames.IDEMPOTENCY_REQUESTS, "decision", decision).increment();
    LOG.debug("idempotency_decision decision={}", decision);
  }

  private UUID processOnce(Entry entry, Supplier<UUID> processPayment) {
    try {
      UUID paymentId = processPayment.get();
      entry.result().complete(new Outcome(paymentId, null));
      return paymentId;
    } catch (BankProcessingException exception) {
      // Never retain exception causes: upstream messages can contain sensitive request data.
      entry.result().complete(new Outcome(null, exception.getCode()));
      throw exception;
    } catch (RuntimeException | Error exception) {
      // Unblock waiters without retrying a potentially completed bank operation.
      entry.result().complete(new Outcome(null, null));
      throw exception;
    }
  }

  private record Entry(String fingerprint, CompletableFuture<Outcome> result) {
  }

  private record Outcome(UUID paymentId, BankErrorCode bankFailure) {

    private UUID paymentIdOrThrow() {
      if (paymentId != null) {
        return paymentId;
      }
      if (bankFailure == null) {
        throw new IllegalStateException("Original payment attempt failed; outcome is unknown");
      }
      throw switch (bankFailure) {
        case BANK_TIMEOUT -> new BankTimeoutException();
        case BANK_UNAVAILABLE -> new BankUnavailableException();
        case BANK_INVALID_RESPONSE -> new InvalidBankResponseException();
      };
    }
  }
}
