package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One station's card, in force from a date until the next one replaces it.
 *
 * <p>Cards are effective dated and never edited. A rate rise is a new card, because an order
 * written last October is still entitled to last October's card and the invoice run has to be
 * able to prove which one it used.
 *
 * @param code the card's code
 * @param station the station it prices
 * @param effective the day it took effect
 * @param quotes one quoted thirty per daypart and class
 */
public record RateCard(String code, String station, LocalDate effective, List<Quote> quotes) {

    /**
     * One line of a card as it is typed in.
     *
     * @param daypart the daypart code
     * @param grade the class of time
     * @param dollars the price of a thirty, in dollars
     */
    public record Row(String daypart, ClassOfTime grade, BigDecimal dollars) {

        public static Row row(String daypart, ClassOfTime grade, long dollars) {
            return new Row(daypart, grade, BigDecimal.valueOf(dollars));
        }

        public static Row row(String daypart, ClassOfTime grade, String dollars) {
            return new Row(daypart, grade, new BigDecimal(dollars));
        }
    }

    public RateCard {
        quotes = List.copyOf(quotes);
        if (code == null || code.isEmpty()) {
            throw new RateError("a card needs a code");
        }
        Set<String> seen = new HashSet<>();
        for (Quote quote : quotes) {
            if (!seen.add(quote.daypart() + "|" + quote.grade())) {
                throw new RateError("card " + code + " quotes " + quote.daypart() + " "
                        + quote.grade().value() + " twice");
            }
        }
    }

    /** A card from rows of daypart, class and dollars, which is how a card is typed in. */
    public static RateCard build(String code, String station, LocalDate effective, List<Row> rows) {
        List<Quote> quotes = rows.stream()
                .map(row -> new Quote(row.daypart(), row.grade(), Money.dollars(row.dollars())))
                .toList();
        return new RateCard(code, station, effective, quotes);
    }

    /** The quote for this daypart and class, when the card has one. */
    public Optional<Quote> find(String daypart, ClassOfTime grade) {
        return quotes.stream()
                .filter(candidate -> candidate.daypart().equals(daypart) && candidate.grade() == grade)
                .findFirst();
    }

    /** The quote for this daypart and class, or a refusal rather than a guess. */
    public Quote quote(String daypart, ClassOfTime grade) {
        return find(daypart, grade)
                .orElseThrow(
                        () -> new RateError("card " + code + " does not quote " + daypart + " at " + grade.value()));
    }

    /**
     * The most preemptible class the card quotes a daypart at: the floor a seller can go down to.
     *
     * <p>It is the class, not the money, that decides. A card that prices notice under immediate in
     * some daypart has a typing error in it, and the floor should show the error rather than hide
     * it behind whichever number happens to be smaller.
     */
    public Optional<Quote> floor(String daypart) {
        return quotes.stream()
                .filter(candidate -> candidate.daypart().equals(daypart))
                .min(Comparator.comparingInt(candidate -> candidate.grade().rank()));
    }

    public Money rate(String daypart, ClassOfTime grade, SpotLength length) {
        return quote(daypart, grade).atLength(length);
    }

    /** The dayparts quoted, in the order they were filed, without repeats. */
    public List<String> dayparts() {
        Set<String> found = new LinkedHashSet<>();
        quotes.forEach(quote -> found.add(quote.daypart()));
        return List.copyOf(found);
    }

    public boolean covers(String daypart) {
        return quotes.stream().anyMatch(quote -> quote.daypart().equals(daypart));
    }

    public int size() {
        return quotes.size();
    }
}
