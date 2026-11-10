package com.checkout.payment.gateway.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.payment.repository.PaymentsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

/**
 * Red-phase acceptance tests for processing: POST /payment creates a result (201),
 * or rejects invalid input (400). These HTTP choices are our proposed API contract.
 * Only bank HTTP transport is substituted; controller and service remain real.
 */
@SpringBootTest(properties = "bank.base-url=http://bank-simulator.test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentProcessingTest {

  @Autowired
  private MockMvc mvc;
  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private RestTemplate restTemplate;
  @SpyBean
  private PaymentsRepository paymentsRepository;

  private MockRestServiceServer bank;

  @BeforeEach
  void bindBankTransport() {
    bank = MockRestServiceServer.bindTo(restTemplate).build();
  }

  @AfterEach
  void verifyBankCalls() {
    // With no registered expectations, any bank call from a rejection test fails.
    bank.verify();
    bank.reset();
  }

  @ParameterizedTest(name = "card ending {0} produces {2}")
  @CsvSource({
      "0001, true, Authorized",
      "0002, false, Declined"
  })
  void returnsBankOutcomeAndMaskedPaymentDetails(String suffix, boolean authorized,
      String expectedStatus) throws Exception {
    String cardNumber = "222240534324" + suffix;
    ObjectNode request = validRequest();
    request.put("card_number", cardNumber);

    ObjectNode bankRequest = objectMapper.createObjectNode();
    bankRequest.put("card_number", cardNumber);
    bankRequest.put("expiry_date", "12/2099");
    bankRequest.put("currency", "GBP");
    bankRequest.put("amount", 1050);
    bankRequest.put("cvv", "012");

    // Same authorized/declined payloads as the supplied Docker simulator.
    String bankAuthorizationCode = authorized ? "7e54281a-32c0-4f7e-8a61-f8095d8c5a10" : "";
    ObjectNode bankResponse = objectMapper.createObjectNode();
    bankResponse.put("authorized", authorized);
    bankResponse.put("authorization_code", bankAuthorizationCode);
    bank.expect(requestTo("http://bank-simulator.test/payments"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json(bankRequest.toString(), true))
        .andRespond(withSuccess(bankResponse.toString(), MediaType.APPLICATION_JSON));

    String body = mvc.perform(post("/payment")
            .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isString())
        .andExpect(jsonPath("$.status").value(expectedStatus))
        .andExpect(jsonPath("$.cardNumberLastFour").value(suffix))
        .andExpect(jsonPath("$.expiryMonth").value(12))
        .andExpect(jsonPath("$.expiryYear").value(2099))
        .andExpect(jsonPath("$.currency").value("GBP"))
        .andExpect(jsonPath("$.amount").value(1050))
        .andExpect(jsonPath("$.cardNumber").doesNotExist())
        .andExpect(jsonPath("$.card_number").doesNotExist())
        .andExpect(jsonPath("$.cvv").doesNotExist())
        .andReturn().getResponse().getContentAsString();

    JsonNode response = objectMapper.readTree(body);
    UUID paymentId = UUID.fromString(response.get("id").asText());
    assertThat(paymentId.toString()).isNotEqualTo(bankAuthorizationCode);
    assertThat(body).doesNotContain(cardNumber);
  }

  @ParameterizedTest
  @ValueSource(strings = {"card_number", "expiry_month", "expiry_year", "currency", "amount", "cvv"})
  void rejectsMissingRequiredFieldWithoutCallingBankOrSavingPayment(String field) throws Exception {
    ObjectNode request = validRequest();
    request.remove(field);
    assertRejected(request.toString());
  }

  @ParameterizedTest
  @CsvSource({
      "card_number, 1234",
      "expiry_month, 13",
      "expiry_year, 2000",
      "currency, JPY",
      "cvv, 12"
  })
  void rejectsInvalidFieldWithoutCallingBankOrSavingPayment(String field, String value)
      throws Exception {
    ObjectNode request = validRequest();
    if (field.startsWith("expiry_")) {
      request.put(field, Integer.parseInt(value));
    } else {
      request.put(field, value);
    }
    assertRejected(request.toString());
  }

  @Test
  void rejectsFractionalAmountWithoutCallingBankOrSavingPayment() throws Exception {
    ObjectNode request = validRequest();
    request.put("amount", 10.5);
    assertRejected(request.toString());
  }

  @Test
  void rejectsMalformedJsonWithoutCallingBankOrSavingPayment() throws Exception {
    assertRejected("{invalid json}");
  }

  private void assertRejected(String request) throws Exception {
    mvc.perform(post("/payment").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("Rejected"))
        .andExpect(jsonPath("$.id").doesNotExist())
        .andExpect(jsonPath("$.card_number").doesNotExist())
        .andExpect(jsonPath("$.cvv").doesNotExist());
    verify(paymentsRepository, never()).add(any());
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
