package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.AirTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The dayparts one station sells, with run of schedule underneath.
 *
 * <p>Dayparts on a grid may overlap on purpose: morning drive is inside AAA, and AAA is inside
 * ROS, and a rate card quotes all three at different money. What a grid may not have is a hole,
 * because run of schedule is defined as everything and a hole means some hour is unsellable
 * without anybody deciding that.
 *
 * @param dayparts every daypart on the grid, in filing order
 */
public record DaypartGrid(List<Daypart> dayparts) {

    public DaypartGrid {
        dayparts = List.copyOf(dayparts);
        Set<String> seen = new HashSet<>();
        Set<String> repeated = new TreeSet<>();
        for (Daypart daypart : dayparts) {
            if (!seen.add(daypart.code())) {
                repeated.add(daypart.code());
            }
        }
        if (!repeated.isEmpty()) {
            throw new DaypartError(String.join(", ", repeated) + " is on the grid twice");
        }
    }

    /** What the music stations run. Sports and farm override pieces of it. */
    public static DaypartGrid standardGrid() {
        return new DaypartGrid(List.of(
                Daypart.build("AMD", "morning drive", "M-F", 6, 10),
                Daypart.build("MID", "midday", "M-F", 10, 15),
                Daypart.build("PMD", "afternoon drive", "M-F", 15, 19),
                Daypart.build("EVE", "evening", "M-F", 19, 24),
                Daypart.build("SAT", "saturday", "Sa", 6, 24),
                Daypart.build("SUN", "sunday", "Su", 6, 24),
                Daypart.build("OVN", "overnight", "M-Su", 0, 6),
                Daypart.build("AAA", "6a to 7p", "M-Su", 6, 19),
                Daypart.build("ROS", "run of schedule", "M-Su", 0, 24)));
    }

    /** The daypart with this code. */
    public Daypart of(String code) {
        return dayparts.stream()
                .filter(daypart -> daypart.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new DaypartError(code + " is not on this grid"));
    }

    public boolean has(String code) {
        return dayparts.stream().anyMatch(daypart -> daypart.code().equals(code));
    }

    /** Every daypart that instant falls in, narrowest first. */
    public List<Daypart> covering(Weekday day, AirTime at) {
        return dayparts.stream()
                .filter(daypart -> daypart.holds(day, at))
                .sorted(Comparator.comparingInt(
                                (Daypart daypart) -> daypart.cells().size())
                        .thenComparing(Daypart::code))
                .toList();
    }

    /** Every hour of the week some daypart sells. */
    public Set<Cell> covered() {
        Set<Cell> cells = new TreeSet<>();
        dayparts.forEach(daypart -> cells.addAll(daypart.cells()));
        return Collections.unmodifiableSet(cells);
    }

    /** Hours of the week no daypart sells. */
    public Set<Cell> gaps() {
        Set<Cell> missing = new TreeSet<>(Cell.week());
        missing.removeAll(covered());
        return Collections.unmodifiableSet(missing);
    }

    public boolean isWholeWeek() {
        return gaps().isEmpty();
    }
}
