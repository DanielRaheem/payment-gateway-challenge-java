package com.checkout.payment.gateway.payment.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class PostPaymentRequestTest {

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void requestRoundTripPreservesJsonNamesAndLeadingZeroes() throws Exception {
    PostPaymentRequest request = objectMapper.readValue("""
        {
          "card_number": "00000000000001",
          "expiry_month": 4,
          "expiry_year": 2030,
          "currency": "GBP",
          "amount": 100,
          "cvv": "012"
        }
        """, PostPaymentRequest.class);

    assertThat(request).isEqualTo(new PostPaymentRequest("00000000000001", 4, 2030, "GBP", 100, "012"));
    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));
    assertThat(json.get("card_number").asText()).isEqualTo("00000000000001");
    assertThat(json.get("cvv").asText()).isEqualTo("012");
    assertThat(json.get("expiry_month").asInt()).isEqualTo(4);
    assertThat(json.get("expiry_year").asInt()).isEqualTo(2030);
    assertThat(json.has("expiry_date")).isFalse();
    assertThat(json.has("cardNumberLastFour")).isFalse();
    assertThat(json.has("expiryMonth")).isFalse();
  }

  @Test
  void omittedNumericFieldsRemainNullRatherThanDefaultingToZero() throws Exception {
    PostPaymentRequest request = objectMapper.readValue("{}", PostPaymentRequest.class);

    assertThat(request.expiryMonth()).isNull();
    assertThat(request.expiryYear()).isNull();
    assertThat(request.amount()).isNull();
    assertThat(objectMapper.readTree(objectMapper.writeValueAsString(request))
        .has("expiry_date")).isFalse();
  }

  @Test
  void fractionalAmountCannotBeSilentlyTruncated() {
    assertThatThrownBy(() -> objectMapper.readValue("{\"amount\": 10.5}",
        PostPaymentRequest.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
  }

  @Test
  void requestDescriptionDoesNotExposeCardDetails() {
    PostPaymentRequest request = new PostPaymentRequest("12345678904321", 4, 2030, "GBP", 100, "987");

    assertThat(request.toString()).doesNotContain("12345678904321", "987").contains("REDACTED");
  }
}
