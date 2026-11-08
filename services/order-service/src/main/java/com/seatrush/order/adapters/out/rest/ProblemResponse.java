package com.seatrush.order.adapters.out.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.seatrush.common.error.ErrorCode;
import java.util.UUID;

/**
 * A Problem Details document as this service reads one back from another (README section 12.6).
 *
 * <p>Only the members the checkout acts on are mapped. The rest of RFC 9457 is for a human or a log
 * line, and binding it would couple this client to fields it does not branch on.
 *
 * @param code the stable error code, which is the contract
 * @param detail the sentence describing this occurrence, passed through into the local exception so
 *     a log line says what inventory said
 * @param orderId the order a hold is already frozen for, which inventory sends with {@code
 *     HOLD_ALREADY_USED} for exactly one purpose: so the caller can tell its own retry apart from a
 *     genuine collision. A string and not a {@link java.util.UUID}, because a malformed one is a
 *     deployment skew to be ignored rather than a parse failure on an error path
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ProblemResponse(String code, String detail, String orderId) {

  static ProblemResponse empty() {
    return new ProblemResponse(null, null, null);
  }

  /**
   * The order the hold is frozen for, or null when there is none to read.
   *
   * <p>Null rather than an exception for anything that is not a uuid, for the reason {@link
   * #errorCode()} gives about an unknown code: this is an error path, and a member that cannot be
   * read is one the caller has to carry on without.
   */
  UUID frozenFor() {
    if (orderId == null || orderId.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(orderId.strip());
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }

  /**
   * The code as the shared vocabulary, or null when it is missing or not one this build knows.
   *
   * <p>An unknown name is treated as a missing one rather than as a failure: a newer inventory
   * answering with a code this build has never heard of is a deployment skew, and the caller
   * handles it as inventory being unusable instead of crashing on it.
   */
  ErrorCode errorCode() {
    if (code == null || code.isBlank()) {
      return null;
    }
    try {
      return ErrorCode.of(code);
    } catch (IllegalArgumentException unknownToThisBuild) {
      return null;
    }
  }

  /** The detail, falling back to the code so a message is never empty. */
  String detailOrCode() {
    if (detail != null && !detail.isBlank()) {
      return detail;
    }
    return code == null ? "no detail" : code;
  }
}
