package com.checkout.payment.gateway.payment.service;

import com.checkout.payment.gateway.observability.MetricNames;
import com.checkout.payment.gateway.bank.client.BankClient;
import com.checkout.payment.gateway.bank.exception.BankProcessingException;
import com.checkout.payment.gateway.bank.model.BankPaymentRequest;
import com.checkout.payment.gateway.bank.model.BankPaymentResponse;
import com.checkout.payment.gateway.payment.enums.PaymentStatus;
import com.checkout.payment.gateway.payment.exception.EventProcessingException;
import com.checkout.payment.gateway.payment.idempotency.PaymentIdempotencyService;
import com.checkout.payment.gateway.payment.mapping.BankPaymentRequestMapper;
import com.checkout.payment.gateway.payment.model.PostPaymentRequest;
import com.checkout.payment.gateway.payment.model.PaymentResponse;
import com.checkout.payment.gateway.payment.repository.PaymentsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  private final PaymentsRepository paymentsRepository;
  private final BankClient bankClient;
  private final MeterRegistry meters;
  private final PaymentIdempotencyService idempotencyService;

  public PaymentGatewayService(PaymentsRepository paymentsRepository, BankClient bankClient,
      PaymentIdempotencyService idempotencyService, MeterRegistry meters) {
    this.paymentsRepository = paymentsRepository;
    this.bankClient = bankClient;
    this.meters = meters;
    this.idempotencyService = idempotencyService;
  }

  public PaymentResponse getPaymentById(UUID id) {
    LOG.debug("Requesting payment with ID {}", id);
    return paymentsRepository.get(id)
        .orElseThrow(() -> new EventProcessingException("Invalid ID"));
  }

  public UUID processPayment(PostPaymentRequest paymentRequest) {
    return processPayment(paymentRequest, null);
  }

  public UUID processPayment(PostPaymentRequest paymentRequest, String idempotencyKey) {
    return idempotencyService.execute(idempotencyKey, paymentRequest,
        () -> processNewPayment(paymentRequest));
  }

  private UUID processNewPayment(PostPaymentRequest paymentRequest) {
    // Count actual processing attempts, not cached idempotency replays.
    Timer.Sample sample = Timer.start(meters);
    String outcome = "INTERNAL_ERROR";
    try {
      PaymentResponse payment = submitAndSave(paymentRequest);
      outcome = payment.status().name();
      return payment.id();
    } catch (BankProcessingException exception) {
      outcome = exception.getCode().name();
      throw exception;
    } finally {
      sample.stop(meters.timer(MetricNames.PAYMENT_ATTEMPTS, "outcome", outcome));
    }
  }

  private PaymentResponse submitAndSave(PostPaymentRequest paymentRequest) {
    // Inputs must be validated by the controller before reaching this service.
    BankPaymentRequest bankRequest = BankPaymentRequestMapper.from(paymentRequest);
    BankPaymentResponse bankResponse = bankClient.processPayment(bankRequest);

    PaymentStatus paymentStatus = bankResponse.authorized()
        ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED;
    UUID paymentId = UUID.randomUUID();
    String cardNumber = paymentRequest.cardNumber();
    String lastFour = cardNumber.substring(cardNumber.length() - 4);

    // Only masked details are retained; bank failures propagate before any write.
    PaymentResponse payment = new PaymentResponse(
        paymentId, paymentStatus, lastFour, paymentRequest.expiryMonth(),
        paymentRequest.expiryYear(), paymentRequest.currency(), paymentRequest.amount());
    paymentsRepository.add(payment);
    LOG.info("Payment {} processed with status {}", paymentId, paymentStatus);

    return payment;
  }
}
