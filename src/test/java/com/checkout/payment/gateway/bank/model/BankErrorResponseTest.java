package com.checkout.payment.gateway.bank.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.bank.enums.BankErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class BankErrorResponseTest {

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void serializesOnlyStableCodeAndSafeMessage() throws Exception {
    BankErrorResponse response = new BankErrorResponse(
        BankErrorCode.BANK_TIMEOUT, BankErrorCode.BANK_TIMEOUT.getMessage());

    assertThat(objectMapper.readTree(objectMapper.writeValueAsString(response)))
        .isEqualTo(objectMapper.readTree("""
            {"code":"BANK_TIMEOUT","message":"Bank request timed out; payment outcome is unknown"}
            """));
  }
}
