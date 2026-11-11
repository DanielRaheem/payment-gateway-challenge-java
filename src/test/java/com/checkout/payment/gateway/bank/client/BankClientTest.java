package com.checkout.payment.gateway.bank.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

import com.checkout.payment.gateway.bank.exception.BankProcessingException;
import com.checkout.payment.gateway.bank.support.BankFailureScenario;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.checkout.payment.gateway.bank.model.BankPaymentRequest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class BankClientTest {
  private BankClient client;
  private MockRestServiceServer bank;

  @BeforeEach
  void bindBank() {
    RestTemplate template = new RestTemplateBuilder()
        .rootUri("http://bank-failure.test").build();
    bank = MockRestServiceServer.bindTo(template).build();
    client = new BankClient(template,
        new BankResponseMapper(),
        new SimpleMeterRegistry());
  }

  @AfterEach
  void verifySingleBankCall() {
    bank.verify();
  }

  @ParameterizedTest
  @EnumSource(BankFailureScenario.class)
  void translatesTransportAndResponseFailures(BankFailureScenario scenario) {
    bank.expect(requestTo("http://bank-failure.test/payments"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(scenario.response());

    BankPaymentRequest request =
        new BankPaymentRequest(
            "2222405343240001", "12/2099", "GBP", 1050, "987");

    assertThatThrownBy(() -> client.processPayment(request))
        .isInstanceOfSatisfying(BankProcessingException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(scenario.code());
          assertThat(exception.getMessage()).isEqualTo(scenario.code().getMessage())
              .doesNotContain("2222405343240001", "987", "internal-bank-debug");
        });
  }
}
