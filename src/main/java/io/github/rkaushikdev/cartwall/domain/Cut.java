package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One piece of audio, with the dates it is allowed to run between.
 *
 * <p>A cut's actual running time is not its sold length. Production hands over a thirty that runs
 * 29.4 and another that runs 30.3, and both are fine. The tolerance is half a second either way
 * and it is checked when the cut is filed, not when the log runs.
 *
 * @param cart the cart number the automation plays it by
 * @param isci the industry code on the copy, ISCI or Ad-ID
 * @param advertiser whose commercial it is
 * @param length the sold length it runs against
 * @param actual the running time production measured
 * @param firstAir the first day it may run
 * @param lastAir the last day it may run, when there is one
 * @param killed pulled by the client or by production; the dates still decide what ran
 */
public record Cut(
        String cart,
        String isci,
        String advertiser,
        SpotLength length,
        Duration actual,
        LocalDate firstAir,
        Optional<LocalDate> lastAir,
        boolean killed) {

    private static final Pattern CART = Pattern.compile("^[A-Z]\\d{3,5}$");

    public Cut {
        Objects.requireNonNull(lastAir, "lastAir");
        if (!CART.matcher(cart).matches()) {
            throw new SpotError("'" + cart + "' is not a cart number");
        }
        CopyCode.parse(isci);
        if (advertiser == null || advertiser.isEmpty()) {
            throw new SpotError("cart " + cart + " names no advertiser");
        }
        if (!length.fits(actual)) {
            throw new SpotError("cart " + cart + " runs " + actual + " against a " + length + " sold length");
        }
        if (lastAir.filter(last -> last.isBefore(firstAir)).isPresent()) {
            throw new SpotError("cart " + cart + " is killed before it starts");
        }
    }

    /** A live cut with no end date. */
    public Cut(String cart, String isci, String advertiser, SpotLength length, Duration actual, LocalDate firstAir) {
        this(cart, isci, advertiser, length, actual, firstAir, Optional.empty(), false);
    }

    /**
     * Eligible on {@code day}.
     *
     * <p>A killed cut is not special here. Killing it set a last air date, and the dates are what
     * the affidavit has to agree with. Asking whether a killed cut ran last Tuesday has to answer
     * yes if it did.
     */
    public boolean runsOn(LocalDate day) {
        if (day.isBefore(firstAir)) {
            return false;
        }
        return lastAir.map(last -> !day.isAfter(last)).orElse(true);
    }

    /** The industry code, read for which system issued it. */
    public CopyCode code() {
        return CopyCode.parse(isci);
    }

    public boolean isShort() {
        return actual.compareTo(length.air()) < 0;
    }

    public boolean isLong() {
        return actual.compareTo(length.air()) > 0;
    }

    /** The same cut, stopped after {@code on}. */
    public Cut killedAfter(LocalDate on) {
        return new Cut(cart, isci, advertiser, length, actual, firstAir, Optional.of(on), true);
    }

    @Override
    public String toString() {
        return cart + " " + advertiser + " " + length;
    }
}
