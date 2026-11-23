package io.github.rkaushikdev.cartwall.reports;

import static io.github.rkaushikdev.cartwall.domain.RateCard.Row.row;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.Charge;
import io.github.rkaushikdev.cartwall.domain.ChargeBook;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Election;
import io.github.rkaushikdev.cartwall.domain.PoliticalError;
import io.github.rkaushikdev.cartwall.domain.Race;
import io.github.rkaushikdev.cartwall.domain.RateCard;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateDisclosureTest {

    private static final Race RACE = new Race("Iowa House 63", Election.GENERAL, LocalDate.of(2026, 11, 3));
    private static final LocalDate IN_WINDOW = LocalDate.of(2026, 9, 21);

    private static RateCard aCard() {
        return RateCard.build(
                "KWLO-26",
                "KWLO",
                LocalDate.of(2026, 1, 1),
                List.of(
                        row("AMD", ClassOfTime.FIXED, 60),
                        row("AMD", ClassOfTime.IMMEDIATE, 36),
                        row("MID", ClassOfTime.FIXED, 32)));
    }

    private static ChargeBook charges() {
        return new ChargeBook(List.of(
                new Charge("KWLO", "AMD", ClassOfTime.IMMEDIATE, THIRTY, Money.dollars(30), LocalDate.of(2026, 9, 10)),
                new Charge("KWLO", "AMD", ClassOfTime.IMMEDIATE, THIRTY, Money.dollars(24), LocalDate.of(2026, 8, 20)),
                new Charge("KWLO", "AMD", ClassOfTime.FIXED, THIRTY, Money.dollars(66), LocalDate.of(2026, 9, 12)),
                new Charge("KWLO", "MID", ClassOfTime.FIXED, THIRTY, Money.dollars(28), LocalDate.of(2026, 10, 1)),
                new Charge("KCRR", "AMD", ClassOfTime.FIXED, THIRTY, Money.dollars(10), LocalDate.of(2026, 9, 12))));
    }

    @Test
    void everyClassOnTheCardIsDisclosedAtEveryLengthAsked() {
        RateDisclosure disclosure = RateDisclosure.of(RACE, aCard(), charges(), List.of(THIRTY, SIXTY), IN_WINDOW);
        assertThat(disclosure.rows()).hasSize(6);
        assertThat(disclosure.rows())
                .extracting(RateDisclosure.Row::daypart)
                .containsExactly("AMD", "AMD", "AMD", "AMD", "MID", "MID");
        assertThat(disclosure.rows().get(1).length()).isEqualTo(SIXTY);
        assertThat(disclosure.rows().get(1).card()).isEqualTo(Money.dollars(105));
    }

    @Test
    void theLowestIsWhatAnybodyPaidInsideTheWindowSoFar() {
        RateDisclosure disclosure = RateDisclosure.of(RACE, aCard(), charges(), List.of(THIRTY), IN_WINDOW);
        RateDisclosure.Row immediate = disclosure.rows().get(1);
        // The $24 charge is from before the window opened on 9/4 and does not count.
        assertThat(immediate.lowest()).contains(Money.dollars(30));
        assertThat(immediate.offered()).isEqualTo(Money.dollars(30));
        // Midday's $28 was charged after the disclosure was drawn.
        assertThat(disclosure.rows().get(2).lowest()).isEmpty();
        assertThat(disclosure.rows().get(2).offered()).isEqualTo(Money.dollars(32));
    }

    @Test
    void aCandidateIsNeverOfferedMoreThanTheCard() {
        RateDisclosure.Row fixed = RateDisclosure.of(RACE, aCard(), charges(), List.of(THIRTY), IN_WINDOW)
                .rows()
                .get(0);
        assertThat(fixed.lowest()).contains(Money.dollars(66));
        assertThat(fixed.offered()).isEqualTo(Money.dollars(60));
    }

    @Test
    void theRowsUnderCardAreTheOnesSomebodyBoughtCheaper() {
        RateDisclosure late = RateDisclosure.of(RACE, aCard(), charges(), List.of(THIRTY), LocalDate.of(2026, 12, 1));
        assertThat(late.underCard())
                .extracting(RateDisclosure.Row::daypart, RateDisclosure.Row::offered)
                .containsExactly(tuple("AMD", Money.dollars(30)), tuple("MID", Money.dollars(28)));
    }

    @Test
    void beforeTheWindowOpensThereIsOnlyTheCard() {
        RateDisclosure early = RateDisclosure.of(RACE, aCard(), charges(), List.of(THIRTY), LocalDate.of(2026, 8, 1));
        assertThat(early.rows()).allMatch(row -> row.lowest().isEmpty());
        assertThat(early.underCard()).isEmpty();
    }

    @Test
    void aDisclosureHasToQuoteALength() {
        assertThatThrownBy(() -> RateDisclosure.of(RACE, aCard(), charges(), List.of(), IN_WINDOW))
                .isInstanceOf(PoliticalError.class)
                .hasMessageContaining("at least one length");
    }

    @Test
    void theDisclosureIsTypedOnTheHouseForm() {
        String text = RateDisclosure.of(RACE, aCard(), charges(), List.of(THIRTY), IN_WINDOW)
                .render();
        assertThat(text)
                .startsWith("POLITICAL RATE DISCLOSURE\n\n")
                .contains("STATION      KWLO\n")
                .contains("RACE         Iowa House 63 general 11/03/2026\n")
                .contains("WINDOW       09/04/2026 to 11/03/2026\n")
                .contains("AS OF        09/21/2026\n")
                .contains(
                        "  AMD   immediately preemptible   :30  card     $36.00  lowest     $30.00  offered     $30.00\n")
                .contains(
                        "  MID   fixed                     :30  card     $32.00  lowest  none sold  offered     $32.00\n");
    }
}
