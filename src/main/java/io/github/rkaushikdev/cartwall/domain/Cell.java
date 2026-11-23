package io.github.rkaushikdev.cartwall.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One hour of one weekday: a cell of the 168 that make up the selling week.
 *
 * @param day the weekday
 * @param hour 0 to 23
 */
public record Cell(Weekday day, int hour) implements Comparable<Cell> {

    private static final Comparator<Cell> ORDER =
            Comparator.comparing(Cell::day).thenComparingInt(Cell::hour);

    /** All 168 cells of the week, Monday midnight first. */
    public static List<Cell> week() {
        List<Cell> cells = new ArrayList<>(Daypart.CELLS_IN_WEEK);
        for (Weekday day : Weekday.values()) {
            for (int hour = 0; hour < Daypart.HOURS_IN_DAY; hour++) {
                cells.add(new Cell(day, hour));
            }
        }
        return List.copyOf(cells);
    }

    @Override
    public int compareTo(Cell other) {
        return ORDER.compare(this, other);
    }
}
