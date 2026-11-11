package com.seatrush.security.tokens;

/**
 * Reading a user JWT back, whoever signed it (README section 13).
 *
 * <p>Two implementations, and which one a deployment gets is the difference between a laptop and a
 * cluster. {@link UserTokenSigner} both mints and checks HS256 tokens, which is what the demo login
 * of README section 12.1 needs and what makes a benchmark repeatable. {@link JwksUserTokens} only
 * checks, against the RS256 keys of an identity provider that is not this system.
 *
 * <p>The interface exists so that the gateway's authentication filter depends on the question and
 * not on the answer. Before it, that filter held a {@code UserTokenSigner}, which is a class that
 * can mint: a gateway configured to trust somebody else's identity provider would still have been
 * holding a secret it could issue tokens with, and the one place in this system that must not be
 * able to do that is the one that validates.
 *
 * <p>Minting is deliberately absent from this interface. There is no shape of {@code issue} that
 * both implementations could honestly provide, because the second one has no signing key and no
 * business having one.
 */
public interface UserTokens {

  /**
   * Reads a user token back.
   *
   * @throws TokenException if it was not signed for this deployment, has expired, or is missing a
   *     claim this build needs
   */
  UserToken verify(String token);
}
