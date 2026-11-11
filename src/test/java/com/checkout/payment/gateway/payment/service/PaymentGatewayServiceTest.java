package com.checkout.payment.gateway.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.bank.client.BankClient;
import com.checkout.payment.gateway.bank.exception.BankProcessingException;
import com.checkout.payment.gateway.bank.exception.BankUnavailableException;
import com.checkout.payment.gateway.bank.exception.BankTimeoutException;
import com.checkout.payment.gateway.bank.exception.InvalidBankResponseException;
import com.checkout.payment.gateway.bank.model.BankPaymentRequest;
import com.checkout.payment.gateway.bank.model.BankPaymentResponse;
import com.checkout.payment.gateway.payment.enums.PaymentStatus;
import com.checkout.payment.gateway.payment.exception.EventProcessingException;
import com.checkout.payment.gateway.payment.idempotency.PaymentIdempotencyService;
import com.checkout.payment.gateway.payment.model.PostPaymentRequest;
import com.checkout.payment.gateway.payment.model.PaymentResponse;
import com.checkout.payment.gateway.payment.repository.PaymentsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentGatewayServiceTest {
  @Mock private BankClient bank;
  @Mock private PaymentsRepository repository;
  @Mock private PaymentIdempotencyService idempotency;
  private PaymentGatewayService service;
  private PostPaymentRequest request;
  private BankPaymentRequest bankRequest;

  @BeforeEach
  void setUp() {
    request = new PostPaymentRequest("2222405343240001", 12, 2099, "GBP", 1050, "012");
    bankRequest = new BankPaymentRequest("2222405343240001", "12/2099", "GBP", 1050, "012");
    service = new PaymentGatewayService(repository, bank, idempotency, new SimpleMeterRegistry());
  }

  private void allowNewAttempt() {
    // Coordination itself is tested separately; here execute the supplied service operation.
    when(idempotency.execute(isNull(), eq(request), any())).thenAnswer(invocation -> {
      Supplier<UUID> operation = invocation.getArgument(2);
      return operation.get();
    });
  }

  @ParameterizedTest
  @CsvSource({"true, AUTHORIZED", "false, DECLINED"})
  void savesMaskedBankOutcome(boolean authorized, PaymentStatus status) {
    allowNewAttempt();
    when(bank.processPayment(bankRequest)).thenReturn(new BankPaymentResponse(authorized, "bank-id"));
    UUID id = service.processPayment(request);
    ArgumentCaptor<PaymentResponse> saved = ArgumentCaptor.forClass(PaymentResponse.class);
    verify(repository).add(saved.capture());
    assertThat(saved.getValue()).isEqualTo(new PaymentResponse(id, status, "0001", 12, 2099, "GBP", 1050));
    assertThat(id).isNotNull();
    verify(bank).processPayment(bankRequest);
    verifyNoMoreInteractions(bank, repository);
  }

  static Stream<BankProcessingException> failures() {
    return Stream.of(new BankUnavailableException(), new BankTimeoutException(), new InvalidBankResponseException());
  }

  @ParameterizedTest
  @MethodSource("failures")
  void propagatesBankFailureWithoutSavingOrRetrying(BankProcessingException failure) {
    allowNewAttempt();
    when(bank.processPayment(bankRequest)).thenThrow(failure);
    assertThatThrownBy(() -> service.processPayment(request)).isSameAs(failure);
    verify(bank).processPayment(bankRequest);
    verifyNoMoreInteractions(bank);
    verifyNoInteractions(repository);
  }

  @Test
  void delegatesKeyAndReturnsExistingOutcomeWithoutProcessing() {
    UUID id = UUID.randomUUID();
    when(idempotency.execute(eq("key"), eq(request), any())).thenReturn(id);
    assertThat(service.processPayment(request, "key")).isEqualTo(id);
    verifyNoInteractions(bank, repository);
  }

  @Test
  void retrievesStoredPaymentWithoutCallingBank() {
    UUID id = UUID.randomUUID();
    PaymentResponse payment = new PaymentResponse(id, PaymentStatus.AUTHORIZED, "0001", 12, 2099, "GBP", 1050);
    when(repository.get(id)).thenReturn(Optional.of(payment));
    assertThat(service.getPaymentById(id)).isSameAs(payment);
    verifyNoInteractions(bank, idempotency);
  }

  @Test
  void missingPaymentThrowsWithoutCallingBank() {
    UUID id = UUID.randomUUID();
    when(repository.get(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getPaymentById(id)).isInstanceOf(EventProcessingException.class);
    verifyNoInteractions(bank, idempotency);
  }
}
