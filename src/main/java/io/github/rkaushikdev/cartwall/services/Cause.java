package io.github.rkaushikdev.cartwall.services;

/** Why a spot is owed. Each source is its own report. */
public enum Cause {
    DID_NOT_RUN("did not run"),
    OUT_OF_DAYPART("ran outside its daypart"),
    BUMPED("bumped"),
    NEVER_SCHEDULED("never scheduled");

    private final String value;

    Cause(String value) {
        this.value = value;
    }

    /** The name stored in the database and printed on the reports. */
    public String value() {
        return value;
    }

    public static Cause fromValue(String value) {
        for (Cause cause : values()) {
            if (cause.value.equals(value)) {
                return cause;
            }
        }
        throw new MakegoodError("'" + value + "' is not a cause");
    }
}
