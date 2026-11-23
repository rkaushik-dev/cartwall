package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.Candidacy;
import io.github.rkaushikdev.cartwall.domain.Race;
import io.github.rkaushikdev.cartwall.domain.Use;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * One request for political time, and what became of it.
 *
 * <p>The entry is opened on the day of the request and the air times are added as the as-run
 * comes in, so it has two ages: when it was opened and when it was last touched.
 *
 * <p>Who paid has to be on it in a form the public can check. A candidate's spot carries a
 * disclaimer, and the disclaimer has to name the committee that is paying, not a nickname for it.
 * Issue advertising has no committee to hide behind, so the file lists the people who run the
 * sponsor: its officers or its board.
 */
public final class FileEntry {

    private final LocalDate requested;
    private final Candidacy candidacy;
    private final Use use;
    private final boolean accepted;
    private final String order;
    private final String disposition;
    private final List<Bought> bought;
    private final List<String> officers;
    private final List<Aired> aired = new ArrayList<>();
    private LocalDate touched;

    /**
     * @param order the order number, when the request was accepted
     * @param disposition why, when the station said no or said no to part of it
     */
    public FileEntry(
            LocalDate requested,
            Candidacy candidacy,
            Use use,
            boolean accepted,
            String order,
            String disposition,
            List<Bought> bought) {
        this(requested, candidacy, use, accepted, order, disposition, bought, List.of());
    }

    /**
     * @param order the order number, when the request was accepted
     * @param disposition why, when the station said no or said no to part of it
     * @param officers for issue advertising, who runs the sponsor
     */
    public FileEntry(
            LocalDate requested,
            Candidacy candidacy,
            Use use,
            boolean accepted,
            String order,
            String disposition,
            List<Bought> bought,
            List<String> officers) {
        this.requested = requested;
        this.candidacy = candidacy;
        this.use = use;
        this.accepted = accepted;
        this.order = order;
        this.disposition = disposition;
        this.bought = List.copyOf(bought);
        this.officers = List.copyOf(officers);
    }

    public LocalDate requested() {
        return requested;
    }

    public Candidacy candidacy() {
        return candidacy;
    }

    public Use use() {
        return use;
    }

    public boolean accepted() {
        return accepted;
    }

    public String order() {
        return order;
    }

    public String disposition() {
        return disposition;
    }

    public List<Bought> bought() {
        return bought;
    }

    /** Who runs the sponsor of an issue buy; empty for a candidate's own. */
    public List<String> officers() {
        return officers;
    }

    public List<Aired> aired() {
        return Collections.unmodifiableList(aired);
    }

    /** When the as-run last added to the entry. */
    public Optional<LocalDate> touched() {
        return Optional.ofNullable(touched);
    }

    public int spots() {
        return bought.stream().mapToInt(Bought::spots).sum();
    }

    public Money value() {
        return Money.total(bought.stream().map(Bought::value).toList());
    }

    public boolean isComplete() {
        return missing().isEmpty();
    }

    /** What the rule requires that this entry does not yet have. */
    public List<String> missing() {
        List<String> gaps = new ArrayList<>();
        switch (use) {
            case CANDIDATE -> {
                if (candidacy.authorisedBy().isEmpty()) {
                    gaps.add("the authorised by disclaimer");
                } else if (!candidacy.disclaimerNamesCommittee()) {
                    gaps.add("a disclaimer naming " + candidacy.committee());
                }
            }
            case ISSUE -> {
                if (officers.isEmpty()) {
                    gaps.add("the sponsor's officers or directors");
                }
            }
        }
        if (accepted) {
            if (order.isEmpty()) {
                gaps.add("the order number");
            }
            if (bought.isEmpty()) {
                gaps.add("what was bought");
            }
        } else if (disposition.isEmpty()) {
            gaps.add("why it was turned down");
        }
        return List.copyOf(gaps);
    }

    /** Add a spot off the as-run, on the day the as-run was read. */
    public void noteAir(Aired spot, LocalDate on) {
        aired.add(spot);
        touched = on;
    }

    /** The entry as it goes into the file. Refuses an incomplete one. */
    public String render() {
        List<String> gaps = missing();
        if (!gaps.isEmpty()) {
            throw new FileError(
                    "the entry for " + candidacy.candidate() + " is missing " + String.join(", ", gaps),
                    Details.of("candidate", candidacy.candidate(), "missing", gaps));
        }
        Race race = candidacy.race();
        Form form = new Form()
                .field("REQUEST", Ran.SWORN_DATE.format(requested))
                .field("CANDIDATE", candidacy.candidate())
                .field("OFFICE", race.office())
                .field("ELECTION", race.kind().value() + " " + Ran.SWORN_DATE.format(race.electionDay()))
                .field("COMMITTEE", candidacy.committee())
                .field("USE", use.value())
                .fieldIfAny("OFFICERS", String.join(", ", officers))
                .field("DISPOSITION", accepted ? "accepted" : "rejected")
                .fieldIfAny("NOTE", disposition);
        if (accepted) {
            form.field("ORDER", order)
                    .field("AUTHORISED", candidacy.authorisedBy())
                    .fields("BOUGHT", bought)
                    .field("TOTAL", value())
                    .fields("AIRED", aired);
        }
        return form.render();
    }

    @Override
    public String toString() {
        return Ran.SWORN_DATE.format(requested) + " " + candidacy + " " + (accepted ? "accepted" : "rejected") + " "
                + spots() + " spots " + value();
    }
}
