package io.github.rkaushikdev.cartwall.reports;

import java.util.Optional;

/**
 * The five ages of an open invoice, and the day count each one opens at.
 *
 * <p>The boundaries are inclusive at the low end, which matters more than it sounds: an invoice
 * raised exactly thirty days ago is in the thirty day bucket, not in current, and a station that
 * rounds the other way reports a month that looks a month better than it is.
 *
 * <p>Ninety and a hundred and twenty are kept apart because the office does different things at
 * each: at ninety the sales manager calls, at a hundred and twenty the account stops placing
 * until somebody pays, and a single "90 plus" line hides which of the two is due.
 */
public enum Bucket {
    CURRENT(0, "current"),
    THIRTY(30, "30 days"),
    SIXTY(60, "60 days"),
    NINETY(90, "90 days"),
    OVER_120(120, "120 plus");

    private final int opens;
    private final String label;

    Bucket(int opens, String label) {
        this.opens = opens;
        this.label = label;
    }

    /** The age, in days, the bucket opens at. */
    public int opens() {
        return opens;
    }

    /** How the office says it. */
    public String label() {
        return label;
    }

    /** The last age the bucket holds, or nothing for the oldest, which holds everything after. */
    public Optional<Integer> closes() {
        Bucket[] all = values();
        return ordinal() == all.length - 1 ? Optional.empty() : Optional.of(all[ordinal() + 1].opens - 1);
    }

    /** Which bucket an age falls in. Inclusive at the low end. */
    public static Bucket of(long days) {
        if (days < 0) {
            throw new StatementError(days + " days is not an age");
        }
        Bucket found = CURRENT;
        for (Bucket bucket : values()) {
            if (days >= bucket.opens) {
                found = bucket;
            }
        }
        return found;
    }
}
