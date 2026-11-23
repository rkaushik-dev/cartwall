package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.Candidacy;
import io.github.rkaushikdev.cartwall.domain.Race;
import io.github.rkaushikdev.cartwall.domain.Use;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The political file, which is a legal obligation and not a report.
 *
 * <p>Every request for political time goes in it, accepted or turned down, and it goes in the day
 * it arrives. Nothing here decides anything: it keeps what is on file, in the order it was
 * requested, refuses to render an entry that is missing something the rule requires, and answers
 * what the file says about who is still in time to ask for equal time.
 */
public final class PoliticalFile {

    /** How long after a use an opponent has to ask for equal time. */
    public static final int EQUAL_OPPORTUNITY_DAYS = 7;

    private static final Comparator<FileEntry> REQUEST_ORDER = Comparator.comparing(FileEntry::requested)
            .thenComparing(entry -> entry.candidacy().candidate());

    private final List<FileEntry> entries = new ArrayList<>();

    public void file(FileEntry entry) {
        entries.add(entry);
        entries.sort(REQUEST_ORDER);
    }

    /** Everything on file, in the order it was requested. */
    public List<FileEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<FileEntry> forCandidate(String candidate) {
        return matching(row -> row.candidacy().candidate().equals(candidate));
    }

    public List<FileEntry> forRace(String office) {
        return matching(row -> row.candidacy().race().office().equals(office));
    }

    /** Entries that cannot be filed yet, which somebody has to chase. */
    public List<FileEntry> incomplete() {
        return matching(row -> !row.isComplete());
    }

    public List<FileEntry> rejected() {
        return matching(row -> !row.accepted());
    }

    /** What each candidate's accepted requests came to, biggest first. */
    public Map<String, Money> spendByCandidate() {
        Map<String, Money> tally = new TreeMap<>();
        matching(FileEntry::accepted)
                .forEach(row -> tally.merge(row.candidacy().candidate(), row.value(), Money::plus));
        Map<String, Money> ordered = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(pair -> ordered.put(pair.getKey(), pair.getValue()));
        return ordered;
    }

    /**
     * The opponents' uses {@code candidacy} may still ask for equal time against, as of {@code on}.
     *
     * <p>When the station sells a legally qualified candidate time, every opponent in the same race
     * is owed the chance to buy the same, but only if they ask within a week of the use: each
     * spot that aired opens its own seven days, counted from the day it ran. Issue advertising is
     * not a use by a candidate and opens nothing. An entry that has not yet been read off the
     * as-run has no air on it, so it opens nothing either, and the clock starts when it does.
     */
    public List<Opening> equalOpportunities(Candidacy candidacy, LocalDate on) {
        Race race = candidacy.race();
        List<Opening> found = new ArrayList<>();
        for (FileEntry entry : matching(row -> row.accepted() && row.use() == Use.CANDIDATE)) {
            Candidacy opponent = entry.candidacy();
            if (opponent.candidate().equals(candidacy.candidate())
                    || !opponent.race().equals(race)) {
                continue;
            }
            for (Aired use : entry.aired()) {
                LocalDate closes = use.day().plusDays(EQUAL_OPPORTUNITY_DAYS);
                if (!on.isBefore(use.day()) && !on.isAfter(closes)) {
                    found.add(new Opening(opponent.candidate(), entry.order(), use, closes));
                }
            }
        }
        found.sort(Comparator.comparing(Opening::closes).thenComparing(Opening::opponent));
        return List.copyOf(found);
    }

    /**
     * One use by an opponent that equal time can still be asked for against.
     *
     * @param opponent whose use it was
     * @param order the order it ran on
     * @param use the spot as it aired
     * @param closes the last day a request against it is in time
     */
    public record Opening(String opponent, String order, Aired use, LocalDate closes) {}

    private List<FileEntry> matching(Predicate<FileEntry> test) {
        return entries.stream().filter(test).toList();
    }

    /** Every complete entry, rendered, one after another. */
    public String renderAll() {
        return entries.stream()
                .filter(FileEntry::isComplete)
                .map(FileEntry::render)
                .collect(Collectors.joining("\n"));
    }

    public int size() {
        return entries.size();
    }
}
