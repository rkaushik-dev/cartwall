package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Rotation;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Putting a week of one line onto the board.
 *
 * <p>Spreading is the whole job and it happens twice. Across days, twelve over five is two and two
 * fifths, so the extras go to the earliest days. Across hours, each spot goes to the hour of the
 * daypart carrying the fewest of this line so far, and within an hour to the emptiest break. That
 * is what makes a schedule sound bought rather than dumped.
 *
 * <p>When a day cannot take its share, the leftovers spill onto the days that can. A spot the
 * client paid for goes somewhere in the week or on the miss list with a reason, and there is no
 * third outcome. Nothing here bumps anything; preemption is the next pass along.
 *
 * <p>A line the buyer capped at so many a day never goes over it, neither in the first spread nor
 * when spilling: a day already at its cap is not somewhere a spilled spot can go.
 */
public final class Placer {

    private Placer() {}

    /** Split {@code count} over {@code buckets}, extras to the front. Never negative. */
    public static List<Integer> spread(int count, int buckets) {
        if (buckets < 1) {
            throw new PlacerError("cannot spread over no buckets");
        }
        if (count < 0) {
            throw new PlacerError("cannot spread a negative count");
        }
        int base = count / buckets;
        int extra = count % buckets;
        List<Integer> shares = new ArrayList<>(buckets);
        for (int index = 0; index < buckets; index++) {
            shares.add(base + (index < extra ? 1 : 0));
        }
        return List.copyOf(shares);
    }

    /** Split {@code count} over {@code buckets}, extras to the front, none above {@code cap} when capped. */
    public static List<Integer> spread(int count, int buckets, int cap) {
        List<Integer> shares = spread(count, buckets);
        return cap > 0 ? shares.stream().map(share -> Math.min(share, cap)).toList() : shares;
    }

    /** Place {@code count} spots of {@code ask} across the days in {@code lays}. */
    public static Result placeWeek(List<Lay> lays, AccountBook accounts, Ask ask, int count) {
        return placeWeek(lays, accounts, ask, count, Optional.empty());
    }

    /** Place {@code count} spots of {@code ask}, the carts taking turns by {@code rotation}. */
    public static Result placeWeek(List<Lay> lays, AccountBook accounts, Ask ask, int count, Rotation rotation) {
        return placeWeek(lays, accounts, ask, count, Optional.of(rotation));
    }

    static Result placeWeek(List<Lay> lays, AccountBook accounts, Ask ask, int count, Optional<Rotation> rotation) {
        return placeWeek(lays, accounts, ask, count, rotation, 0);
    }

    /**
     * Place {@code count} spots of {@code ask}, never more than {@code cap} on one day.
     *
     * @param cap the line's daily cap; zero for none
     */
    static Result placeWeek(
            List<Lay> lays, AccountBook accounts, Ask ask, int count, Optional<Rotation> rotation, int cap) {
        if (count < 0) {
            throw new PlacerError("cannot place a negative number of spots");
        }
        Result result = new Result(ask);
        if (lays.isEmpty()) {
            result.miss(new Miss(Optional.empty(), "the flight has no air days in this week", Map.of()));
            return result;
        }
        lays.forEach(lay -> lay.prime(accounts));

        List<String> carts =
                rotation.map(chosen -> chosen.assign(count)).orElse(Collections.nCopies(count, ask.cart()));
        List<Integer> wanted = spread(count, lays.size(), cap);
        List<Spill> spills = new ArrayList<>();
        int index = 0;
        for (int day = 0; day < lays.size(); day++) {
            for (int spot = 0; spot < wanted.get(day); spot++) {
                Spill attempt = new Spill(lays.get(day), carts.get(index++));
                tryOne(attempt.origin(), accounts, ask, attempt.cart(), cap)
                        .ifPresentOrElse(result::land, () -> spills.add(attempt));
            }
        }
        for (Spill spill : spills) {
            spillOver(lays, spill, accounts, ask, cap)
                    .ifPresentOrElse(result::land, () -> result.miss(miss(spill.origin(), accounts, ask)));
        }
        for (; index < count; index++) {
            result.miss(new Miss(Optional.empty(), "the line runs no more than " + cap + " a day", Map.of()));
        }
        result.sortLanded();
        return result;
    }

    /** A spot that could not go on the day it was spread to, and the cart it was going to play. */
    private record Spill(Lay origin, String cart) {}

    private static Optional<Landed> tryOne(Lay lay, AccountBook accounts, Ask ask, String cart, int cap) {
        if (cap > 0 && lay.board().countFor(ask.order(), ask.line()) >= cap) {
            return Optional.empty();
        }
        Ask seeking = ask.withCart(cart);
        List<Opening> found = Candidates.openings(lay.board(), lay.book(), accounts, seeking, lay.neighbours());
        return pick(found, lay, ask).map(chosen -> Pinning.putSpot(lay, chosen.avail(), seeking, cart));
    }

    /** The emptiest hour of the daypart, then the emptiest break in it. */
    private static Optional<Opening> pick(List<Opening> found, Lay lay, Ask ask) {
        Map<Integer, Integer> mine = mineByHour(lay.board(), ask);
        return found.stream()
                .filter(Opening::isClean)
                .min(Comparator.comparingInt((Opening opening) -> mine.getOrDefault(opening.hour(), 0))
                        .thenComparingInt(opening -> opening.avail().usedUnits())
                        .thenComparingInt(Opening::hour)
                        .thenComparingInt(Opening::number));
    }

    private static Map<Integer, Integer> mineByHour(DayBoard board, Ask ask) {
        Map<Integer, Integer> tally = new HashMap<>();
        for (Avail avail : board.avails()) {
            for (Placement placement : avail.placements()) {
                if (placement.key().equals(ask.key())) {
                    tally.merge(avail.hour(), 1, Integer::sum);
                }
            }
        }
        return tally;
    }

    /** Try the other days, nearest first, when one day cannot take its share. */
    private static Optional<Landed> spillOver(List<Lay> lays, Spill spill, AccountBook accounts, Ask ask, int cap) {
        LocalDate from = spill.origin().day();
        return lays.stream()
                .filter(lay -> lay != spill.origin())
                .sorted(Comparator.comparingLong((Lay lay) -> Math.abs(ChronoUnit.DAYS.between(from, lay.day())))
                        .thenComparing(Lay::day))
                .map(lay -> tryOne(lay, accounts, ask, spill.cart(), cap))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Miss miss(Lay lay, AccountBook accounts, Ask ask) {
        List<Opening> found = Candidates.openings(lay.board(), lay.book(), accounts, ask, lay.neighbours());
        if (found.isEmpty()) {
            return new Miss(lay.day(), "no break in the daypart had room");
        }
        return new Miss(Optional.of(lay.day()), "every open break was blocked", Candidates.whyBlocked(found));
    }
}
