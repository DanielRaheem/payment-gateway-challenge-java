package com.checkout.payment.gateway.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

/** Exercises POST then GET through real controller, service, and repository instances. */
@SpringBootTest(properties = "bank.base-url=http://bank-round-trip.test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentRoundTripTest {

  @Autowired
  private MockMvc mvc;
  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private RestTemplate restTemplate;
  private MockRestServiceServer bank;

  @BeforeEach
  void bindBank() {
    bank = MockRestServiceServer.bindTo(restTemplate).build();
  }

  @AfterEach
  void verifyBankCalls() {
    // Exactly one bank request is allowed: retrieving a payment must not resubmit it.
    bank.verify();
    bank.reset();
  }

  @ParameterizedTest(name = "{2} payment is retrievable with its original details")
  @CsvSource({"0001, true, Authorized", "0002, false, Declined"})
  void retrievesSubmittedPaymentWithoutCallingBankAgain(String suffix, boolean authorized,
      String expectedStatus) throws Exception {
    String cardNumber = "222240534324" + suffix;
    String authorizationCode = authorized ? "7e54281a-32c0-4f7e-8a61-f8095d8c5a10" : "";
    bank.expect(requestTo("http://bank-round-trip.test/payments"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("""
            {"authorized": %s, "authorization_code": "%s"}
            """.formatted(authorized, authorizationCode), MediaType.APPLICATION_JSON));

    // No direct repository seeding: the POST must create everything needed for lookup.
    String postBody = mvc.perform(post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"card_number":"%s","expiry_month":4,"expiry_year":2099,
                 "currency":"EUR","amount":2750,"cvv":"012"}
                """.formatted(cardNumber)))
        .andExpect(status().isCreated())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andReturn().getResponse().getContentAsString();

    JsonNode created = objectMapper.readTree(postBody);
    UUID paymentId = UUID.fromString(created.path("id").asText());
    assertThat(paymentId.toString()).isNotEqualTo(authorizationCode);
    JsonNode expected = objectMapper.readTree("""
        {"id":"%s","status":"%s","cardNumberLastFour":"%s",
         "expiryMonth":4,"expiryYear":2099,"currency":"EUR","amount":2750}
        """.formatted(paymentId, expectedStatus, suffix));
    assertThat(created).isEqualTo(expected);

    // Repeated reads must preserve the result and remain independent of the bank.
    for (int attempt = 0; attempt < 2; attempt++) {
      String getBody = mvc.perform(get("/payment/{id}", paymentId))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andReturn().getResponse().getContentAsString();

      // Exact field comparison also excludes full card numbers, CVVs, and bank metadata.
      assertThat(objectMapper.readTree(getBody)).isEqualTo(expected).isEqualTo(created);
      assertThat(getBody).doesNotContain(cardNumber);
    }
  }
}
