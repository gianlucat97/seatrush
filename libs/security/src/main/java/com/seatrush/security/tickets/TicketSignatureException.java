package com.seatrush.security.tickets;

/**
 * A ticket that does not check out.
 *
 * <p>One exception for every reason, because a gate has one answer: this does not admit anybody. A
 * forged signature, a truncated QR code and a format from a build nobody deployed yet are all the
 * same decision at the turnstile, and telling them apart out loud would help whoever forged it.
 */
public final class TicketSignatureException extends RuntimeException {

  public TicketSignatureException(String message) {
    super(message);
  }

  public TicketSignatureException(String message, Throwable cause) {
    super(message, cause);
  }
}
