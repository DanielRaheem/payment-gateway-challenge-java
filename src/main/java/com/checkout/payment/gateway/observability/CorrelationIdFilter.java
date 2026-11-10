package com.checkout.payment.gateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Correlates synchronous payment requests without logging raw URLs, headers, or bodies. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-ID";
  public static final String MDC_KEY = "correlationId";
  private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);
  private static final Pattern UUID_PATTERN = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE",
      "HEAD", "OPTIONS");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    if (path.isEmpty()) {
      path = request.getRequestURI().substring(request.getContextPath().length());
    }
    return !path.equals("/payment") && !path.startsWith("/payment/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    String correlationId = supplied != null && UUID_PATTERN.matcher(supplied).matches()
        ? supplied : UUID.randomUUID().toString();
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    long started = System.nanoTime();
    boolean completed = false;
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
      int status = completed ? response.getStatus() : 500;
      // Handled bank failures already have a safe WARN in the exception handler.
      // Do not log escaping exception messages/causes, which may contain payment data.
      boolean unexpectedFailure = !completed || (status >= 500 && status != 502
          && status != 503 && status != 504);
      LOG.atLevel(unexpectedFailure ? org.slf4j.event.Level.ERROR : org.slf4j.event.Level.INFO)
          .log("http_request_completed method={} route={} status={} duration_ms={} completed={}",
              method, route == null ? "unmatched" : route, status,
              TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), completed);
      // Servlet threads are reused; never leave request context on the worker thread.
      if (previous == null) {
        MDC.remove(MDC_KEY);
      } else {
        MDC.put(MDC_KEY, previous);
      }
    }
  }
}
