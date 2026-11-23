package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.services.AiredDays.SIGNED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.scheduling.Ask;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Lay;
import io.github.rkaushikdev.cartwall.scheduling.Pinning;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import io.github.rkaushikdev.cartwall.scheduling.StationDay;
import io.github.rkaushikdev.cartwall.services.LogBuild.BuildReport;
import io.github.rkaushikdev.cartwall.services.LogBuild.PinnedBoards;
import io.github.rkaushikdev.cartwall.services.LogBuild.Skipped;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogbuildTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final BroadcastWeek WEEK = new BroadcastWeek(MONDAY);
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);

    private Database engine;
    private House house;

    @BeforeEach
    void putTheClusterOnFile() {
        engine = TestDatabase.fresh();
        house = Fixtures.house();
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            for (String call : List.of("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ")) {
                uow.cards().file(Fixtures.cards().inForce(call, CARD_DATE));
                uow.stations().saveGrid(Fixtures.programming().get(call));
            }
            uow.commit();
        }
    }

    private static Line aLine(int number, String station, String daypart, int perWeek) {
        return new Line(
                number,
                station,
                daypart,
                FIXED,
                THIRTY,
                Money.parse("60.00"),
                Flight.build(MONDAY, FRIDAY, "M-F", perWeek));
    }

    private static Line aLine(int number) {
        return aLine(number, "KWLO", "AMD", 10);
    }

    private static Order anOrder(String number, List<Line> lines) {
        return new Order(number, "HYVEE", LocalDate.of(2025, 7, 21), "DHOLM", lines).confirmed();
    }

    private static Order anOrder() {
        return anOrder("25-1044", List.of(aLine(1)));
    }

    private BuildReport buildWeek(UnitOfWork uow) {
        return HouseLog.quietly(() -> LogBuild.buildWeek(uow, house, WEEK));
    }

    private DayBoard storedMonday() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            return new LogRepository(uow.entityManager())
                    .board(house.programming().get("KWLO"), MONDAY);
        }
    }

    private static Ask aPinnedAsk(AccountBook book, Optional<String> agency) {
        return new Ask("25-0900", 1, book.advertiser("SLUMB"), "AMD", THIRTY, FIXED, "C9001", agency);
    }

    @Test
    void aWeekWithNoOrdersStillWritesALogADayAStation() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            BuildReport report = buildWeek(uow);
            assertThat(report.orders()).isEmpty();
            assertThat(report.placed()).isZero();
            // Five logged signals, seven days. An empty log is still a log.
            assertThat(report.written()).hasSize(35);
        }
    }

    @Test
    void oneOrderPlacesAndTheDaysItTouchedAreWritten() {
        BuildReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            report = buildWeek(uow);
            uow.commit();
        }
        assertThat(report.placed()).isEqualTo(10);
        assertThat(report.isComplete()).isTrue();
        assertThat(report.written()).hasSize(35);
        assertThat(report.written()).contains(new StationDay("KWLO", MONDAY));
    }

    @Test
    void aFlightThatStartsAndEndsInsideTheWeekIsScheduled() {
        Line midweek = new Line(
                1,
                "KWLO",
                "AMD",
                FIXED,
                THIRTY,
                Money.parse("60.00"),
                Flight.build(LocalDate.of(2025, 8, 5), LocalDate.of(2025, 8, 7), "Tu-Th", 6));
        BuildReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder("25-3000", List.of(midweek)));
            report = buildWeek(uow);
            uow.commit();
        }
        assertThat(report.orders()).hasSize(1);
        assertThat(report.placed()).isEqualTo(6);
        assertThat(report.isComplete()).isTrue();
        assertThat(storedMonday().countFor("25-3000", 1)).isZero();
    }

    @Test
    void anOrderAcrossTheWholeWeekIsScheduledOnce() {
        Line wholeWeek = new Line(
                1,
                "KWLO",
                "AMD",
                FIXED,
                THIRTY,
                Money.parse("60.00"),
                Flight.build(MONDAY, LocalDate.of(2025, 8, 10), "M-Su", 7));
        BuildReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder("25-2000", List.of(wholeWeek)));
            report = buildWeek(uow);
        }
        assertThat(report.orders()).hasSize(1);
        assertThat(report.placed()).isEqualTo(7);
    }

    @Test
    void whatWasWrittenComesBackAsABoard() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            buildWeek(uow);
            uow.commit();
        }
        assertThat(storedMonday().countFor("25-1044", 1)).isEqualTo(2);
    }

    @Test
    void aPendingOrderIsLeftAlone() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder().cancelled());
            assertThat(buildWeek(uow).orders()).isEmpty();
        }
    }

    @Test
    void twoStationsGetTwoSetsOfLogs() {
        Order order = anOrder("25-1044", List.of(aLine(1), aLine(2, "KCRR", "MID", 10)));
        BuildReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(order);
            report = buildWeek(uow);
            uow.commit();
        }
        assertThat(report.placed()).isEqualTo(20);
        assertThat(report.written()).contains(new StationDay("KCRR", MONDAY));
    }

    @Test
    void aRebuildKeepsTheHandPlacedSpots() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            ClockGrid grid = house.programming().get("KWLO");
            Lay lay = new Lay(DayBoard.build(grid, MONDAY), house.book("KWLO", MONDAY));
            AccountBook book = uow.accounts().book();
            Pinning.pin(lay, book, aPinnedAsk(book, Optional.of("CRS")), 7, 5);
            new LogRepository(uow.entityManager()).save(lay.board());
            uow.orders().save(anOrder());
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            buildWeek(uow);
            uow.commit();
        }
        DayBoard board = storedMonday();
        assertThat(board.countFor("25-0900", 1)).isEqualTo(1);
        assertThat(board.avail(7, 5).placements().get(0).fixed()).isTrue();
    }

    @Test
    void aRebuildThrowsAwayWhatTheBuilderItselfPlaced() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            buildWeek(uow);
            uow.commit();
        }
        BuildReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().delete("25-1044");
            report = buildWeek(uow);
            uow.commit();
        }
        assertThat(report.placed()).isZero();
        assertThat(storedMonday().usedUnits()).isZero();
    }

    @Test
    void aSecondOrderAddedLaterPlacesBesideTheFirst() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            buildWeek(uow);
            uow.commit();
        }
        BuildReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder("25-2000", List.of(aLine(1, "KWLO", "MID", 10))));
            report = buildWeek(uow);
            uow.commit();
        }
        // Both orders are placed from scratch, so both are on the rebuilt board once.
        assertThat(report.placed()).isEqualTo(20);
        DayBoard board = storedMonday();
        assertThat(board.countFor("25-1044", 1)).isEqualTo(2);
        assertThat(board.countFor("25-2000", 1)).isEqualTo(2);
    }

    @Test
    void aDayThatHasGoneOutIsNotTouched() {
        BuildReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            LogRepository logs = new LogRepository(uow.entityManager());
            logs.save(DayBoard.build(house.programming().get("KWLO"), MONDAY));
            logs.approve("KWLO", MONDAY, "T. Ronan", SIGNED);
            logs.release("KWLO", MONDAY, SIGNED);
            uow.orders().save(anOrder());
            report = buildWeek(uow);
            uow.commit();
        }
        assertThat(report.skipped()).contains(new Skipped("KWLO", MONDAY, "released"));
        assertThat(report.written()).doesNotContain(new StationDay("KWLO", MONDAY));
        assertThat(storedMonday().usedUnits()).isZero();
    }

    @Test
    void theReportReadsAsASentence() {
        BuildReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            report = buildWeek(uow);
            uow.commit();
        }
        String printed = report.toString();
        assertThat(printed).contains("10 of 10 placed");
        assertThat(printed).contains("35 logs written");
    }

    @Test
    void aShortfallIsReportedOrderByOrder() {
        Order heavy = anOrder("25-1044", List.of(aLine(1, "KWLO", "AMD", 40)));
        BuildReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(heavy);
            report = buildWeek(uow);
            uow.commit();
        }
        assertThat(report.isComplete()).isFalse();
        assertThat(report.shortfalls())
                .extracting(result -> result.order().number())
                .containsExactly("25-1044");
    }

    @Test
    void restoringPinsCopiesOnlyTheHandPlacedSpots() {
        AccountBook accounts = Fixtures.accounts();
        ClockGrid grid = house.programming().get("KWLO");
        Lay lay = new Lay(DayBoard.build(grid, MONDAY), house.book("KWLO", MONDAY));
        Ask ask = aPinnedAsk(accounts, Optional.empty());
        Pinning.pin(lay, accounts, ask, 7, 5);
        Pinning.putSpot(lay, lay.board().avail(9, 2), ask, "");
        DayBoard fresh = DayBoard.build(grid, MONDAY);
        List<Placement> kept = LogBuild.restorePins(fresh, lay.board());
        assertThat(kept).hasSize(1);
        assertThat(fresh.usedUnits()).isEqualTo(1);
        assertThat(fresh.avail(7, 5).placements().get(0).fixed()).isTrue();
    }

    @Test
    void theBoardCacheStartsFromWhatIsStored() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            DayBoard board = DayBoard.build(house.programming().get("KWLO"), MONDAY);
            new LogRepository(uow.entityManager()).save(board);
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            PinnedBoards boards = new PinnedBoards(house, new LogRepository(uow.entityManager()));
            assertThat(boards.board("KWLO", MONDAY).usedUnits()).isZero();
            assertThat(boards.built()).containsExactly(new StationDay("KWLO", MONDAY));
        }
    }
}
