package com.seatrush.messaging;

/**
 * The message is a version of its type that this build does not know.
 *
 * <p>Refusing it is the point. A consumer that reads a version 2 payload with version 1 rules would
 * act on a message whose extra field it cannot see, and the field may be exactly the one that
 * changed what the message means. Parking it in the DLQ keeps it replayable once the consumer is
 * upgraded.
 */
public final class UnsupportedSchemaVersionException extends MessagingException {

  private final String type;
  private final int received;
  private final int supported;

  public UnsupportedSchemaVersionException(String type, int received, int supported) {
    super(
        "%s arrived with schema version %d, this build supports up to %d"
            .formatted(type, received, supported));
    this.type = type;
    this.received = received;
    this.supported = supported;
  }

  public String type() {
    return type;
  }

  public int received() {
    return received;
  }

  public int supported() {
    return supported;
  }
}
