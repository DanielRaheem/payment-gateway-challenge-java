package com.checkout.payment.gateway.bank.support;

import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.checkout.payment.gateway.bank.enums.BankErrorCode;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ResponseCreator;

/** Shared upstream fixtures for bank-client translation and public HTTP contract tests. */
public enum BankFailureScenario {
  UNAVAILABLE(503, BankErrorCode.BANK_UNAVAILABLE),
  CONNECTION_REFUSED(503, BankErrorCode.BANK_UNAVAILABLE),
  TIMEOUT(504, BankErrorCode.BANK_TIMEOUT),
  BANK_BAD_REQUEST(502, BankErrorCode.BANK_INVALID_RESPONSE),
  BANK_NOT_FOUND(502, BankErrorCode.BANK_INVALID_RESPONSE),
  BANK_SERVER_ERROR(502, BankErrorCode.BANK_INVALID_RESPONSE),
  EMPTY_BODY(502, BankErrorCode.BANK_INVALID_RESPONSE),
  MALFORMED_JSON(502, BankErrorCode.BANK_INVALID_RESPONSE),
  MISSING_AUTHORIZED(502, BankErrorCode.BANK_INVALID_RESPONSE),
  NULL_AUTHORIZED(502, BankErrorCode.BANK_INVALID_RESPONSE),
  STRING_AUTHORIZED(502, BankErrorCode.BANK_INVALID_RESPONSE),
  NUMBER_AUTHORIZED(502, BankErrorCode.BANK_INVALID_RESPONSE),
  ARRAY_BODY(502, BankErrorCode.BANK_INVALID_RESPONSE);

  // Deliberately unsafe upstream content must never appear in public responses.
  public static final String SENSITIVE_BODY =
      "card_number=2222405343240001 cvv=987 internal-bank-debug";

  private final int httpStatus;
  private final BankErrorCode code;

  BankFailureScenario(int httpStatus, BankErrorCode code) {
    this.httpStatus = httpStatus;
    this.code = code;
  }

  public int httpStatus() {
    return httpStatus;
  }

  public BankErrorCode code() {
    return code;
  }

  public ResponseCreator response() {
    return switch (this) {
      case UNAVAILABLE -> withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(SENSITIVE_BODY);
      case CONNECTION_REFUSED -> withException(new ConnectException(SENSITIVE_BODY));
      case TIMEOUT -> withException(new SocketTimeoutException(SENSITIVE_BODY));
      case BANK_BAD_REQUEST -> withStatus(HttpStatus.BAD_REQUEST).body(SENSITIVE_BODY);
      case BANK_NOT_FOUND -> withStatus(HttpStatus.NOT_FOUND).body(SENSITIVE_BODY);
      case BANK_SERVER_ERROR -> withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body(SENSITIVE_BODY);
      case EMPTY_BODY -> withSuccess("", MediaType.APPLICATION_JSON);
      case MALFORMED_JSON -> withSuccess(SENSITIVE_BODY, MediaType.APPLICATION_JSON);
      case MISSING_AUTHORIZED -> withSuccess("{}", MediaType.APPLICATION_JSON);
      case NULL_AUTHORIZED -> withSuccess("{\"authorized\":null}", MediaType.APPLICATION_JSON);
      case STRING_AUTHORIZED -> withSuccess("{\"authorized\":\"false\"}", MediaType.APPLICATION_JSON);
      case NUMBER_AUTHORIZED -> withSuccess("{\"authorized\":0}", MediaType.APPLICATION_JSON);
      case ARRAY_BODY -> withSuccess("[]", MediaType.APPLICATION_JSON);
    };
  }
}
