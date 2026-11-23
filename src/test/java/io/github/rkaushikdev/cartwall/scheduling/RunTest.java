package io.github.rkaushikdev.cartwall.scheduling;

import static io.github.rkaushikdev.cartwall.Fixtures.FIXED;
import static io.github.rkaushikdev.cartwall.Fixtures.IMMEDIATE;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.NOTICE;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.scheduling.Run.placementOrder;
import static io.github.rkaushikdev.cartwall.scheduling.Run.scheduleAll;
import static io.github.rkaushikdev.cartwall.scheduling.Run.scheduleOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.LineBuilder;
import io.github.rkaushikdev.cartwall.OrderBuilder;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Credit;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.Rotation;
import io.github.rkaushikdev.cartwall.domain.Slice;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunTest {

    private static final BroadcastWeek WEEK = new BroadcastWeek(MONDAY);
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);

    private AccountBook accounts;
    private Boards boards;

    @BeforeEach
    void openTheRun() {
        accounts = Fixtures.accounts();
        boards = new Boards(Fixtures.house(Fixtures.cluster(), accounts, Fixtures.cards(), Fixtures.programming()));
    }

    /** Ten a week, Monday to Friday of the week of 8/4. */
    static Flight aFlight(int perWeek, LocalDate first, LocalDate last) {
        return Flight.build(first, last, "M-F", perWeek);
    }

    static LineBuilder aLine(int number) {
        return Fixtures.aLine(number).flight(aFlight(10, MONDAY, FRIDAY));
    }

    static OrderBuilder anOrder(Line... lines) {
        return Fixtures.anOrder().lines(lines);
    }

    static Order confirmed(OrderBuilder order) {
        return order.build().confirmed();
    }

    private static List<String> carts(OrderResult result) {
        return result.lines().get(0).result().landed().stream()
                .map(Landed::cart)
                .toList();
    }

    @Test
    void theBoardCacheBuildsEachStationDayOnce() {
        DayBoard first = boards.board("KWLO", MONDAY);
        assertThat(boards.board("KWLO", MONDAY)).isSameAs(first);
        boards.board("KCRR", MONDAY);
        boards.board("KWLO", FRIDAY);
        assertThat(boards.size()).isEqualTo(3);
        assertThat(boards.stations()).containsExactly("KCRR", "KWLO");
        assertThat(boards.days()).containsExactly(MONDAY, FRIDAY);
    }

    @Test
    void theLayForAStationDayCarriesTheMatchingRules() {
        Lay lay = boards.lay("KWLO", MONDAY);
        assertThat(lay.book().call()).isEqualTo("KWLO");
        assertThat(lay.board().day()).isEqualTo(MONDAY);
        assertThat(boards.lay("KWLO", MONDAY)).isSameAs(lay);
    }

    @Test
    void hardestToPlaceGoesFirst() {
        List<Line> lines = List.of(
                aLine(1).grade(IMMEDIATE).rate("12.00").build(),
                aLine(2).grade(FIXED).rate("60.00").build(),
                aLine(3).grade(NOTICE).rate("48.00").build(),
                aLine(4).grade(FIXED).rate("32.00").build());
        assertThat(placementOrder(lines)).extracting(Line::number).containsExactly(2, 4, 3, 1);
    }

    @Test
    void aPendingOrderIsNotScheduled() {
        Order order = anOrder(aLine(1).build()).build();
        assertThatThrownBy(() -> scheduleOrder(boards, accounts, order, WEEK))
                .isInstanceOf(RunError.class)
                .hasMessageContaining("is pending and cannot be scheduled");
    }

    @Test
    void aCancelledOrderIsNotScheduled() {
        Order order = confirmed(anOrder(aLine(1).build())).cancelled();
        assertThatThrownBy(() -> scheduleOrder(boards, accounts, order, WEEK))
                .isInstanceOf(RunError.class)
                .hasMessageContaining("is cancelled");
    }

    @Test
    void anAdvertiserOnCreditHoldIsNotScheduled() {
        accounts.addAdvertiser(Advertiser.build("SLOW", "Slow Pay").withCredit(Credit.HOLD));
        Order order = confirmed(anOrder(aLine(1).build()).advertiser("SLOW"));
        RunError caught = catchThrowableOfType(RunError.class, () -> scheduleOrder(boards, accounts, order, WEEK));
        assertThat(caught).hasMessageContaining("SLOW is on credit hold");
        assertThat(caught.context()).containsEntry("order", "25-1044");
    }

    @Test
    void aStoreWhoseChainIsHeldIsNotScheduled() {
        accounts.addAdvertiser(Advertiser.build("CHAIN", "Chain Office").withCredit(Credit.HOLD));
        accounts.addAdvertiser(Advertiser.build("STORE", "Chain Store").withParent("CHAIN"));
        Order order = confirmed(anOrder(aLine(1).build()).advertiser("STORE"));
        RunError caught = catchThrowableOfType(RunError.class, () -> scheduleOrder(boards, accounts, order, WEEK));
        assertThat(caught).hasMessage("STORE is on credit hold through CHAIN");
        assertThat(caught.context()).containsEntry("advertiser", "STORE");
    }

    @Test
    void aOneLineOrderPlacesItsWeek() {
        OrderResult result = scheduleOrder(boards, accounts, confirmed(anOrder(aLine(1).build())), WEEK);
        assertThat(result.wanted()).isEqualTo(10);
        assertThat(result.placed()).isEqualTo(10);
        assertThat(result.isComplete()).isTrue();
        assertThat(result.shortfalls()).isEmpty();
        assertThat(result.toString()).startsWith("25-1044 10 of 10");
    }

    @Test
    void twoLinesOfOneOrderShareABoardAndStayApart() {
        Order order = confirmed(
                anOrder(aLine(1).build(), aLine(2).daypart("MID").rate("32.00").build()));
        OrderResult result = scheduleOrder(boards, accounts, order, WEEK);
        assertThat(result.placed()).isEqualTo(20);
        assertThat(result.isComplete()).isTrue();
        Set<Integer> hours = boards.board("KWLO", MONDAY).placements().stream()
                .map(timed -> timed.at().hour())
                .collect(Collectors.toSet());
        assertThat(Set.of(6, 7, 8, 9, 10, 11, 12, 13, 14)).containsAll(hours);
    }

    @Test
    void linesOnTwoStationsUseTwoBoards() {
        Order order = confirmed(anOrder(
                aLine(1).build(), aLine(2).station("KCRR").daypart("MID").build()));
        OrderResult result = scheduleOrder(boards, accounts, order, WEEK);
        assertThat(result.placed()).isEqualTo(20);
        assertThat(boards.stations()).containsExactly("KCRR", "KWLO");
    }

    @Test
    void aLineBoughtOnTheTranslatorIsScheduledOnTheParent() {
        Order order = confirmed(anOrder(aLine(1).station("KWLO-LP").build()));
        OrderResult result = scheduleOrder(boards, accounts, order, WEEK);
        assertThat(result.placed()).isEqualTo(10);
        assertThat(boards.stations()).containsExactly("KWLO");
    }

    @Test
    void theResultComesBackInLineNumberOrder() {
        Order order = confirmed(anOrder(
                aLine(1).grade(IMMEDIATE).rate("12.00").daypart("ROS").build(),
                aLine(2).grade(FIXED).rate("60.00").build(),
                aLine(3).grade(NOTICE).rate("48.00").daypart("MID").build()));
        OrderResult result = scheduleOrder(boards, accounts, order, WEEK);
        assertThat(result.lines()).extracting(entry -> entry.line().number()).containsExactly(1, 2, 3);
        assertThat(result.lines()).allMatch(LineResult::isComplete);
    }

    @Test
    void aLineThatDoesNotRunThisWeekWantsNothing() {
        Flight away = aFlight(10, LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 5));
        OrderResult result = scheduleOrder(
                boards, accounts, confirmed(anOrder(aLine(1).flight(away).build())), WEEK);
        assertThat(result.wanted()).isZero();
        assertThat(result.placed()).isZero();
        assertThat(result.isComplete()).isFalse();
        assertThat(result.lines().get(0).result().missed().get(0).toString()).contains("no air days");
    }

    @Test
    void aStubWeekAsksForFewerAndGetsThem() {
        Flight stub = aFlight(10, LocalDate.of(2025, 8, 7), LocalDate.of(2025, 8, 22));
        OrderResult result = scheduleOrder(
                boards, accounts, confirmed(anOrder(aLine(1).flight(stub).build())), WEEK);
        assertThat(result.wanted()).isEqualTo(4);
        assertThat(result.placed()).isEqualTo(4);
    }

    @Test
    void severalCartsOnALineRotateWithoutBeingAsked() {
        Order order = confirmed(anOrder(aLine(1).carts("C1204", "C1205").build()));
        List<String> carts = carts(scheduleOrder(boards, accounts, order, WEEK));
        assertThat(Collections.frequency(carts, "C1204")).isEqualTo(5);
        assertThat(Collections.frequency(carts, "C1205")).isEqualTo(5);
    }

    @Test
    void aNamedRotationBeatsTheCartsOnTheLine() {
        Order order = confirmed(anOrder(aLine(1).carts("C1204", "C1205").build()));
        Rotation weighted = Rotation.weighted(new Slice("C1204", 4), new Slice("C1205", 1));
        List<String> carts = carts(scheduleOrder(boards, accounts, order, WEEK, Map.of(1, weighted)));
        assertThat(Collections.frequency(carts, "C1204")).isEqualTo(8);
        assertThat(Collections.frequency(carts, "C1205")).isEqualTo(2);
    }

    @Test
    void oneCartOnALineGoesOnEverySpot() {
        Order order = confirmed(anOrder(aLine(1).carts("C1204").build()));
        assertThat(Set.copyOf(carts(scheduleOrder(boards, accounts, order, WEEK))))
                .isEqualTo(Set.of("C1204"));
    }

    @Test
    void aShortfallIsReportedLineByLine() {
        Line heavy = aLine(1).flight(aFlight(40, MONDAY, FRIDAY)).build();
        OrderResult result = scheduleOrder(boards, accounts, confirmed(anOrder(heavy)), WEEK);
        assertThat(result.wanted()).isEqualTo(40);
        assertThat(result.placed()).isLessThan(40);
        assertThat(result.isComplete()).isFalse();
        assertThat(result.shortfalls())
                .extracting(entry -> entry.line().number())
                .containsExactly(1);
        assertThat(result.lines().get(0).toString()).startsWith("line 1");
    }

    @Test
    void aWholeWeekOfOrdersRunsDearestFirst() {
        Order small =
                confirmed(anOrder(aLine(1).rate("12.00").daypart("ROS").build()).number("25-1000"));
        Order big = confirmed(anOrder(aLine(1).rate("60.00").build())
                .number("25-2000")
                .advertiser("DEALF")
                .agency("MOP"));
        List<OrderResult> results = scheduleAll(boards, accounts, List.of(small, big), WEEK);
        assertThat(results).extracting(result -> result.order().number()).containsExactly("25-2000", "25-1000");
        assertThat(results).allMatch(OrderResult::isComplete);
    }

    @Test
    void aWeekRunLeavesUnconfirmedOrdersAlone() {
        Order done = confirmed(anOrder(aLine(1).build()).number("25-1000"));
        Order pending = anOrder(aLine(1).build()).number("25-2000").build();
        List<OrderResult> results = scheduleAll(boards, accounts, List.of(done, pending), WEEK);
        assertThat(results).extracting(result -> result.order().number()).containsExactly("25-1000");
    }

    @Test
    void aSixtySecondLinePlacesTheSameCountAsAThirty() {
        Order order = confirmed(anOrder(aLine(1).length(SIXTY).build()));
        assertThat(scheduleOrder(boards, accounts, order, WEEK).placed()).isEqualTo(10);
    }
}
