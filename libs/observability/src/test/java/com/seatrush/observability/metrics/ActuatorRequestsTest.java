package com.seatrush.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Which requests are measured and which are not.
 *
 * <p>Both stacks, because the two predicates are separate classes and a change to one of them is
 * exactly the kind that gets made in one place.
 */
class ActuatorRequestsTest {

  private final ObservationPredicate servlet = ActuatorRequests.onServlet();
  private final ObservationPredicate reactive = ReactiveActuatorRequests.onReactive();

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/prometheus", "/actuator/health/readiness", "/actuator"})
  void leavesOutWhatTheMonitoringCalls(String path) {
    assertThat(servlet.test("http.server.requests", servletRequest(path))).isFalse();
    assertThat(reactive.test("http.server.requests", reactiveRequest(path))).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/orders", "/api/events/1/holds", "/actuators-elsewhere"})
  void measuresWhatAUserCalls(String path) {
    assertThat(servlet.test("http.server.requests", servletRequest(path))).isTrue();
    assertThat(reactive.test("http.server.requests", reactiveRequest(path))).isTrue();
  }

  /**
   * Everything else this system observes is a message handler or a scheduled task, and those are
   * the observations the traces of README section 14.2 are made of: a predicate that answered false
   * to a context it did not recognise would silently switch tracing off.
   */
  @Test
  void leavesEveryOtherKindOfObservationAlone() {
    Observation.Context somethingElse = new Observation.Context();

    assertThat(servlet.test("tasks.scheduled.execution", somethingElse)).isTrue();
    assertThat(reactive.test("tasks.scheduled.execution", somethingElse)).isTrue();
  }

  private static org.springframework.http.server.observation.ServerRequestObservationContext
      servletRequest(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    return new org.springframework.http.server.observation.ServerRequestObservationContext(
        request, new MockHttpServletResponse());
  }

  private static org.springframework.http.server.reactive.observation
          .ServerRequestObservationContext
      reactiveRequest(String path) {
    return new org.springframework.http.server.reactive.observation.ServerRequestObservationContext(
        MockServerHttpRequest.get(path).build(), new MockServerHttpResponse(), new HashMap<>());
  }
}
