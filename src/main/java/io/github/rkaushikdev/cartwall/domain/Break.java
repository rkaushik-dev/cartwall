package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import java.util.Comparator;

/**
 * One stopset, at a fixed offset into whatever hour the clock runs in.
 *
 * <p>A break carries a position measured from the top of the hour, a length, which is what can be
 * sold, and a unit count, which is how many spots will fit. A two minute break holds four thirties
 * or two sixties, and a station that sells units will refuse the fifth thirty even though eleven
 * seconds are free.
 *
 * @param position how far into the hour the break starts
 * @param number the break's number on the clock
 * @param kind local, network, legal ID or promo
 * @param length how much air the break holds
 * @param units how many spots the break holds
 * @param paid whether paid orders may go in; promo breaks sell only to house and trade
 */
public record Break(Duration position, int number, BreakKind kind, Duration length, int units, boolean paid)
        implements Comparable<Break> {

    /** An hour of air. */
    public static final Duration AN_HOUR = Duration.ofSeconds(Duration.SECONDS_PER_HOUR);

    private static final Comparator<Break> AIR_ORDER =
            Comparator.comparing(Break::position).thenComparingInt(Break::number);

    public Break {
        if (number < 1) {
            throw new ClockError("break " + number + " is not a break number");
        }
        if (position.compareTo(AN_HOUR) >= 0) {
            throw new ClockError("break " + number + " starts at " + position + ", past the hour");
        }
        if (length.isZero()) {
            throw new ClockError("break " + number + " has no length");
        }
        if (position.plus(length).compareTo(AN_HOUR) > 0) {
            throw new ClockError("break " + number + " runs past the top of the next hour");
        }
        if (units < 1) {
            throw new ClockError("break " + number + " holds no spots");
        }
        if (!kind.sells() && paid) {
            throw new ClockError("break " + number + " is a " + kind.value() + " break and cannot be paid");
        }
    }

    /** A break paid orders may go in. */
    public Break(Duration position, int number, BreakKind kind, Duration length, int units) {
        this(position, number, kind, length, units, true);
    }

    /** Shorthand for the common case, a local stopset on a whole minute. */
    public static Break local(int number, int minute, int seconds, int units) {
        return new Break(Duration.ofSeconds(minute * 60), number, BreakKind.LOCAL, Duration.ofSeconds(seconds), units);
    }

    /** Whether a paid spot may go in: a kind traffic sells, and not held back for house and trade. */
    public boolean isSellable() {
        return kind.sells() && paid;
    }

    /** The same break moved to {@code position} in the hour. */
    public Break at(Duration position) {
        return new Break(position, number, kind, length, units, paid);
    }

    /** Where the break ends, measured from the top of the hour. */
    public Duration ends() {
        return position.plus(length);
    }

    /** The air time the break starts at in {@code hour}. */
    public AirTime startsAt(int hour) {
        return AirTime.at(hour).plus(position);
    }

    public boolean overlaps(Break other) {
        return position.compareTo(other.ends()) < 0 && other.position.compareTo(ends()) < 0;
    }

    @Override
    public int compareTo(Break other) {
        return AIR_ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return "break " + number + " at " + position;
    }
}
