package io.github.rkaushikdev.cartwall.logs;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.domain.Break;
import io.github.rkaushikdev.cartwall.domain.BreakKind;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.scheduling.Avail;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import io.github.rkaushikdev.cartwall.scheduling.Timed;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The log: one station, one day, every element in the order it hits the air.
 *
 * <p>The board only holds what was sold. The log accounts for the whole day: the legal ID at the
 * top of every hour, network breaks the operator must not fill, and the units that did not sell as
 * holes. Air times are computed from the clock and the lengths in front, never stored, which is
 * the only way the log and the board are guaranteed to agree.
 *
 * @param station the logged station
 * @param day the date
 * @param lines every element, in air order
 */
public record DayLog(String station, LocalDate day, List<LogLine> lines) implements Iterable<LogLine> {

    private static final Comparator<LogLine> AIR_ORDER =
            Comparator.comparing(LogLine::at).thenComparingInt(LogLine::breakNumber);

    public DayLog {
        lines = List.copyOf(lines);
    }

    /** Lay the whole day out from the grid's clocks and what the board has in each break. */
    public static DayLog build(ClockGrid grid, DayBoard board) {
        if (!grid.station().equals(board.station())) {
            throw new LogError(grid.station() + "'s grid was handed " + board.station() + "'s board");
        }
        List<LogLine> lines = new ArrayList<>();
        grid.breaksOn(board.day())
                .forEach((hour, breaks) -> breaks.forEach(stopset -> lines.addAll(breakLines(board, hour, stopset))));
        lines.sort(AIR_ORDER);
        return new DayLog(board.station(), board.day(), lines);
    }

    public List<LogLine> inHour(int hour) {
        return lines.stream().filter(entry -> entry.hour() == hour).toList();
    }

    public List<LogLine> inBreak(int hour, int number) {
        return lines.stream()
                .filter(entry -> entry.hour() == hour && entry.breakNumber() == number)
                .toList();
    }

    public List<LogLine> spots() {
        return lines.stream().filter(LogLine::isSpot).toList();
    }

    public List<LogLine> unsold() {
        return lines.stream().filter(entry -> entry.element() == Element.UNSOLD).toList();
    }

    /** What the automation is handed. Holes are not part of it. */
    public List<LogLine> airable() {
        return lines.stream().filter(entry -> entry.element().airs()).toList();
    }

    public List<LogLine> forOrder(String order) {
        return spots().stream().filter(entry -> entry.order().equals(order)).toList();
    }

    public List<LogLine> forOrder(String order, int line) {
        return forOrder(order).stream().filter(entry -> entry.line() == line).toList();
    }

    public List<LogLine> forAdvertiser(String advertiser) {
        return spots().stream()
                .filter(entry -> entry.advertiser().equals(advertiser))
                .toList();
    }

    public Duration commercialLength() {
        return Duration.total(spots().stream().map(LogLine::length).toList());
    }

    public int soldUnits() {
        return spots().stream()
                .mapToInt(entry -> new SpotLength(entry.length().wholeSeconds()).units())
                .sum();
    }

    public int openUnits() {
        return unsold().size();
    }

    public List<Integer> hours() {
        TreeSet<Integer> hours = new TreeSet<>();
        lines.forEach(entry -> hours.add(entry.hour()));
        return List.copyOf(hours);
    }

    public List<LogLine> legalIds() {
        return lines.stream()
                .filter(entry -> entry.element() == Element.LEGAL_ID)
                .toList();
    }

    public int size() {
        return lines.size();
    }

    @Override
    public Iterator<LogLine> iterator() {
        return lines.iterator();
    }

    /** Every element inside one break, in air order, holes included. */
    private static List<LogLine> breakLines(DayBoard board, int hour, Break stopset) {
        AirTime starts = AirTime.at(hour).plus(stopset.position());
        if (!stopset.isSellable()) {
            return List.of(new LogLine(starts, stopset.number(), elementFor(stopset.kind()), stopset.length()));
        }
        Avail avail = board.avail(hour, stopset.number());
        List<LogLine> lines = new ArrayList<>();
        for (Timed timed : avail.airTimes()) {
            Placement placement = timed.placement();
            lines.add(new LogLine(
                    timed.at(),
                    stopset.number(),
                    Element.SPOT,
                    placement.length().air(),
                    placement.order(),
                    placement.line(),
                    placement.advertiser(),
                    placement.cart(),
                    Optional.of(placement.grade())));
        }
        AirTime cursor = starts.plus(avail.usedLength());
        for (int hole = 0; hole < avail.freeUnits(); hole++) {
            lines.add(new LogLine(cursor, stopset.number(), Element.UNSOLD, Duration.ZERO));
        }
        return lines;
    }

    private static Element elementFor(BreakKind kind) {
        return switch (kind) {
            case LEGAL_ID -> Element.LEGAL_ID;
            case NETWORK -> Element.NETWORK;
            case PROMO, LOCAL -> Element.PROMO;
        };
    }
}
