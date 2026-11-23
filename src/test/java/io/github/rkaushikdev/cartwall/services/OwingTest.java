package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.services.AiredDays.aDay;
import static io.github.rkaushikdev.cartwall.services.AiredDays.ran;
import static io.github.rkaushikdev.cartwall.services.Owing.bumped;
import static io.github.rkaushikdev.cartwall.services.Owing.didNotRun;
import static io.github.rkaushikdev.cartwall.services.Owing.gather;
import static io.github.rkaushikdev.cartwall.services.Owing.neverScheduled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.persistence.BumpRepository;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.BumpRow;
import io.github.rkaushikdev.cartwall.scheduling.Bumped;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import io.github.rkaushikdev.cartwall.services.AiredDays.Spot;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OwingTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final BroadcastWeek WEEK = new BroadcastWeek(MONDAY);
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);
    private static final LocalDate SUNDAY = LocalDate.of(2025, 8, 10);

    private Database engine;

    @BeforeEach
    void putTheStationOnFile() {
        engine = AiredDays.aStationOnFile();
    }

    private static Order anOrder(int perWeek, boolean confirm) {
        Order order = new Order(
                "25-1044",
                "HYVEE",
                LocalDate.of(2025, 7, 21),
                "DHOLM",
                List.of(new Line(
                        1,
                        "KWLO",
                        "AMD",
                        FIXED,
                        THIRTY,
                        Money.parse("60.00"),
                        Flight.build(MONDAY, FRIDAY, "M-F", perWeek))));
        return confirm ? order.confirmed() : order;
    }

    private static Order anOrder(int perWeek) {
        return anOrder(perWeek, true);
    }

    private static Bumped aBump(LocalDate day) {
        return new Bumped(new Placement("25-1044", 1, "HYVEE", THIRTY, FIXED), day, 7, 2, "25-9000", 1);
    }

    private static List<Spot> oneSpot(int line) {
        return List.of(new Spot(7, 2, "25-1044", line));
    }

    @Test
    void aSpotThatRanOwesNothing() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(1));
            aDay(uow, oneSpot(1), List.of(ran("07:10:00")), MONDAY, true);
            assertThat(didNotRun(uow, MONDAY, SUNDAY)).isEmpty();
        }
    }

    @Test
    void aSpotTheAutomationSkippedIsOwed() {
        List<Debt> owed;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(1));
            aDay(uow, oneSpot(1), List.of(ran("07:10:00", "skip", "25-1044-1")), MONDAY, true);
            owed = didNotRun(uow, MONDAY, SUNDAY);
        }
        assertThat(owed).hasSize(1);
        assertThat(owed.get(0).cause()).isSameAs(Cause.DID_NOT_RUN);
        assertThat(owed.get(0).rate()).isEqualTo(Money.dollars(60));
        assertThat(owed.get(0).station()).isEqualTo("KWLO");
        assertThat(owed.get(0).daypart()).isEqualTo("AMD");
    }

    @Test
    void aDayNobodyPostedAnAsRunForOwesNothingYet() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(1));
            aDay(uow, oneSpot(1), List.of(), MONDAY, false);
            assertThat(didNotRun(uow, MONDAY, SUNDAY)).isEmpty();
        }
    }

    @Test
    void aCancelledOrderOwesNothingHoweverMuchDidNotRun() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(1));
            aDay(uow, oneSpot(1), List.of(ran("07:10:00", "skip", "25-1044-1")), MONDAY, true);
            uow.orders().save(anOrder(1).cancelled());
            assertThat(didNotRun(uow, MONDAY, SUNDAY)).isEmpty();
        }
    }

    @Test
    void aSpotForALineThatIsNoLongerOnTheOrderIsSkipped() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(1));
            aDay(uow, oneSpot(9), List.of(), MONDAY, true);
            assertThat(didNotRun(uow, MONDAY, SUNDAY)).isEmpty();
        }
    }

    @Test
    void aBumpNobodyHasMadeGoodIsOwed() {
        List<Debt> owed;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(1));
            new BumpRepository(uow.entityManager()).record("KWLO", aBump(MONDAY));
            uow.entityManager().flush();
            owed = bumped(uow, MONDAY, SUNDAY);
        }
        assertThat(owed).hasSize(1);
        assertThat(owed.get(0).cause()).isSameAs(Cause.BUMPED);
        assertThat(owed.get(0).rate()).isEqualTo(Money.dollars(60));
    }

    @Test
    void aBumpThatHasBeenMadeGoodIsNotOwedAgain() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(1));
            BumpRepository repo = new BumpRepository(uow.entityManager());
            BumpRow row = repo.record("KWLO", aBump(MONDAY));
            uow.entityManager().flush();
            repo.settle(row.getId());
            assertThat(bumped(uow, MONDAY, SUNDAY)).isEmpty();
        }
    }

    @Test
    void aLineThatWasFullyScheduledOwesNothing() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(2));
            aDay(uow, List.of(new Spot(7, 2, "25-1044", 1), new Spot(9, 2, "25-1044", 1)), List.of(), MONDAY, false);
            assertThat(neverScheduled(uow, WEEK)).isEmpty();
        }
    }

    @Test
    void aLineTheBuilderCouldNotPlaceIsOwedTheDifference() {
        List<Debt> owed;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(4));
            aDay(uow, oneSpot(1), List.of(), MONDAY, false);
            owed = neverScheduled(uow, WEEK);
        }
        assertThat(owed).hasSize(3);
        assertThat(owed).allMatch(debt -> debt.cause() == Cause.NEVER_SCHEDULED);
        assertThat(owed.get(0).rate()).isEqualTo(Money.dollars(60));
    }

    @Test
    void aLineWithNoLogAtAllOwesItsWholeWeek() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(3));
            assertThat(neverScheduled(uow, WEEK)).hasSize(3);
        }
    }

    @Test
    void aPendingOrderOwesNothingUntilItIsConfirmed() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(3, false));
            assertThat(neverScheduled(uow, WEEK)).isEmpty();
        }
    }

    private static Order anOrderFlown(LocalDate first, LocalDate last, String days, int perWeek) {
        return new Order(
                        "25-2000",
                        "HYVEE",
                        LocalDate.of(2025, 7, 21),
                        "DHOLM",
                        List.of(new Line(
                                1,
                                "KWLO",
                                "AMD",
                                FIXED,
                                THIRTY,
                                Money.parse("60.00"),
                                Flight.build(first, last, days, perWeek))))
                .confirmed();
    }

    @Test
    void aFlightAcrossTheWholeWeekIsOwedItsCountOnce() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrderFlown(MONDAY, SUNDAY, "M-Su", 7));
            assertThat(neverScheduled(uow, WEEK)).hasSize(7);
        }
    }

    @Test
    void aFlightSoldInsideTheWeekIsStillOwed() {
        List<Debt> owed;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrderFlown(LocalDate.of(2025, 8, 5), LocalDate.of(2025, 8, 7), "Tu-Th", 3));
            owed = neverScheduled(uow, WEEK);
        }
        assertThat(owed).hasSize(3);
        assertThat(owed).extracting(Debt::order).containsOnly("25-2000");
        assertThat(owed).extracting(Debt::day).containsOnly(MONDAY);
    }

    @Test
    void aWeekTheFlightDoesNotTouchOwesNothing() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(3));
            assertThat(neverScheduled(uow, new BroadcastWeek(LocalDate.of(2025, 9, 1))))
                    .isEmpty();
        }
    }

    @Test
    void theGatherPutsAllThreeSourcesOnOneLedger() {
        Ledger ledger;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(3));
            aDay(uow, oneSpot(1), List.of(ran("07:10:00", "skip", "25-1044-1")), MONDAY, true);
            new BumpRepository(uow.entityManager()).record("KWLO", aBump(FRIDAY));
            uow.entityManager().flush();
            ledger = gather(uow, MONDAY, SUNDAY);
        }
        // One skipped, one bumped, and two of the three a week never scheduled.
        assertThat(ledger.byCause()).isEqualTo(Map.of("did not run", 1, "never scheduled", 2, "bumped", 1));
        assertThat(ledger.size()).isEqualTo(4);
        assertThat(ledger.owedValue()).isEqualTo(Money.dollars(240));
    }

    @Test
    void theExpensivePassCanBeTurnedOff() {
        Ledger ledger;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(3));
            aDay(uow, oneSpot(1), List.of(ran("07:10:00", "skip", "25-1044-1")), MONDAY, true);
            ledger = gather(uow, MONDAY, SUNDAY, false);
        }
        assertThat(ledger.byCause()).isEqualTo(Map.of("did not run", 1));
    }

    @Test
    void aSpanThatRunsBackwardsIsRefused() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThatThrownBy(() -> gather(uow, SUNDAY, MONDAY))
                    .isInstanceOf(OwingError.class)
                    .hasMessageContaining("runs backwards");
        }
    }

    @Test
    void aSpanCoveringTwoWeeksCountsEachWeekOnce() {
        Ledger ledger;
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.orders().save(anOrder(2));
            ledger = HouseLog.quietly(() -> gather(uow, MONDAY, LocalDate.of(2025, 8, 17)));
        }
        // The flight only runs in the first week, so only that week is short.
        assertThat(ledger.byCause()).isEqualTo(Map.of("never scheduled", 2));
    }
}
