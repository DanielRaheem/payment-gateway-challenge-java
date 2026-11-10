package com.checkout.payment.gateway.payment.exception;

import com.checkout.payment.gateway.observability.MetricNames;
import com.checkout.payment.gateway.bank.exception.BankProcessingException;
import com.checkout.payment.gateway.bank.exception.BankTimeoutException;
import com.checkout.payment.gateway.bank.exception.BankUnavailableException;
import com.checkout.payment.gateway.bank.exception.InvalidBankResponseException;
import com.checkout.payment.gateway.bank.model.BankErrorResponse;
import com.checkout.payment.gateway.payment.model.ErrorResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final MeterRegistry meters;

  public GlobalExceptionHandler(MeterRegistry meters) {
    this.meters = meters;
  }

  @ExceptionHandler(EventProcessingException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse handleException(EventProcessingException exception) {
    meters.counter(MetricNames.PAYMENT_ERRORS, "code", "PAYMENT_NOT_FOUND").increment();
    LOG.info("Payment lookup returned not found");
    return new ErrorResponse("Page not found");
  }

  @ExceptionHandler(BankUnavailableException.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public BankErrorResponse handleBankUnavailable(BankUnavailableException exception) {
    return bankError(exception);
  }

  @ExceptionHandler(BankTimeoutException.class)
  @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
  public BankErrorResponse handleBankTimeout(BankTimeoutException exception) {
    return bankError(exception);
  }

  @ExceptionHandler(InvalidBankResponseException.class)
  @ResponseStatus(HttpStatus.BAD_GATEWAY)
  public BankErrorResponse handleInvalidBankResponse(InvalidBankResponseException exception) {
    return bankError(exception);
  }

  @ExceptionHandler({MethodArgumentNotValidException.class,
      HttpMessageNotReadableException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> handleRejectedPayment() {
    // Keep handlers together so a bank failure wins over a nested JSON parsing exception.
    // Do not echo rejected values or parsing errors containing card data.
    meters.counter(MetricNames.PAYMENT_ERRORS, "code", "INVALID_REQUEST").increment();
    LOG.info("Payment request rejected: invalid input");
    return Map.of("status", "Rejected", "message", "Invalid payment request");
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Map<String, String> handleIdempotencyConflict(IdempotencyConflictException exception) {
    meters.counter(MetricNames.PAYMENT_ERRORS, "code", "IDEMPOTENCY_CONFLICT").increment();
    LOG.info("Payment request rejected: idempotency conflict");
    return Map.of("code", "IDEMPOTENCY_CONFLICT", "message", exception.getMessage());
  }

  private BankErrorResponse bankError(BankProcessingException exception) {
    // Upstream exception causes can contain card data. Log only the stable error code.
    meters.counter(MetricNames.PAYMENT_ERRORS, "code", exception.getCode().name()).increment();
    LOG.warn("Bank payment request failed with code {}", exception.getCode());
    return new BankErrorResponse(exception.getCode(), exception.getCode().getMessage());
  }
}
