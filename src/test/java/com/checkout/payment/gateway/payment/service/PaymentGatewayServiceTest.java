package com.checkout.payment.gateway.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.checkout.payment.gateway.payment.enums.PaymentStatus;
import com.checkout.payment.gateway.payment.model.PostPaymentRequest;
import com.checkout.payment.gateway.payment.model.PaymentResponse;
import com.checkout.payment.gateway.payment.repository.PaymentsRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Service-boundary tests: no controller or Docker dependency. The service is real;
 * repository writes and bank HTTP calls are observed through test doubles.
 * Inputs are validated at the controller boundary, not by this service.
 * Keep the existing UUID return contract until explicitly changed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "bank.base-url=http://bank-simulator.test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentGatewayServiceTest {

  @Autowired
  private PaymentGatewayService service;
  @Autowired
  private RestTemplate restTemplate;
  @MockBean
  private PaymentsRepository repository;

  private MockRestServiceServer bank;

  @BeforeEach
  void bindBankTransport() {
    bank = MockRestServiceServer.bindTo(restTemplate).build();
  }

  @AfterEach
  void verifyBankCalls() {
    bank.verify();
    bank.reset();
  }

  @ParameterizedTest
  @CsvSource({"0001, true, AUTHORIZED", "0002, false, DECLINED"})
  void savesBankOutcomeWithMaskedDetailsAndReturnsItsGatewayId(String suffix,
      boolean authorized, PaymentStatus expectedStatus) {
    String cardNumber = "222240534324" + suffix;
    PostPaymentRequest request = new PostPaymentRequest(cardNumber, 12, 2099, "GBP", 1050, "012");
    String authorizationCode = authorized ? "7e54281a-32c0-4f7e-8a61-f8095d8c5a10" : "";

    bank.expect(requestTo("http://bank-simulator.test/payments"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("""
            {
              "card_number": "%s",
              "expiry_date": "12/2099",
              "currency": "GBP",
              "amount": 1050,
              "cvv": "012"
            }
            """.formatted(cardNumber), true))
        .andRespond(withSuccess("""
            {"authorized": %s, "authorization_code": "%s"}
            """.formatted(authorized, authorizationCode), MediaType.APPLICATION_JSON));

    UUID paymentId = service.processPayment(request);

    ArgumentCaptor<PaymentResponse> saved = ArgumentCaptor.forClass(PaymentResponse.class);
    verify(repository).add(saved.capture());
    verifyNoMoreInteractions(repository);
    assertThat(paymentId).isNotNull();
    assertThat(paymentId.toString()).isNotEqualTo(authorizationCode);
    assertThat(saved.getValue()).isEqualTo(new PaymentResponse(
        paymentId, expectedStatus, suffix, 12, 2099, "GBP", 1050));
  }

}
