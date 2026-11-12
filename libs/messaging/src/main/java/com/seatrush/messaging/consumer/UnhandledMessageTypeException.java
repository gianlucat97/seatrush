package com.seatrush.messaging.consumer;

import com.seatrush.messaging.MessagingException;

/**
 * The message is a type this consumer has no handler for.
 *
 * <p>One of the {@link MessagingException} family on purpose, so it is parked in the DLQ instead of
 * being retried: a handler that is not registered will not appear on the next attempt. Skipping it
 * quietly would be worse, since it means either a topic carrying something that does not belong to
 * it or a service deployed without a step it is supposed to perform, and both are for an operator
 * to see.
 */
public final class UnhandledMessageTypeException extends MessagingException {

  private final String type;

  public UnhandledMessageTypeException(String type) {
    super("no handler is registered for message type " + type);
    this.type = type;
  }

  /** The type nobody handled, kept so a log line or a DLQ header can carry it. */
  public String type() {
    return type;
  }
}
