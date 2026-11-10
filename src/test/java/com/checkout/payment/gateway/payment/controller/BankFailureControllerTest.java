package com.checkout.payment.gateway.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.payment.repository.PaymentsRepository;
import com.checkout.payment.gateway.bank.support.BankFailureScenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(properties = "bank.base-url=http://bank-failure.test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BankFailureControllerTest {

  @Autowired
  private MockMvc mvc;
  @Autowired
  private RestTemplate restTemplate;
  @MockBean
  private PaymentsRepository repository;
  private MockRestServiceServer bank;

  @BeforeEach
  void bindBank() {
    bank = MockRestServiceServer.bindTo(restTemplate).build();
  }

  @AfterEach
  void verifyNoPersistenceOrRetries() {
    try {
      bank.verify();
    } finally {
      verifyNoInteractions(repository);
      bank.reset();
    }
  }

  @ParameterizedTest
  @EnumSource(BankFailureScenario.class)
  void returnsSafeTechnicalErrorRatherThanPaymentOutcome(BankFailureScenario scenario)
      throws Exception {
    bank.expect(requestTo("http://bank-failure.test/payments"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(scenario.response());

    String body = mvc.perform(post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"card_number":"2222405343240001","expiry_month":12,"expiry_year":2099,
                 "currency":"GBP","amount":1050,"cvv":"987"}
                """))
        .andExpect(status().is(scenario.httpStatus()))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("""
            {"code":"%s","message":"%s"}
            """.formatted(scenario.code().name(), scenario.code().getMessage()), true))
        .andExpect(jsonPath("$.status").doesNotExist())
        .andExpect(jsonPath("$.id").doesNotExist())
        .andReturn().getResponse().getContentAsString();

    assertThat(body).doesNotContain("2222405343240001", "987", "internal-bank-debug",
        "bank-failure.test", "stackTrace", "authorization_code");
  }
}
