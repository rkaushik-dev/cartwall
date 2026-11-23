package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.domain.Break.AN_HOUR;
import static io.github.rkaushikdev.cartwall.domain.Break.local;

import io.github.rkaushikdev.cartwall.Duration;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A named hour, drawn once by programming and assigned to many hours of the week.
 *
 * <p>The clock is a pie: music, a break at twenty past, more music, a break at forty past, the
 * network news at the top. Traffic never decides where a break is. It decides what goes in one.
 *
 * @param code the clock's code on the grid
 * @param name what programming calls it
 * @param breaks the stopsets, in air order
 */
public record Clock(String code, String name, List<Break> breaks) implements Iterable<Break> {

    /** The legal ID has to sit within this much of the top of the hour. */
    public static final Duration LEGAL_ID_WINDOW = Duration.ofSeconds(5 * 60);

    public Clock {
        breaks = List.copyOf(breaks);
        if (code == null || code.isEmpty()) {
            throw new ClockError("a clock needs a code");
        }
        Set<Integer> numbers = new HashSet<>();
        for (Break stopset : breaks) {
            if (!numbers.add(stopset.number())) {
                throw new ClockError("clock " + code + " numbers a break twice");
            }
        }
        List<Break> ordered = breaks.stream().sorted().toList();
        for (int index = 1; index < ordered.size(); index++) {
            Break earlier = ordered.get(index - 1);
            Break later = ordered.get(index);
            if (earlier.overlaps(later)) {
                throw new ClockError(
                        "clock " + code + " has break " + earlier.number() + " running into break " + later.number());
            }
        }
        List<Break> legal = breaks.stream()
                .filter(stopset -> stopset.kind() == BreakKind.LEGAL_ID)
                .toList();
        if (legal.size() > 1) {
            throw new ClockError("clock " + code + " calls for two legal IDs");
        }
        for (Break stopset : legal) {
            boolean nearTop = stopset.position().compareTo(LEGAL_ID_WINDOW) <= 0
                    || stopset.position().compareTo(AN_HOUR.minus(LEGAL_ID_WINDOW)) >= 0;
            if (!nearTop) {
                throw new ClockError("clock " + code + " puts the legal ID at " + stopset.position());
            }
        }
    }

    /** A clock with its breaks put in air order, however they were handed in. */
    public static Clock build(String code, String name, List<Break> breaks) {
        return new Clock(code, name, breaks.stream().sorted().toList());
    }

    /** Four stopsets, twelve minutes, legal ID at the top. */
    public static Clock musicClock() {
        return build(
                "MUS",
                "music hour",
                List.of(
                        legalId(),
                        local(2, 10, 180, 6),
                        local(3, 22, 180, 6),
                        local(4, 38, 180, 6),
                        local(5, 50, 180, 6)));
    }

    /** Longer breaks, fewer of them, and the network takes the bottom of the hour. */
    public static Clock talkClock() {
        return build(
                "TLK",
                "news talk hour",
                List.of(
                        legalId(),
                        local(2, 8, 240, 8),
                        local(3, 20, 240, 8),
                        new Break(Duration.ofSeconds(30 * 60), 4, BreakKind.NETWORK, Duration.ofSeconds(300), 1, false),
                        local(5, 42, 240, 8),
                        local(6, 52, 180, 6)));
    }

    /** The break with this number. */
    public Break numbered(int number) {
        return breaks.stream()
                .filter(stopset -> stopset.number() == number)
                .findFirst()
                .orElseThrow(() -> new ClockError("clock " + code + " has no break " + number));
    }

    /** The breaks a paid spot may go in. */
    public List<Break> sellable() {
        return breaks.stream().filter(Break::isSellable).toList();
    }

    public Duration commercialLength() {
        return Duration.total(sellable().stream().map(Break::length).toList());
    }

    public double commercialMinutes() {
        return commercialLength().seconds() / 60;
    }

    public int unitCount() {
        return sellable().stream().mapToInt(Break::units).sum();
    }

    /** Whether a station carrying this clock stays inside its stated load. */
    public boolean fitsLoad(int minutes) {
        return commercialLength().compareTo(Duration.ofSeconds(minutes * 60)) <= 0;
    }

    public int size() {
        return breaks.size();
    }

    @Override
    public Iterator<Break> iterator() {
        return breaks.iterator();
    }

    /** {@code MUS (12 minutes)}. */
    @Override
    public String toString() {
        return code + " (" + general(commercialMinutes()) + " minutes)";
    }

    /** A number in general form: six significant digits, no trailing zeros. */
    static String general(double value) {
        return new BigDecimal(value)
                .round(new MathContext(6, RoundingMode.HALF_EVEN))
                .stripTrailingZeros()
                .toPlainString();
    }

    private static Break legalId() {
        return new Break(Duration.ZERO, 1, BreakKind.LEGAL_ID, Duration.ofSeconds(10), 1, false);
    }
}
