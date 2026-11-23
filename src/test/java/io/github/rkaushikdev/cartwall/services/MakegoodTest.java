package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.services.Makegood.offer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MakegoodTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);
    private static final LocalDate NEXT_WEEK = LocalDate.of(2025, 8, 11);

    private static Debt aDebt(int line, LocalDate day, String rate, Cause cause) {
        return new Debt("25-1044", line, "HYVEE", "KWLO", "AMD", FIXED, THIRTY, Money.parse(rate), day, cause);
    }

    private static Debt aDebt(int line) {
        return aDebt(line, MONDAY, "60.00", Cause.DID_NOT_RUN);
    }

    private static Debt aDebt(int line, Cause cause) {
        return aDebt(line, MONDAY, "60.00", cause);
    }

    private static Debt aDebt() {
        return aDebt(1);
    }

    /** An offer in whatever state, since the ledger no longer moves them. */
    private static Makegood anOffer(LocalDate day, String daypart, String rate, Offer state) {
        Makegood made = offer(aDebt(), day, daypart, Money.parse(rate));
        return new Makegood(made.debt(), made.day(), made.daypart(), made.rate(), state, made.isShort());
    }

    private static Makegood anOffer(LocalDate day, Offer state) {
        return anOffer(day, "AMD", "60.00", state);
    }

    private static Makegood anOffer(Offer state) {
        return anOffer(FRIDAY, state);
    }

    private static Makegood anOffer() {
        return anOffer(Offer.OPEN);
    }

    @Test
    void aDebtSaysWhatWasLostAndWhy() {
        Debt debt = aDebt();
        assertThat(debt.key()).isEqualTo(new LineKey("25-1044", 1));
        assertThat(debt).hasToString("25-1044-1 KWLO 2025-08-04 did not run, worth $60.00");
        assertThat(aDebt(1, Cause.BUMPED).cause()).isSameAs(Cause.BUMPED);
    }

    @Test
    void anOfferWorthTheSameIsNotShort() {
        Makegood made = offer(aDebt(), FRIDAY, "AMD", Money.dollars(60));
        assertThat(made.isShort()).isFalse();
        assertThat(made.shortfall()).isEqualTo(new Money(0));
        assertThat(made.state()).isSameAs(Offer.OPEN);
    }

    @Test
    void anOfferWorthLessIsMarkedRatherThanQuietlyBooked() {
        Makegood made = offer(aDebt(), FRIDAY, "OVN", Money.dollars(6));
        assertThat(made.isShort()).isTrue();
        assertThat(made.shortfall()).isEqualTo(Money.dollars(54));
        assertThat(made.toString()).contains("short");
    }

    @Test
    void anOfferWorthMoreIsNotShortAndOwesNothingBack() {
        Makegood made = offer(aDebt(), FRIDAY, "AMD", Money.dollars(75));
        assertThat(made.isShort()).isFalse();
        assertThat(made.shortfall()).isEqualTo(new Money(0));
    }

    @Test
    void aMakegoodCannotRunBeforeTheSpotItReplaces() {
        MakegoodError caught = catchThrowableOfType(
                MakegoodError.class, () -> offer(aDebt(), LocalDate.of(2025, 8, 1), "AMD", Money.dollars(60)));
        assertThat(caught).hasMessageContaining("cannot run on 2025-08-01");
        assertThat(caught.context()).isEqualTo(Map.of("owed", "2025-08-04", "offered", "2025-08-01"));
    }

    @Test
    void aMakegoodOnTheSameDayIsAllowed() {
        assertThat(offer(aDebt(), MONDAY, "AMD", Money.dollars(60)).day()).isEqualTo(MONDAY);
    }

    @Test
    void anOfferNeedsADaypart() {
        assertThatThrownBy(() -> offer(aDebt(), FRIDAY, "", Money.dollars(60)))
                .isInstanceOf(MakegoodError.class)
                .hasMessageContaining("needs a daypart");
    }

    @Test
    void anOfferReadsTheWayTheSellerReadsIt() {
        Makegood made = offer(aDebt(), FRIDAY, "MID", Money.dollars(32));
        assertThat(made).hasToString("25-1044-1 offered MID on 2025-08-08 at $32.00 short (open)");
    }

    @Test
    void nothingIsOwedUntilADebtIsRecorded() {
        Ledger ledger = new Ledger();
        assertThat(ledger.size()).isZero();
        assertThat(ledger.stillOwed()).isEmpty();
        assertThat(ledger.owedValue()).isEqualTo(new Money(0));
        assertThat(ledger.openOffers()).isEmpty();
    }

    @Test
    void aDebtWithNoOfferIsStillOwed() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt());
        assertThat(ledger.stillOwed()).containsExactly(aDebt());
        assertThat(ledger.owedValue()).isEqualTo(Money.dollars(60));
    }

    @Test
    void anOpenOfferDoesNotCloseTheDebt() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(), List.of(anOffer()));
        assertThat(ledger.stillOwed()).containsExactly(aDebt());
        assertThat(ledger.openOffers()).hasSize(1);
    }

    @Test
    void anAcceptedOfferClosesTheDebt() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(), List.of(anOffer(Offer.ACCEPTED)));
        assertThat(ledger.stillOwed()).isEmpty();
        assertThat(ledger.owedValue()).isEqualTo(new Money(0));
        assertThat(ledger.openOffers()).isEmpty();
    }

    @Test
    void aRefusedOfferLeavesTheDebtOwed() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(), List.of(anOffer(Offer.REFUSED)));
        assertThat(ledger.stillOwed()).containsExactly(aDebt());
    }

    @Test
    void anExpiredOfferLeavesTheDebtOwed() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(), List.of(anOffer(Offer.EXPIRED)));
        assertThat(ledger.stillOwed()).containsExactly(aDebt());
        assertThat(ledger.openOffers()).isEmpty();
    }

    @Test
    void aRefusalThenAnAcceptanceClosesIt() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(), List.of(anOffer(FRIDAY, "OVN", "6.00", Offer.REFUSED), anOffer(NEXT_WEEK, Offer.ACCEPTED)));
        assertThat(ledger.stillOwed()).isEmpty();
    }

    /** One accepted makegood closes one of them, not all four. */
    @Test
    void fourIdenticalDebtsAreFourEntries() {
        Ledger ledger = new Ledger();
        for (int n = 0; n < 4; n++) {
            ledger.owe(aDebt(1, Cause.NEVER_SCHEDULED));
        }
        assertThat(ledger.size()).isEqualTo(4);
        assertThat(ledger.owedValue()).isEqualTo(Money.dollars(240));

        ledger.entries().get(0).addOffer(anOffer(Offer.ACCEPTED));
        assertThat(ledger.stillOwed()).hasSize(3);
        assertThat(ledger.owedValue()).isEqualTo(Money.dollars(180));
    }

    @Test
    void anEntryKnowsWhichOfItsOffersAnsweredIt() {
        LedgerEntry entry =
                new LedgerEntry(aDebt(), List.of(anOffer(Offer.REFUSED), anOffer(NEXT_WEEK, Offer.ACCEPTED)));
        assertThat(entry.accepted()).isPresent();
        assertThat(entry.accepted().orElseThrow().day()).isEqualTo(NEXT_WEEK);
        assertThat(entry.open()).isEmpty();
        assertThat(entry.isOwed()).isFalse();
    }

    @Test
    void anEntryWithNothingOfferedIsOwedAndHasNoOpenOffer() {
        LedgerEntry entry = new LedgerEntry(aDebt(), List.of());
        assertThat(entry.isOwed()).isTrue();
        assertThat(entry.open()).isEmpty();
        assertThat(entry.accepted()).isEmpty();
    }

    @Test
    void anEntryReadsAsTheDebtAndItsAnswer() {
        assertThat(new LedgerEntry(aDebt(), List.of()).toString()).endsWith("(nothing accepted)");
    }

    @Test
    void theDebtsAndOffersReadAcrossEveryEntry() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(1), List.of(anOffer(Offer.REFUSED), anOffer()));
        ledger.owe(aDebt(2));
        assertThat(ledger.debts()).extracting(Debt::line).containsExactly(1, 2);
        assertThat(ledger.offers()).hasSize(2);
    }

    @Test
    void theOpenOffersComeBackSoonestFirst() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(1), List.of(anOffer(NEXT_WEEK, Offer.OPEN)));
        ledger.owe(aDebt(2), List.of(anOffer(FRIDAY, Offer.OPEN)));
        assertThat(ledger.openOffers()).extracting(Makegood::day).containsExactly(FRIDAY, NEXT_WEEK);
    }

    @Test
    void whatIsOwedComesBackOldestFirst() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(2, FRIDAY, "60.00", Cause.DID_NOT_RUN));
        ledger.owe(aDebt(1, MONDAY, "60.00", Cause.DID_NOT_RUN));
        assertThat(ledger.stillOwed()).extracting(Debt::day).containsExactly(MONDAY, FRIDAY);
    }

    @Test
    void theWorstLineIsCountedFirst() {
        Ledger ledger = new Ledger();
        for (int n = 0; n < 3; n++) {
            ledger.owe(aDebt(1));
        }
        ledger.owe(aDebt(2));
        assertThat(ledger.owedByLine().keySet()).containsExactly(new LineKey("25-1044", 1), new LineKey("25-1044", 2));
        assertThat(ledger.owedByLine()).containsEntry(new LineKey("25-1044", 1), 3);
    }

    @Test
    void aClosedDebtLeavesItsLineOutOfTheTally() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(1), List.of(anOffer(Offer.ACCEPTED)));
        ledger.owe(aDebt(2));
        assertThat(ledger.owedByLine()).isEqualTo(Map.of(new LineKey("25-1044", 2), 1));
    }

    @Test
    void theCausesAreCountedWorstFirst() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(1, Cause.NEVER_SCHEDULED));
        ledger.owe(aDebt(2, Cause.NEVER_SCHEDULED));
        ledger.owe(aDebt(3, Cause.BUMPED));
        assertThat(ledger.byCause()).isEqualTo(Map.of("never scheduled", 2, "bumped", 1));
    }

    @Test
    void aLedgerIsWorthTheRateEachSpotWasSoldAt() {
        Ledger ledger = new Ledger();
        ledger.owe(aDebt(1, MONDAY, "60.00", Cause.DID_NOT_RUN));
        ledger.owe(aDebt(2, MONDAY, "12.50", Cause.DID_NOT_RUN));
        assertThat(ledger.owedValue()).isEqualTo(Money.parse("72.50"));
    }

    @Test
    void anOfferEarlierThanTheDebtIsRefused() {
        assertThatThrownBy(() -> offer(aDebt(1, FRIDAY, "60.00", Cause.DID_NOT_RUN), MONDAY, "AMD", Money.dollars(60)))
                .isInstanceOf(MakegoodError.class)
                .hasMessageContaining("cannot run on");
    }

    private static final LocalDate MONTH_END = LocalDate.of(2025, 8, 24);
    private static final LocalDate SEPTEMBER = LocalDate.of(2025, 8, 25);

    private static Makegood accepted(Debt debt, LocalDate day) {
        return offer(debt, day, "AMD", Money.dollars(60)).withState(Offer.ACCEPTED);
    }

    @Test
    void aMakegoodAfterTheBroadcastMonthClosesSaysSo() {
        assertThat(offer(aDebt(), MONTH_END, "AMD", Money.dollars(60)).crossesMonth())
                .isFalse();
        Makegood late = offer(aDebt(), SEPTEMBER, "AMD", Money.dollars(60));
        assertThat(late.crossesMonth()).isTrue();
        assertThat(late).hasToString("25-1044-1 offered AMD on 2025-08-25 at $60.00 next month (open)");
        assertThat(offer(aDebt(), SEPTEMBER, "MID", Money.dollars(32)))
                .hasToString("25-1044-1 offered MID on 2025-08-25 at $32.00 short next month (open)");
    }

    @Test
    void theMonthCreditsWhatItDidNotMakeGoodInsideItself() {
        Ledger ledger = new Ledger();
        Debt madeGood = aDebt(1);
        Debt madeGoodLate = aDebt(2);
        Debt stillOpen = aDebt(3, NEXT_WEEK, "45.00", Cause.BUMPED);
        Debt nextMonth = aDebt(4, SEPTEMBER, "60.00", Cause.DID_NOT_RUN);
        ledger.owe(madeGood, List.of(accepted(madeGood, FRIDAY)));
        ledger.owe(madeGoodLate, List.of(accepted(madeGoodLate, SEPTEMBER)));
        ledger.owe(stillOpen, List.of(offer(stillOpen, MONTH_END, "AMD", Money.dollars(45))));
        ledger.owe(nextMonth);
        BroadcastMonth august = new BroadcastMonth(2025, 8);
        assertThat(ledger.toCreditAt(august)).containsExactly(madeGoodLate, stillOpen);
        assertThat(ledger.creditAt(august)).isEqualTo(Money.dollars(105));
        assertThat(ledger.toCreditAt(new BroadcastMonth(2025, 9))).containsExactly(nextMonth);
        assertThat(ledger.stillOwed()).containsExactly(stillOpen, nextMonth);
    }

    @Test
    void anOfferStateIsReadByItsStoredName() {
        assertThat(Offer.fromValue("accepted")).isSameAs(Offer.ACCEPTED);
        assertThatThrownBy(() -> Offer.fromValue("maybe"))
                .isInstanceOf(MakegoodError.class)
                .hasMessageContaining("'maybe' is not an offer state");
    }
}
