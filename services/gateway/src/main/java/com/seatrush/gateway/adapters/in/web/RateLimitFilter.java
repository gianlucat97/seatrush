package com.seatrush.gateway.adapters.in.web;

import com.seatrush.common.error.ErrorCode;
import com.seatrush.gateway.config.RateLimitProperties;
import com.seatrush.gateway.domain.RateLimitScope;
import com.seatrush.gateway.domain.RateLimitVerdict;
import com.seatrush.gateway.domain.RateLimitedRequest;
import com.seatrush.gateway.domain.TokenBuckets;
import com.seatrush.gateway.domain.TokenBuckets.SubjectKind;
import com.seatrush.security.tokens.UserToken;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * How often one buyer, and everybody behind one address, may ask for something (README sections 6.1
 * and 13).
 *
 * <p>Four requests are limited and the rule is {@link RateLimitedRequest}. Two buckets are spent on
 * each of them, the caller's own and the one for the address they came from, and either being empty
 * is a refusal: {@code 429 RATE_LIMITED} with the {@code Retry-After} of README section 12.6.
 *
 * <p><strong>After the admission filter, on purpose.</strong> Checking an admission is a signature
 * and an expiry, which is arithmetic inside this process; checking a bucket is two round trips to
 * Redis. A purchase command with no turn is therefore refused by the cheaper of the two checks and
 * never reaches this one, which matters precisely in the case both exist for: somebody hammering
 * {@code POST /holds} without an admission would otherwise be spending Redis calls at the rate they
 * chose. The one limited request that needs no admission, the queue join, is unaffected by the
 * order.
 *
 * <p>Both buckets are read, even when the first of them refuses. It costs one round trip on a path
 * that has already been refused, and it buys an honest pair of counters: an operator looking at
 * {@code seatrush_ratelimit_refusals_total} wants to know whether an address is being limited as
 * well as a buyer, and a short-circuit would make the second number a function of which check ran
 * first.
 */
@Component
public class RateLimitFilter implements WebFilter, Ordered {

  /** What a proxy in front of this one records the caller's address in. */
  public static final String FORWARDED_FOR = "X-Forwarded-For";

  private static final String UNKNOWN_ADDRESS = "unknown";

  private final TokenBuckets buckets;
  private final RateLimitProperties properties;
  private final ProblemResponses problems;

  public RateLimitFilter(
      TokenBuckets buckets, RateLimitProperties properties, ProblemResponses problems) {
    this.buckets = buckets;
    this.properties = properties;
    this.problems = problems;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (!properties.enabled()) {
      return chain.filter(exchange);
    }

    ServerHttpRequest request = exchange.getRequest();
    Optional<RateLimitScope> scope =
        RateLimitedRequest.scopeOf(request.getMethod().name(), request.getPath().value());
    if (scope.isEmpty()) {
      return chain.filter(exchange);
    }

    UserToken caller = exchange.getAttribute(UserAuthenticationFilter.USER_TOKEN_ATTRIBUTE);
    if (caller == null) {
      // Unreachable while the user filter runs first, and worth failing loudly rather than
      // skipping: a limit counted against nobody is no limit at all.
      throw new IllegalStateException(
          "a limited request reached the rate limit filter with no authenticated caller");
    }

    RateLimitScope limited = scope.get();
    Mono<RateLimitVerdict> user =
        Mono.fromCompletionStage(
            () -> buckets.take(limited, caller.userId().toString(), SubjectKind.USER));
    Mono<RateLimitVerdict> address =
        Mono.fromCompletionStage(
            () -> buckets.take(limited, addressOf(request), SubjectKind.ADDRESS));

    return Mono.zip(user, address)
        .map(both -> both.getT1().and(both.getT2()))
        .flatMap(
            verdict ->
                verdict.allowed()
                    ? chain.filter(exchange)
                    : problems.write(
                        exchange,
                        ErrorCode.RATE_LIMITED,
                        "You are asking for this more often than the sale allows. "
                            + "Wait and try again.",
                        verdict.retryAfterSeconds()));
  }

  /** After the admission filter, for the reason in this class's own documentation. */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 30;
  }

  /**
   * The address this request came from, as the nearest trusted proxy saw it.
   *
   * <p>The <strong>last</strong> entry of {@code X-Forwarded-For} and not the first, which is the
   * one thing this method has to get right. Every proxy in front of this one appends what it saw,
   * so the rightmost entry is what the proxy closest to this service observed and the entries to
   * its left are whatever the client sent, including anything it made up. Reading the leftmost is
   * the classic way to build a limit per address that a client resets by sending one header.
   *
   * <p>Both deployments of this system put exactly one trusted hop in front of the gateway: nginx
   * in the Compose stack of README section 23, and the ingress controller in the cluster of section
   * 24.1. Both set the header, so the rightmost entry is the real caller in both.
   *
   * <p>What a spoofed header can still do is move a client between address buckets, and what it
   * cannot do is escape the per-user bucket, because that one is keyed on an id out of a signed
   * token. That is the division of labour the two buckets exist for, and it is why the per-user
   * numbers are the strict ones.
   *
   * <p>A request with no header and no remote address is counted under one shared key rather than
   * waved through, so a deployment that hid both from this service would get a limit that is too
   * strict rather than none at all.
   */
  private static String addressOf(ServerHttpRequest request) {
    String forwarded = request.getHeaders().getFirst(FORWARDED_FOR);
    if (forwarded != null && !forwarded.isBlank()) {
      String[] hops = forwarded.split(",");
      String nearest = hops[hops.length - 1].strip();
      if (!nearest.isEmpty()) {
        return nearest;
      }
    }
    InetSocketAddress remote = request.getRemoteAddress();
    if (remote == null || remote.getAddress() == null) {
      return UNKNOWN_ADDRESS;
    }
    return remote.getAddress().getHostAddress();
  }
}
