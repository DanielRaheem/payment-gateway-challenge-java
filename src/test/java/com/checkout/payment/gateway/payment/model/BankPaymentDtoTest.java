package com.checkout.payment.gateway.payment.model;

import com.checkout.payment.gateway.payment.mapping.BankPaymentRequestMapper;

import com.checkout.payment.gateway.bank.model.BankPaymentRequest;
import com.checkout.payment.gateway.bank.model.BankPaymentResponse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class BankPaymentDtoTest {

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void mapsValidatedRequestToExactBankPayload() throws Exception {
    BankPaymentRequest request = BankPaymentRequestMapper.from(
        new PostPaymentRequest("00000000000001", 4, 2099, "GBP", 1050, "012"));

    assertThat(objectMapper.readTree(objectMapper.writeValueAsString(request)))
        .isEqualTo(objectMapper.readTree("""
            {
              "card_number": "00000000000001",
              "expiry_date": "04/2099",
              "currency": "GBP",
              "amount": 1050,
              "cvv": "012"
            }
            """));
  }

  @Test
  void requestDescriptionRedactsCardNumberAndCvv() {
    BankPaymentRequest request = new BankPaymentRequest(
        "2222405343240001", "04/2099", "GBP", 1050, "987");

    assertThat(request.toString()).contains("REDACTED")
        .doesNotContain("2222405343240001", "987");
  }

  @Test
  void readsAuthorizedBankResponse() throws Exception {
    BankPaymentResponse response = objectMapper.readValue("""
        {"authorized": true, "authorization_code": "bank-code"}
        """, BankPaymentResponse.class);

    assertThat(response).isEqualTo(new BankPaymentResponse(true, "bank-code"));
  }

  @Test
  void distinguishesDeclineFromMissingOutcome() throws Exception {
    BankPaymentResponse declined = objectMapper.readValue("""
        {"authorized": false, "authorization_code": ""}
        """, BankPaymentResponse.class);
    BankPaymentResponse missing = objectMapper.readValue("{}", BankPaymentResponse.class);

    assertThat(declined.authorized()).isFalse();
    assertThat(declined.authorizationCode()).isEmpty();
    assertThat(missing.authorized()).isNull();
  }
}
