package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One payer, everything open, as of one date, with the aging that is the only part anybody reads.
 *
 * <p>The list is courtesy. The aging is the document: an agency at ninety days is a conversation
 * with the sales manager, and one at a hundred and twenty is a conversation about whether they keep
 * placing.
 *
 * @param payer whose statement it is
 * @param on the date it is drawn as of
 * @param items every invoice on it
 */
public record Statement(String payer, LocalDate on, List<Item> items) {

    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ROOT);
    private static final Comparator<Item> OLDEST_FIRST =
            Comparator.comparing(Item::raised).thenComparing(Item::invoice);

    public Statement {
        items = List.copyOf(items);
    }

    /** Payers with the most past due beyond sixty days, for the sales manager's Monday list. */
    public static List<Statement> worstFirst(List<Statement> statements) {
        return worstFirst(statements, 60);
    }

    /** Payers with the most past due beyond {@code days}, worst first. */
    public static List<Statement> worstFirst(List<Statement> statements, int days) {
        return statements.stream()
                .filter(entry -> !entry.pastDue(days).isZero())
                .sorted(Comparator.comparing((Statement entry) -> entry.pastDue(days))
                        .reversed()
                        .thenComparing(Statement::payer))
                .toList();
    }

    public Money total() {
        return Money.total(items.stream().map(Item::owing).toList());
    }

    public boolean isClear() {
        return total().isZero();
    }

    /** Anything still owing, oldest first. */
    public List<Item> openItems() {
        return items.stream()
                .filter(item -> !item.owing().isZero())
                .sorted(OLDEST_FIRST)
                .toList();
    }

    /** Every bucket, always all of them, even the empty ones, in bucket order. */
    public Map<Bucket, Money> aging() {
        Map<Bucket, Money> tally = new EnumMap<>(Bucket.class);
        Arrays.stream(Bucket.values()).forEach(bucket -> tally.put(bucket, Money.ZERO));
        openItems().forEach(item -> tally.merge(item.bucket(on), item.owing(), Money::plus));
        return Collections.unmodifiableMap(tally);
    }

    /**
     * What share of the open money sits in {@code bucket}, zero to one.
     *
     * <p>The office reads the aging as shares rather than dollars when it compares payers: two
     * thousand dollars at ninety days is a rounding error on a large agency and the whole account
     * of a florist. A clear statement has nothing in any bucket, so every share is zero.
     */
    public double share(Bucket bucket) {
        if (isClear()) {
            return 0.0;
        }
        return aging().get(bucket).cents() / (double) total().cents();
    }

    /** Everything thirty days old or more. */
    public Money pastDue() {
        return pastDue(30);
    }

    /** Everything {@code days} old or more. The number that starts the call. */
    public Money pastDue(int days) {
        return Money.total(openItems().stream()
                .filter(item -> item.age(on) >= days)
                .map(Item::owing)
                .toList());
    }

    /** The oldest bucket with anything in it, or nothing on a clear statement. */
    public Optional<Bucket> oldestBucket() {
        return oldest().map(item -> item.bucket(on));
    }

    public Optional<Item> oldest() {
        return openItems().stream().findFirst();
    }

    /** The statement as it is posted, aging at the foot. */
    public String render() {
        Form form = new Form()
                .field("STATEMENT", payer)
                .field("AS OF", PRINTED.format(on))
                .blank()
                .rows(openItems(), "nothing outstanding")
                .blank()
                .field("TOTAL", total())
                .field("PAST DUE", pastDue())
                .blank();
        aging().forEach((bucket, money) ->
                form.row("%-10s %-12s %6s".formatted(bucket.label(), money, Percent.of(share(bucket)))));
        return form.render();
    }

    @Override
    public String toString() {
        return payer + " as of " + PRINTED.format(on) + ": " + total() + " on "
                + openItems().size();
    }
}
