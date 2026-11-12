package com.seatrush.security.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The secret admission tokens are signed with, and how long an admission lasts (README sections 13
 * and 22).
 *
 * <p>Two services bind this, and they bind it to the same value: queue-service signs with it and
 * the gateway checks with it. It is not the user token secret, so a queue that was made to sign
 * whatever it liked still could not mint an identity.
 *
 * <p>Ten minutes by default, and the figure is a product decision rather than a security one: it is
 * how long a buyer who reached the front has to choose seats and pay before their slot goes to the
 * next person. It bounds nothing else, because an admission permits only what {@code hold.lua}
 * would have permitted anyway. Note that the same duration also scores the buyer's slot in {@code
 * active:{evt:<id>}}, which is why queue-service reads this property rather than inventing a second
 * one: a token outliving its slot would be a buyer nothing is counting.
 *
 * <p>Only these two keys are bound here. {@code seatrush.admission.rate-per-second} and {@code
 * max-active-users} are under the same prefix and belong to queue-service, which is where the
 * admission job that reads them lives.
 *
 * @param secret base64 of at least 32 bytes, shared by queue-service and the gateway and nothing
 *     else
 * @param tokenTtl how long the purchase area stays open for an admitted buyer
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.admission")
public record AdmissionTokenProperties(
    String secret, @NotNull @DefaultValue("10m") Duration tokenTtl) {

  public AdmissionTokenProperties {
    if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.admission.token-ttl must be positive, was " + tokenTtl);
    }
  }
}
