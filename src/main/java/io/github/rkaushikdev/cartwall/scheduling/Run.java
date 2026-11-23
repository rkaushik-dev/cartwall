package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.Rotation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scheduling a whole order, which is where the week runs stop being independent.
 *
 * <p>Line order matters and it is not the order they were typed. Fixed position lines go first
 * because they have nowhere else to go, then the rest by class and rate, dearest first. A cheap run
 * of schedule line placed first will sit in the drive break that the fixed line needed.
 */
public final class Run {

    private Run() {}

    /** Hardest to place first: fixed position, then dearest, then as typed. */
    public static List<Line> placementOrder(List<Line> lines) {
        return lines.stream()
                .sorted(Comparator.comparingInt((Line line) -> -line.grade().rank())
                        .thenComparingLong(line -> -line.rate().cents())
                        .thenComparingInt(Line::number))
                .toList();
    }

    /** Place every line of {@code order} that runs in {@code week}. */
    public static OrderResult scheduleOrder(Boards boards, AccountBook accounts, Order order, BroadcastWeek week) {
        return scheduleOrder(boards, accounts, order, week, Map.of());
    }

    /**
     * Place every line of {@code order} that runs in {@code week}.
     *
     * @param rotations a rotation for a line by number; a line with several carts and no rotation
     *     rotates them evenly
     */
    public static OrderResult scheduleOrder(
            Boards boards, AccountBook accounts, Order order, BroadcastWeek week, Map<Integer, Rotation> rotations) {
        if (!order.status().maySchedule()) {
            throw new RunError(
                    "order " + order.number() + " is " + order.status().value() + " and cannot be scheduled",
                    Details.of("order", order.number(), "status", order.status().value()));
        }
        accounts.whyNotScheduled(order.advertiser()).ifPresent(why -> {
            throw new RunError(why, Details.of("order", order.number(), "advertiser", order.advertiser()));
        });
        List<LineResult> results = new ArrayList<>();
        for (Line line : placementOrder(order.lines())) {
            int count = line.flight().countIn(week);
            List<LocalDate> days = line.flight().daysIn(week);
            String call = boards.house().loggingCall(line.station());
            List<Lay> lays = days.stream().map(day -> boards.lay(call, day)).toList();
            Ask ask = new Ask(
                    order.number(),
                    line.number(),
                    accounts.advertiser(order.advertiser()),
                    line.daypart(),
                    line.length(),
                    line.grade(),
                    line.carts().isEmpty() ? "" : line.carts().get(0),
                    order.agency());
            Optional<Rotation> rotation = Optional.ofNullable(rotations.get(line.number()));
            if (rotation.isEmpty() && line.carts().size() > 1) {
                rotation = Optional.of(Rotation.even(line.carts().toArray(String[]::new)));
            }
            Result placed = Placer.placeWeek(
                    lays, accounts, ask, count, rotation, line.flight().dailyCap());
            results.add(new LineResult(line, week, placed));
        }
        results.sort(Comparator.comparingInt(entry -> entry.line().number()));
        return new OrderResult(order, week, results);
    }

    /** Run a week's book. Confirmed orders only, dearest order first. */
    public static List<OrderResult> scheduleAll(
            Boards boards, AccountBook accounts, List<Order> orders, BroadcastWeek week) {
        return orders.stream()
                .filter(order -> order.status().maySchedule())
                .sorted(Comparator.comparingLong(
                                (Order order) -> -order.valueIn(week).cents())
                        .thenComparing(Order::number))
                .map(order -> scheduleOrder(boards, accounts, order, week))
                .toList();
    }
}
