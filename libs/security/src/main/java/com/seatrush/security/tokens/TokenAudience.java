package com.seatrush.security.tokens;

/**
 * Which of the two things a token is, written into its {@code aud} claim and checked on the way
 * back.
 *
 * <p>The two kinds already have separate secrets, so this is a second lock on a door that is
 * already locked. It is here because the first lock is an operational property and this one is a
 * property of the format: the day somebody configures both services with the same secret, by
 * copying an environment file or by reusing a Kubernetes Secret, the audience is what still refuses
 * a user JWT presented as an admission token. Confused-deputy bugs of exactly that shape are the
 * most common way a JWT deployment fails, and they fail silently.
 */
public enum TokenAudience {

  /** Who somebody is. Minted at login, read by every authenticated endpoint. */
  USER("seatrush:user"),

  /** That somebody may buy right now. Minted by the queue, demanded on purchase commands. */
  ADMISSION("seatrush:admission");

  private final String claim;

  TokenAudience(String claim) {
    this.claim = claim;
  }

  /** The {@code aud} value of this kind of token. */
  public String claim() {
    return claim;
  }
}
