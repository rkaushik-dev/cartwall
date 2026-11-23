package io.github.rkaushikdev.cartwall.domain;

import java.time.LocalDate;
import java.util.Locale;

/**
 * A legally qualified candidate and the committee that pays.
 *
 * @param candidate the candidate's name
 * @param race the race they are in
 * @param committee the authorised committee
 * @param authorisedBy the disclaimer that has to be in the copy, kept so it can be checked
 */
public record Candidacy(String candidate, Race race, String committee, String authorisedBy) {

    public Candidacy {
        if (candidate == null || candidate.isEmpty()) {
            throw new PoliticalError("a candidacy needs a candidate");
        }
        if (committee == null || committee.isEmpty()) {
            throw new PoliticalError(candidate + " names no committee");
        }
    }

    /** A candidacy with no disclaimer recorded yet. */
    public Candidacy(String candidate, Race race, String committee) {
        this(candidate, race, committee, "");
    }

    /**
     * Whether the disclaimer names the committee that pays, ignoring case and spacing.
     *
     * <p>"Paid for by Merrick for Iowa" names it. "Paid for by Friends of Rob" does not, even
     * when the friends are the committee, because the public file is read by people who only have
     * the committee's registered name to go on.
     */
    public boolean disclaimerNamesCommittee() {
        return squeezed(authorisedBy).contains(squeezed(committee));
    }

    private static String squeezed(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** Whether the lowest unit charge applies to a spot on this day. */
    public boolean entitledOn(LocalDate day, Use use) {
        return use.entitled() && race.inWindow(day);
    }

    @Override
    public String toString() {
        return candidate + " for " + race.office();
    }
}
