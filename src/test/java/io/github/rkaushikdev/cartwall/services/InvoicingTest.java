package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.aGrid;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.services.AiredDays.HEADER;
import static io.github.rkaushikdev.cartwall.services.AiredDays.SIGNED;
import static io.github.rkaushikdev.cartwall.services.Invoicing.buildInvoice;
import static io.github.rkaushikdev.cartwall.services.Invoicing.drifted;
import static io.github.rkaushikdev.cartwall.services.Invoicing.ordersWithAir;
import static io.github.rkaushikdev.cartwall.services.Invoicing.raiseOne;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceDetailRow;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceRow;
import io.github.rkaushikdev.cartwall.playout.AsRun;
import io.github.rkaushikdev.cartwall.playout.AsRunFile;
import io.github.rkaushikdev.cartwall.reconcile.Matcher;
import io.github.rkaushikdev.cartwall.reconcile.Posting;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import io.github.rkaushikdev.cartwall.services.Invoicing.RunReport;
import io.github.rkaushikdev.cartwall.services.Invoicing.Skipped;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvoicingTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final BroadcastMonth AUGUST = new BroadcastMonth(2025, 8);
    private static final LocalDate RAISED = LocalDate.of(2025, 8, 25);

    private Database engine;

    @BeforeEach
    void putTheStationOnFile() {
        engine = AiredDays.aStationOnFile();
    }

    private static Line aLine(Flight flight) {
        return new Line(1, "KWLO", "AMD", FIXED, THIRTY, Money.parse("60.00"), flight);
    }

    private static Order anOrder(String number, String advertiser, String agency, List<Line> lines) {
        Order order = new Order(number, advertiser, LocalDate.of(2025, 7, 21), "DHOLM", lines);
        return (agency == null ? order : order.withAgency(agency)).confirmed();
    }

    private static Order anOrder(String number) {
        return anOrder(
                number, "HYVEE", null, List.of(aLine(Flight.build(MONDAY, LocalDate.of(2025, 8, 8), "M-F", 10))));
    }

    private static Order anOrder() {
        return anOrder("25-1044");
    }

    /** One spot on the log, with the cart it carries. */
    private record Spot(int hour, int number, String order, int line, String cart) {}

    private static Spot aSpot(int hour, String order, int line, String cart) {
        return new Spot(hour, 2, order, line, cart);
    }

    /** Build a day, air it, and post an as-run against it. */
    private static void aDay(UnitOfWork uow, ClockGrid grid, LocalDate day, List<Spot> spots, List<String> asRun) {
        DayBoard board = DayBoard.build(grid, day);
        for (Spot spot : spots) {
            board.avail(spot.hour(), spot.number())
                    .add(new Placement(spot.order(), spot.line(), "HYVEE", THIRTY, FIXED, spot.cart()));
        }
        LogRepository logs = new LogRepository(uow.entityManager());
        logs.save(board);
        logs.approve("KWLO", day, "T. Ronan", SIGNED);
        logs.release("KWLO", day, SIGNED);
        logs.markAired("KWLO", day);
        List<String> all = new ArrayList<>();
        all.add(HEADER);
        all.addAll(asRun);
        List<AsRun> rows = asRun.isEmpty() ? List.of() : AsRunFile.parse(String.join("\n", all) + "\n");
        HouseLog.quietly(() -> Posting.post(uow.entityManager(), Matcher.reconcile(DayLog.build(grid, board), rows)));
    }

    private static String ran(String day, String at, String cart, String status, String reference) {
        return day + "," + at + "," + cart + ",a title,30.0," + status + "," + reference;
    }

    private static String ran(String day, String at) {
        return ran(day, at, "C1204", "played", "25-1044-1");
    }

    private static RunReport runMonth(UnitOfWork uow, BroadcastMonth month, LocalDate raisedOn) {
        return HouseLog.quietly(() -> Invoicing.runMonth(uow, month, raisedOn));
    }

    @Test
    void aMonthWithNothingAiredRaisesNothing() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            RunReport report = runMonth(uow, AUGUST, RAISED);
            assertThat(report.count()).isZero();
            assertThat(report.raised()).isEmpty();
            assertThat(ordersWithAir(uow, AUGUST)).isEmpty();
        }
    }

    @Test
    void anOrderThatAiredGetsAnInvoice() {
        ClockGrid grid = aGrid("KWLO");
        RunReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            aDay(uow, grid, MONDAY, List.of(aSpot(7, "25-1044", 1, "C1204")), List.of(ran("2025-08-04", "07:10:00")));
            report = runMonth(uow, AUGUST, RAISED);
            uow.commit();
        }
        assertThat(report.count()).isEqualTo(1);
        assertThat(report.raised()).containsExactly("25-1001");
        try (UnitOfWork uow = engine.unitOfWork()) {
            InvoiceRow invoice = uow.entityManager().find(InvoiceRow.class, "25-1001");
            assertThat(invoice.getOrderNumber()).isEqualTo("25-1044");
            assertThat(invoice.getSpots()).isEqualTo(1);
            assertThat(invoice.getGross()).isEqualTo(Money.dollars(60));
            assertThat(invoice.getNet()).isEqualTo(Money.dollars(60));
            assertThat(invoice.getRaised()).isEqualTo(RAISED);
        }
    }

    @Test
    void onlyTheSpotsThatAiredAreBilled() {
        ClockGrid grid = aGrid("KWLO");
        RunReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            aDay(
                    uow,
                    grid,
                    MONDAY,
                    List.of(aSpot(7, "25-1044", 1, "C1204"), aSpot(9, "25-1044", 1, "C1204")),
                    List.of(
                            ran("2025-08-04", "07:10:00"),
                            ran("2025-08-04", "09:10:00", "C1204", "skip", "25-1044-1")));
            report = runMonth(uow, AUGUST, RAISED);
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            InvoiceRow invoice =
                    uow.entityManager().find(InvoiceRow.class, report.raised().get(0));
            assertThat(invoice.getSpots()).isEqualTo(1);
            assertThat(invoice.getGross()).isEqualTo(Money.dollars(60));
        }
    }

    @Test
    void aDayNobodyPostedAnAsRunForBillsNothing() {
        ClockGrid grid = aGrid("KWLO");
        RunReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            DayBoard board = DayBoard.build(grid, MONDAY);
            board.avail(7, 2).add(new Placement("25-1044", 1, "HYVEE", THIRTY, FIXED, "C1204"));
            new LogRepository(uow.entityManager()).save(board);
            report = runMonth(uow, AUGUST, RAISED);
        }
        assertThat(report.count()).isZero();
    }

    @Test
    void anAgencyOrderIsBilledToTheAgencyNetOfCommission() {
        ClockGrid grid = aGrid("KWLO");
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders()
                    .save(anOrder(
                            "25-1044",
                            "DEALF",
                            "MOP",
                            List.of(aLine(Flight.build(MONDAY, LocalDate.of(2025, 8, 8), "M-F", 10)))));
            aDay(uow, grid, MONDAY, List.of(aSpot(7, "25-1044", 1, "C1204")), List.of(ran("2025-08-04", "07:10:00")));
            runMonth(uow, AUGUST, RAISED);
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            InvoiceRow invoice = uow.entityManager().find(InvoiceRow.class, "25-1001");
            assertThat(invoice.getPayer()).isEqualTo("MOP");
            assertThat(invoice.getCommissionRate()).isEqualByComparingTo("0.1500");
            assertThat(invoice.getGross()).isEqualTo(Money.dollars(60));
            assertThat(invoice.getCommission()).isEqualTo(Money.dollars(9));
            assertThat(invoice.getNet()).isEqualTo(Money.dollars(51));
        }
    }

    @Test
    void theDetailCarriesTheTimeItReallyRan() {
        ClockGrid grid = aGrid("KWLO");
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            aDay(uow, grid, MONDAY, List.of(aSpot(7, "25-1044", 1, "C1204")), List.of(ran("2025-08-04", "07:10:04")));
            runMonth(uow, AUGUST, RAISED);
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            InvoiceDetailRow detail = uow.entityManager()
                    .find(InvoiceRow.class, "25-1001")
                    .getDetails()
                    .get(0);
            assertThat(detail.getAt().second()).isEqualTo(4);
            assertThat(detail.isMoved()).isFalse();
            assertThat(detail.getCart()).isEqualTo("C1204");
        }
    }

    @Test
    void aSpotThatMovedIsMarkedOnTheDetail() {
        ClockGrid grid = aGrid("KWLO");
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            aDay(uow, grid, MONDAY, List.of(aSpot(7, "25-1044", 1, "C1204")), List.of(ran("2025-08-04", "08:22:00")));
            runMonth(uow, AUGUST, RAISED);
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(uow.entityManager()
                            .find(InvoiceRow.class, "25-1001")
                            .getDetails()
                            .get(0)
                            .isMoved())
                    .isTrue();
        }
    }

    @Test
    void theSummaryRollsUpWhatTheDetailSays() {
        ClockGrid grid = aGrid("KWLO");
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            aDay(
                    uow,
                    grid,
                    MONDAY,
                    List.of(aSpot(7, "25-1044", 1, "C1204"), aSpot(9, "25-1044", 1, "C1204")),
                    List.of(ran("2025-08-04", "07:10:00"), ran("2025-08-04", "09:10:00")));
            runMonth(uow, AUGUST, RAISED);
            uow.commit();
        }
        try (UnitOfWork uow = engine.unitOfWork()) {
            InvoiceRow invoice = uow.entityManager().find(InvoiceRow.class, "25-1001");
            assertThat(invoice.getSummaries()).hasSize(1);
            assertThat(invoice.getSummaries().get(0).getSpots()).isEqualTo(2);
            assertThat(invoice.getSummaries().get(0).getStation()).isEqualTo("KWLO");
            assertThat(invoice.getSummaries().get(0).getGross()).isEqualTo(Money.dollars(120));
        }
    }

    @Test
    void runningTheMonthTwiceDoesNotRaiseASecondInvoice() {
        ClockGrid grid = aGrid("KWLO");
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            aDay(uow, grid, MONDAY, List.of(aSpot(7, "25-1044", 1, "C1204")), List.of(ran("2025-08-04", "07:10:00")));
            runMonth(uow, AUGUST, RAISED);
            uow.commit();
        }
        RunReport again;
        try (UnitOfWork uow = engine.unitOfWork()) {
            again = runMonth(uow, AUGUST, RAISED);
            uow.commit();
        }
        assertThat(again.count()).isZero();
        assertThat(again.skipped()).containsExactly(new Skipped("25-1044", "already invoiced"));
    }

    @Test
    void raisingOneOrderTwiceIsRefusedOutright() {
        ClockGrid grid = aGrid("KWLO");
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            aDay(uow, grid, MONDAY, List.of(aSpot(7, "25-1044", 1, "C1204")), List.of(ran("2025-08-04", "07:10:00")));
            raiseOne(uow, "25-1044", AUGUST, RAISED);
            AlreadyInvoiced caught =
                    catchThrowableOfType(AlreadyInvoiced.class, () -> raiseOne(uow, "25-1044", AUGUST, RAISED));
            assertThat(caught).hasMessageContaining("already has an invoice");
            assertThat(caught.context()).containsEntry("order", "25-1044");
        }
    }

    /** A day built and saved but not yet aired, so its as-run is still to come. */
    private static void anUnpostedDay(UnitOfWork uow, ClockGrid grid, LocalDate day, Spot spot) {
        DayBoard board = DayBoard.build(grid, day);
        board.avail(spot.hour(), spot.number())
                .add(new Placement(spot.order(), spot.line(), "HYVEE", THIRTY, FIXED, spot.cart()));
        new LogRepository(uow.entityManager()).save(board);
    }

    @Test
    void anOrderStillWaitingOnAnAsRunIsLeftForTheNextRun() {
        ClockGrid grid = aGrid("KWLO");
        RunReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            aDay(uow, grid, MONDAY, List.of(aSpot(7, "25-1044", 1, "C1204")), List.of(ran("2025-08-04", "07:10:00")));
            anUnpostedDay(uow, grid, MONDAY.plusDays(2), aSpot(7, "25-1044", 1, "C1204"));
            anUnpostedDay(uow, grid, MONDAY.plusDays(1), aSpot(9, "25-1044", 1, "C1204"));
            assertThat(Invoicing.waitingOn(uow, "25-1044", AUGUST))
                    .containsExactly("KWLO 2025-08-05", "KWLO 2025-08-06");
            report = runMonth(uow, AUGUST, RAISED);
        }
        assertThat(report.count()).isZero();
        assertThat(report.skipped())
                .containsExactly(new Skipped("25-1044", "waiting on the as-run for KWLO 2025-08-05, KWLO 2025-08-06"));
    }

    @Test
    void raisingOneOrderStillWaitingOnAnAsRunIsRefused() {
        ClockGrid grid = aGrid("KWLO");
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            aDay(uow, grid, MONDAY, List.of(aSpot(7, "25-1044", 1, "C1204")), List.of(ran("2025-08-04", "07:10:00")));
            anUnpostedDay(uow, grid, MONDAY.plusDays(1), aSpot(9, "25-1044", 1, "C1204"));
            InvoicingError caught =
                    catchThrowableOfType(InvoicingError.class, () -> raiseOne(uow, "25-1044", AUGUST, RAISED));
            assertThat(caught).hasMessage("25-1044 is waiting on the as-run for KWLO 2025-08-05");
            assertThat(caught.context()).containsEntry("month", "2025-08");
            assertThat(Invoicing.waitingOn(uow, "25-1044", new BroadcastMonth(2025, 9)))
                    .isEmpty();
        }
    }

    @Test
    void raisingAnOrderThatAiredNothingIsRefused() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            assertThatThrownBy(() -> raiseOne(uow, "25-1044", AUGUST, RAISED))
                    .isInstanceOf(InvoicingError.class)
                    .hasMessageContaining("aired nothing in 2025-08");
        }
    }

    @Test
    void theNumbersComeOffTheCounterWithoutGaps() {
        ClockGrid grid = aGrid("KWLO");
        RunReport report;
        try (UnitOfWork uow = engine.unitOfWork()) {
            for (String number : List.of("25-1044", "25-2000", "25-3000")) {
                uow.orders().save(anOrder(number));
            }
            aDay(
                    uow,
                    grid,
                    MONDAY,
                    List.of(
                            aSpot(7, "25-1044", 1, "C1204"),
                            aSpot(9, "25-2000", 1, "C1205"),
                            aSpot(11, "25-3000", 1, "C1206")),
                    List.of(
                            ran("2025-08-04", "07:10:00", "C1204", "played", "25-1044-1"),
                            ran("2025-08-04", "09:10:00", "C1205", "played", "25-2000-1"),
                            ran("2025-08-04", "11:10:00", "C1206", "played", "25-3000-1")));
            report = runMonth(uow, AUGUST, RAISED);
            uow.commit();
        }
        assertThat(report.raised()).containsExactly("25-1001", "25-1002", "25-1003");
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(new Numbering(uow.entityManager()).peek("INV", 2025)).isEqualTo(1003);
        }
    }

    @Test
    void aMonthBoundaryIsTheBroadcastOne() {
        ClockGrid grid = aGrid("KWLO");
        RunReport september;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders()
                    .save(anOrder(
                            "25-1044",
                            "HYVEE",
                            null,
                            List.of(aLine(
                                    Flight.build(LocalDate.of(2025, 8, 18), LocalDate.of(2025, 8, 29), "M-F", 5)))));
            // 8/25 is in broadcast September, not August, even though August has 31 days.
            aDay(
                    uow,
                    grid,
                    LocalDate.of(2025, 8, 25),
                    List.of(aSpot(7, "25-1044", 1, "C1204")),
                    List.of(ran("2025-08-25", "07:10:00")));
            RunReport august = runMonth(uow, AUGUST, RAISED);
            assertThat(august.count()).isZero();
            september = runMonth(uow, new BroadcastMonth(2025, 9), LocalDate.of(2025, 9, 29));
            uow.commit();
        }
        assertThat(september.count()).isEqualTo(1);
    }

    @Test
    void theReportReadsAsASentence() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThat(runMonth(uow, AUGUST, RAISED))
                    .hasToString("2025-08: 0 raised, 0 already done, 0 with nothing to bill");
        }
    }

    @Test
    void aSpotForALineThatIsNoLongerOnTheOrderIsRefused() {
        ClockGrid grid = aGrid("KWLO");
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder());
            aDay(
                    uow,
                    grid,
                    MONDAY,
                    List.of(aSpot(7, "25-1044", 9, "C1204")),
                    List.of(ran("2025-08-04", "07:10:00", "C1204", "played", "25-1044-9")));
            assertThatThrownBy(() -> buildInvoice(uow, "25-1044", AUGUST, RAISED))
                    .isInstanceOf(InvoicingError.class)
                    .hasMessageContaining("aired a line 9 that is not on the order");
        }
    }

    @Test
    void theDriftTestIsSymmetric() {
        AirTime early = AirTime.at(7, 10);
        AirTime late = AirTime.at(7, 20);
        assertThat(drifted(late, early)).isTrue();
        assertThat(drifted(early, late)).isTrue();
        assertThat(drifted(early, AirTime.at(7, 14))).isFalse();
    }
}
