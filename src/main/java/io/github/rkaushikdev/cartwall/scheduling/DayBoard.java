package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.domain.Break;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Every avail one station has on one date: what is actually available to sell.
 *
 * <p>The grid says which clock runs in which hour. This turns that into a real board, one avail
 * per sellable break per hour, each with whatever has already been put in it. Nothing here decides
 * what to place; that is the log builder's job, and it asks these questions constantly.
 */
public final class DayBoard {

    /** Air order: by hour, then by where the break sits in the hour. */
    static final Comparator<Avail> AIR_ORDER =
            Comparator.comparingInt(Avail::hour).thenComparing(Avail::position);

    private final String station;
    private final LocalDate day;
    private final List<Avail> avails = new ArrayList<>();

    public DayBoard(String station, LocalDate day) {
        this.station = station;
        this.day = day;
    }

    /** Lay out a date from the grid, skipping preempted hours and anything the licence has off the air. */
    public static DayBoard build(ClockGrid grid, LocalDate day) {
        DayBoard board = new DayBoard(grid.station(), day);
        grid.breaksOn(day).forEach((hour, breaks) -> breaks.stream()
                .filter(Break::isSellable)
                .forEach(stopset -> board.avails.add(Avail.of(grid.station(), day, hour, stopset))));
        return board;
    }

    public String station() {
        return station;
    }

    public LocalDate day() {
        return day;
    }

    /** Every avail, in the order the grid laid them out. */
    public List<Avail> avails() {
        return Collections.unmodifiableList(avails);
    }

    /** Add an avail by hand, for a board read back rather than built from a grid. */
    public void addAvail(Avail avail) {
        avails.add(avail);
    }

    public Avail avail(int hour, int number) {
        return avails.stream()
                .filter(avail -> avail.hour() == hour && avail.number() == number)
                .findFirst()
                .orElseThrow(() ->
                        new InventoryError(station + " has no break " + number + " in hour " + hour + " on " + day));
    }

    public List<Avail> inHour(int hour) {
        return avails.stream().filter(avail -> avail.hour() == hour).toList();
    }

    public List<Integer> hours() {
        TreeSet<Integer> hours = new TreeSet<>();
        avails.forEach(avail -> hours.add(avail.hour()));
        return List.copyOf(hours);
    }

    /** Every avail that would take a spot of this length, in air order. */
    public List<Avail> openAvails(SpotLength length) {
        return avails.stream()
                .filter(avail -> avail.accepts(length))
                .sorted(AIR_ORDER)
                .toList();
    }

    public int totalUnits() {
        return avails.stream().mapToInt(Avail::units).sum();
    }

    public int usedUnits() {
        return avails.stream().mapToInt(Avail::usedUnits).sum();
    }

    public int freeUnits() {
        return totalUnits() - usedUnits();
    }

    /** Fraction of the day's units that are spoken for, zero to one. */
    public double sellout() {
        if (totalUnits() == 0) {
            return 0.0;
        }
        return usedUnits() / (double) totalUnits();
    }

    /** Everything on the board, in the order it will air. */
    public List<Timed> placements() {
        List<Timed> timed = new ArrayList<>();
        avails.stream().sorted(AIR_ORDER).forEach(avail -> timed.addAll(avail.airTimes()));
        return List.copyOf(timed);
    }

    /** The avails holding a spot of this line. */
    public List<Avail> find(String order, int line) {
        return avails.stream().filter(avail -> avail.holds(order, line)).toList();
    }

    /** How many spots of this line are on the board. */
    public int countFor(String order, int line) {
        LineKey key = new LineKey(order, line);
        return (int) avails.stream()
                .flatMap(avail -> avail.placements().stream())
                .filter(placement -> placement.key().equals(key))
                .count();
    }

    public int size() {
        return avails.size();
    }
}
