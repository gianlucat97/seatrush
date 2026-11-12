package com.seatrush.messaging;

/**
 * A message that cannot be turned into something a consumer could act on.
 *
 * <p>One family for every decoding failure, so a consumer has a single thing to catch around the
 * codec and one place to decide to send the record to the DLQ. A business refusal is never one of
 * these: a declined payment or a lost seat race travels as a reply message (README section 10.3).
 */
public abstract class MessagingException extends RuntimeException {

  protected MessagingException(String message) {
    super(message);
  }

  protected MessagingException(String message, Throwable cause) {
    super(message, cause);
  }
}
