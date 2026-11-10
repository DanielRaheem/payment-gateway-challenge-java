package com.checkout.payment.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.SocketTimeoutException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(properties = {"bank.base-url=http://observability-bank.test",
    "logging.level.com.checkout.payment.gateway=DEBUG"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest {

  @Autowired
  private MockMvc mvc;
  @Autowired
  private RestTemplate restTemplate;
  @Autowired
  private MeterRegistry meters;
  private MockRestServiceServer bank;

  @BeforeEach
  void bindBank() {
    bank = MockRestServiceServer.bindTo(restTemplate).build();
  }

  @AfterEach
  void verifyBankAndThreadContext() {
    bank.verify();
    bank.reset();
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void countsActualProcessingSeparatelyFromReplayConflictAndRejection() throws Exception {
    long bankBefore = timerCount("gateway.bank.requests", "AUTHORIZED");
    long paymentBefore = timerCount("gateway.payment.attempts", "AUTHORIZED");
    double newBefore = decision("NEW");
    double replayBefore = decision("REPLAY");
    double conflictBefore = decision("CONFLICT");
    double rejectedBefore = errorCount("INVALID_REQUEST");
    double entriesBefore = meters.get("gateway.idempotency.entries").gauge().value();
    String key = UUID.randomUUID().toString();
    bank.expect(requestTo("http://observability-bank.test/payments"))
        .andRespond(withSuccess("{\"authorized\":true}", MediaType.APPLICATION_JSON));

    assertThat(submit(key, payload(100)).getStatus()).isEqualTo(201);
    assertThat(submit(key, payload(100)).getStatus()).isEqualTo(201);
    assertThat(submit(key, payload(200)).getStatus()).isEqualTo(409);
    assertThat(submit(UUID.randomUUID().toString(), "{}").getStatus()).isEqualTo(400);

    assertThat(timerCount("gateway.bank.requests", "AUTHORIZED")).isEqualTo(bankBefore + 1);
    assertThat(timerCount("gateway.payment.attempts", "AUTHORIZED")).isEqualTo(paymentBefore + 1);
    assertThat(decision("NEW")).isEqualTo(newBefore + 1);
    assertThat(decision("REPLAY")).isEqualTo(replayBefore + 1);
    assertThat(decision("CONFLICT")).isEqualTo(conflictBefore + 1);
    assertThat(errorCount("INVALID_REQUEST")).isEqualTo(rejectedBefore + 1);
    assertThat(meters.get("gateway.idempotency.entries").gauge().value()).isEqualTo(entriesBefore + 1);
    assertThat(meters.get("gateway.bank.requests").tag("outcome", "AUTHORIZED").timer()
        .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();
  }

  @Test
  void countsDeclinesAsProcessedOutcomes() throws Exception {
    long before = timerCount("gateway.bank.requests", "DECLINED");
    long paymentBefore = timerCount("gateway.payment.attempts", "DECLINED");
    bank.expect(requestTo("http://observability-bank.test/payments"))
        .andRespond(withSuccess("{\"authorized\":false}", MediaType.APPLICATION_JSON));
    assertThat(submit(UUID.randomUUID().toString(), payload(100)).getStatus()).isEqualTo(201);
    assertThat(timerCount("gateway.bank.requests", "DECLINED")).isEqualTo(before + 1);
    assertThat(timerCount("gateway.payment.attempts", "DECLINED")).isEqualTo(paymentBefore + 1);
  }

  @Test
  void correlatesBankRequestAndCountsReplayedTimeoutWithoutLeakingData(CapturedOutput output)
      throws Exception {
    String correlationId = UUID.randomUUID().toString();
    String key = "private-idempotency-" + UUID.randomUUID();
    long before = timerCount("gateway.bank.requests", "BANK_TIMEOUT");
    double errorsBefore = errorCount("BANK_TIMEOUT");
    bank.expect(requestTo("http://observability-bank.test/payments"))
        .andExpect(header(CorrelationIdFilter.HEADER, correlationId))
        .andRespond(withException(new SocketTimeoutException(
            "upstream-secret card=2222405343240001 cvv=987")));

    MockHttpServletResponse first = mvc.perform(post("/payment")
            .header(CorrelationIdFilter.HEADER, correlationId).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(payload(100)))
        .andExpect(status().isGatewayTimeout()).andReturn().getResponse();
    MockHttpServletResponse replay = submit(key, payload(100));
    assertThat(first.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(correlationId);
    assertThat(replay.getStatus()).isEqualTo(504);
    assertThat(UUID.fromString(replay.getHeader(CorrelationIdFilter.HEADER))).isNotNull();
    assertThat(replay.getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo(correlationId);
    assertThat(timerCount("gateway.bank.requests", "BANK_TIMEOUT")).isEqualTo(before + 1);
    assertThat(errorCount("BANK_TIMEOUT")).isEqualTo(errorsBefore + 2);
    assertThat(output.getOut()).contains(correlationId, "BANK_TIMEOUT", "duration_ms=",
            "bank_request_completed outcome=BANK_TIMEOUT",
            "idempotency_decision decision=NEW", "idempotency_decision decision=REPLAY")
        .doesNotContain("2222405343240001", "cvv=987", "upstream-secret", key);
    meters.getMeters().stream().filter(m -> m.getId().getName().startsWith("gateway."))
        .forEach(m -> assertThat(m.getId().getTags()).allMatch(tag ->
            java.util.Set.of("application", "outcome", "decision", "code").contains(tag.getKey())));
  }

  @Test
  void replacesUnsafeCorrelationHeaderAndDoesNotLogQueryOrBody(CapturedOutput output)
      throws Exception {
    MockHttpServletResponse response = mvc.perform(post("/payment?secret=query-secret")
            .header(CorrelationIdFilter.HEADER, "unsafe-header-secret")
            .contentType(MediaType.APPLICATION_JSON).content("{body-secret"))
        .andExpect(status().isBadRequest()).andReturn().getResponse();
    assertThat(UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER))).isNotNull();
    assertThat(output.getOut()).doesNotContain("query-secret", "unsafe-header-secret", "body-secret");
  }

  @Test
  void restoresExistingThreadContextAndReportsLookupMiss() throws Exception {
    double before = errorCount("PAYMENT_NOT_FOUND");
    MDC.put(CorrelationIdFilter.MDC_KEY, "outer-context");
    try {
      mvc.perform(get("/payment/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
      assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("outer-context");
    } finally {
      MDC.remove(CorrelationIdFilter.MDC_KEY);
    }
    assertThat(errorCount("PAYMENT_NOT_FOUND")).isEqualTo(before + 1);
  }

  @Test
  void exposesHealthProbesInfoAndPrometheusWithoutCallingBank() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    mvc.perform(get("/actuator/info")).andExpect(status().isOk())
        .andExpect(jsonPath("$.app.name").value("payment-gateway"));
    mvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
    String scrape = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(scrape).contains("jvm_memory_used_bytes", "gateway_idempotency_entries");
    mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
  }

  @Test
  void countsRequestsWithoutIdempotencyKey() throws Exception {
    double before = decision("NO_KEY");
    bank.expect(requestTo("http://observability-bank.test/payments"))
        .andRespond(withSuccess("{\"authorized\":true}", MediaType.APPLICATION_JSON));
    mvc.perform(post("/payment").contentType(MediaType.APPLICATION_JSON).content(payload(100)))
        .andExpect(status().isCreated());
    assertThat(decision("NO_KEY")).isEqualTo(before + 1);
    assertThat(meters.find("http.server.requests").tag("uri", "/payment").timers()).isNotEmpty();
  }

  @Test
  void clearsCorrelationContextWhenUnhandledExceptionEscapes() {
    CorrelationIdFilter filter = new CorrelationIdFilter();
    org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest("POST", "/payment");
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertThatThrownBy(() -> filter.doFilter(request, response, (incoming, outgoing) -> {
      assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotBlank();
      throw new jakarta.servlet.ServletException("test failure");
    })).isInstanceOf(jakarta.servlet.ServletException.class);
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    assertThat(UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER))).isNotNull();
  }

  private MockHttpServletResponse submit(String key, String body) throws Exception {
    return mvc.perform(post("/payment").header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
  }

  private String payload(int amount) {
    return """
        {"card_number":"2222405343240001","expiry_month":12,"expiry_year":2099,
         "currency":"GBP","amount":%d,"cvv":"987"}
        """.formatted(amount);
  }

  private long timerCount(String name, String outcome) {
    Timer timer = meters.find(name).tag("outcome", outcome).timer();
    return timer == null ? 0 : timer.count();
  }

  private double decision(String decision) {
    Counter counter = meters.find("gateway.idempotency.requests").tag("decision", decision).counter();
    return counter == null ? 0 : counter.count();
  }

  private double errorCount(String code) {
    Counter counter = meters.find("gateway.payment.errors").tag("code", code).counter();
    return counter == null ? 0 : counter.count();
  }
}
