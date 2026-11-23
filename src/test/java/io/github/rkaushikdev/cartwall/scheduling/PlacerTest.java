package io.github.rkaushikdev.cartwall.scheduling;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.scheduling.Placer.placeWeek;
import static io.github.rkaushikdev.cartwall.scheduling.Placer.spread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Candidate;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.Rotation;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.domain.Violation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlacerTest {

    private static final List<LocalDate> WEEKDAYS =
            IntStream.range(0, 5).mapToObj(MONDAY::plusDays).toList();
    private static final Comparator<Landed> AIR_ORDER =
            Comparator.comparing(Landed::day).thenComparingInt(Landed::hour).thenComparingInt(Landed::number);

    private AccountBook accounts;
    private House house;
    private List<Lay> lays;

    @BeforeEach
    void layOutTheWeek() {
        accounts = Fixtures.accounts();
        house = Fixtures.house(Fixtures.cluster(), accounts, Fixtures.cards(), Fixtures.programming());
        ClockGrid grid = house.programming().get("KWLO");
        lays = WEEKDAYS.stream()
                .map(day -> new Lay(DayBoard.build(grid, day), house.book("KWLO", day)))
                .toList();
    }

    private Ask anAsk(String code, String daypart, SpotLength length, String cart, int line) {
        Advertiser advertiser = accounts.advertiser(code);
        return new Ask("25-1044", line, advertiser, daypart, length, ClassOfTime.FIXED, cart, advertiser.agency());
    }

    private Ask anAsk() {
        return anAsk("HYVEE", "AMD", THIRTY, "", 1);
    }

    /** Nothing the placer put down may break a rule it was checking. */
    private void assertNoViolations() {
        for (Lay lay : lays) {
            List<Candidate> around = Candidates.boardCandidates(lay.board(), accounts);
            for (Candidate candidate : around) {
                List<Candidate> others =
                        around.stream().filter(other -> other != candidate).toList();
                List<Violation> broken = lay.book().separation().violations(candidate, others);
                assertThat(broken).as(candidate + " broke " + broken).isEmpty();
            }
        }
    }

    /** Pack the morning drive breaks of one day so nothing else fits. */
    private static void fill(Lay lay, String code) {
        int line = 1;
        for (int hour : List.of(6, 7, 8, 9)) {
            for (int number : List.of(2, 3, 4, 5)) {
                for (int spot = 0; spot < 6; spot++) {
                    lay.board()
                            .avail(hour, number)
                            .add(new Placement("25-0001", line, code, THIRTY, ClassOfTime.IMMEDIATE));
                    line++;
                }
            }
        }
    }

    private static List<Landed> on(Result result, LocalDate day) {
        return result.landed().stream().filter(spot -> spot.day().equals(day)).toList();
    }

    @Test
    void spreadingACountOverBucketsPutsTheExtrasAtTheFront() {
        assertThat(spread(12, 5)).containsExactly(3, 3, 2, 2, 2);
        assertThat(spread(10, 5)).containsExactly(2, 2, 2, 2, 2);
        assertThat(spread(3, 5)).containsExactly(1, 1, 1, 0, 0);
        assertThat(spread(0, 5)).containsExactly(0, 0, 0, 0, 0);
        assertThat(spread(47, 7).stream().mapToInt(Integer::intValue).sum()).isEqualTo(47);
    }

    @Test
    void aCappedSpreadNeverPutsMoreThanTheCapInABucket() {
        assertThat(spread(12, 5, 2)).containsExactly(2, 2, 2, 2, 2);
        assertThat(spread(12, 5, 3)).containsExactly(3, 3, 2, 2, 2);
        assertThat(spread(12, 5, 0)).containsExactly(3, 3, 2, 2, 2);
    }

    @Test
    void aCappedLineSpillsOnlyOntoDaysUnderTheCap() {
        fill(lays.get(0), "VGM");
        Result result = Placer.placeWeek(lays, accounts, anAsk(), 10, Optional.empty(), 3);
        assertThat(result.isComplete()).isTrue();
        assertThat(on(result, MONDAY)).isEmpty();
        for (LocalDate day : WEEKDAYS.subList(1, 5)) {
            assertThat(on(result, day)).hasSizeLessThanOrEqualTo(3);
        }
        assertThat(on(result, MONDAY.plusDays(1))).hasSize(3);
        assertThat(result.landed()).hasSize(10);
    }

    @Test
    void whatTheCapLeavesNowhereToGoIsMissedWithTheReason() {
        fill(lays.get(0), "VGM");
        fill(lays.get(1), "VGM");
        Result result = Placer.placeWeek(lays, accounts, anAsk(), 10, Optional.empty(), 2);
        assertThat(result.landed()).hasSize(6);
        assertThat(result.missed()).hasSize(4);
        Result over = Placer.placeWeek(
                lays.subList(2, 5), accounts, anAsk("HYVEE", "AMD", THIRTY, "", 2), 8, Optional.empty(), 2);
        assertThat(over.landed()).hasSize(6);
        assertThat(over.missed())
                .extracting(Miss::reason)
                .containsExactly("the line runs no more than 2 a day", "the line runs no more than 2 a day");
    }

    @Test
    void spreadingOverNothingOrBelowNothingIsRefused() {
        assertThatThrownBy(() -> spread(12, 0)).isInstanceOf(PlacerError.class).hasMessageContaining("no buckets");
        assertThatThrownBy(() -> spread(-1, 5)).isInstanceOf(PlacerError.class).hasMessageContaining("negative count");
    }

    @Test
    void aLayRefusesABoardAndRulesThatDoNotMatch() {
        ClockGrid grid = house.programming().get("KWLO");
        assertThatThrownBy(() -> new Lay(DayBoard.build(grid, MONDAY), house.book("KWLO", WEEKDAYS.get(1))))
                .isInstanceOf(PlacerError.class)
                .hasMessageContaining("came with rules for");
        assertThatThrownBy(() -> new Lay(DayBoard.build(grid, MONDAY), house.book("KCRR", MONDAY)))
                .isInstanceOf(PlacerError.class)
                .hasMessageContaining("board came with KCRR's rules");
    }

    @Test
    void twelveAWeekLandsThreeThreeTwoTwoTwo() {
        Result result = placeWeek(lays, accounts, anAsk(), 12);
        assertThat(result.placed()).isEqualTo(12);
        assertThat(result.isComplete()).isTrue();
        assertThat(WEEKDAYS.stream().map(day -> result.byDay().get(day))).containsExactly(3, 3, 2, 2, 2);
    }

    @Test
    void everythingLandsInsideTheDaypartThatWasBought() {
        Result result = placeWeek(lays, accounts, anAsk(), 20);
        assertThat(result.hours()).allMatch(hour -> Set.of(6, 7, 8, 9).contains(hour));
        assertThat(result.placed()).isEqualTo(20);
    }

    @Test
    void threeSpotsInOneDayLandInThreeDifferentHours() {
        Result result = placeWeek(lays, accounts, anAsk(), 12);
        List<Landed> monday = on(result, MONDAY);
        assertThat(monday).hasSize(3);
        assertThat(monday.stream().map(Landed::hour).collect(Collectors.toSet()))
                .hasSize(3);
    }

    @Test
    void aHeavyScheduleKeepsSpreadingRatherThanStackingOneHour() {
        Result result = placeWeek(lays, accounts, anAsk(), 20);
        assertThat(result.placed()).isEqualTo(20);
        for (LocalDate day : WEEKDAYS) {
            assertThat(on(result, day).stream().map(Landed::hour).sorted()).containsExactly(6, 7, 8, 9);
        }
    }

    @Test
    void aDaypartRunsOutOfRoomLongBeforeItRunsOutOfUnits() {
        // Morning drive is sixteen breaks a day and ninety six units, but the half hour rule
        // means one advertiser cannot have more than about six of them.
        Result result = placeWeek(lays, accounts, anAsk(), 40);
        assertThat(result.placed()).isLessThan(40);
        assertThat(result.shortBy()).isPositive();
        assertThat(Collections.max(result.byDay().values())).isLessThanOrEqualTo(6);
        for (Miss miss : result.missed()) {
            assertThat(miss.blocked()).containsKey("SEP-ADV");
        }
    }

    @Test
    void nothingLandsWhereTheHalfHourRuleWouldBeBroken() {
        Result result = placeWeek(lays, accounts, anAsk(), 20);
        for (LocalDate day : WEEKDAYS) {
            List<Integer> hours = on(result, day).stream().map(Landed::hour).toList();
            assertThat(new HashSet<>(hours))
                    .as("two in one hour breaks the half hour rule")
                    .hasSameSizeAs(hours);
        }
    }

    @Test
    void aDayThatCannotTakeItsShareSpillsOntoItsNeighbours() {
        fill(lays.get(2), "SLUMB");
        Result result = placeWeek(lays, accounts, anAsk(), 10);
        assertThat(result.placed()).isEqualTo(10);
        assertThat(result.isComplete()).isTrue();
        Map<LocalDate, Integer> tally = result.byDay();
        assertThat(tally.getOrDefault(WEEKDAYS.get(2), 0)).isZero();
        assertThat(tally.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
    }

    @Test
    void theSpillGoesToTheNearestDayThatCanTakeIt() {
        fill(lays.get(0), "SLUMB");
        Map<LocalDate, Integer> tally = placeWeek(lays, accounts, anAsk(), 5).byDay();
        assertThat(tally.getOrDefault(MONDAY, 0)).isZero();
        assertThat(tally.get(WEEKDAYS.get(1))).isEqualTo(2);
    }

    @Test
    void aWeekWithNowhereLeftReportsAMissForEverySpot() {
        lays.forEach(lay -> fill(lay, "SLUMB"));
        Result result = placeWeek(lays, accounts, anAsk(), 4);
        assertThat(result.placed()).isZero();
        assertThat(result.shortBy()).isEqualTo(4);
        assertThat(result.isComplete()).isFalse();
        assertThat(result.missed()).allMatch(miss -> miss.toString().contains("no break in the daypart had room"));
    }

    @Test
    void aMissCausedBySeparationNamesTheRulesThatDidIt() {
        // Fill every drive break on one day with the same advertiser we are placing, leaving one
        // unit free in each so there is room but no clean room.
        for (int hour : List.of(6, 7, 8, 9)) {
            for (int number : List.of(2, 3, 4, 5)) {
                for (int line = 1; line <= 5; line++) {
                    lays.get(0)
                            .board()
                            .avail(hour, number)
                            .add(new Placement("25-0001", line, "HYVEE", THIRTY, ClassOfTime.IMMEDIATE));
                }
            }
        }
        lays.subList(1, lays.size()).forEach(lay -> fill(lay, "SLUMB"));
        Result result = placeWeek(lays, accounts, anAsk(), 1);
        assertThat(result.shortBy()).isEqualTo(1);
        Miss miss = result.missed().get(0);
        assertThat(miss.toString()).contains("every open break was blocked");
        assertThat(miss.blocked()).containsKey("SEP-ADV");
    }

    @Test
    void aFlightWithNoDaysInThisWeekMissesTheWholeLot() {
        Result result = placeWeek(List.of(), accounts, anAsk(), 6);
        assertThat(result.placed()).isZero();
        assertThat(result.shortBy()).isEqualTo(1);
        assertThat(result.missed().get(0).toString()).contains("no air days");
    }

    @Test
    void placingNothingPlacesNothingAndComplainsAboutNothing() {
        Result result = placeWeek(lays, accounts, anAsk(), 0);
        assertThat(result.placed()).isZero();
        assertThat(result.isComplete()).isTrue();
    }

    @Test
    void placingANegativeCountIsRefused() {
        assertThatThrownBy(() -> placeWeek(lays, accounts, anAsk(), -1))
                .isInstanceOf(PlacerError.class)
                .hasMessageContaining("negative number");
    }

    @Test
    void aRotationHandsTheCartsOutAcrossTheWeek() {
        Rotation rotation = Rotation.even("C1204", "C1205", "C1206");
        Result result = placeWeek(lays, accounts, anAsk(), 12, rotation);
        List<String> carts = result.landed().stream().map(Landed::cart).toList();
        assertThat(Collections.frequency(carts, "C1204")).isEqualTo(4);
        assertThat(Collections.frequency(carts, "C1205")).isEqualTo(4);
        assertThat(Collections.frequency(carts, "C1206")).isEqualTo(4);
    }

    @Test
    void withNoRotationEverySpotCarriesTheAsksOwnCart() {
        Result result = placeWeek(lays, accounts, anAsk("HYVEE", "AMD", THIRTY, "C1204", 1), 5);
        assertThat(result.landed().stream().map(Landed::cart).collect(Collectors.toSet()))
                .isEqualTo(Set.of("C1204"));
    }

    @Test
    void sixtiesTakeTwoUnitsEachAndStillPlace() {
        Result result = placeWeek(lays, accounts, anAsk("HYVEE", "AMD", SIXTY, "", 1), 10);
        assertThat(result.placed()).isEqualTo(10);
        assertThat(result.isComplete()).isTrue();
    }

    @Test
    void twoLinesOfTheSameAdvertiserKeepOutOfEachOthersWay() {
        Result first = placeWeek(lays, accounts, anAsk("HYVEE", "AMD", THIRTY, "", 1), 10);
        Result second = placeWeek(lays, accounts, anAsk("HYVEE", "AMD", THIRTY, "", 2), 10);
        assertThat(first.placed()).isEqualTo(10);
        assertThat(second.placed()).isEqualTo(10);
        assertNoViolations();
    }

    @Test
    void competingDealersDoNotLandInTheSameBreak() {
        Result ford = placeWeek(lays, accounts, anAsk("DEALF", "AMD", THIRTY, "", 1), 15);
        Result chev = placeWeek(lays, accounts, anAsk("DEALC", "AMD", THIRTY, "", 2), 15);
        assertThat(ford.placed()).isEqualTo(15);
        assertThat(chev.placed()).isEqualTo(15);
        List<List<Object>> breaks = new ArrayList<>();
        for (Landed spot : ford.landed()) {
            breaks.add(List.of(spot.day(), spot.hour(), spot.number()));
        }
        for (Landed spot : chev.landed()) {
            breaks.add(List.of(spot.day(), spot.hour(), spot.number()));
        }
        assertThat(new HashSet<>(breaks)).hasSameSizeAs(breaks);
        assertNoViolations();
    }

    @Test
    void theResultSortsIntoAirOrderHoweverTheSpillRan() {
        fill(lays.get(1), "SLUMB");
        Result result = placeWeek(lays, accounts, anAsk(), 9);
        assertThat(result.landed()).isSortedAccordingTo(AIR_ORDER);
    }

    @Test
    void aSaturdayLinePlacesOnTheSaturdayGrid() {
        LocalDate saturday = LocalDate.of(2025, 8, 9);
        ClockGrid grid = house.programming().get("KWLO");
        List<Lay> weekend = List.of(new Lay(DayBoard.build(grid, saturday), house.book("KWLO", saturday)));
        Result result = placeWeek(weekend, accounts, anAsk("HYVEE", "SAT", THIRTY, "", 1), 6);
        assertThat(result.placed()).isEqualTo(6);
        assertThat(result.hours()).allMatch(hour -> hour >= 6 && hour < 24);
    }
}
