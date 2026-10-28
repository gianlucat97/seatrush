package com.seatrush.gateway.adapters.in.web;

import com.seatrush.common.error.ErrorCode;
import com.seatrush.security.tokens.TokenException;
import com.seatrush.security.tokens.UserToken;
import com.seatrush.security.tokens.UserTokenSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Who the caller is, decided once for the whole system (README section 13).
 *
 * <p>Two things happen here and the second is the one that matters. The token is verified, and then
 * {@code X-User-Id} is removed from the request and written again from the token. Services behind
 * the gateway trust that header completely, because they are reachable only from inside the
 * cluster, so a client that sent one of its own must not be able to have it forwarded: dropping it
 * before writing is the whole of that guarantee, and it happens on every request including the ones
 * that need no token at all.
 *
 * <p>The admin check lives here as well, because it is a question about the token rather than about
 * the request: {@code /api/admin} is open to a caller whose token carries the role and to nobody
 * else, and answering that at the edge keeps inventory from having to know what a role is.
 *
 * <p>The login endpoint is the one path with no token, which is what a login is for. Actuator is
 * the other, because a readiness probe cannot log in and the endpoint says nothing about anybody.
 */
@Component
public class UserAuthenticationFilter implements WebFilter, Ordered {

  /** The identity header every service behind this one trusts, and only this writes. */
  public static final String USER_ID_HEADER = "X-User-Id";

  /** Where the verified token is kept for the filters after this one. */
  static final String USER_TOKEN_ATTRIBUTE = UserAuthenticationFilter.class.getName() + ".user";

  private static final String BEARER = "Bearer ";
  private static final String LOGIN_PREFIX = "/api/auth/";
  private static final String ACTUATOR_PREFIX = "/actuator";
  private static final String ADMIN_PREFIX = "/api/admin";

  private static final Logger log = LoggerFactory.getLogger(UserAuthenticationFilter.class);

  private final UserTokenSigner users;
  private final ProblemResponses problems;

  public UserAuthenticationFilter(UserTokenSigner users, ProblemResponses problems) {
    this.users = users;
    this.problems = problems;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (isPublic(path)) {
      return chain.filter(withoutClientIdentity(exchange));
    }

    String token = bearerOf(exchange.getRequest());
    if (token == null) {
      return problems.write(
          exchange, ErrorCode.UNAUTHENTICATED, "This endpoint needs a bearer token");
    }

    UserToken user;
    try {
      user = users.verify(token);
    } catch (TokenException invalid) {
      // The reason is deliberately not in the answer: whether a token was forged, expired or
      // meant for something else is a distinction only an attacker benefits from. It is logged.
      log.debug("a request to {} carried a token that is not valid", path, invalid);
      return problems.write(
          exchange, ErrorCode.UNAUTHENTICATED, "The token is not valid or has expired");
    }

    if (path.startsWith(ADMIN_PREFIX) && !user.admin()) {
      return problems.write(
          exchange, ErrorCode.FORBIDDEN, "This endpoint is open to administrators only");
    }

    ServerWebExchange identified = withIdentityOf(exchange, user);
    identified.getAttributes().put(USER_TOKEN_ATTRIBUTE, user);
    return chain.filter(identified);
  }

  /**
   * Early, because everything after this one is allowed to assume a caller.
   *
   * <p>It is still not the first thing to run: tracing and the access log come before it, so a
   * request that is refused here is a request that was recorded.
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10;
  }

  private static boolean isPublic(String path) {
    return path.startsWith(LOGIN_PREFIX) || path.startsWith(ACTUATOR_PREFIX);
  }

  private static String bearerOf(ServerHttpRequest request) {
    String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return null;
    }
    String token = header.substring(BEARER.length()).strip();
    return token.isEmpty() ? null : token;
  }

  /** The request with whatever identity the client claimed taken off it. */
  private static ServerWebExchange withoutClientIdentity(ServerWebExchange exchange) {
    return exchange
        .mutate()
        .request(request -> request.headers(headers -> headers.remove(USER_ID_HEADER)))
        .build();
  }

  /** The same, with the identity the token proves written in its place. */
  private static ServerWebExchange withIdentityOf(ServerWebExchange exchange, UserToken user) {
    return exchange
        .mutate()
        .request(
            request ->
                request.headers(
                    headers -> {
                      headers.remove(USER_ID_HEADER);
                      headers.set(USER_ID_HEADER, user.userId().toString());
                    }))
        .build();
  }
}
