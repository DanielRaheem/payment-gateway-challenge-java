package com.checkout.payment.gateway.payment.mapping;

import com.checkout.payment.gateway.bank.model.BankPaymentRequest;
import com.checkout.payment.gateway.payment.model.PostPaymentRequest;
import java.util.Locale;

/** Converts validated payment input into the bank's independent request contract. */
public final class BankPaymentRequestMapper {

  private BankPaymentRequestMapper() {
  }

  public static BankPaymentRequest from(PostPaymentRequest request) {
    String expiryDate = String.format(Locale.ROOT, "%02d/%04d",
        request.expiryMonth(), request.expiryYear());
    return new BankPaymentRequest(request.cardNumber(), expiryDate,
        request.currency(), request.amount(), request.cvv());
  }
}
