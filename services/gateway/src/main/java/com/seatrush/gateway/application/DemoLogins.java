package com.seatrush.gateway.application;

import com.seatrush.security.tokens.IssuedToken;
import com.seatrush.security.tokens.UserToken;
import com.seatrush.security.tokens.UserTokenSigner;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The login of README section 12.1, which is a demo and says so.
 *
 * <p>There is no password and no user table, because a portfolio system that asked for one would be
 * demonstrating a login form instead of an on-sale. A name is enough to be somebody for the length
 * of a demo or a benchmark, and on Kubernetes this whole path is switched off by the profile: the
 * tokens then come from an identity provider and the gateway only reads them.
 *
 * <p>The id is derived from the name rather than generated, and that is the one decision in here.
 * Logging in twice has to be the same person, or a buyer who reloaded the page would lose the hold,
 * the order and the tickets they just paid for, which are all keyed by the user id. Deriving it
 * also makes a benchmark repeatable: the same hundred names are the same hundred buyers on every
 * run, so a seat somebody held in the last run belongs to the same user in this one.
 */
@Service
public class DemoLogins {

  /**
   * What the names are hashed with.
   *
   * <p>Only so that two systems seeded with the same names do not produce ids that collide with ids
   * from somewhere else. It is not a secret and it is not protecting anything: these tokens are
   * issued to whoever asks for one.
   */
  private static final String NAMESPACE = "seatrush:demo:";

  private final UserTokenSigner users;

  public DemoLogins(UserTokenSigner users) {
    this.users = users;
  }

  /**
   * Issues a token for a name.
   *
   * @param admin whether the token carries the role the admin endpoints of section 12.2 demand
   */
  public IssuedToken<UserToken> login(String username, boolean admin) {
    Objects.requireNonNull(username, "username must not be null");
    String name = username.strip();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    return users.issue(userIdOf(name), name, admin ? Set.of(UserToken.ADMIN) : Set.of());
  }

  /**
   * The same name is always the same buyer.
   *
   * <p>Case folded, so that logging in as Ada and as ada is not two people with one hold each.
   */
  public static UUID userIdOf(String username) {
    String canonical = NAMESPACE + username.strip().toLowerCase(Locale.ROOT);
    return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
  }
}
