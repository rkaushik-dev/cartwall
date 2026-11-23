package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.Fixtures.aLine;
import static io.github.rkaushikdev.cartwall.Fixtures.anOrder;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class OrderTest {

    // 2025-08-04 is a Monday. 2025-08-07 is the Thursday of that week.
    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate THURSDAY = LocalDate.of(2025, 8, 7);
    private static final LocalDate END = LocalDate.of(2025, 8, 29);

    static Flight aFlight(LocalDate first, LocalDate last) {
        return Flight.build(first, last, "M-F", 12);
    }

    static Flight aFlight() {
        return aFlight(MONDAY, END);
    }

    private static List<Integer> counts(Flight flight) {
        return flight.weeklyCounts().stream().map(Flight.WeekCount::count).toList();
    }

    @Test
    void aFlightThatCannotBeWritten() {
        assertThatThrownBy(() -> Flight.build(END, MONDAY, "M-F", 12))
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("runs backwards");
        assertThatThrownBy(() -> new Flight(MONDAY, END, Set.of(), 12))
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("runs on no days");
        assertThatThrownBy(() -> Flight.build(MONDAY, END, "M-F", 0))
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("is not a schedule");
    }

    @Test
    void aFlightOnlyRunsOnTheDaysItWasSold() {
        Flight flight = aFlight();
        assertThat(flight.runsOn(MONDAY)).isTrue();
        assertThat(flight.runsOn(LocalDate.of(2025, 8, 8))).isTrue();
        assertThat(flight.runsOn(LocalDate.of(2025, 8, 9))).isFalse();
        assertThat(flight.runsOn(LocalDate.of(2025, 8, 3))).isFalse();
        assertThat(flight.runsOn(LocalDate.of(2025, 9, 1))).isFalse();
    }

    @Test
    void aFlightListsItsAirDaysInOrder() {
        Flight flight = Flight.build(MONDAY, LocalDate.of(2025, 8, 10), "M,W,F", 3);
        assertThat(flight.airDays())
                .containsExactly(LocalDate.of(2025, 8, 4), LocalDate.of(2025, 8, 6), LocalDate.of(2025, 8, 8));
    }

    @Test
    void fourWholeWeeksOfTwelveAWeekIsFortyEight() {
        Flight flight = aFlight();
        assertThat(flight.weeks()).hasSize(4);
        assertThat(counts(flight)).containsExactly(12, 12, 12, 12);
        assertThat(flight.spots()).isEqualTo(48);
    }

    @Test
    void aStubWeekIsProratedDownByTheDaysItActuallyHas() {
        // Starts Thursday, so two of the five weekday air days are left.
        Flight flight = aFlight(THURSDAY, END);
        assertThat(counts(flight)).containsExactly(4, 12, 12, 12);
        assertThat(flight.spots()).isEqualTo(40);
    }

    @Test
    void theStubWeekNeverRoundsUp() {
        Flight flight = Flight.build(LocalDate.of(2025, 8, 8), END, "M-F", 6);
        // One day of five, six a week. Six fifths is 1.2 and the client gets one.
        assertThat(flight.countIn(new BroadcastWeek(MONDAY))).isEqualTo(1);
    }

    @Test
    void aPaidWeekIsNeverLeftEmpty() {
        Flight flight = Flight.build(LocalDate.of(2025, 8, 8), END, "M-F", 2);
        // Two fifths of a spot rounds to nothing, and nothing is not an answer.
        assertThat(flight.countIn(new BroadcastWeek(MONDAY))).isEqualTo(1);
    }

    @Test
    void aWeekTheFlightDoesNotTouchGetsNothing() {
        Flight flight = aFlight();
        assertThat(flight.countIn(new BroadcastWeek(LocalDate.of(2025, 7, 28)))).isZero();
        assertThat(flight.countIn(new BroadcastWeek(LocalDate.of(2025, 9, 1)))).isZero();
    }

    @Test
    void aWeekendFlightProratesAgainstItsOwnTwoDays() {
        Flight flight = Flight.build(LocalDate.of(2025, 8, 10), LocalDate.of(2025, 8, 24), "Sa,Su", 4);
        // Starts on a Sunday, so the first week has one of its two air days.
        assertThat(counts(flight)).containsExactly(2, 4, 4);
    }

    @Test
    void aFlightReadsTheWayItPrintsOnAConfirmation() {
        assertThat(aFlight()).hasToString("08/04-08/29 M-F 12x");
    }

    @Test
    void aLineThatCannotBeWritten() {
        assertThatThrownBy(() -> aLine(0).build())
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("not a line number");
        assertThatThrownBy(() -> aLine(1).station("").build())
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("names no station");
        assertThatThrownBy(() -> aLine(1).daypart("").build())
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("names no daypart");
        assertThatThrownBy(() -> aLine(1).rate("-1.00").build())
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("priced at -");
    }

    @Test
    void aLineIsWorthItsRateTimesTheSpotsThatWillRun() {
        Line line = aLine(1).rate("60.00").build();
        assertThat(line.spots()).isEqualTo(48);
        assertThat(line.value()).isEqualTo(Money.parse("2,880.00"));
        assertThat(line.valueIn(new BroadcastWeek(MONDAY))).isEqualTo(Money.parse("720.00"));
    }

    @Test
    void aLineKnowsWhetherProductionHasDubbedAnythingForIt() {
        assertThat(aLine(1).build().hasCopy()).isFalse();
        assertThat(aLine(1).carts("C1204", "C1205").build().hasCopy()).isTrue();
    }

    @Test
    void anOrderThatCannotBeWritten() {
        assertThatThrownBy(() -> anOrder().number("").build())
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("needs a number");
        assertThatThrownBy(() -> anOrder().advertiser("").build())
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("names no advertiser");
        assertThatThrownBy(() -> anOrder()
                        .lines(aLine(1).build(), aLine(1).station("KCRR").build())
                        .build())
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("numbers line 1 twice");
    }

    @Test
    void anOrderAddsUpItsLines() {
        Order order = anOrder()
                .lines(
                        aLine(1).rate("60.00").build(),
                        aLine(2).station("KCRR")
                                .rate("40.00")
                                .grade(ClassOfTime.NOTICE)
                                .length(SIXTY)
                                .build())
                .build();
        assertThat(order.size()).isEqualTo(2);
        assertThat(order.spots()).isEqualTo(96);
        assertThat(order.value()).isEqualTo(Money.parse("4,800.00"));
        assertThat(order.valueIn(new BroadcastWeek(MONDAY))).isEqualTo(Money.parse("1,200.00"));
        assertThat(order).hasToString("25-1044 HYVEE $4,800.00");
    }

    @Test
    void anOrderFindsItsOwnLines() {
        Order order = anOrder()
                .lines(aLine(1).build(), aLine(2).station("KCRR").build())
                .build();
        assertThat(order.line(2).station()).isEqualTo("KCRR");
        assertThat(StreamSupport.stream(order.spliterator(), false).map(Line::number))
                .containsExactly(1, 2);
        assertThatThrownBy(() -> order.line(9)).isInstanceOf(OrderError.class).hasMessageContaining("has no line 9");
    }

    @Test
    void anOrderReportsWhichStationsItTouchesOnceEach() {
        Order order = anOrder()
                .lines(
                        aLine(1).station("KWLO").build(),
                        aLine(2).station("KCRR").build(),
                        aLine(3).station("KWLO").build())
                .build();
        assertThat(order.stations()).containsExactly("KWLO", "KCRR");
        assertThat(order.linesOn("KWLO")).extracting(Line::number).containsExactly(1, 3);
        assertThat(order.linesOn("KXEL")).isEmpty();
    }

    @Test
    void anOrderSpansTheEarliestAndLatestAirDatesOfItsLines() {
        Order order = anOrder()
                .lines(
                        aLine(1).flight(aFlight(MONDAY, LocalDate.of(2025, 8, 15)))
                                .build(),
                        aLine(2).flight(aFlight(LocalDate.of(2025, 8, 11), END)).build())
                .build();
        assertThat(order.firstAir()).isEqualTo(MONDAY);
        assertThat(order.lastAir()).isEqualTo(END);
    }

    @Test
    void anOrderWithNoLinesHasNoDatesToReport() {
        Order order = anOrder().lines(List.of()).build();
        assertThatThrownBy(order::firstAir).isInstanceOf(OrderError.class).hasMessageContaining("has no lines");
        assertThatThrownBy(order::lastAir).isInstanceOf(OrderError.class).hasMessageContaining("has no lines");
    }

    @Test
    void onlyAConfirmedOrderMayBeScheduled() {
        Order order = anOrder().build();
        assertThat(order.status()).isSameAs(OrderStatus.PENDING);
        assertThat(order.status().maySchedule()).isFalse();
        assertThat(order.confirmed().status().maySchedule()).isTrue();
        assertThat(order.cancelled().status().maySchedule()).isFalse();
    }

    @Test
    void anEmptyOrderCannotBeConfirmed() {
        assertThatThrownBy(() -> anOrder().lines(List.of()).build().confirmed())
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("no lines to confirm");
    }

    @Test
    void aCancelledOrderDoesNotComeBack() {
        Order order = anOrder().build().cancelled();
        assertThatThrownBy(order::confirmed).isInstanceOf(OrderError.class).hasMessageContaining("was cancelled");
    }

    @Test
    void changingAnOrderLeavesTheOriginalAlone() {
        Order order = anOrder().build();
        Order revised = order.withLines(
                List.of(aLine(1).build(), aLine(2).station("KCRR").build()));
        assertThat(order.size()).isEqualTo(1);
        assertThat(revised.size()).isEqualTo(2);
        assertThat(revised.number()).isEqualTo(order.number());
        assertThat(revised.status()).isSameAs(order.status());
    }

    @Test
    void anOrderBillsEachBroadcastMonthForTheWeeksInIt() {
        Order order = anOrder()
                .lines(
                        aLine(1).build(),
                        aLine(2).rate("40.00")
                                .flight(LocalDate.of(2025, 8, 25), LocalDate.of(2025, 9, 26), "M-F", 5)
                                .build())
                .build();
        // Broadcast August closes on 8/24, so the fourth week of line 1 bills in September.
        assertThat(order.valueByMonth())
                .containsExactly(
                        entry(new BroadcastMonth(2025, 8), Money.dollars(2160)),
                        entry(new BroadcastMonth(2025, 9), Money.dollars(720 + 5 * 5 * 40)));
        assertThat(Money.total(List.copyOf(order.valueByMonth().values()))).isEqualTo(order.value());
    }

    @Test
    void aBonusLineBillsNoMonth() {
        Order order = anOrder().lines(aLine(1).rate("0.00").build()).build();
        assertThat(order.valueByMonth()).isEmpty();
    }
}
