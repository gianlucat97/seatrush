package com.seatrush.order.adapters.out.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.seatrush.common.error.ErrorCode;

/**
 * A Problem Details document as this service reads one back from another (README section 12.6).
 *
 * <p>Only the two members the checkout acts on are mapped. The rest of RFC 9457 is for a human or a
 * log line, and binding it would couple this client to fields it does not branch on.
 *
 * @param code the stable error code, which is the contract
 * @param detail the sentence describing this occurrence, passed through into the local exception so
 *     a log line says what inventory said
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ProblemResponse(String code, String detail) {

  static ProblemResponse empty() {
    return new ProblemResponse(null, null);
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
