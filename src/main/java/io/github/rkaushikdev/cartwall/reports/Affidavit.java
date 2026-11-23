package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The affidavit of performance, which is a sworn statement and not a printout.
 *
 * <p>Everything on it has to be a fact somebody is prepared to swear to: it comes off the as-run,
 * never off the log; a spot that did not run is not on it at all; and a spot with no as-run line
 * cannot go on it, because not knowing whether something ran is not knowing that it did. It is
 * refused rather than rendered when any of that is missing.
 */
public final class Affidavit {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM/dd", Locale.ROOT);
    private static final Comparator<Ran> AIR_ORDER =
            Comparator.comparing(Ran::day).thenComparing(Ran::at);

    private final String order;
    private final String advertiser;
    private final LocalDate first;
    private final LocalDate last;
    private final List<Ran> spots;
    private String swornBy = "";
    private LocalDate swornOn;
    private String notary = "";

    public Affidavit(String order, String advertiser, LocalDate first, LocalDate last, List<Ran> spots) {
        if (last.isBefore(first)) {
            throw new AffidavitError("affidavit for " + order + " runs backwards");
        }
        this.order = order;
        this.advertiser = advertiser;
        this.first = first;
        this.last = last;
        this.spots = List.copyOf(spots);
    }

    /** Build one, keeping only the spots that ran inside the period, in air order. */
    public static Affidavit forPeriod(
            String order, String advertiser, LocalDate first, LocalDate last, List<Ran> spots) {
        List<Ran> inside = spots.stream()
                .filter(spot -> !spot.day().isBefore(first) && !spot.day().isAfter(last))
                .sorted(AIR_ORDER)
                .toList();
        return new Affidavit(order, advertiser, first, last, inside);
    }

    public String order() {
        return order;
    }

    public String advertiser() {
        return advertiser;
    }

    public LocalDate first() {
        return first;
    }

    public LocalDate last() {
        return last;
    }

    public List<Ran> spots() {
        return spots;
    }

    public String swornBy() {
        return swornBy;
    }

    public Optional<LocalDate> swornOn() {
        return Optional.ofNullable(swornOn);
    }

    public String notary() {
        return notary;
    }

    public int count() {
        return spots.size();
    }

    public Money value() {
        return Money.total(spots.stream().map(Ran::rate).toList());
    }

    /** The stations the spots ran on, once each, in order. */
    public List<String> stations() {
        TreeSet<String> found = new TreeSet<>();
        spots.forEach(spot -> found.add(spot.station()));
        return List.copyOf(found);
    }

    /** What still stands between the statement and a signature. */
    public List<String> missing() {
        List<String> gaps = new ArrayList<>();
        if (spots.isEmpty()) {
            gaps.add("any spots to swear to");
        }
        if (swornBy.isEmpty()) {
            gaps.add("who is swearing to it");
        }
        if (swornOn == null) {
            gaps.add("when it was sworn");
        }
        return List.copyOf(gaps);
    }

    public boolean isReady() {
        return missing().isEmpty();
    }

    /** Spots that ran outside the period the affidavit covers. */
    public List<Ran> outside() {
        return spots.stream()
                .filter(spot -> spot.day().isBefore(first) || spot.day().isAfter(last))
                .toList();
    }

    /** Swear to it, with no notary. */
    public void swear(String who, LocalDate on) {
        swear(who, on, "");
    }

    /** Swear to it, before a notary for co-op. */
    public void swear(String who, LocalDate on, String notary) {
        if (who == null || who.isEmpty()) {
            throw new AffidavitError("somebody has to swear to it");
        }
        this.swornBy = who;
        this.swornOn = on;
        this.notary = notary;
    }

    /** Spots and money per station, by station. */
    public Map<String, StationTally> byStation() {
        Map<String, StationTally> tally = new TreeMap<>();
        for (Ran spot : spots) {
            tally.put(
                    spot.station(),
                    tally.getOrDefault(spot.station(), new StationTally(0, Money.ZERO))
                            .add(spot.rate()));
        }
        return Collections.unmodifiableMap(tally);
    }

    /** The document. Refuses to render one that cannot be sworn to. */
    public String render() {
        List<String> gaps = missing();
        if (!gaps.isEmpty()) {
            throw new AffidavitError(
                    "the affidavit for " + order + " is missing " + String.join(", ", gaps),
                    Details.of("order", order, "missing", gaps));
        }
        List<Ran> stray = outside();
        if (!stray.isEmpty()) {
            throw new AffidavitError(
                    stray.size() + " spot(s) ran outside " + first + " to " + last,
                    Details.of("order", order, "first", stray.get(0).day().toString()));
        }
        return new Form("AFFIDAVIT OF PERFORMANCE")
                .field("ORDER", order)
                .field("ADVERTISER", advertiser)
                .field("PERIOD", Ran.SWORN_DATE.format(first) + " to " + Ran.SWORN_DATE.format(last))
                .field("STATIONS", String.join(", ", stations()))
                .blank()
                .text("The spots below were broadcast at the dates and times shown.")
                .blank()
                .rows(spots.stream().sorted(AIR_ORDER).toList(), "")
                .blank()
                .field("TOTAL", count() + " spots, " + value())
                .blank()
                .field("SWORN BY", swornBy)
                .field("ON", Ran.SWORN_DATE.format(swornOn))
                .fieldIfAny("NOTARY", notary)
                .render();
    }

    @Override
    public String toString() {
        return order + " " + advertiser + " " + MONTH_DAY.format(first) + "-" + MONTH_DAY.format(last) + ": " + count()
                + " spots " + value();
    }
}
