package io.github.rkaushikdev.cartwall.logs;

import static io.github.rkaushikdev.cartwall.Fixtures.FRIDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.LicensedHours;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.scheduling.Avail;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DayLogTest {

    private Map<String, ClockGrid> programming;
    private ClockGrid grid;
    private DayBoard board;

    @BeforeEach
    void layOutMonday() {
        programming = Fixtures.programming();
        grid = programming.get("KWLO");
        board = DayBoard.build(grid, MONDAY);
    }

    private void put(int hour, int number, String code, SpotLength length, String order, int line, String cart) {
        board.avail(hour, number).add(new Placement(order, line, code, length, ClassOfTime.FIXED, cart, false));
    }

    private void put(int hour, int number, String code, SpotLength length, int line) {
        put(hour, number, code, length, "25-1044", line, "");
    }

    private void put(int hour, int number, String code, int line) {
        put(hour, number, code, THIRTY, line);
    }

    @Test
    void anEmptyDayStillCarriesItsLegalIds() {
        DayLog log = DayLog.build(grid, board);
        assertThat(log.legalIds()).hasSize(24);
        assertThat(log.legalIds().get(0).at()).isEqualTo(AirTime.at(0));
        assertThat(log.legalIds().get(23).at()).isEqualTo(AirTime.at(23));
        assertThat(log.spots()).isEmpty();
    }

    @Test
    void anEmptyDayIsAllHoles() {
        DayLog log = DayLog.build(grid, board);
        assertThat(log.openUnits()).isEqualTo(24 * 24);
        assertThat(log.soldUnits()).isZero();
        assertThat(log.commercialLength()).isEqualTo(new Duration(0));
    }

    @Test
    void aDaytimersLogStopsAtSignOffAndHasNoHolesAfterIt() {
        ClockGrid daytimer = Fixtures.aGrid("KXEL");
        daytimer.license(LicensedHours.of(
                "7:45a-4:45p",
                "7:15a-5:30p",
                "7:00a-6:15p",
                "6:15a-7:45p",
                "5:45a-8:15p",
                "5:30a-8:45p",
                "5:30a-8:45p",
                "6:00a-8:15p",
                "6:30a-7:30p",
                "7:00a-6:30p",
                "6:45a-4:45p",
                "7:30a-4:30p"));
        DayBoard august = DayBoard.build(daytimer, MONDAY);
        DayLog log = DayLog.build(daytimer, august);
        assertThat(log.hours()).first().isEqualTo(6);
        assertThat(log.hours()).last().isEqualTo(20);
        assertThat(log.legalIds())
                .extracting(LogLine::hour)
                .doesNotContain(5, 21)
                .contains(6, 20);
        assertThat(log.inHour(20)).extracting(LogLine::breakNumber).containsOnly(1, 2);
        assertThat(log.openUnits()).isEqualTo(august.totalUnits());
        assertThat(august.inHour(6)).extracting(Avail::number).containsExactly(2, 3, 4, 5);
        assertThat(august.inHour(20)).extracting(Avail::number).containsExactly(2);
        assertThat(log.legalIds()).last().extracting(LogLine::at).isEqualTo(AirTime.at(20, 14, 50));
    }

    @Test
    void aDaytimerSigningOnPartWayIntoAnHourCanStillBeSigned() {
        ClockGrid daytimer = Fixtures.aGrid("KXEL");
        daytimer.license(LicensedHours.of(
                "7:45a-4:45p",
                "7:15a-5:30p",
                "7:00a-6:15p",
                "6:15a-7:45p",
                "5:45a-8:15p",
                "5:30a-8:45p",
                "5:30a-8:45p",
                "6:00a-8:15p",
                "6:30a-7:30p",
                "7:00a-6:30p",
                "6:45a-4:45p",
                "7:30a-4:30p"));
        LocalDate december = LocalDate.of(2025, 12, 1);
        DayLog log = DayLog.build(daytimer, DayBoard.build(daytimer, december));
        assertThat(log.legalIds()).first().extracting(LogLine::at).isEqualTo(AirTime.at(7, 30));
        assertThat(Approval.problems(log)).isEmpty();
    }

    @Test
    void aSpotLandsOnTheLogAtTheSecondItAirs() {
        put(7, 2, "HYVEE", THIRTY, "25-1044", 1, "C1204");
        LogLine spot = DayLog.build(grid, board).spots().get(0);
        assertThat(spot.at()).isEqualTo(AirTime.at(7, 10));
        assertThat(spot.advertiser()).isEqualTo("HYVEE");
        assertThat(spot.cart()).isEqualTo("C1204");
        assertThat(spot.breakNumber()).isEqualTo(2);
        assertThat(spot.hour()).isEqualTo(7);
        assertThat(spot.grade()).contains(ClassOfTime.FIXED);
    }

    @Test
    void aSecondSpotInABreakStartsWhereTheFirstOneEnds() {
        put(7, 2, "HYVEE", SIXTY, 1);
        put(7, 2, "SLUMB", THIRTY, 2);
        assertThat(DayLog.build(grid, board).spots())
                .extracting(LogLine::at)
                .containsExactly(AirTime.at(7, 10), AirTime.at(7, 11));
    }

    @Test
    void theLogReadsInAirOrderAcrossTheWholeDay() {
        put(9, 4, "SLUMB", 1);
        put(7, 2, "HYVEE", 2);
        put(7, 5, "CASEY", 3);
        DayLog log = DayLog.build(grid, board);
        List<AirTime> times =
                StreamSupport.stream(log.spliterator(), false).map(LogLine::at).toList();
        assertThat(times).isSorted();
        assertThat(log.spots()).extracting(LogLine::advertiser).containsExactly("HYVEE", "CASEY", "SLUMB");
    }

    @Test
    void theHolesInABreakShowUpAfterWhatSold() {
        put(7, 2, "HYVEE", SIXTY, 1);
        assertThat(DayLog.build(grid, board).inBreak(7, 2))
                .extracting(LogLine::element)
                .containsExactly(Element.SPOT, Element.UNSOLD, Element.UNSOLD, Element.UNSOLD, Element.UNSOLD);
    }

    @Test
    void theAutomationIsNotHandedTheHoles() {
        put(7, 2, "HYVEE", 1);
        DayLog log = DayLog.build(grid, board);
        assertThat(log.airable()).allMatch(entry -> entry.element().airs());
        assertThat(log.airable().size()).isLessThan(log.size());
        assertThat(log.airable()).extracting(LogLine::element).doesNotContain(Element.UNSOLD);
    }

    @Test
    void aFullBreakLeavesNoHoles() {
        for (int line = 1; line <= 6; line++) {
            put(7, 2, "SLUMB", line);
        }
        assertThat(DayLog.build(grid, board).inBreak(7, 2))
                .extracting(LogLine::element)
                .isEqualTo(Collections.nCopies(6, Element.SPOT));
    }

    @Test
    void theNetworkBreakIsOnTheLogAndIsNobodysToSell() {
        ClockGrid talk = programming.get("KCRR");
        DayLog log = DayLog.build(talk, DayBoard.build(talk, MONDAY));
        List<LogLine> network = log.inHour(7).stream()
                .filter(entry -> entry.element() == Element.NETWORK)
                .toList();
        assertThat(network).hasSize(1);
        assertThat(network.get(0).at()).isEqualTo(AirTime.at(7, 30));
        assertThat(network.get(0).length()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void aPreemptedHourIsSimplyNotOnTheLog() {
        ClockGrid talk = programming.get("KCRR");
        DayLog log = DayLog.build(talk, DayBoard.build(talk, FRIDAY));
        assertThat(log.hours()).doesNotContain(20).contains(18);
        assertThat(log.legalIds()).hasSize(20);
    }

    @Test
    void theLogTotalsWhatActuallySold() {
        put(7, 2, "HYVEE", SIXTY, 1);
        put(7, 3, "SLUMB", THIRTY, 2);
        put(9, 2, "CASEY", THIRTY, 3);
        DayLog log = DayLog.build(grid, board);
        assertThat(log.soldUnits()).isEqualTo(4);
        assertThat(log.commercialLength()).isEqualTo(Duration.ofSeconds(120));
        assertThat(log.openUnits()).isEqualTo(24 * 24 - 4);
    }

    @Test
    void theLogFindsOneOrderAndOneLineOfIt() {
        put(7, 2, "HYVEE", THIRTY, "25-1044", 1, "");
        put(8, 2, "HYVEE", THIRTY, "25-1044", 2, "");
        put(9, 2, "SLUMB", THIRTY, "25-2000", 1, "");
        DayLog log = DayLog.build(grid, board);
        assertThat(log.forOrder("25-1044")).hasSize(2);
        assertThat(log.forOrder("25-1044", 2)).hasSize(1);
        assertThat(log.forOrder("25-1044", 2).get(0).hour()).isEqualTo(8);
        assertThat(log.forOrder("25-9999")).isEmpty();
    }

    @Test
    void theLogFindsEverythingOneAdvertiserHasOnTheDay() {
        put(7, 2, "HYVEE", 1);
        put(9, 2, "HYVEE", 2);
        put(9, 3, "SLUMB", 3);
        DayLog log = DayLog.build(grid, board);
        assertThat(log.forAdvertiser("HYVEE")).extracting(LogLine::hour).containsExactly(7, 9);
        assertThat(log.forAdvertiser("NOBODY")).isEmpty();
    }

    @Test
    void aLogLinePrintsTheWayTheOperatorReadsIt() {
        put(7, 2, "HYVEE", THIRTY, "25-1044", 1, "C1204");
        DayLog log = DayLog.build(grid, board);
        assertThat(log.spots().get(0).toString()).contains("7:10:00a", "C1204", "HYVEE");
        assertThat(log.legalIds().get(7).toString()).contains("legal id");
    }

    @Test
    void aGridAndABoardFromTwoDifferentStationsAreRefused() {
        DayBoard other = DayBoard.build(programming.get("KCRR"), MONDAY);
        assertThatThrownBy(() -> DayLog.build(grid, other))
                .isInstanceOf(LogError.class)
                .hasMessageContaining("was handed KCRR's board");
    }

    @Test
    void aSundayLogUsesTheSundayGrid() {
        ClockGrid farm = programming.get("KCNZ");
        DayLog monday = DayLog.build(farm, DayBoard.build(farm, MONDAY));
        DayLog sunday = DayLog.build(farm, DayBoard.build(farm, LocalDate.of(2025, 8, 10)));
        // The farm clock runs Monday to Saturday and has fewer units than talk.
        assertThat(monday.openUnits()).isLessThan(sunday.openUnits());
    }
}
