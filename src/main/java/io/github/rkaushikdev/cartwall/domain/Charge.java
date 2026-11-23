package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;

/**
 * One thing somebody actually paid, which is what the lowest unit charge measures.
 *
 * @param station the station
 * @param daypart the daypart code
 * @param grade the class of time
 * @param length the sold length
 * @param rate what was charged per spot
 * @param day the day it aired
 * @param advertiser who paid it
 */
public record Charge(
        String station,
        String daypart,
        ClassOfTime grade,
        SpotLength length,
        Money rate,
        LocalDate day,
        String advertiser) {

    /** A charge nobody needs to attribute. */
    public Charge(String station, String daypart, ClassOfTime grade, SpotLength length, Money rate, LocalDate day) {
        this(station, daypart, grade, length, rate, day, "");
    }

    /** Same class and same amount of time on the same station. */
    public boolean comparableTo(Charge other) {
        return station.equals(other.station)
                && daypart.equals(other.daypart)
                && grade == other.grade
                && length.equals(other.length);
    }

    /** Whether it aired inside the inclusive span. */
    public boolean within(LocalDate first, LocalDate last) {
        return !day.isBefore(first) && !day.isAfter(last);
    }

    @Override
    public String toString() {
        return station + " " + daypart + " " + grade.value() + " " + length + " " + rate;
    }
}
