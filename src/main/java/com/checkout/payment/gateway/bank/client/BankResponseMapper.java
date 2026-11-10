package com.checkout.payment.gateway.bank.client;

import com.checkout.payment.gateway.bank.exception.InvalidBankResponseException;
import com.checkout.payment.gateway.bank.model.BankPaymentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Checks the bank's JSON contract before creating a typed response. */
@Component
public class BankResponseMapper {

  public BankPaymentResponse map(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new InvalidBankResponseException();
    }
    return new BankPaymentResponse(readAuthorized(body), readAuthorizationCode(body));
  }

  private boolean readAuthorized(JsonNode body) {
    JsonNode authorized = body.path("authorized");
    // Reject strings and numbers rather than allowing Jackson's Boolean coercion.
    if (!authorized.isBoolean()) {
      throw new InvalidBankResponseException();
    }
    return authorized.booleanValue();
  }

  private String readAuthorizationCode(JsonNode body) {
    JsonNode code = body.path("authorization_code");
    if (code.isMissingNode() || code.isNull()) {
      return null;
    }
    if (!code.isTextual()) {
      throw new InvalidBankResponseException();
    }
    return code.textValue();
  }
}
