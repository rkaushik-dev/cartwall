package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.BroadcastCalendar;
import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.CartwallError;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.domain.Weekday;
import io.github.rkaushikdev.cartwall.logs.LogState;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.BumpRow;
import io.github.rkaushikdev.cartwall.persistence.models.SpotRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Gathering what is owed from the three places it comes from, onto one makegood ledger.
 *
 * <ul>
 *   <li><b>Spots that did not run</b>: posted spot rows marked not aired, on days that have aired.
 *       A day whose as-run has not been posted has not failed to run anything.
 *   <li><b>Spots that ran outside their daypart</b>: aired, but at a time the daypart the line
 *       bought does not cover. Drive time sold and overnight delivered is not what was paid for,
 *       whatever the as-run says about it having played.
 *   <li><b>Spots that were bumped</b>: the bump table, where nothing has been made good yet.
 *   <li><b>Spots that were never scheduled</b>: the gap between what a line's flight asked for in
 *       a week and what the log carries. Nothing failed, the builder ran out of room, which is
 *       why this one gets forgotten.
 * </ul>
 *
 * <p>The rate on a debt comes off the order line, not off the rate card: what is owed is what the
 * client paid for. Only orders that may schedule are gathered; a cancelled order owes nothing.
 */
public final class Owing {

    private Owing() {}

    /** Spots the as-run says did not go out, on days that have aired. */
    public static List<Debt> didNotRun(UnitOfWork uow, LocalDate first, LocalDate last) {
        List<Lost> lost =
                postedSpots(uow, false, first, last).stream().map(Lost::of).toList();
        return owedFor(uow, lost, Cause.DID_NOT_RUN);
    }

    /**
     * Spots that aired, but outside the daypart their line was sold in.
     *
     * <p>Measured at the time the as-run gives, falling back to the logged time for a spot the
     * automation did not time. A station with no daypart grid on file, or a daypart that is not on
     * it, cannot be judged and owes nothing here: the readiness check is where that gets caught.
     */
    public static List<Debt> ranOutsideDaypart(UnitOfWork uow, LocalDate first, LocalDate last) {
        Map<String, DaypartGrid> grids = new HashMap<>();
        Map<String, Optional<Order>> orders = new HashMap<>();
        List<Lost> lost = new ArrayList<>();
        for (SpotRow row : postedSpots(uow, true, first, last)) {
            Optional<Line> line = lineOf(uow, orders, row.getOrderNumber(), row.getLine());
            String station = row.getLog().getStation();
            DaypartGrid grid =
                    grids.computeIfAbsent(station, call -> uow.stations().dayparts(call));
            if (line.isEmpty() || !grid.has(line.get().daypart())) {
                continue;
            }
            AirTime aired = row.getAiredAt() != null ? row.getAiredAt() : row.getAt();
            Weekday weekday = Weekday.of(row.getLog().getDay());
            if (!grid.of(line.get().daypart()).holds(weekday, aired)) {
                lost.add(Lost.of(row));
            }
        }
        return owedFor(uow, lost, Cause.OUT_OF_DAYPART);
    }

    /** Spots a higher class took the unit from, not yet made good. */
    public static List<Debt> bumped(UnitOfWork uow, LocalDate first, LocalDate last) {
        List<Lost> lost = uow
                .entityManager()
                .createQuery(
                        "select b from BumpRow b where b.madeGood = false and b.day >= :first and b.day <= :last"
                                + " order by b.day, b.hour, b.breakNumber",
                        BumpRow.class)
                .setParameter("first", first)
                .setParameter("last", last)
                .getResultList()
                .stream()
                .map(row -> new Lost(
                        row.getOrderNumber(),
                        row.getLine(),
                        row.getAdvertiser(),
                        row.getStation(),
                        row.getGrade(),
                        row.getLength(),
                        row.getDay()))
                .toList();
        return owedFor(uow, lost, Cause.BUMPED);
    }

    /**
     * The gap between what each line asked for in a week and what is on the log.
     *
     * <p>Every order with a flight anywhere in the week is read once: a flight across the whole
     * week owes its count once, and a flight sold Tuesday to Thursday is read at all.
     */
    public static List<Debt> neverScheduled(UnitOfWork uow, BroadcastWeek week) {
        List<Lost> lost = new ArrayList<>();
        for (Order order : uow.orders().runningBetween(week.start(), week.end())) {
            if (!order.status().maySchedule()) {
                continue;
            }
            for (Line line : order.lines()) {
                int wanted = line.flight().countIn(week);
                long carried = wanted == 0 ? 0 : scheduled(uow, order.number(), line.number(), week);
                for (long n = 0; n < Math.max(wanted - carried, 0); n++) {
                    lost.add(new Lost(
                            order.number(),
                            line.number(),
                            order.advertiser(),
                            line.station(),
                            line.grade(),
                            line.length(),
                            week.start()));
                }
            }
        }
        return owedFor(uow, lost, Cause.NEVER_SCHEDULED);
    }

