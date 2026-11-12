package com.seatrush.security.tokens;

/**
 * A token that does not check out.
 *
 * <p>One exception for every reason, for the same motive {@link
 * com.seatrush.security.tickets.TicketSignatureException} has one: the caller has a single answer
 * to give. A missing signature, a secret from another environment, an expired admission and a user
 * token presented where an admission belongs are all a refusal at the gateway, and a response that
 * told them apart would tell whoever is probing which part of their forgery to fix.
 *
 * <p>The message exists for the log on the gateway side, never for the body of the response.
 */
public final class TokenException extends RuntimeException {

  public TokenException(String message) {
    super(message);
  }

  public TokenException(String message, Throwable cause) {
    super(message, cause);
  }
}
