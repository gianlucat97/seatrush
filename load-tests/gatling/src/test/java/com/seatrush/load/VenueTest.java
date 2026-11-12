package com.seatrush.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The venue and the seat picking, which is the one piece of this module with an answer to check.
 *
 * <p>Worth testing because it is where a load test lies to itself most easily. A picker that
 * drifted off the end of a row would ask for seats the event does not have and read back a
 * validation error as contention; one that picked scattered seats would never collide on a block;
 * and one that ignored the hot sections would spread the demand evenly over the venue and measure a
 * sale nobody is fighting over.
 */
class VenueTest {

  private static final LoadProfile SMALL = LoadProfile.small();

  @Test
  void laysOutTheVenueTheProfileAsksFor() {
    Venue venue = Venue.of(SMALL);

    assertThat(venue.sectionIds())
        .containsExactly("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
    assertThat(venue.rows()).isEqualTo(SMALL.rows());
    assertThat(venue.seatsPerRow()).isEqualTo(SMALL.seatsPerRow());
  }

  @Test
  void pricesDescendFromTheSectionsThatAreFoughtOver() {
    String json = Venue.of(SMALL).sectionsJson();

    // The two in demand are the two dearest, which is what makes the money reconcile meaningfully.
    assertThat(json).contains("{\"id\":\"A\",\"name\":\"Section A\",\"priceCents\":12000");
    assertThat(json).contains("{\"id\":\"B\",\"name\":\"Section B\",\"priceCents\":11000");
    assertThat(json).contains("{\"id\":\"J\",\"name\":\"Section J\",\"priceCents\":3000");
    assertThat(json).contains("\"rows\":10,\"seatsPerRow\":20");
    assertThat(json).contains("\"currency\":\"EUR\"");
  }

  @Test
  void neverPricesASectionAtNothingHoweverManyThereAre() {
    LoadProfile wide = profileWith("seatrush.load.sections", "26");

    assertThat(Venue.of(wide).sectionsJson())
        .contains("{\"id\":\"Z\",\"name\":\"Section Z\",\"priceCents\":1000");
  }

  @Test
  void everyBlockIsAdjacentInOneRowOfOneSection() {
    Venue venue = Venue.of(SMALL);
    Random random = new Random(SMALL.seed());

    for (int attempt = 0; attempt < 10_000; attempt++) {
      List<String> block = venue.block(random);

      assertThat(block).hasSizeBetween(1, SMALL.maxSeatsPerHold());
      String[] first = block.getFirst().split("-");
      assertThat(venue.sectionIds()).contains(first[0]);
      int row = Integer.parseInt(first[1]);
      int number = Integer.parseInt(first[2]);
      assertThat(row).isBetween(1, venue.rows());
      for (int offset = 0; offset < block.size(); offset++) {
        assertThat(block.get(offset)).isEqualTo(first[0] + "-" + row + "-" + (number + offset));
      }
      // The whole block fits: a seat past the end of the row is a request the event refuses as
      // malformed, and a load test would read that back as contention.
      assertThat(number + block.size() - 1).isLessThanOrEqualTo(venue.seatsPerRow());
    }
  }

  @Test
  void mostBuyersReachForTheSectionsInDemand() {
    Venue venue = Venue.of(SMALL);
    Random random = new Random(SMALL.seed());
    int hot = 0;
    int attempts = 100_000;

    for (int attempt = 0; attempt < attempts; attempt++) {
      String section = venue.block(random).getFirst().split("-")[0];
      if (section.equals("A") || section.equals("B")) {
        hot++;
      }
    }

    assertThat((double) hot / attempts).isCloseTo(SMALL.hotSectionShare(), within(0.01));
  }

  @Test
  void aBlockIsTheSameTwiceForTheSameSeed() {
    Venue venue = Venue.of(SMALL);

    assertThat(venue.block(new Random(42))).isEqualTo(venue.block(new Random(42)));
  }

  @Test
  void aBlockIsAJsonArrayOfSeatIds() {
    String json = Venue.of(SMALL).blockJson(new Random(7));

    assertThat(json).matches("\\[(\"[A-J]-\\d+-\\d+\"(,|(?=])))+]");
  }

  @Test
  void refusesAVenueTheSeatIdsCouldNotName() {
    assertThatThrownBy(() -> Venue.of(profileWith("seatrush.load.sections", "27")))
        .hasMessageContaining("between 1 and 26 sections");
    assertThatThrownBy(() -> Venue.of(profileWith("seatrush.load.sections", "0")))
        .hasMessageContaining("between 1 and 26 sections");
  }

  @Test
  void refusesSectionsInDemandThatAreNotSections() {
    assertThatThrownBy(
            () ->
                Venue.of(
                    profileWith("seatrush.load.sections", "4", "seatrush.load.hotSections", "5")))
        .hasMessageContaining("the sections in demand have to be some of the sections");
  }

  @Test
  void refusesARowTooNarrowForTheBlocksItWouldBeAskedFor() {
    assertThatThrownBy(
            () ->
                Venue.of(
                    profileWith(
                        "seatrush.load.seatsPerRow", "2", "seatrush.load.maxSeatsPerHold", "4")))
        .hasMessageContaining("cannot hold a block of");
  }

  /**
   * The small preset with a few knobs moved, built the way a run builds it.
   *
   * <p>Through the system properties rather than the constructor, which is not only shorter: the
   * record has two dozen components and a test that listed them all would have to be edited every
   * time one is added, which is how a test stops being read.
   */
  private static LoadProfile profileWith(String... properties) {
    for (int index = 0; index < properties.length; index += 2) {
      System.setProperty(properties[index], properties[index + 1]);
    }
    try {
      return LoadProfile.fromSystemProperties();
    } finally {
      for (int index = 0; index < properties.length; index += 2) {
        System.clearProperty(properties[index]);
      }
    }
  }
}