    /** Every debt in a span, from every source, on one ledger. */
    public static Ledger gather(UnitOfWork uow, LocalDate first, LocalDate last) {
        return gather(uow, first, last, true);
    }

    /**
     * Every debt in a span on one ledger.
     *
     * @param scheduled run the never scheduled pass, the expensive one. The nightly report wants
     *     it; a quick look at last night does not.
     */
    public static Ledger gather(UnitOfWork uow, LocalDate first, LocalDate last, boolean scheduled) {
        if (last.isBefore(first)) {
            throw new OwingError("the span runs backwards");
        }
        Ledger ledger = new Ledger();
        didNotRun(uow, first, last).forEach(ledger::owe);
        ranOutsideDaypart(uow, first, last).forEach(ledger::owe);
        bumped(uow, first, last).forEach(ledger::owe);
        if (scheduled) {
            Set<LocalDate> seen = new HashSet<>();
            for (BroadcastWeek week : BroadcastCalendar.weeksBetween(first, last)) {
                if (!seen.add(week.start())) {
                    continue;
                }
                neverScheduled(uow, week).forEach(ledger::owe);
            }
        }
        return ledger;
    }

    /** Posted spots on aired days in the span that did, or did not, go out, in air order. */
    private static List<SpotRow> postedSpots(UnitOfWork uow, boolean aired, LocalDate first, LocalDate last) {
        return uow.entityManager()
                .createQuery(
                        "select s from SpotRow s join s.log l where s.aired = :ran and l.state = :aired"
                                + " and l.day >= :first and l.day <= :last order by l.day, s.at",
                        SpotRow.class)
                .setParameter("ran", aired)
                .setParameter("aired", LogState.AIRED)
                .setParameter("first", first)
                .setParameter("last", last)
                .getResultList();
    }

    /** What each lost spot is owed, priced off its line; spots whose order no longer schedules owe nothing. */
    private static List<Debt> owedFor(UnitOfWork uow, List<Lost> lost, Cause cause) {
        Map<String, Optional<Order>> orders = new HashMap<>();
        List<Debt> found = new ArrayList<>();
        for (Lost spot : lost) {
            lineOf(uow, orders, spot.order(), spot.line())
                    .ifPresent(sold -> found.add(new Debt(
                            spot.order(),
                            spot.line(),
                            spot.advertiser(),
                            spot.station(),
                            sold.daypart(),
                            spot.grade(),
                            spot.length(),
                            sold.rate(),
                            spot.day(),
                            cause)));
        }
        return found;
    }

    /** One spot that was meant to air and did not air as sold, whichever table recorded it. */
    private record Lost(
            String order,
            int line,
            String advertiser,
            String station,
            ClassOfTime grade,
            SpotLength length,
            LocalDate day) {

        static Lost of(SpotRow row) {
            return new Lost(
                    row.getOrderNumber(),
                    row.getLine(),
                    row.getAdvertiser(),
                    row.getLog().getStation(),
                    row.getGrade(),
                    row.getLength(),
                    row.getLog().getDay());
        }
    }

    private static Optional<Line> lineOf(UnitOfWork uow, Map<String, Optional<Order>> orders, String number, int line) {
        Optional<Order> order = orders.computeIfAbsent(number, key -> orderOnFile(uow, key));
        if (order.isEmpty() || !order.get().status().maySchedule()) {
            return Optional.empty();
        }
        return order.get().lines().stream()
                .filter(sold -> sold.number() == line)
                .findFirst();
    }

    private static Optional<Order> orderOnFile(UnitOfWork uow, String number) {
        try {
            return Optional.of(uow.orders().get(number));
        } catch (CartwallError notThere) {
            return Optional.empty();
        }
    }

    private static long scheduled(UnitOfWork uow, String order, int line, BroadcastWeek week) {
        return uow.entityManager()
                .createQuery(
                        "select count(s) from SpotRow s join s.log l where s.orderNumber = :order"
                                + " and s.line = :line and l.day >= :first and l.day <= :last",
                        Long.class)
                .setParameter("order", order)
                .setParameter("line", line)
                .setParameter("first", week.start())
                .setParameter("last", week.end())
                .getSingleResult();
    }
}
