package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ChargeBook;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.PoliticalError;
import io.github.rkaushikdev.cartwall.domain.Quote;
import io.github.rkaushikdev.cartwall.domain.Race;
import io.github.rkaushikdev.cartwall.domain.RateCard;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a candidate is told before buying: every class of time the station sells, what it lists at,
 * and the lowest anybody has actually been charged for it in the window.
 *
 * <p>The disclosure goes to the campaign with the first quote, because a candidate cannot claim a
 * rate they were never told existed. Each row is one daypart, one class and one length, which is
 * the unit the lowest unit charge is measured in. A row nobody has bought in the window shows the
 * card and says so, and the rate offered is never above the card: the charge rule is a floor on
 * what a candidate pays, not a reason to charge more.
 *
 * @param race the race the window belongs to
 * @param station the station quoting
 * @param asOf the day the disclosure was drawn; the window is read up to it
 * @param rows one per daypart, class and length, in card order
 */
public record RateDisclosure(Race race, String station, LocalDate asOf, List<Row> rows) {

    /**
     * One class of time, one length.
     *
     * @param daypart the daypart code
     * @param grade the class of time
     * @param length the length
     * @param card what the card lists it at
     * @param lowest the lowest anybody was charged in the window so far, when anybody was
     */
    public record Row(String daypart, ClassOfTime grade, SpotLength length, Money card, Optional<Money> lowest) {

        /** What the candidate pays: the lowest charge, never more than the card. */
        public Money offered() {
            return lowest.filter(charged -> charged.compareTo(card) < 0).orElse(card);
        }

        @Override
        public String toString() {
            String measured = lowest.map(Money::toString).orElse("none sold");
            return "%-5s %-24s %4s  card %10s  lowest %10s  offered %10s"
                    .formatted(daypart, grade.value(), length, card, measured, offered());
        }
    }

    public RateDisclosure {
        rows = List.copyOf(rows);
    }

    /**
     * The disclosure for {@code race} on the card's station, as of {@code asOf}.
     *
     * <p>Before the window opens there is no lowest unit charge to disclose yet, only the card;
     * the window is read from the day it opens up to {@code asOf} or election day, whichever is
     * first.
     */
    public static RateDisclosure of(
            Race race, RateCard card, ChargeBook charges, List<SpotLength> lengths, LocalDate asOf) {
        if (lengths.isEmpty()) {
            throw new PoliticalError("a disclosure has to quote at least one length");
        }
        LocalDate through = asOf.isAfter(race.electionDay()) ? race.electionDay() : asOf;
        boolean open = !through.isBefore(race.windowOpens());
        List<Row> rows = new ArrayList<>();
        for (Quote quote : card.quotes()) {
            for (SpotLength length : lengths) {
                Optional<Money> lowest = open
                        ? charges.lowest(
                                card.station(), quote.daypart(), quote.grade(), length, race.windowOpens(), through)
                        : Optional.empty();
                rows.add(new Row(quote.daypart(), quote.grade(), length, quote.atLength(length), lowest));
            }
        }
        return new RateDisclosure(race, card.station(), asOf, rows);
    }

    /** The rows where somebody has already paid less than the card. */
    public List<Row> underCard() {
        return rows.stream()
                .filter(row -> row.offered().compareTo(row.card()) < 0)
                .toList();
    }

    /** The document, as it is handed to the campaign. */
    public String render() {
        return new Form("POLITICAL RATE DISCLOSURE")
                .field("STATION", station)
                .field("RACE", race)
                .field(
                        "WINDOW",
                        Ran.SWORN_DATE.format(race.windowOpens()) + " to " + Ran.SWORN_DATE.format(race.electionDay()))
                .field("AS OF", Ran.SWORN_DATE.format(asOf))
                .blank()
                .rows(rows, "nothing on the card")
                .blank()
                .text("Lowest is what any advertiser was charged for the same class and length in the window.")
                .render();
    }
}
