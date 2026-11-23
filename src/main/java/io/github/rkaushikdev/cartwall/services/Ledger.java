package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * What is owed and what has been offered back: a read model.
 *
 * <p>Nothing here writes. Debts are raised and offers are made and settled through the makegood
 * repository, which is the only place that can do it durably and the only place that can tell one
 * of several identical debts from another. This assembles what those rows mean and answers the
 * questions the reports ask.
 */
public final class Ledger {

    private static final Comparator<LedgerEntry> OLDEST_FIRST = Comparator.comparing(
                    (LedgerEntry entry) -> entry.debt().day())
            .thenComparing(entry -> entry.debt().order())
            .thenComparingInt(entry -> entry.debt().line());

    private final List<LedgerEntry> entries = new ArrayList<>();

    public List<LedgerEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<Debt> debts() {
        return entries.stream().map(LedgerEntry::debt).toList();
    }

    public List<Makegood> offers() {
        return entries.stream().flatMap(entry -> entry.offers().stream()).toList();
    }

    /** Put one debt on the ledger with nothing offered against it. */
    public LedgerEntry owe(Debt debt) {
        return owe(debt, List.of());
    }

    /** Put one debt on the ledger, with whatever was offered against it. */
    public LedgerEntry owe(Debt debt, List<Makegood> offers) {
        LedgerEntry entry = new LedgerEntry(debt, offers);
        entries.add(entry);
        return entry;
    }

    /** Entries with no accepted makegood, oldest first. */
    public List<LedgerEntry> owed() {
        return entries.stream().filter(LedgerEntry::isOwed).sorted(OLDEST_FIRST).toList();
    }

    public List<Debt> stillOwed() {
        return owed().stream().map(LedgerEntry::debt).toList();
    }

    /** Offers nobody has answered, soonest air date first. */
    public List<Makegood> openOffers() {
        return entries.stream()
                .map(LedgerEntry::open)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(Makegood::day))
                .toList();
    }

    /** Spots still owed per line, worst first. */
    public Map<LineKey, Integer> owedByLine() {
        return worstFirst(stillOwed(), Debt::key);
    }

    /** What the open debt is worth at the rate it was sold at. */
    public Money owedValue() {
        return Money.total(stillOwed().stream().map(Debt::rate).toList());
    }

    /** Spots still owed per cause, worst first. */
    public Map<String, Integer> byCause() {
        return worstFirst(stillOwed(), debt -> debt.cause().value());
    }

    /**
     * What has to be credited when {@code month} is invoiced: spots owed in the month with no
     * makegood accepted inside it, oldest first.
     *
     * <p>A miss made good in the same month nets out on the invoice. One still open at the close,
     * or made good only in the next month, is a spot the month billed for and did not deliver,
     * and the invoice carries a credit for it rather than a promise.
     */
    public List<Debt> toCreditAt(BroadcastMonth month) {
        return entries.stream()
                .filter(entry -> month.contains(entry.debt().day()))
                .filter(entry ->
                        entry.accepted().filter(made -> !made.crossesMonth()).isEmpty())
                .sorted(OLDEST_FIRST)
                .map(LedgerEntry::debt)
                .toList();
    }

    /** What the month's close has to credit, at the rate each spot was sold at. */
    public Money creditAt(BroadcastMonth month) {
        return Money.total(toCreditAt(month).stream().map(Debt::rate).toList());
    }

    public int size() {
        return entries.size();
    }

    private static <K extends Comparable<K>> Map<K, Integer> worstFirst(List<Debt> debts, Function<Debt, K> key) {
        Map<K, Integer> tally = new TreeMap<>();
        debts.forEach(debt -> tally.merge(key.apply(debt), 1, Integer::sum));
        Map<K, Integer> ordered = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }
}
