package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.Station;
import io.github.rkaushikdev.cartwall.logs.LogState;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.scheduling.Avail;
import io.github.rkaushikdev.cartwall.scheduling.Boards;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Lay;
import io.github.rkaushikdev.cartwall.scheduling.OrderResult;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import io.github.rkaushikdev.cartwall.scheduling.Run;
import io.github.rkaushikdev.cartwall.scheduling.StationDay;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The nightly run: read the orders, place the week, write the days down.
 *
 * <p>The order of operations is a business decision. Load the house once for the week; take the
 * confirmed orders running that week; restore what is already on the boards, because a rebuild is
 * not a fresh start (pinned spots stay where the traffic director put them, and a day that has gone
 * out is not touched); place, dearest order first; write every day that was touched. Skipping the
 * restore produces a better looking log and throws away every hand placement since the last run.
 */
public final class LogBuild {

    private LogBuild() {}

    /** Copy the hand placed spots off a stored board onto a fresh one. */
    public static List<Placement> restorePins(DayBoard board, DayBoard stored) {
        List<Placement> kept = new ArrayList<>();
        for (Avail avail : stored.avails()) {
            for (Placement placement : avail.placements()) {
                if (placement.fixed()) {
                    board.avail(avail.hour(), avail.number()).add(placement);
                    kept.add(placement);
                }
            }
        }
        return kept;
    }

    /**
     * Place and store one broadcast week across the whole cluster.
     *
     * <p>Every logged station gets a board for every day of the week, whether an order touches it or
     * not: an empty day is still a day the automation needs a log for, and a day that used to have
     * something on it has to be written empty rather than left holding last week's answer.
     */
    public static BuildReport buildWeek(UnitOfWork uow, House house, BroadcastWeek week) {
        LogRepository logs = new LogRepository(uow.entityManager());
        PinnedBoards boards = new PinnedBoards(house, logs);
        for (Station station : house.cluster().logged()) {
            for (LocalDate day : week.days()) {
                boards.board(station.call(), day);
            }
        }
        AccountBook accounts = uow.accounts().book();
        List<Order> running = uow.orders().runningBetween(week.start(), week.end()).stream()
                .filter(order -> order.status().maySchedule())
                .toList();
        BuildReport report = new BuildReport(week);
        report.orders.addAll(Run.scheduleAll(boards, accounts, running, week));

        for (StationDay built : boards.built()) {
            Optional<LogState> state = stateOf(logs, built.call(), built.day());
            if (state.isPresent() && state.get().isOut()) {
                report.skipped.add(
                        new Skipped(built.call(), built.day(), state.get().value()));
                HouseLog.warn(
                        "build.skipped",
                        field("station", built.call()),
                        field("day", built.day()),
                        field("state", state.get().value()));
                continue;
            }
            logs.save(boards.board(built.call(), built.day()));
            report.written.add(built);
        }

        HouseLog.event(
                "build.finished",
                field("week", week.toString()),
                field("orders", report.orders.size()),
                field("placed", report.placed()),
                field("wanted", report.wanted()),
                field("written", report.written.size()),
                field("skipped", report.skipped.size()));
        for (OrderResult shortfall : report.shortfalls()) {
            HouseLog.warn(
                    "build.short",
                    field("order", shortfall.order().number()),
                    field("placed", shortfall.placed()),
                    field("wanted", shortfall.wanted()));
        }
        return report;
    }

    /** The board and rules for each day, for a caller placing one line by hand. */
    public static List<Lay> laysFor(House house, Boards boards, String call, List<LocalDate> days) {
        String loggingCall = house.loggingCall(call);
        return days.stream().map(day -> boards.lay(loggingCall, day)).toList();
    }

    private static Optional<LogState> stateOf(LogRepository logs, String call, LocalDate day) {
        if (!logs.exists(call, day)) {
            return Optional.empty();
        }
        return Optional.of(logs.status(call, day).state());
    }

    /** A board cache that starts from the hand placed spots on file, not from empty. */
    public static final class PinnedBoards extends Boards {

        private final LogRepository logs;

        public PinnedBoards(House house, LogRepository logs) {
            super(house);
            this.logs = logs;
        }

        /**
         * A fresh board carrying only what somebody put there by hand. Everything the builder placed
         * last time is thrown away, because the orders it placed from may have changed, and a spot
         * still on the board for a cancelled order is a spot that will air.
         */
        @Override
        protected DayBoard freshBoard(String call, LocalDate day) {
            ClockGrid grid = house().programming().get(call);
            DayBoard fresh = DayBoard.build(grid, day);
            if (logs.exists(call, day)) {
                restorePins(fresh, logs.board(grid, day));
            }
            return fresh;
        }
    }

    /** A station day the builder left alone, and the state it was in. */
    public record Skipped(String call, LocalDate day, String state) {}

    /** What one run of the builder did. */
    public static final class BuildReport {

        private final BroadcastWeek week;
        private final List<OrderResult> orders = new ArrayList<>();
        private final List<StationDay> written = new ArrayList<>();
        private final List<Skipped> skipped = new ArrayList<>();

        public BuildReport(BroadcastWeek week) {
            this.week = week;
        }

        public BroadcastWeek week() {
            return week;
        }

        public List<OrderResult> orders() {
            return List.copyOf(orders);
        }

        public List<StationDay> written() {
            return List.copyOf(written);
        }

        public List<Skipped> skipped() {
            return List.copyOf(skipped);
        }

        public int placed() {
            return orders.stream().mapToInt(OrderResult::placed).sum();
        }

        public int wanted() {
            return orders.stream().mapToInt(OrderResult::wanted).sum();
        }

        public boolean isComplete() {
            return orders.stream().allMatch(OrderResult::isComplete);
        }

        public List<OrderResult> shortfalls() {
            return orders.stream().filter(result -> !result.isComplete()).toList();
        }

        @Override
        public String toString() {
            return week + ": " + placed() + " of " + wanted() + " placed, " + written.size() + " logs written, "
                    + skipped.size() + " left alone";
        }
    }
}
