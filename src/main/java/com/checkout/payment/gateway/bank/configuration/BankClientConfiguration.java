package com.checkout.payment.gateway.bank.configuration;

import com.checkout.payment.gateway.observability.CorrelationIdFilter;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class BankClientConfiguration {

  @Bean
  public RestTemplate restTemplate(RestTemplateBuilder builder,
      @Value("${bank.base-url}") String bankBaseUrl) {
    return builder
        .rootUri(bankBaseUrl)
        .additionalInterceptors((request, body, execution) -> {
          String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
          if (correlationId != null) {
            request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
          }
          return execution.execute(request, body);
        })
        .setConnectTimeout(Duration.ofMillis(10000))
        .setReadTimeout(Duration.ofMillis(10000))
        .build();
  }
}
