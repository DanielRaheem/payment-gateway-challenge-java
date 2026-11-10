package com.checkout.payment.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingTest {

  private final Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Level previousLevel;

  @BeforeEach
  void captureLogs() {
    previousLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void restoreLogging() {
    logger.detachAppender(logs);
    logger.setLevel(previousLevel);
    logs.stop();
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 400, 404, 409, 502, 503, 504})
  void expectedResponsesHaveInformationalCompletion(int status) throws Exception {
    filterStatus(status);
    assertThat(logs.list).singleElement().satisfies(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.INFO);
      assertThat(event.getFormattedMessage()).contains("status=" + status, "completed=true");
    });
  }

  @Test
  void unexpectedServerFailureIsAnError() throws Exception {
    filterStatus(500);
    assertThat(logs.list).singleElement().satisfies(event ->
        assertThat(event.getLevel()).isEqualTo(Level.ERROR));
  }

  @Test
  void escapingFailureIsAnErrorWithoutSensitiveExceptionDetails() {
    assertThatThrownBy(() -> new CorrelationIdFilter().doFilter(
        new MockHttpServletRequest("POST", "/payment"), new MockHttpServletResponse(),
        (request, response) -> { throw new ServletException("secret-card-and-cvv"); }))
        .isInstanceOf(ServletException.class);
    assertThat(logs.list).singleElement().satisfies(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.ERROR);
      assertThat(event.getFormattedMessage()).contains("status=500", "completed=false")
          .doesNotContain("secret-card-and-cvv");
      assertThat(event.getThrowableProxy()).isNull();
    });
  }

  private void filterStatus(int status) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    new CorrelationIdFilter().doFilter(new MockHttpServletRequest("POST", "/payment"),
        response, (incoming, outgoing) -> response.setStatus(status));
  }
}
