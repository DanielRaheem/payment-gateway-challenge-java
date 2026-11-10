package com.checkout.payment.gateway.payment.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.payment.enums.PaymentStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentValidationTest {

  private static final ValidatorFactory FACTORY = Validation.byDefaultProvider().configure()
      .clockProvider(() -> Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC))
      .buildValidatorFactory();
  private static final Validator VALIDATOR = FACTORY.getValidator();

  @AfterAll
  static void closeFactory() {
    FACTORY.close();
  }

  @ParameterizedTest
  @MethodSource("invalidRequests")
  void rejectsInvalidFields(PostPaymentRequest request, String property) {
    assertThat(VALIDATOR.validate(request))
        .anyMatch(v -> v.getPropertyPath().toString().equals(property));
  }

  static Stream<Arguments> invalidRequests() {
    Stream.Builder<Arguments> cases = Stream.builder();
    for (String card : new String[]{null, "", "1".repeat(13), "1".repeat(20),
        "1234567890123a", "1234567890123 ", "１２３４５６７８９０１２３４"}) {
      cases.add(Arguments.of(new PostPaymentRequest(card, 12, 2026, "GBP", 100, "012"),
          "cardNumber"));
    }
    for (Integer month : new Integer[]{null, 0, 13}) {
      cases.add(Arguments.of(new PostPaymentRequest("1".repeat(14), month, 2027, "GBP", 100,
          "012"), "expiryMonth"));
    }
    for (Integer year : new Integer[]{null, 0, 10000}) {
      cases.add(Arguments.of(new PostPaymentRequest("1".repeat(14), 12, year, "GBP", 100,
          "012"), "expiryYear"));
    }
    for (String currency : new String[]{null, "", "GB", "gbp", "JPY", "GBPP"}) {
      cases.add(Arguments.of(new PostPaymentRequest("1".repeat(14), 12, 2026, currency, 100,
          "012"), "currency"));
    }
    for (String cvv : new String[]{null, "", "12", "12345", "12a", "１２３"}) {
      cases.add(Arguments.of(new PostPaymentRequest("1".repeat(14), 12, 2026, "GBP", 100,
          cvv), "cvv"));
    }
    cases.add(Arguments.of(new PostPaymentRequest("1".repeat(14), 12, 2026, "GBP", null,
        "012"), "amount"));
    return cases.build();
  }

  @Test
  void acceptsLengthBoundariesAndSupportedCurrencies() {
    for (String currency : new String[]{"GBP", "USD", "EUR"}) {
      for (int length : new int[]{14, 19}) {
        for (String cvv : new String[]{"012", "0123"}) {
          assertThat(VALIDATOR.validate(new PostPaymentRequest("0".repeat(length), 12, 2026,
              currency, 0, cvv))).isEmpty();
        }
      }
    }
  }

  @Test
  void comparesCombinedExpiryIncludingCurrentMonthAndYearRollover() {
    assertThat(VALIDATOR.validate(new PostPaymentRequest("1".repeat(14), 11, 2026,
        "GBP", 100, "123"))).isNotEmpty();
    assertThat(VALIDATOR.validate(new PostPaymentRequest("1".repeat(14), 12, 2025,
        "GBP", 100, "123"))).isNotEmpty();
    assertThat(VALIDATOR.validate(new PostPaymentRequest("1".repeat(14), 1, 2027,
        "GBP", 100, "123"))).isEmpty();
  }

  @Test
  void responsesAllowHistoricalExpiryButRequireProcessedStatusAndMaskedCard() {
    UUID id = UUID.randomUUID();
    assertThat(VALIDATOR.validate(new PaymentResponse(id, PaymentStatus.AUTHORIZED,
        "0001", 12, 2024, "GBP", 100))).isEmpty();
    assertThat(VALIDATOR.validate(new PaymentResponse(id, PaymentStatus.DECLINED,
        "0001", 12, 2024, "USD", 100))).isEmpty();
    assertThat(VALIDATOR.validate(new PaymentResponse(id, PaymentStatus.REJECTED,
        "0001", 12, 2024, "GBP", 100))).isNotEmpty();
    assertThat(VALIDATOR.validate(new PaymentResponse(id, PaymentStatus.AUTHORIZED,
        "12345678901234", 12, 2024, "GBP", 100))).isNotEmpty();
  }
}
