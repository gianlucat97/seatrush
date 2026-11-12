package com.seatrush.gateway.adapters.in.web;

import com.seatrush.common.error.ErrorCode;
import com.seatrush.common.error.ProblemDetails;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a refusal by the gateway into the same document every service answers with (README section
 * 12.6).
 *
 * <p>It matters that these look identical. A client that is told {@code UNAUTHENTICATED} by the
 * edge and {@code HOLD_EXPIRED} by inventory should branch on one field in one shape, and a gateway
 * that answered a bare 401 with an empty body would be the single response nobody could parse.
 *
 * <p>Written by hand into the response rather than thrown, because these refusals happen in a
 * filter: there is no handler to map an exception for, and a request that is rejected here is one
 * that must not reach a route at all.
 *
 * <p>The document is flattened the way Spring flattens one, with the extra members of the problem
 * beside its standard fields rather than nested under a {@code properties} object, so {@code code}
 * sits where README section 12.6 says it does.
 */
@Component
public class ProblemResponses {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Refuses the request with the status and title the code carries. */
  public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, String detail) {
    return write(exchange, code, detail, null);
  }

  /**
   * The same, with {@code Retry-After} on it.
   *
   * <p>For the one refusal that is a measured wait rather than a verdict about the request. {@code
   * 429 RATE_LIMITED} tells a client it asked too often, and a client told that and nothing else
   * retries on a schedule of its own, which under load is the schedule that caused the refusal. The
   * header is the whole difference between a limit that sheds load and a limit that adds a retry
   * storm to it, which is why README section 12.6 lists it beside the code.
   *
   * @param retryAfter how long to wait, in whole seconds; no header is set when null
   */
  public Mono<Void> write(
      ServerWebExchange exchange, ErrorCode code, String detail, Long retryAfter) {
    ProblemDetail problem = ProblemDetails.of(code, detail);
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(HttpStatus.valueOf(problem.getStatus()));
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    if (retryAfter != null) {
      response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
    }

    DataBuffer body = response.bufferFactory().wrap(JSON.writeValueAsBytes(flatten(problem)));
    return response.writeWith(Mono.just(body));
  }

  private static Map<String, Object> flatten(ProblemDetail problem) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("type", problem.getType().toString());
    document.put("title", problem.getTitle());
    document.put("status", problem.getStatus());
    if (problem.getDetail() != null) {
      document.put("detail", problem.getDetail());
    }
    Map<String, Object> properties = problem.getProperties();
    if (properties != null) {
      document.putAll(properties);
    }
    return document;
  }
}
