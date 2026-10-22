package com.seatrush.messaging;

/** The bytes are not an envelope: broken JSON, a missing required field, an invalid payload. */
public final class MalformedMessageException extends MessagingException {

  public MalformedMessageException(String message) {
    super(message);
  }

  public MalformedMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}
