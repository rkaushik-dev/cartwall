package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The hours a daytimer's licence lets it be on the air, one sign-on and sign-off per month.
 *
 * <p>The licence does not say "sunset". It carries a table of twelve pairs of times, one for each
 * calendar month, worked out once from the average sunrise and sunset at the transmitter, and the
 * station signs off at the time on the table whatever the sky is doing. The months are wall
 * calendar months, not broadcast months: the table is the regulator's and the regulator does not
 * keep a broadcast calendar.
 *
 * <p>A break is on the air only when all of it is: it starts at or after sign-on and ends at or
 * before sign-off. A stopset that straddles sign-off is dead air the automation will never reach,
 * and a spot sold into it is a spot that cannot run. The legal ID is the exception that moves:
 * the station has to identify itself at sign-on and sign-off whatever the clock says.
 *
 * @param months twelve windows, January first
 */
public record LicensedHours(List<Window> months) {

    /**
     * One month's sign-on and sign-off.
     *
     * @param signOn the earliest the transmitter may come up
     * @param signOff when it has to be down
     */
    public record Window(AirTime signOn, AirTime signOff) {

        public Window {
            if (!signOff.isAfter(signOn)) {
                throw new StationError("a licence that signs off at " + signOff.twelveHour() + " before signing on at "
                        + signOn.twelveHour() + " is not a licence");
            }
        }

        /** {@code 7:45a-4:45p}, the way the licence table is copied onto the traffic desk. */
        public static Window parse(String text) {
            String[] ends = text.strip().split("-", -1);
            if (ends.length != 2) {
                throw new StationError("'" + text + "' is not a sign-on and a sign-off");
            }
            return new Window(AirTime.parse(ends[0]), AirTime.parse(ends[1]));
        }

        /** Whether a stretch of air starting at {@code start} and running {@code length} is all inside. */
        public boolean holds(AirTime start, Duration length) {
            if (start.isBefore(signOn)) {
                return false;
            }
            return start.tenths() + length.tenths() <= signOff.tenths();
        }

        @Override
        public String toString() {
            return signOn.twelveHour() + "-" + signOff.twelveHour();
        }
    }

    public LicensedHours {
        months = List.copyOf(months);
        if (months.size() != Month.values().length) {
            throw new StationError("a daytimer's licence lists twelve months, not " + months.size());
        }
    }

    /** The table as it is typed off the licence: twelve {@code sign-on-sign-off} pairs, January first. */
    public static LicensedHours of(String... windows) {
        return new LicensedHours(Arrays.stream(windows).map(Window::parse).toList());
    }

    /** The window in force on {@code day}. */
    public Window on(LocalDate day) {
        return months.get(day.getMonthValue() - 1);
    }

    /** Whether a break in hour {@code hour} of {@code day} is wholly on the air. */
    public boolean keeps(LocalDate day, int hour, Break stopset) {
        return on(day).holds(stopset.startsAt(hour), stopset.length());
    }

    /**
     * The breaks of {@code clock} that go out in hour {@code hour} of {@code day}, in air order, with
     * the station identified at sign-on and at sign-off.
     *
     * <p>The licence wants the call letters when the transmitter comes up and just before it goes
     * down, as well as at the top of every hour. When sign-on falls partway into an hour the
     * clock's top of hour ID is dead air, so the ID moves to the moment of sign-on; and the last
     * hour gets a second ID that runs out exactly at sign-off. A clock with no legal ID in it has
     * nothing to move.
     */
    public List<Break> onAir(LocalDate day, int hour, Clock clock) {
        Window window = on(day);
        List<Break> kept = new ArrayList<>(clock.breaks().stream()
                .filter(stopset -> keeps(day, hour, stopset))
                .toList());
        Optional<Break> legal = clock.breaks().stream()
                .filter(stopset -> stopset.kind() == BreakKind.LEGAL_ID)
                .findFirst();
        if (kept.isEmpty() || legal.isEmpty()) {
            return List.copyOf(kept);
        }
        Break id = legal.get();
        if (window.signOn().hour() == hour && !kept.contains(id)) {
            kept.add(id.at(window.signOn().intoHour()));
        }
        AirTime lastId = window.signOff().minus(id.length());
        if (lastId.hour() == hour) {
            kept.add(id.at(lastId.intoHour()));
        }
        return kept.stream().distinct().sorted().toList();
    }
}
