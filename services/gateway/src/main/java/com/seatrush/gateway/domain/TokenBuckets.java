package com.seatrush.gateway.domain;

import java.util.concurrent.CompletionStage;

/**
 * Where a token is taken from, as the gateway sees it (README section 6.1).
 *
 * <p>A port, so that the rule of {@link RateLimitedRequest} and the arithmetic of {@link
 * RateLimitVerdict} can be tested without a Redis and so that the filter does not reach into an
 * adapter. The implementation is one Lua call per bucket.
 *
 * <p>It answers a {@link CompletionStage} and not a value, and that is the one piece of transport
 * that reaches into the domain here. The alternative was worse in both directions. A blocking
 * method would put a round trip to Redis on the event loop of the one service in this repository
 * that holds hundreds of thousands of SSE connections, which is the whole reason that service is
 * reactive ({@code README} section 6.1). A method answering a {@code Mono} would put Reactor in the
 * domain, which the layering rules of {@code README} section 17 refuse and which would tie this
 * rule to how its caller happens to serve requests. {@code CompletionStage} is in the JDK, so it
 * does neither: the adapter bridges it to Reactor on one line and the domain stays framework free.
 */
public interface TokenBuckets {

  /**
   * Takes one token from the bucket of this subject in this scope.
   *
   * @param scope which limit applies, which decides the two numbers and part of the key
   * @param subject who is being limited: a user id, or an address
   * @param kind whether the subject is a user or an address, which keeps the two buckets of one
   *     caller apart
   * @return the verdict, once Redis has answered
   */
  CompletionStage<RateLimitVerdict> take(RateLimitScope scope, String subject, SubjectKind kind);

  /**
   * The two things a limit is counted against (README section 13).
   *
   * <p>Both, and not one, because each alone has a hole the other closes. A limit per user is
   * evaded by signing in again, which the demo login makes free and which any registration flow
   * makes cheap. A limit per address refuses a whole office or a mobile carrier for the behaviour
   * of one buyer behind it, which is why its numbers are the looser pair rather than the same pair
   * applied twice.
   */
  enum SubjectKind {
    USER("u"),
    ADDRESS("i");

    private final String prefix;

    SubjectKind(String prefix) {
      this.prefix = prefix;
    }

    /** One letter in the Redis key, so that a user id and an address can never collide. */
    public String prefix() {
      return prefix;
    }
  }
}
