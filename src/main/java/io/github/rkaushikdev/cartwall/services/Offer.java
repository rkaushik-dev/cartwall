package io.github.rkaushikdev.cartwall.services;

import java.util.Arrays;

/** Where a makegood offer stands. Nothing airs because one was proposed; it has to be accepted. */
public enum Offer {
    OPEN("open"),
    ACCEPTED("accepted"),
    REFUSED("refused"),
    /** Withdrawn by the station, usually because the flight ended first. */
    EXPIRED("expired");

    private final String value;

    Offer(String value) {
        this.value = value;
    }

    /** The name stored in the database and sent over the API. */
    public String value() {
        return value;
    }

    public boolean isSettled() {
        return this != OPEN;
    }

    public static Offer fromValue(String value) {
        return Arrays.stream(values())
                .filter(state -> state.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new MakegoodError("'" + value + "' is not an offer state"));
    }
}
