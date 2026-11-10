package com.checkout.payment.gateway.payment.controller;

import com.checkout.payment.gateway.payment.model.PostPaymentRequest;
import com.checkout.payment.gateway.payment.model.PaymentResponse;
import com.checkout.payment.gateway.payment.service.PaymentGatewayService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// "api" names the Spring bean; it does not add a URL prefix to the endpoint.
@RestController("api")
public class PaymentGatewayController {

  private final PaymentGatewayService paymentGatewayService;

  public PaymentGatewayController(PaymentGatewayService paymentGatewayService) {
    this.paymentGatewayService = paymentGatewayService;
  }

  @PostMapping("/payment")
  public ResponseEntity<PaymentResponse> processPayment(
      @Valid @RequestBody PostPaymentRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    UUID paymentId = paymentGatewayService.processPayment(request, idempotencyKey);
    PaymentResponse payment = paymentGatewayService.getPaymentById(paymentId);
    return new ResponseEntity<>(payment, HttpStatus.CREATED);
  }

  @GetMapping("/payment/{id}")
  public ResponseEntity<PaymentResponse> getPostPaymentEventById(@PathVariable UUID id) {
    PaymentResponse payment = paymentGatewayService.getPaymentById(id);
    return new ResponseEntity<>(payment, HttpStatus.OK);
  }

}
