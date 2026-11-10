package com.checkout.payment.gateway.payment.idempotency;

import com.checkout.payment.gateway.payment.model.PostPaymentRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** Keyed comparison token; avoids retaining card numbers and CVVs in the idempotency cache. */
@Component
public class PaymentRequestFingerprint {

  private final SecretKey key;

  public PaymentRequestFingerprint() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("HmacSHA256");
      generator.init(256);
      key = generator.generateKey();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Cannot initialize request fingerprinting", exception);
    }
  }

  public String create(PostPaymentRequest request) {
    try {
      // Mac is mutable: use a fresh instance per call, sharing only the immutable key.
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      append(mac, request.cardNumber());
      append(mac, request.expiryMonth().toString());
      append(mac, request.expiryYear().toString());
      append(mac, request.currency());
      append(mac, request.amount().toString());
      append(mac, request.cvv());
      return Base64.getEncoder().encodeToString(mac.doFinal());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Cannot fingerprint payment request", exception);
    }
  }

  private void append(Mac mac, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    // Length-prefix fields so different field boundaries cannot produce the same input.
    mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    mac.update(bytes);
  }
}
