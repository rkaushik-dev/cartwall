package io.github.rkaushikdev.cartwall.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The industry code on a piece of copy: the old ISCI, or the Ad-ID that replaced it.
 *
 * <p>Both open with the four letter prefix the advertiser or its agency registered, which is how
 * traffic tells whose copy a dub is before anybody listens to it. An ISCI is that prefix and four
 * digits. An Ad-ID is the prefix and seven letters or digits, and may carry a twelfth character
 * saying which television version it is: {@code H} for high definition, {@code D} for 3D. Neither
 * of those is audio, so a radio station refuses them rather than file a TV spot's code against a
 * cart.
 *
 * @param code the code as it appears on the copy
 * @param kind which of the two it is
 */
public record CopyCode(String code, Kind kind) {

    /** Which system issued the code. */
    public enum Kind {
        ISCI,
        AD_ID
    }

    private static final Pattern ISCI = Pattern.compile("^[A-Z]{4}\\d{4}$");
    private static final Pattern AD_ID = Pattern.compile("^[A-Z]{4}[A-Z0-9]{7}([A-Z0-9]?)$");
    private static final Pattern AUDIO_AD_ID = Pattern.compile("^[A-Z]{4}[A-Z0-9]{7}$");

    public CopyCode {
        if (code == null
                || !(kind == Kind.ISCI ? ISCI : AUDIO_AD_ID).matcher(code).matches()) {
            throw new SpotError("'" + code + "' is not an " + (kind == Kind.ISCI ? "ISCI code" : "Ad-ID"));
        }
    }

    /** Whichever of the two {@code text} is, or a refusal naming both. */
    public static CopyCode parse(String text) {
        if (text != null && ISCI.matcher(text).matches()) {
            return new CopyCode(text, Kind.ISCI);
        }
        Matcher adId = text == null ? null : AD_ID.matcher(text);
        if (adId == null || !adId.matches()) {
            throw new SpotError("'" + text + "' is not an ISCI code or an Ad-ID");
        }
        String version = adId.group(1);
        if (version.equals("H") || version.equals("D")) {
            throw new SpotError("'" + text + "' is the " + (version.equals("H") ? "high definition" : "3D")
                    + " television version of an Ad-ID, not audio");
        }
        if (!version.isEmpty()) {
            throw new SpotError("'" + text + "' carries a version mark Ad-ID does not issue");
        }
        return new CopyCode(text, Kind.AD_ID);
    }

    /** The four letters the advertiser or its agency registered. */
    public String prefix() {
        return code.substring(0, 4);
    }

    @Override
    public String toString() {
        return code;
    }
}
