package com.seatrush.security.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The identity record itself: what it accepts, and what it makes of roles. */
class UserTokenTest {

  private static final UUID USER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Instant ISSUED_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Instant EXPIRES_AT = Instant.parse("2026-06-01T11:00:00Z");

  @Test
  void upperCasesAndTrimsTheRolesItWasGiven() {
    UserToken token = token(Set.of(" admin "));

    assertThat(token.roles()).containsExactly("ADMIN");
    assertThat(token.admin()).isTrue();
  }

  @Test
  void keepsARoleThisBuildHasNeverHeardOf() {
    UserToken token = token(new LinkedHashSet<>(Set.of("SUPPORT")));

    assertThat(token.roles()).containsExactly("SUPPORT");
    assertThat(token.admin())
        .as("an identity provider may say more than this build acts on")
        .isFalse();
  }

  @Test
  void holdsRolesNobodyCanChangeAfterwards() {
    Set<String> mutable = new LinkedHashSet<>(Set.of("SUPPORT"));
    UserToken token = token(mutable);

    mutable.add("ADMIN");

    assertThat(token.admin()).isFalse();
    assertThatThrownBy(() -> token.roles().add("ADMIN"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void refusesANamelessUser() {
    assertThatThrownBy(() -> new UserToken(USER_ID, "  ", Set.of(), ISSUED_AT, EXPIRES_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("username must not be blank");
  }

  @Test
  void refusesAMissingField() {
    assertThatThrownBy(() -> new UserToken(null, "ada", Set.of(), ISSUED_AT, EXPIRES_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("userId must not be null");
    assertThatThrownBy(() -> new UserToken(USER_ID, "ada", null, ISSUED_AT, EXPIRES_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("roles must not be null");
    assertThatThrownBy(() -> new UserToken(USER_ID, "ada", Set.of(), null, EXPIRES_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("issuedAt must not be null");
    assertThatThrownBy(() -> new UserToken(USER_ID, "ada", Set.of(), ISSUED_AT, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("expiresAt must not be null");
  }

  private static UserToken token(Set<String> roles) {
    return new UserToken(USER_ID, "ada", roles, ISSUED_AT, EXPIRES_AT);
  }
}
