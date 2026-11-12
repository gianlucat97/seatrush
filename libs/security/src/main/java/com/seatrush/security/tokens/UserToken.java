package com.seatrush.security.tokens;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who the caller is, as the gateway reads it out of a user JWT (README sections 12.1 and 13).
 *
 * <p>The gateway turns this into the {@code X-User-Id} header the services behind it trust, after
 * stripping whatever the client sent under that name. That is the whole reason this record is
 * small: the only field a downstream service is given is the id, so everything else here exists to
 * be checked at the edge and then dropped.
 *
 * <p>Roles are a set of strings rather than an enum because on Kubernetes these tokens come from an
 * identity provider through a JWKS endpoint, not from the demo login, and a token carrying a role
 * this build has never heard of is a normal thing rather than a malformed one. {@link #admin()} is
 * the only question anybody asks of them today.
 *
 * @param userId the {@code sub} claim, which is what identifies the buyer everywhere downstream
 * @param username display name, carried so the UI need not look one up
 * @param roles role names, upper cased, empty for an ordinary buyer
 * @param expiresAt when it stops being accepted, so a client knows when to log in again
 */
public record UserToken(
    UUID userId, String username, Set<String> roles, Instant issuedAt, Instant expiresAt) {

  /** The one role this build acts on (README section 13). */
  public static final String ADMIN = "ADMIN";

  public UserToken {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(username, "username must not be null");
    Objects.requireNonNull(roles, "roles must not be null");
    Objects.requireNonNull(issuedAt, "issuedAt must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    if (username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    Set<String> upperCased = new LinkedHashSet<>();
    for (String role : roles) {
      Objects.requireNonNull(role, "a role must not be null");
      upperCased.add(role.strip().toUpperCase(Locale.ROOT));
    }
    roles = Set.copyOf(upperCased);
  }

  /** Whether the admin endpoints of README section 12.2 are open to this caller. */
  public boolean admin() {
    return roles.contains(ADMIN);
  }
}
