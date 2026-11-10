package com.checkout.payment.gateway.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.payment.enums.PaymentStatus;
import com.checkout.payment.gateway.payment.model.PaymentResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PaymentsRepositoryConcurrencyTest {

  @Test
  void concurrentWritesAndReadsRetainEveryPayment() throws Exception {
    PaymentsRepository repository = new PaymentsRepository();
    int workers = 8;
    int paymentsPerWorker = 1000;
    List<List<PaymentResponse>> batches = new ArrayList<>();
    for (int worker = 0; worker < workers; worker++) {
      List<PaymentResponse> batch = new ArrayList<>();
      for (int index = 0; index < paymentsPerWorker; index++) {
        batch.add(new PaymentResponse(UUID.randomUUID(), PaymentStatus.AUTHORIZED,
            "0001", 12, 2099, "GBP", index));
      }
      batches.add(batch);
    }

    ExecutorService executor = Executors.newFixedThreadPool(workers);
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<PaymentResponse>> futures = new ArrayList<>();
    try {
      for (List<PaymentResponse> batch : batches) {
        futures.add(executor.submit(() -> {
          ready.countDown();
          assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
          for (PaymentResponse payment : batch) {
            repository.add(payment);
            assertThat(repository.get(payment.id())).contains(payment);
          }
          return null;
        }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<PaymentResponse> future : futures) {
        future.get(15, TimeUnit.SECONDS);
      }
      for (List<PaymentResponse> batch : batches) {
        for (PaymentResponse payment : batch) {
          assertThat(repository.get(payment.id())).contains(payment);
        }
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
