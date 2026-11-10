package com.checkout.payment.gateway.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

import com.checkout.payment.gateway.bank.exception.BankProcessingException;
import com.checkout.payment.gateway.payment.model.PostPaymentRequest;
import com.checkout.payment.gateway.payment.repository.PaymentsRepository;
import com.checkout.payment.gateway.bank.support.BankFailureScenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "bank.base-url=http://bank-failure.test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BankFailureServiceTest {

  @Autowired
  private PaymentGatewayService service;
  @Autowired
  private RestTemplate restTemplate;
  @MockBean
  private PaymentsRepository repository;
  private MockRestServiceServer bank;

  @BeforeEach
  void bindBank() {
    bank = MockRestServiceServer.bindTo(restTemplate).build();
  }

  @AfterEach
  void verifyNoPersistenceOrRetries() {
    try {
      // Each test permits exactly one bank call; retries fail the expectation.
      bank.verify();
    } finally {
      verifyNoInteractions(repository);
      bank.reset();
    }
  }

  @ParameterizedTest
  @EnumSource(BankFailureScenario.class)
  void translatesBankFailureWithoutSavingPayment(BankFailureScenario scenario) {
    bank.expect(requestTo("http://bank-failure.test/payments"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(scenario.response());

    PostPaymentRequest request = new PostPaymentRequest(
        "2222405343240001", 12, 2099, "GBP", 1050, "987");

    assertThatThrownBy(() -> service.processPayment(request))
        .isInstanceOfSatisfying(BankProcessingException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo(scenario.code());
          assertThat(exception.getMessage()).isEqualTo(scenario.code().getMessage())
              .doesNotContain("2222405343240001", "987", "internal-bank-debug");
        });
  }
}
