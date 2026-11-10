package com.checkout.payment.gateway.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.checkout.payment.gateway.bank.client.BankClient;
import com.checkout.payment.gateway.bank.exception.BankProcessingException;
import com.checkout.payment.gateway.bank.exception.BankTimeoutException;
import com.checkout.payment.gateway.bank.exception.BankUnavailableException;
import com.checkout.payment.gateway.bank.exception.InvalidBankResponseException;
import com.checkout.payment.gateway.bank.model.BankPaymentResponse;
import com.checkout.payment.gateway.payment.repository.PaymentsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real payment service/repository; bank substitution isolates idempotency from transport. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentIdempotencyTest {

  @Autowired
  private MockMvc mvc;
  @Autowired
  private ObjectMapper objectMapper;
  @MockBean
  private BankClient bankClient;
  @SpyBean
  private PaymentsRepository repository;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void replaysCompletedOutcomeWithoutAnotherBankCallOrWrite(boolean authorized) throws Exception {
    when(bankClient.processPayment(any())).thenReturn(new BankPaymentResponse(authorized, "code"));
    String key = UUID.randomUUID().toString();
    String request = validRequest().toString();
    MockHttpServletResponse first = submit(key, request);
    MockHttpServletResponse replay = submit(key, request);

    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(replay.getStatus()).isEqualTo(201);
    JsonNode result = objectMapper.readTree(first.getContentAsString());
    assertThat(result.path("status").asText()).isEqualTo(authorized ? "Authorized" : "Declined");
    assertThat(objectMapper.readTree(replay.getContentAsString())).isEqualTo(result);
    UUID id = UUID.fromString(result.path("id").asText());
    assertThat(repository.get(id)).isPresent();
    verify(bankClient, times(1)).processPayment(any());
    verify(repository, times(1)).add(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"card_number", "expiry_month", "expiry_year", "currency", "amount", "cvv"})
  void changedPaymentDetailsConflictWithExistingKey(String field) throws Exception {
    when(bankClient.processPayment(any())).thenReturn(new BankPaymentResponse(true, "code"));
    String key = UUID.randomUUID().toString();
    ObjectNode request = validRequest();
    MockHttpServletResponse first = submit(key, request.toString());
    switch (field) {
      // Same suffix: a comparison of only masked card details is insufficient.
      case "card_number" -> request.put(field, "3333405343240001");
      case "expiry_month" -> request.put(field, 11);
      case "expiry_year" -> request.put(field, 2098);
      case "currency" -> request.put(field, "USD");
      case "amount" -> request.put(field, 200);
      case "cvv" -> request.put(field, "456");
      default -> throw new IllegalArgumentException(field);
    }
    MockHttpServletResponse conflict = submit(key, request.toString());

    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(conflict.getStatus()).isEqualTo(409);
    assertThat(objectMapper.readTree(conflict.getContentAsString()))
        .isEqualTo(objectMapper.readTree("""
            {"code":"IDEMPOTENCY_CONFLICT",
             "message":"Idempotency key has already been used for a different request"}
            """));
    verify(bankClient, times(1)).processPayment(any());
    verify(repository, times(1)).add(any());
  }

  @Test
  void differentKeysCreateIndependentPayments() throws Exception {
    when(bankClient.processPayment(any())).thenReturn(new BankPaymentResponse(true, "code"));
    String request = validRequest().toString();
    MockHttpServletResponse first = submit(UUID.randomUUID().toString(), request);
    MockHttpServletResponse second = submit(UUID.randomUUID().toString(), request);
    assertDistinctPayments(first, second);
    verify(bankClient, times(2)).processPayment(any());
    verify(repository, times(2)).add(any());
  }

  @Test
  void requestsWithoutKeysKeepExistingBehavior() throws Exception {
    when(bankClient.processPayment(any())).thenReturn(new BankPaymentResponse(true, "code"));
    String request = validRequest().toString();
    assertDistinctPayments(submit(null, request), submit(null, request));
    verify(bankClient, times(2)).processPayment(any());
  }

  @Test
  void validationFailureDoesNotReserveKey() throws Exception {
    when(bankClient.processPayment(any())).thenReturn(new BankPaymentResponse(true, "code"));
    String key = UUID.randomUUID().toString();
    ObjectNode invalid = validRequest();
    invalid.remove("amount");
    assertThat(submit(key, invalid.toString()).getStatus()).isEqualTo(400);
    assertThat(submit(key, validRequest().toString()).getStatus()).isEqualTo(201);
    verify(bankClient, times(1)).processPayment(any());
    verify(repository, times(1)).add(any());
  }

  @ParameterizedTest
  @MethodSource("bankFailures")
  void replaysBankFailureWithoutResubmittingUncertainPayment(BankProcessingException failure,
      int status) throws Exception {
    when(bankClient.processPayment(any())).thenThrow(failure);
    String key = UUID.randomUUID().toString();
    String request = validRequest().toString();
    MockHttpServletResponse first = submit(key, request);
    MockHttpServletResponse replay = submit(key, request);
    assertThat(first.getStatus()).isEqualTo(status);
    assertThat(replay.getStatus()).isEqualTo(status);
    assertThat(objectMapper.readTree(replay.getContentAsString()))
        .isEqualTo(objectMapper.readTree(first.getContentAsString()));
    verify(bankClient, times(1)).processPayment(any());
    verify(repository, times(0)).add(any());
  }

  static Stream<Arguments> bankFailures() {
    return Stream.of(Arguments.of(new BankTimeoutException(), 504),
        Arguments.of(new BankUnavailableException(), 503),
        Arguments.of(new InvalidBankResponseException(), 502));
  }

  @Test
  void concurrentRequestsWithSameKeyShareOnePayment() throws Exception {
    int workers = 6;
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch bankEntered = new CountDownLatch(1);
    CountDownLatch releaseBank = new CountDownLatch(1);
    when(bankClient.processPayment(any())).thenAnswer(invocation -> {
      bankEntered.countDown();
      assertThat(releaseBank.await(5, TimeUnit.SECONDS)).isTrue();
      return new BankPaymentResponse(true, "code");
    });
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    String key = UUID.randomUUID().toString();
    String request = validRequest().toString();
    List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < workers; i++) {
        futures.add(executor.submit(() -> {
          ready.countDown();
          assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
          return submit(key, request);
        }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(bankEntered.await(5, TimeUnit.SECONDS)).isTrue();
      releaseBank.countDown();
      List<JsonNode> results = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        MockHttpServletResponse response = future.get(10, TimeUnit.SECONDS);
        assertThat(response.getStatus()).isEqualTo(201);
        results.add(objectMapper.readTree(response.getContentAsString()));
      }
      assertThat(results).allMatch(result -> result.equals(results.get(0)));
      verify(bankClient, times(1)).processPayment(any());
      verify(repository, times(1)).add(any());
    } finally {
      start.countDown();
      releaseBank.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void distinctKeysCanReachBankConcurrently() throws Exception {
    CountDownLatch bothAtBank = new CountDownLatch(2);
    when(bankClient.processPayment(any())).thenAnswer(invocation -> {
      bothAtBank.countDown();
      assertThat(bothAtBank.await(5, TimeUnit.SECONDS))
          .as("unrelated keys must not share a lock across the bank call").isTrue();
      return new BankPaymentResponse(true, "code");
    });
    ExecutorService executor = Executors.newFixedThreadPool(2);
    String request = validRequest().toString();
    try {
      Future<MockHttpServletResponse> first = executor.submit(
          () -> submit(UUID.randomUUID().toString(), request));
      Future<MockHttpServletResponse> second = executor.submit(
          () -> submit(UUID.randomUUID().toString(), request));
      assertDistinctPayments(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      verify(bankClient, times(2)).processPayment(any());
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  void rejectsBlankIdempotencyKeyWithoutCallingBank(
      String key) throws Exception {

    MockHttpServletResponse response =
        submit(key, validRequest().toString());

    assertThat(response.getStatus()).isEqualTo(400);

    assertThat(objectMapper.readTree(
        response.getContentAsString()
    )).isEqualTo(objectMapper.readTree("""
        {
          "status": "Rejected",
          "message": "Invalid payment request"
        }
        """));

    verifyNoInteractions(bankClient);
    verify(repository, never()).add(any());
  }

  private MockHttpServletResponse submit(String key, String body) throws Exception {
    MockHttpServletRequestBuilder request = post("/payment")
        .contentType(MediaType.APPLICATION_JSON).content(body);
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private void assertDistinctPayments(MockHttpServletResponse first,
      MockHttpServletResponse second) throws Exception {
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(second.getStatus()).isEqualTo(201);
    UUID firstId = UUID.fromString(objectMapper.readTree(first.getContentAsString()).path("id").asText());
    UUID secondId = UUID.fromString(objectMapper.readTree(second.getContentAsString()).path("id").asText());
    assertThat(firstId).isNotEqualTo(secondId);
  }

  private ObjectNode validRequest() {
    ObjectNode request = objectMapper.createObjectNode();
    request.put("card_number", "2222405343240001");
    request.put("expiry_month", 12);
    request.put("expiry_year", 2099);
    request.put("currency", "GBP");
    request.put("amount", 1050);
    request.put("cvv", "012");
    return request;
  }
}
