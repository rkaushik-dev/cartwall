package io.github.rkaushikdev.cartwall.logs;

import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.CutLibrary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What stands between a log and a signature.
 *
 * <p>Approval is gated on problems, not on warnings. A hole in a break is a warning and every log
 * has them. A spot with no cart is a problem, because the operator will reach the break and have
 * nothing to play, and an hour with no legal ID is a problem because that is the licence.
 *
 * <p>Read against the copy library, a cart that is on the log but outside its air dates is a
 * problem too: a sale that ended Sunday playing on Monday is a claim the advertiser never made.
 * A cart whose last day falls within the next week is only a warning, so production has a few
 * days to dub the next one before it becomes a problem.
 */
public final class Approval {

    /** How far ahead the log looks for copy that is about to run out. */
    public static final int EXPIRY_WARNING_DAYS = 7;

    private Approval() {}

    /** Everything that has to be fixed before this log can be signed. */
    public static List<Problem> problems(DayLog log) {
        List<Problem> found = new ArrayList<>();
        for (LogLine spot : uncarted(log)) {
            found.add(new Problem(
                    "missing_copy",
                    spot.at().twelveHour(),
                    spot.order() + "-" + spot.line() + " for " + spot.advertiser() + " has no cart"));
        }
        Set<Integer> withIds = new HashSet<>();
        log.legalIds().forEach(entry -> withIds.add(entry.hour()));
        for (int hour : log.hours()) {
            if (!withIds.contains(hour)) {
                found.add(new Problem("no_legal_id", "hour " + hour, "the hour carries no legal identification"));
            }
        }
        return List.copyOf(found);
    }

    /** Everything to fix, the carts checked against the dates production filed them for. */
    public static List<Problem> problems(DayLog log, CutLibrary library) {
        List<Problem> found = new ArrayList<>(problems(log));
        for (LogLine spot : log.spots()) {
            if (spot.cart().isEmpty()) {
                continue;
            }
            String who = spot.order() + "-" + spot.line() + " plays " + spot.cart();
            if (!library.contains(spot.cart())) {
                found.add(new Problem("copy_not_on_file", spot.at().twelveHour(), who + ", which nobody has filed"));
            } else if (!library.cut(spot.cart()).runsOn(log.day())) {
                found.add(new Problem(
                        "copy_out_of_dates",
                        spot.at().twelveHour(),
                        who + ", which " + airDates(library.cut(spot.cart()))));
            }
        }
        return List.copyOf(found);
    }

    /** Worth saying, not worth stopping for. */
    public static List<Problem> warnings(DayLog log) {
        int holes = log.openUnits();
        if (holes == 0) {
            return List.of();
        }
        return List.of(new Problem("unsold", log.station() + " " + log.day(), holes + " units did not sell"));
    }

    /** Worth saying, with copy that runs out inside the next week named once per cart. */
    public static List<Problem> warnings(DayLog log, CutLibrary library) {
        List<Problem> found = new ArrayList<>(warnings(log));
        LocalDate horizon = log.day().plusDays(EXPIRY_WARNING_DAYS);
        log.spots().stream()
                .map(LogLine::cart)
                .filter(cart -> !cart.isEmpty() && library.contains(cart))
                .distinct()
                .sorted()
                .map(library::cut)
                .filter(cut -> cut.runsOn(log.day())
                        && cut.lastAir().filter(last -> last.isBefore(horizon)).isPresent())
                .forEach(cut -> found.add(new Problem(
                        "copy_expiring", cut.cart(), cut.advertiser() + "'s " + cut.length() + " " + airDates(cut))));
        return List.copyOf(found);
    }

    /** Order and line of every spot the operator would have nothing to play for. */
    public static List<String> missingCopy(DayLog log) {
        return uncarted(log).stream()
                .map(entry -> entry.order() + "-" + entry.line())
                .distinct()
                .sorted()
                .toList();
    }

    private static List<LogLine> uncarted(DayLog log) {
        return log.spots().stream().filter(spot -> spot.cart().isEmpty()).toList();
    }

    private static String airDates(Cut cut) {
        String last = cut.lastAir().map(day -> " to " + day).orElse("");
        return "runs " + cut.firstAir() + last;
    }
}
