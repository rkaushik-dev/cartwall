package io.github.rkaushikdev.cartwall.reports;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.reports.Avails.byDay;
import static io.github.rkaushikdev.cartwall.reports.Avails.byDaypart;
import static io.github.rkaushikdev.cartwall.reports.Avails.byHour;
import static io.github.rkaushikdev.cartwall.reports.Avails.thinnestHours;
import static io.github.rkaushikdev.cartwall.reports.Avails.weekOpenUnits;
import static io.github.rkaushikdev.cartwall.reports.Avails.weekValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.RuleBook;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AvailsReportTest {

    private House house;
    private DayBoard board;
    private RuleBook book;

    @BeforeEach
    void layOutMonday() {
        house = Fixtures.house();
        board = DayBoard.build(house.programming().get("KWLO"), MONDAY);
        book = house.book("KWLO", MONDAY);
    }

    private static void put(DayBoard board, int hour, int number, int count) {
        for (int line = 0; line < count; line++) {
            board.avail(hour, number).add(new Placement("25-1044", line + 1, "HYVEE", THIRTY, ClassOfTime.FIXED));
        }
    }

    private static Map<String, AvailsRow> byLabel(List<AvailsRow> rows) {
        return rows.stream().collect(Collectors.toMap(AvailsRow::label, Function.identity()));
    }

    @Test
    void aRowWorksOutItsOwnHoleAndItsOwnSellout() {
        AvailsRow row = new AvailsRow("AMD", 96, 24);
        assertThat(row.open()).isEqualTo(72);
        assertThat(row.sellout()).isEqualTo(0.25);
        assertThat(row.toString()).contains("AMD").contains("25.0%");
    }

    @Test
    void anEmptyRowDoesNotDivideByZero() {
        assertThat(new AvailsRow("ZZZ", 0, 0).sellout()).isZero();
    }

    @Test
    void anUntouchedDayIsOpenEverywhere() {
        Map<String, AvailsRow> rows = byLabel(byDaypart(board, book));
        assertThat(rows.get("AMD").units()).isEqualTo(96);
        assertThat(rows.get("AMD").sold()).isZero();
        assertThat(rows.get("AMD").sellout()).isZero();
        assertThat(rows.get("ROS").units()).isEqualTo(24 * 24);
    }

    @Test
    void theDaypartsComeBackNarrowestFirst() {
        List<String> labels =
                byDaypart(board, book).stream().map(AvailsRow::label).toList();
        assertThat(labels.indexOf("AMD")).isLessThan(labels.indexOf("AAA"));
        assertThat(labels.indexOf("AAA")).isLessThan(labels.indexOf("ROS"));
    }

    @Test
    void theRowsOverlapAndAreNotMeantToAddUp() {
        List<AvailsRow> all = byDaypart(board, book);
        Map<String, AvailsRow> rows = byLabel(all);
        int weekdayParts = rows.get("AMD").units()
                + rows.get("MID").units()
                + rows.get("PMD").units();
        assertThat(weekdayParts).isLessThan(rows.get("ROS").units());
        assertThat(rows.get("AAA").units()).isGreaterThan(rows.get("AMD").units());
        assertThat(all.stream().mapToInt(AvailsRow::units).sum())
                .isGreaterThan(rows.get("ROS").units());
    }

    @Test
    void aSoldUnitShowsUpInEveryDaypartThatCoversIt() {
        put(board, 7, 2, 1);
        Map<String, AvailsRow> rows = byLabel(byDaypart(board, book));
        assertThat(rows.get("AMD").sold()).isEqualTo(1);
        assertThat(rows.get("AAA").sold()).isEqualTo(1);
        assertThat(rows.get("ROS").sold()).isEqualTo(1);
        assertThat(rows.get("MID").sold()).isZero();
    }

    @Test
    void theHoleIsPricedAtTheCheapestClassTheCardQuotes() {
        Map<String, AvailsRow> rows = byLabel(byDaypart(board, book));
        // KWLO morning drive is quoted at 36 immediately preemptible, 96 units open.
        assertThat(rows.get("AMD").value()).isEqualTo(Money.dollars(36 * 96));
        // Saturday is only quoted at notice, so that is what prices it.
        assertThat(rows.get("ROS").value()).isEqualTo(Money.dollars(12 * 24 * 24));
    }

    @Test
    void aSellerQuotingFixedSeesTheHolePricedAtFixed() {
        put(board, 7, 2, 1);
        Map<String, AvailsRow> fixed = byLabel(byDaypart(board, book, ClassOfTime.FIXED));
        assertThat(fixed.get("AMD").value()).isEqualTo(Money.dollars(60 * 95));
        assertThat(fixed.get("AMD").sold()).isEqualTo(1);
        Map<String, AvailsRow> notice = byLabel(byDaypart(board, book, ClassOfTime.NOTICE));
        assertThat(notice.get("AMD").value()).isEqualTo(Money.dollars(48 * 95));
    }

    @Test
    void aDaypartTheCardDoesNotSellAtTheClassHasAHoleWithNoPrice() {
        Map<String, AvailsRow> fixed = byLabel(byDaypart(board, book, ClassOfTime.FIXED));
        assertThat(fixed.get("EVE").units()).isPositive();
        assertThat(fixed.get("EVE").value()).isEqualTo(Money.ZERO);
        assertThat(fixed.get("ROS").value()).isEqualTo(Money.ZERO);
        assertThat(byLabel(byDaypart(board, book)).get("EVE").value().isZero()).isFalse();
    }

    @Test
    void sellingAUnitTakesItsValueOutOfTheHole() {
        Money before = byLabel(byDaypart(board, book)).get("AMD").value();
        put(board, 7, 2, 1);
        Money after = byLabel(byDaypart(board, book)).get("AMD").value();
        assertThat(before.minus(after)).isEqualTo(Money.dollars(36));
    }

    @Test
    void aSaturdayReportUsesTheSaturdayDayparts() {
        LocalDate saturday = MONDAY.plusDays(5);
        DayBoard weekend = DayBoard.build(house.programming().get("KWLO"), saturday);
        Map<String, AvailsRow> rows = byLabel(byDaypart(weekend, house.book("KWLO", saturday)));
        assertThat(rows).doesNotContainKey("AMD");
        assertThat(rows.get("SAT").units()).isEqualTo(18 * 24);
        assertThat(rows.get("OVN").units()).isEqualTo(6 * 24);
    }

    @Test
    void aBoardAndACardFromTwoStationsAreRefused() {
        assertThatThrownBy(() -> byDaypart(board, house.book("KCRR", MONDAY)))
                .isInstanceOf(ReportError.class)
                .hasMessageContaining("was handed KWLO's board");
    }

    @Test
    void theHourReportRunsInAirOrder() {
        List<AvailsRow> rows = byHour(board);
        assertThat(rows).hasSize(24);
        assertThat(rows.get(0).label()).isEqualTo("12:00a");
        assertThat(rows.get(7).label()).isEqualTo("7:00a");
        assertThat(rows.get(13).label()).isEqualTo("1:00p");
        assertThat(rows).allMatch(row -> row.units() == 24);
    }

    @Test
    void theHourReportCountsWhatSoldInThatHour() {
        put(board, 7, 2, 6);
        put(board, 7, 3, 3);
        Map<String, AvailsRow> rows = byLabel(byHour(board));
        assertThat(rows.get("7:00a").sold()).isEqualTo(9);
        assertThat(rows.get("7:00a").open()).isEqualTo(15);
        assertThat(rows.get("8:00a").sold()).isZero();
    }

    @Test
    void theThinnestHoursAreWhereAScheduleWillJam() {
        put(board, 7, 2, 6);
        put(board, 7, 3, 6);
        put(board, 7, 4, 6);
        put(board, 15, 2, 6);
        List<AvailsRow> thin = thinnestHours(board, 2);
        assertThat(thin.get(0).label()).isEqualTo("7:00a");
        assertThat(thin.get(0).open()).isEqualTo(6);
        assertThat(thin.get(1).label()).isEqualTo("3:00p");
    }

    @Test
    void aPreemptedHourIsNotOnTheHourReport() {
        DayBoard friday = DayBoard.build(house.programming().get("KCRR"), MONDAY.plusDays(4));
        List<String> labels = byHour(friday).stream().map(AvailsRow::label).toList();
        assertThat(labels).doesNotContain("7:00p").contains("6:00p").hasSize(20);
    }

    @Test
    void theDayReportRunsAWeekOfOneStation() {
        ClockGrid grid = house.programming().get("KWLO");
        List<DayBoard> boards = IntStream.range(0, 7)
                .mapToObj(day -> DayBoard.build(grid, MONDAY.plusDays(day)))
                .toList();
        put(boards.get(4), 7, 2, 6);
        List<AvailsRow> rows = byDay(boards);
        assertThat(rows.get(0).label()).isEqualTo("Mon 08/04");
        assertThat(rows.get(4).sold()).isEqualTo(6);
        assertThat(rows.get(0).sold()).isZero();
        assertThat(rows).hasSize(7);
    }

    @Test
    void theDayReportRefusesAMixedBagOfStations() {
        List<DayBoard> boards = List.of(
                DayBoard.build(house.programming().get("KWLO"), MONDAY),
                DayBoard.build(house.programming().get("KCRR"), MONDAY));
        assertThatThrownBy(() -> byDay(boards))
                .isInstanceOf(ReportError.class)
                .hasMessageContaining("one station, not 2");
    }

    @Test
    void theWeekTotalsWhatIsOpenAndWhatItWouldFetch() {
        ClockGrid grid = house.programming().get("KWLO");
        List<LocalDate> days = IntStream.range(0, 5).mapToObj(MONDAY::plusDays).toList();
        List<DayBoard> boards =
                days.stream().map(day -> DayBoard.build(grid, day)).toList();
        Map<LocalDate, RuleBook> books =
                days.stream().collect(Collectors.toMap(Function.identity(), day -> house.book("KWLO", day)));
        put(boards.get(0), 7, 2, 6);
        assertThat(weekOpenUnits(boards)).isEqualTo(5 * 24 * 24 - 6);
        assertThat(weekValue(boards, books, "AMD")).isEqualTo(Money.dollars(36 * (5 * 96 - 6)));
    }

    @Test
    void aWeekValueWithADayNobodyGaveRulesFor() {
        List<DayBoard> boards = List.of(DayBoard.build(house.programming().get("KWLO"), MONDAY));
        assertThatThrownBy(() -> weekValue(boards, Map.of(), "AMD"))
                .isInstanceOf(ReportError.class)
                .hasMessageContaining("no rules were given for 2025-08-04");
    }
}
