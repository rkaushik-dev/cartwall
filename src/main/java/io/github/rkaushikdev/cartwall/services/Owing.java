package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.BroadcastCalendar;
import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.CartwallError;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
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
        List<SpotRow> rows = uow.entityManager()
                .createQuery(
                        "select s from SpotRow s join s.log l where s.aired = false and l.state = :aired"
                                + " and l.day >= :first and l.day <= :last order by l.day, s.at",
                        SpotRow.class)
                .setParameter("aired", LogState.AIRED)
                .setParameter("first", first)
                .setParameter("last", last)
                .getResultList();
        Map<String, Optional<Order>> orders = new HashMap<>();
        List<Debt> found = new ArrayList<>();
        for (SpotRow row : rows) {
            Optional<Line> line = lineOf(uow, orders, row.getOrderNumber(), row.getLine());
            line.ifPresent(sold -> found.add(new Debt(
                    row.getOrderNumber(),
                    row.getLine(),
                    row.getAdvertiser(),
                    row.getLog().getStation(),
                    sold.daypart(),
                    row.getGrade(),
                    row.getLength(),
                    sold.rate(),
                    row.getLog().getDay(),
                    Cause.DID_NOT_RUN)));
        }
        return found;
    }

    /** Spots a higher class took the unit from, not yet made good. */
    public static List<Debt> bumped(UnitOfWork uow, LocalDate first, LocalDate last) {
        List<BumpRow> rows = uow.entityManager()
                .createQuery(
                        "select b from BumpRow b where b.madeGood = false and b.day >= :first and b.day <= :last"
                                + " order by b.day, b.hour, b.breakNumber",
                        BumpRow.class)
                .setParameter("first", first)
                .setParameter("last", last)
                .getResultList();
        Map<String, Optional<Order>> orders = new HashMap<>();
        List<Debt> found = new ArrayList<>();
        for (BumpRow row : rows) {
            Optional<Line> line = lineOf(uow, orders, row.getOrderNumber(), row.getLine());
            line.ifPresent(sold -> found.add(new Debt(
                    row.getOrderNumber(),
                    row.getLine(),
                    row.getAdvertiser(),
                    row.getStation(),
                    sold.daypart(),
                    row.getGrade(),
                    row.getLength(),
                    sold.rate(),
                    row.getDay(),
                    Cause.BUMPED)));
        }
        return found;
    }

    /**
     * The gap between what each line asked for in a week and what is on the log.
     *
     * <p>Every order with a flight anywhere in the week is read once: a flight across the whole
     * week owes its count once, and a flight sold Tuesday to Thursday is read at all.
     */
    public static List<Debt> neverScheduled(UnitOfWork uow, BroadcastWeek week) {
        List<Debt> found = new ArrayList<>();
        for (Order order : uow.orders().runningBetween(week.start(), week.end())) {
            if (!order.status().maySchedule()) {
                continue;
            }
            for (Line line : order.lines()) {
                int wanted = line.flight().countIn(week);
                if (wanted == 0) {
                    continue;
                }
                long carried = scheduled(uow, order.number(), line.number(), week);
                for (long n = 0; n < Math.max(wanted - carried, 0); n++) {
                    found.add(new Debt(
                            order.number(),
                            line.number(),
                            order.advertiser(),
                            line.station(),
                            line.daypart(),
                            line.grade(),
                            line.length(),
                            line.rate(),
                            week.start(),
                            Cause.NEVER_SCHEDULED));
                }
            }
        }
        return found;
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
