package com.seatrush.inventory.adapters.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seatrush.common.error.ErrorCode;
import com.seatrush.common.error.ProblemDetails;
import com.seatrush.inventory.application.HoldService;
import com.seatrush.inventory.application.PlaceHoldCommand;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hold endpoints of README section 12.4.
 *
 * <p>{@code X-User-Id} is the identity of the caller. The gateway strips whatever a client sent and
 * sets it from the validated JWT (README section 13), so inside the cluster it is trusted; until
 * the gateway exists, a caller sets it itself, which is also what makes the local stack usable.
 *
 * <p>The outcomes of a hold attempt are answered here rather than thrown, because this is where
 * they become status codes: a lost race is a 409 with the seats that were taken, and a user who
 * already holds seats is a different 409 with the hold they already have.
 */
@RestController
@RequestMapping("/api")
public class HoldController {

  private final HoldService holds;

  public HoldController(HoldService holds) {
    this.holds = holds;
  }

  /**
   * Holds seats, all of them or none.
   *
   * <p>{@code Idempotency-Key} is required: a client that retries a hold after a timeout must not
   * end up with two holds, and the key is what makes the retry return the first answer (README
   * section 9.4).
   */
  @PostMapping("/events/{eventId}/holds")
  public ResponseEntity<?> place(
      @PathVariable UUID eventId,
      @RequestHeader("X-User-Id") UUID userId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[\\w-]{1,64}") String idempotencyKey,
      @Valid @RequestBody HoldRequestBody body) {
    HoldResult result =
        holds.place(new PlaceHoldCommand(eventId, userId, body.seatIds(), idempotencyKey));

    return switch (result) {
      case HoldResult.Held held ->
          ResponseEntity.created(locationOf(held.hold().id())).body(HoldResponse.of(held));
      case HoldResult.Conflict conflict ->
          problem(
              ErrorCode.SEAT_UNAVAILABLE,
              "At least one seat is no longer available",
              Map.of("conflictingSeatIds", conflict.conflictingSeatIds()));
      case HoldResult.LimitReached limit ->
          problem(
              ErrorCode.HOLD_LIMIT_REACHED,
              "You already hold seats for this event",
              Map.of("holdId", limit.existingHoldId().toString()));
      case HoldResult.UnknownSeats unknown ->
          problem(
              ErrorCode.VALIDATION_ERROR,
              "This event has no such seats",
              Map.of("unknownSeatIds", unknown.unknownSeatIds()));
      case HoldResult.IdempotencyKeyReused ignored ->
          problem(
              ErrorCode.IDEMPOTENCY_KEY_REUSED,
              "This idempotency key was used for a different request",
              Map.of());
    };
  }

  /** One hold, for its owner only. */
  @GetMapping("/holds/{holdId}")
  public HoldResponse details(@PathVariable UUID holdId, @RequestHeader("X-User-Id") UUID userId) {
    return HoldResponse.of(holds.get(holdId, userId));
  }

  /** Gives the seats back early, which only the owner of an active hold may do. */
  @DeleteMapping("/holds/{holdId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable UUID holdId, @RequestHeader("X-User-Id") UUID userId) {
    holds.release(holdId, userId);
  }

  private static ResponseEntity<Object> problem(
      ErrorCode code, String detail, Map<String, Object> members) {
    return ResponseEntity.status(code.status()).body(ProblemDetails.of(code, detail, members));
  }

  private static URI locationOf(UUID holdId) {
    return URI.create("/api/holds/" + holdId);
  }

  /** The body of a hold request: the seats, and nothing else. */
  public record HoldRequestBody(
      @NotNull @NotEmpty @Size(max = 50)
          List<@Pattern(regexp = "[A-Z0-9][A-Z0-9-]{0,47}") String> seatIds) {}

  /**
   * A hold as the API returns it.
   *
   * <p>{@code version} is present only when the hold was just taken: it is the version of the seat
   * map the change produced, which a client that follows the delta stream uses to know where this
   * hold sits in the sequence.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record HoldResponse(
      UUID holdId,
      UUID eventId,
      List<String> seatIds,
      Instant expiresAt,
      String status,
      Long version) {

    static HoldResponse of(HoldResult.Held held) {
      Hold hold = held.hold();
      return new HoldResponse(
          hold.id(),
          hold.eventId(),
          hold.seatIds(),
          hold.expiresAt(),
          hold.status().name(),
          held.version());
    }

    static HoldResponse of(Hold hold) {
      return new HoldResponse(
          hold.id(), hold.eventId(), hold.seatIds(), hold.expiresAt(), hold.status().name(), null);
    }
  }
}
