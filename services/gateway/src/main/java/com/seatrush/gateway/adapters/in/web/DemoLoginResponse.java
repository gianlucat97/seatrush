package com.seatrush.gateway.adapters.in.web;

import com.seatrush.security.tokens.IssuedToken;
import com.seatrush.security.tokens.UserToken;
import java.time.Instant;
import java.util.UUID;

/**
 * The token a demo login hands out (README section 12.1).
 *
 * <p>More than the {@code accessToken} the table names, and both extras are things a client would
 * otherwise have to get by decoding the token itself: who it says they are, so the page can show a
 * name and recognise its own holds, and when it stops working, so the page can ask for another one
 * before a checkout fails halfway through.
 */
public record DemoLoginResponse(
    String accessToken, UUID userId, String username, Instant expiresAt) {

  static DemoLoginResponse of(IssuedToken<UserToken> issued) {
    UserToken user = issued.claims();
    return new DemoLoginResponse(
        issued.compact(), user.userId(), user.username(), user.expiresAt());
  }
}
