package io.github.rkaushikdev.cartwall.domain;

import java.util.Arrays;

/**
 * What the business office will let an account do, from most to least trusting.
 *
 * <p>The order is the point. An account moves down it when it stops paying and back up only when
 * somebody in the office decides it should, so anything that recommends a standing only ever
 * recommends a stricter one.
 */
public enum Credit {
    OPEN("open"),
    PREPAY("cash in advance"),
    HOLD("hold");

    private final String value;

    Credit(String value) {
        this.value = value;
    }

    /** The name stored in the database and sent over the API. */
    public String value() {
        return value;
    }

    public boolean maySchedule() {
        return this != HOLD;
    }

    /** Whether this standing lets the account do less than {@code other}. */
    public boolean stricterThan(Credit other) {
        return compareTo(other) > 0;
    }

    /** The stricter of the two. */
    public Credit atLeast(Credit other) {
        return stricterThan(other) ? this : other;
    }

    public static Credit fromValue(String value) {
        return Arrays.stream(values())
                .filter(credit -> credit.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new AccountError("'" + value + "' is not a credit standing"));
    }
}
