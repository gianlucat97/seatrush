package com.seatrush.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.security.tokens.IssuedToken;
import com.seatrush.security.tokens.TokenSecrets;
import com.seatrush.security.tokens.UserToken;
import com.seatrush.security.tokens.UserTokenSigner;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The demo login, whose only real decision is that a name is always the same person.
 *
 * <p>That is not a convenience. Holds, orders and tickets are all keyed by the user id, so a buyer
 * who reloaded the page and logged in again under the same name has to come back as the same user
 * or the checkout they just paid for belongs to nobody.
 */
class DemoLoginsTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration TTL = Duration.ofHours(1);

  private final MutableClock clock = MutableClock.at(NOW);
  private final UserTokenSigner users =
      new UserTokenSigner(TokenSecrets.key(TokenSecrets.generate()), clock, TTL);
  private final DemoLogins logins = new DemoLogins(users);

  @Test
  void issuesATokenThatReadsBackAsTheUserWhoAskedForIt() {
    IssuedToken<UserToken> issued = logins.login("Ada", false);

    UserToken user = users.verify(issued.compact());
    assertThat(user.username()).isEqualTo("Ada");
    assertThat(user.userId()).isEqualTo(issued.claims().userId());
    assertThat(user.expiresAt()).isEqualTo(NOW.plus(TTL));
    assertThat(user.admin()).isFalse();
  }

  @Test
  void givesTheSameNameTheSameUserEveryTime() {
    assertThat(logins.login("Ada", false).claims().userId())
        .isEqualTo(logins.login("Ada", false).claims().userId());
  }

  /** Case and surrounding space are not a second person either, which a demo types by accident. */
  @Test
  void treatsTheSameNameWrittenDifferentlyAsOnePerson() {
    assertThat(DemoLogins.userIdOf("Ada"))
        .isEqualTo(DemoLogins.userIdOf("  ada  "))
        .isEqualTo(DemoLogins.userIdOf("ADA"));
  }

  @Test
  void givesDifferentNamesDifferentUsers() {
    assertThat(DemoLogins.userIdOf("Ada")).isNotEqualTo(DemoLogins.userIdOf("Grace"));
  }

  /** A demo needs an operator who can open a sale and a buyer who cannot. */
  @Test
  void issuesAnAdminTokenWhenOneIsAskedFor() {
    assertThat(users.verify(logins.login("Grace", true).compact()).admin()).isTrue();
  }

  @Test
  void refusesANameThatIsNotOne() {
    assertThatThrownBy(() -> logins.login("   ", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("username must not be blank");
  }
}
