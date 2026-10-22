package com.seatrush.inventory.adapters.in.web;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.CreateEventCommand;
import com.seatrush.inventory.domain.LayoutGenerator;
import com.seatrush.inventory.domain.SectionLayout;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * The body of {@code POST /api/admin/events} (README section 12.2).
 *
 * <p>The constraints here are the ones that let a bad request be refused with a field list before
 * anything is generated. The rules the domain cannot delegate, such as a section id used twice or
 * an event larger than the generator allows, still live in the domain and surface as the same error
 * code.
 */
public record CreateEventRequest(
    @NotBlank @Size(max = 200) String name,
    @NotNull Instant onSaleAt,
    @NotEmpty @Size(max = 100) @Valid List<SectionRequest> sections) {

  /** One section to generate: a rectangle of {@code rows} by {@code seatsPerRow} seats. */
  public record SectionRequest(
      @NotNull @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{0,15}") String id,
      @NotBlank @Size(max = 100) String name,
      @Positive long priceCents,
      @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
      @Min(1) @Max(LayoutGenerator.MAX_ROWS_PER_SECTION) int rows,
      @Min(1) @Max(LayoutGenerator.MAX_SEATS_PER_ROW) int seatsPerRow) {

    SectionLayout toLayout() {
      return new SectionLayout(id, name, Money.of(priceCents, currency), rows, seatsPerRow);
    }
  }

  /** The command the use case takes, which is this request without its validation annotations. */
  public CreateEventCommand toCommand() {
    return new CreateEventCommand(
        name, onSaleAt, sections.stream().map(SectionRequest::toLayout).toList());
  }
}
