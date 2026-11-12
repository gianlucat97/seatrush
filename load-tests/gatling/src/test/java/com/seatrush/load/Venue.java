package com.seatrush.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The venue of a run, and how a buyer picks seats in it (README section 18.1).
 *
 * <p>The layout is known here rather than read back from the API, because this is what asked for
 * it: {@code POST /api/admin/events} takes a rectangle per section and the generator is
 * deterministic, so the seat ids follow from the three numbers of {@link LoadProfile} without a
 * single request. {@code GET /api/events/{id}} does not publish the rows and seats per row anyway,
 * and nothing should: a client picks seats off a seat map, not out of arithmetic.
 *
 * <p>Seat ids are {@code <section>-<row>-<number>} with rows and numbers counted from one, which is
 * the format of README section 11.1 and the one the layout generator produces.
 */
record Venue(
    List<String> sectionIds,
    int rows,
    int seatsPerRow,
    int hotSections,
    double hotSectionShare,
    int maxSeatsPerHold) {

  /** The dearest section, in cents. Every section below it is a thousand cheaper. */
  private static final long TOP_PRICE_CENTS = 12_000;

  private static final long PRICE_STEP_CENTS = 1_000;

  private static final String CURRENCY = "EUR";

  static Venue of(LoadProfile profile) {
    if (profile.sections() < 1 || profile.sections() > 26) {
      throw new IllegalArgumentException(
          "a venue here has between 1 and 26 sections, one letter each, but was "
              + profile.sections());
    }
    if (profile.hotSections() < 1 || profile.hotSections() > profile.sections()) {
      throw new IllegalArgumentException(
          "the sections in demand have to be some of the sections, but "
              + profile.hotSections()
              + " of "
              + profile.sections()
              + " were asked for");
    }
    if (profile.seatsPerRow() < profile.maxSeatsPerHold()) {
      throw new IllegalArgumentException(
          "a row of "
              + profile.seatsPerRow()
              + " seats cannot hold a block of "
              + profile.maxSeatsPerHold());
    }
    List<String> ids = new ArrayList<>(profile.sections());
    for (int index = 0; index < profile.sections(); index++) {
      ids.add(String.valueOf((char) ('A' + index)));
    }
    return new Venue(
        List.copyOf(ids),
        profile.rows(),
        profile.seatsPerRow(),
        profile.hotSections(),
        profile.hotSectionShare(),
        profile.maxSeatsPerHold());
  }

  /**
   * The {@code sections} array of the request that creates the event.
   *
   * <p>Prices descend from the first section, so the two the buyers fight over are also the two
   * dearest ones. That is the shape of a real on-sale and it matters for more than realism: the
   * money the invariant checker reconciles is the sum of what was actually bought, so a run where
   * every section cost the same would reconcile even if the wrong prices had been charged.
   */
  String sectionsJson() {
    StringBuilder json = new StringBuilder("[");
    for (int index = 0; index < sectionIds.size(); index++) {
      if (index > 0) {
        json.append(',');
      }
      json.append(
          """
          {"id":"%s","name":"Section %s","priceCents":%d,"currency":"%s","rows":%d,"seatsPerRow":%d}"""
              .formatted(
                  sectionIds.get(index),
                  sectionIds.get(index),
                  priceCentsOf(index),
                  CURRENCY,
                  rows,
                  seatsPerRow));
    }
    return json.append(']').toString();
  }

  /**
   * A block of adjacent seats for one buyer, as the JSON array the hold endpoint takes.
   *
   * <p>Adjacent and not scattered, because that is what a buyer wants and because it is what makes
   * the contention real: two buyers reaching for the same four seats of the same row collide on all
   * four, and the all-or-nothing hold script of README section 9.2 is the thing being tested.
   *
   * <p>The random is the caller's and is seeded per buyer, so the second attempt of one buyer picks
   * a different block while two runs of the same profile pick the same ones.
   */
  String blockJson(Random random) {
    List<String> seats = block(random);
    StringBuilder json = new StringBuilder("[");
    for (int index = 0; index < seats.size(); index++) {
      if (index > 0) {
        json.append(',');
      }
      json.append('"').append(seats.get(index)).append('"');
    }
    return json.append(']').toString();
  }

  /** The seats of one block, in order. */
  List<String> block(Random random) {
    String section = sectionIds.get(sectionIndex(random));
    int size = 1 + random.nextInt(maxSeatsPerHold);
    int row = 1 + random.nextInt(rows);
    int first = 1 + random.nextInt(seatsPerRow - size + 1);
    List<String> seats = new ArrayList<>(size);
    for (int offset = 0; offset < size; offset++) {
      seats.add(section + "-" + row + "-" + (first + offset));
    }
    return seats;
  }

  /** The two best sections most of the time, one of the rest otherwise. */
  private int sectionIndex(Random random) {
    boolean cold = sectionIds.size() > hotSections && random.nextDouble() >= hotSectionShare;
    return cold
        ? hotSections + random.nextInt(sectionIds.size() - hotSections)
        : random.nextInt(hotSections);
  }

  private static long priceCentsOf(int index) {
    // Never below a thousand, however many sections a run asks for.
    return Math.max(PRICE_STEP_CENTS, TOP_PRICE_CENTS - (long) index * PRICE_STEP_CENTS);
  }
}
