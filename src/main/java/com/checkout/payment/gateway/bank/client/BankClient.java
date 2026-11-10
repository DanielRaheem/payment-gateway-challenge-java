package com.checkout.payment.gateway.bank.client;

import com.checkout.payment.gateway.observability.MetricNames;
import com.checkout.payment.gateway.bank.exception.BankProcessingException;
import com.checkout.payment.gateway.bank.exception.BankTimeoutException;
import com.checkout.payment.gateway.bank.exception.BankUnavailableException;
import com.checkout.payment.gateway.bank.exception.InvalidBankResponseException;
import com.checkout.payment.gateway.bank.model.BankPaymentRequest;
import com.checkout.payment.gateway.bank.model.BankPaymentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/** Sends one bank request and translates transport failures; never retries payments. */
@Component
public class BankClient {

  private static final Logger LOG = LoggerFactory.getLogger(BankClient.class);

  private final RestTemplate restTemplate;
  private final BankResponseMapper responseMapper;
  private final MeterRegistry meters;

  public BankClient(RestTemplate restTemplate, BankResponseMapper responseMapper,
      MeterRegistry meters) {
    this.restTemplate = restTemplate;
    this.responseMapper = responseMapper;
    this.meters = meters;
  }

  public BankPaymentResponse processPayment(BankPaymentRequest request) {
    Timer.Sample sample = Timer.start(meters);
    String outcome = "INTERNAL_ERROR";
    try {
      BankPaymentResponse response = requestPayment(request);
      outcome = response.authorized() ? "AUTHORIZED" : "DECLINED";
      return response;
    } catch (BankProcessingException exception) {
      outcome = exception.getCode().name();
      throw exception;
    } finally {
      long durationNanos = sample.stop(meters.timer(MetricNames.BANK_REQUESTS, "outcome", outcome));
      LOG.debug("bank_request_completed outcome={} duration_ms={}", outcome,
          java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(durationNanos));
    }
  }

  private BankPaymentResponse requestPayment(BankPaymentRequest request) {
    ResponseEntity<JsonNode> response = send(request);
    if (response.getStatusCode() != HttpStatus.OK) {
      throw new InvalidBankResponseException();
    }
    return responseMapper.map(response.getBody());
  }

  private ResponseEntity<JsonNode> send(BankPaymentRequest request) {
    try {
      return restTemplate.postForEntity("/payments", request, JsonNode.class);
    } catch (RestClientResponseException exception) {
      throw translateHttpFailure(exception);
    } catch (ResourceAccessException exception) {
      throw translateConnectionFailure(exception);
    } catch (RestClientException exception) {
      throw new InvalidBankResponseException(exception);
    }
  }

  private BankProcessingException translateHttpFailure(RestClientResponseException exception) {
    if (exception.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
      return new BankUnavailableException(exception);
    }
    return new InvalidBankResponseException(exception);
  }

  private BankProcessingException translateConnectionFailure(ResourceAccessException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
        return new BankTimeoutException(exception);
      }
    }
    return new BankUnavailableException(exception);
  }
}
