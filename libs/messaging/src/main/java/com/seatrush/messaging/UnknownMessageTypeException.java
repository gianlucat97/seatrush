package com.seatrush.messaging;

/**
 * The {@code type} of the envelope is not in the catalog of this build.
 *
 * <p>Either the producer is newer than this consumer, or the topic carries something that does not
 * belong to it. Both are for an operator to look at, so the record goes to the DLQ rather than
 * being skipped quietly.
 */
public final class UnknownMessageTypeException extends MessagingException {

  private final String type;

  public UnknownMessageTypeException(String type) {
    super("unknown message type: " + type);
    this.type = type;
  }

  /** The unknown name, kept so a log line or a DLQ header can carry it. */
  public String type() {
    return type;
  }
}
