package com.seatrush.payment.adapters.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.payment.domain.ProviderSettings;
import com.seatrush.payment.domain.SimulatorSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract of the internal configuration endpoint of README section 12.7. */
@WebMvcTest(InternalConfigController.class)
class InternalConfigControllerTest {

  private static final SimulatorSettings DEFAULTS =
      new SimulatorSettings(0.05, Duration.ofMillis(300), Duration.ofMillis(1_500));

  @Autowired private MockMvc mvc;

  @MockitoBean private ProviderSettings settings;

  @Test
  void answersWithWhatTheProviderIsDoing() throws Exception {
    given(settings.current()).willReturn(DEFAULTS);

    mvc.perform(get("/internal/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.declineRate").value(0.05))
        .andExpect(jsonPath("$.latencyMsP50").value(300))
        .andExpect(jsonPath("$.latencyMsP99").value(1500));
  }

  @Test
  void appliesNewSettingsAndAnswersWithThem() throws Exception {
    SimulatorSettings replacement =
        new SimulatorSettings(0.5, Duration.ofMillis(10), Duration.ofMillis(20));
    given(settings.apply(any())).willReturn(replacement);

    mvc.perform(
            put("/internal/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"declineRate\":0.5,\"latencyMsP50\":10,\"latencyMsP99\":20}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.declineRate").value(0.5))
        .andExpect(jsonPath("$.latencyMsP50").value(10))
        .andExpect(jsonPath("$.latencyMsP99").value(20));

    verify(settings).apply(replacement);
  }

  @Test
  void refusesARateThatIsNotAShare() throws Exception {
    mvc.perform(
            put("/internal/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"declineRate\":1.5,\"latencyMsP50\":10,\"latencyMsP99\":20}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fields.declineRate").exists());
  }

  @Test
  void refusesALatencyOfNothing() throws Exception {
    mvc.perform(
            put("/internal/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"declineRate\":0.1,\"latencyMsP50\":0,\"latencyMsP99\":20}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void refusesAMissingField() throws Exception {
    mvc.perform(
            put("/internal/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"declineRate\":0.1,\"latencyMsP50\":10}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /** The rule the annotations cannot express, refused by the record that owns it. */
  @Test
  void refusesATailBelowTheMedian() throws Exception {
    mvc.perform(
            put("/internal/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"declineRate\":0.1,\"latencyMsP50\":1000,\"latencyMsP99\":10}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void refusesABodyThatIsNotReadable() throws Exception {
    mvc.perform(put("/internal/config").contentType(MediaType.APPLICATION_JSON).content("not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
