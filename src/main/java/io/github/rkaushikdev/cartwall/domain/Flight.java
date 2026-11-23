package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.BroadcastCalendar;
import io.github.rkaushikdev.cartwall.BroadcastWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * When a line runs, on which weekdays, and how many spots a week.
 *
 * <p>A line is sold as spots per week, but a flight almost never starts on a Monday. A schedule of
 * twelve a week that starts on a Thursday has three days of that first week. The house rounds the
 * stub week down and says so on the order, and a stub week that would round to nothing still gets
 * one spot: zero in a week the client is paying for is worse than being a spot generous.
 *
 * <p>A flight can also go dark for whole broadcast weeks in the middle, which is how a buyer
 * stretches a budget over a season: two weeks on, one off. A dark week carries nothing, is not a
 * stub, and is not owed; it is a week the client did not buy.
 *
 * <p>And a buyer can cap the spots a day, so twelve a week does not land as six on Monday when
 * the rest of the week is tight. The cap has to leave room for the weekly count, or the flight
 * was sold a schedule it can never run.
 *
 * @param first the first day it may air
 * @param last the last day it may air
 * @param days the weekdays it airs on
 * @param perWeek spots in a whole week
 * @param dark the broadcast weeks inside the flight the client bought nothing in
 * @param dailyCap the most spots a day; zero when the buyer set no cap
 */
public record Flight(
        LocalDate first, LocalDate last, Set<Weekday> days, int perWeek, Set<BroadcastWeek> dark, int dailyCap) {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM/dd", Locale.ROOT);

    public Flight {
        days = days.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(days));
        dark = Collections.unmodifiableSortedSet(new TreeSet<>(dark));
        if (last.isBefore(first)) {
            throw new OrderError("a flight from " + first + " to " + last + " runs backwards");
        }
        if (days.isEmpty()) {
            throw new OrderError("a flight runs on no days");
        }
        if (perWeek < 1) {
            throw new OrderError(perWeek + " a week is not a schedule");
        }
        List<BroadcastWeek> touched = BroadcastCalendar.weeksBetween(first, last);
        for (BroadcastWeek week : dark) {
            if (!touched.contains(week)) {
                throw new OrderError("the flight from " + first + " to " + last + " cannot go dark the " + week);
            }
        }
        if (dark.size() == touched.size()) {
            throw new OrderError("a flight dark in every week it touches buys nothing");
        }
        if (dailyCap < 0) {
            throw new OrderError(dailyCap + " a day is not a cap");
        }
        if (dailyCap > 0 && dailyCap * days.size() < perWeek) {
            throw new OrderError(perWeek + " a week at no more than " + dailyCap + " a day over "
                    + Weekday.showDays(days) + " can never run");
        }
    }

    /** A flight that runs every week it touches, as many a day as fit. */
    public Flight(LocalDate first, LocalDate last, Set<Weekday> days, int perWeek) {
        this(first, last, days, perWeek, Set.of(), 0);
    }

    /** A flight with dark weeks and no daily cap. */
    public Flight(LocalDate first, LocalDate last, Set<Weekday> days, int perWeek, Set<BroadcastWeek> dark) {
        this(first, last, days, perWeek, dark, 0);
    }

    /** A flight with its days written the way the order writes them, such as {@code M-F}. */
    public static Flight build(LocalDate first, LocalDate last, String days, int perWeek) {
        return new Flight(first, last, Weekday.parseDays(days), perWeek);
    }

    /** The same flight, dark in the weeks holding each of {@code days}. */
    public Flight goingDark(List<LocalDate> days) {
        Set<BroadcastWeek> weeks = new TreeSet<>(dark);
        days.forEach(day -> weeks.add(BroadcastWeek.of(day)));
        return new Flight(first, last, this.days, perWeek, weeks, dailyCap);
    }

    /** The same flight, never more than {@code cap} spots on one day. */
    public Flight cappedAt(int cap) {
        return new Flight(first, last, days, perWeek, dark, cap);
    }

    /** Whether the buyer limited the spots a day. */
    public boolean isCapped() {
        return dailyCap > 0;
    }

    /** Every broadcast week the flight touches. */
    public List<BroadcastWeek> weeks() {
        return BroadcastCalendar.weeksBetween(first, last);
    }

    public boolean runsOn(LocalDate day) {
        return !day.isBefore(first)
                && !day.isAfter(last)
                && days.contains(Weekday.of(day))
                && !dark.contains(BroadcastWeek.of(day));
    }

    /** Every date this flight may put a spot on, in order. Dark weeks have none. */
    public List<LocalDate> airDays() {
        return first.datesUntil(last.plusDays(1)).filter(this::runsOn).toList();
    }

    public List<LocalDate> daysIn(BroadcastWeek week) {
        return week.days().stream().filter(this::runsOn).toList();
    }

    /** How many air days a whole week of this flight has. */
    public int fullWeekDays() {
        return days.size();
    }

    public boolean isDark(BroadcastWeek week) {
        return dark.contains(week);
    }

    /** A week the flight runs in, but not on every day it was sold for, so its count is prorated. */
    public boolean isStub(BroadcastWeek week) {
        int available = daysIn(week).size();
        return available > 0 && available < fullWeekDays();
    }

    /**
     * Spots ordered in {@code week}, prorating a stub week down.
     *
     * <p>A whole week gets the full weekly count. A stub week gets that count scaled by the air
     * days it actually has, rounded down, floored at one so a paid week is never empty. A dark week
     * and a week outside the flight get nothing.
     */
    public int countIn(BroadcastWeek week) {
        if (!isStub(week)) {
            return daysIn(week).isEmpty() ? 0 : perWeek;
        }
        return Math.max(1, perWeek * daysIn(week).size() / fullWeekDays());
    }

    /**
     * One week of a flight and the spots ordered in it.
     *
     * @param week the broadcast week
     * @param count spots ordered in it
     */
    public record WeekCount(BroadcastWeek week, int count) {}

    public List<WeekCount> weeklyCounts() {
        return weeks().stream().map(week -> new WeekCount(week, countIn(week))).toList();
    }

    /** Every spot the flight orders. */
    public int spots() {
        return weeklyCounts().stream().mapToInt(WeekCount::count).sum();
    }

    /** {@code 08/11, 08/25}: the Mondays of the dark weeks, the way the order writes them. */
    public String showDark() {
        return dark.stream().map(week -> MONTH_DAY.format(week.start())).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        String text = MONTH_DAY.format(first) + "-" + MONTH_DAY.format(last) + " " + Weekday.showDays(days) + " "
                + perWeek + "x";
        if (isCapped()) {
            text += " max " + dailyCap + "/day";
        }
        return dark.isEmpty() ? text : text + " dark " + showDark();
    }
}
