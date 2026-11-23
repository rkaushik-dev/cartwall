package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.domain.RateCard.Row.row;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RatecardTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final ClassOfTime NOTICE = ClassOfTime.NOTICE;
    private static final ClassOfTime IMMEDIATE = ClassOfTime.IMMEDIATE;
    private static final LocalDate JANUARY = LocalDate.of(2025, 1, 1);
    private static final LocalDate JULY = LocalDate.of(2025, 7, 1);

    static RateCard aCard(String code, String station, LocalDate effective) {
        return RateCard.build(
                code,
                station,
                effective,
                List.of(
                        row("AMD", FIXED, 60),
                        row("AMD", NOTICE, 48),
                        row("AMD", IMMEDIATE, 36),
                        row("MID", FIXED, 32),
                        row("MID", NOTICE, 26),
                        row("MID", IMMEDIATE, 20),
                        row("ROS", IMMEDIATE, 12)));
    }

    static RateCard aCard() {
        return aCard("C25A", "KWLO", JANUARY);
    }

    @Test
    void aClassSaysWhetherTheSpotCanBeMovedAndHowMuchWarningIsOwed() {
        assertThat(FIXED.mayBePreempted()).isFalse();
        assertThat(NOTICE.mayBePreempted()).isTrue();
        assertThat(IMMEDIATE.mayBePreempted()).isTrue();
        assertThat(FIXED.noticeDays()).isZero();
        assertThat(NOTICE.noticeDays()).isEqualTo(14);
        assertThat(IMMEDIATE.noticeDays()).isZero();
    }

    @Test
    void aHigherClassOutbidsALowerOne() {
        assertThat(FIXED.rank()).isGreaterThan(NOTICE.rank());
        assertThat(NOTICE.rank()).isGreaterThan(IMMEDIATE.rank());
        assertThat(Stream.of(NOTICE, FIXED, IMMEDIATE)
                        .min(Comparator.comparingInt(ClassOfTime::rank))
                        .orElseThrow())
                .isSameAs(IMMEDIATE);
    }

    @Test
    void theCardQuotesAThirtyAndDerivesTheRest() {
        Quote quote = new Quote("AMD", FIXED, Money.dollars(60));
        assertThat(quote.atLength(THIRTY)).isEqualTo(Money.dollars(60));
        assertThat(quote.atLength(SIXTY)).isEqualTo(Money.dollars(new BigDecimal("105.00")));
        assertThat(quote.atLength(new SpotLength(15))).isEqualTo(Money.dollars(39));
        assertThat(quote.atLength(new SpotLength(10))).isEqualTo(Money.dollars(30));
    }

    @Test
    void aSixtyIsNotTwoThirties() {
        Quote quote = new Quote("AMD", FIXED, Money.dollars(60));
        assertThat(quote.atLength(SIXTY)).isLessThan(quote.atLength(THIRTY).times(2));
        assertThat(quote.atLength(new SpotLength(15)))
                .isGreaterThan(new Money(quote.atLength(THIRTY).cents() / 2));
    }

    @Test
    void theLengthFactorRoundsToTheCent() {
        Quote quote = new Quote("MID", NOTICE, Money.parse("26.00"));
        assertThat(quote.atLength(new SpotLength(15))).isEqualTo(Money.parse("16.90"));
        assertThat(quote.atLength(new SpotLength(45))).isEqualTo(Money.parse("36.40"));
        assertThat(quote.atLength(new SpotLength(90))).isEqualTo(Money.parse("65.00"));
    }

    @Test
    void aQuoteThatCannotBeMade() {
        assertThatThrownBy(() -> new Quote("", FIXED, Money.dollars(60)))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("needs a daypart");
        assertThatThrownBy(() -> new Quote("AMD", FIXED, Money.dollars(-1)))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("quoted at -");
    }

    @Test
    void theCardLooksUpByDaypartAndClassTogether() {
        RateCard card = aCard();
        assertThat(card.rate("AMD", FIXED, THIRTY)).isEqualTo(Money.dollars(60));
        assertThat(card.rate("AMD", IMMEDIATE, THIRTY)).isEqualTo(Money.dollars(36));
        assertThat(card.rate("MID", FIXED, THIRTY)).isEqualTo(Money.dollars(32));
        assertThat(card.rate("AMD", FIXED, SIXTY)).isEqualTo(Money.parse("105.00"));
    }

    @Test
    void findingAQuoteTheCardDoesNotHaveIsNothingRatherThanARefusal() {
        RateCard card = aCard();
        assertThat(card.find("AMD", NOTICE)).map(Quote::thirty).contains(Money.dollars(48));
        assertThat(card.find("ROS", FIXED)).isEmpty();
        assertThat(card.find("EVE", IMMEDIATE)).isEmpty();
    }

    @Test
    void theFloorIsTheMostPreemptibleClassQuotedWhateverItCosts() {
        RateCard card = aCard();
        assertThat(card.floor("AMD")).map(Quote::grade).contains(IMMEDIATE);
        assertThat(card.floor("ROS")).map(Quote::thirty).contains(Money.dollars(12));
        assertThat(card.floor("EVE")).isEmpty();
        RateCard typo =
                RateCard.build("C25T", "KWLO", JANUARY, List.of(row("AMD", NOTICE, 30), row("AMD", IMMEDIATE, 40)));
        assertThat(typo.floor("AMD")).map(Quote::thirty).contains(Money.dollars(40));
    }

    @Test
    void aCombinationTheCardDoesNotQuoteIsAnErrorNotAGuess() {
        RateCard card = aCard();
        assertThatThrownBy(() -> card.quote("ROS", FIXED))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("does not quote ROS at fixed");
        assertThatThrownBy(() -> card.quote("EVE", IMMEDIATE))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("does not quote EVE");
        assertThat(card.covers("ROS")).isTrue();
        assertThat(card.covers("EVE")).isFalse();
    }

    @Test
    void aCardMayNotQuoteTheSameCellTwice() {
        assertThatThrownBy(() ->
                        RateCard.build("DUP", "KWLO", JANUARY, List.of(row("AMD", FIXED, 60), row("AMD", FIXED, 55))))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("quotes AMD fixed twice");
        assertThatThrownBy(() -> RateCard.build("", "KWLO", JANUARY, List.of()))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("needs a code");
    }

    @Test
    void theCardListsItsDaypartsOnceEachInFilingOrder() {
        assertThat(aCard().dayparts()).containsExactly("AMD", "MID", "ROS");
        assertThat(aCard().size()).isEqualTo(7);
    }

    @Test
    void theBookHoldsEveryCardAStationEverHad() {
        RateCardBook book = new RateCardBook();
        book.file(aCard("C25A", "KWLO", JANUARY));
        book.file(aCard("C25B", "KWLO", JULY));
        book.file(aCard("K25A", "KCRR", JANUARY));
        assertThat(book.history("KWLO")).extracting(RateCard::code).containsExactly("C25A", "C25B");
        assertThat(book.history("KCRR")).extracting(RateCard::code).containsExactly("K25A");
    }

    @Test
    void theBookRefusesTwoCardsForOneStationOnOneDay() {
        RateCardBook book = new RateCardBook();
        book.file(aCard("C25A", "KWLO", JANUARY));
        assertThatThrownBy(() -> book.file(aCard("C25X", "KWLO", JANUARY)))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("already has card C25A");
    }

    @Test
    void anOrderWrittenInMarchStillPricesOffTheJanuaryCard() {
        RateCardBook book = new RateCardBook();
        book.file(aCard("C25A", "KWLO", JANUARY));
        book.file(aCard("C25B", "KWLO", JULY));
        assertThat(book.inForce("KWLO", LocalDate.of(2025, 3, 15)).code()).isEqualTo("C25A");
        assertThat(book.inForce("KWLO", LocalDate.of(2025, 6, 30)).code()).isEqualTo("C25A");
        assertThat(book.inForce("KWLO", JULY).code()).isEqualTo("C25B");
        assertThat(book.inForce("KWLO", LocalDate.of(2026, 1, 1)).code()).isEqualTo("C25B");
    }

    @Test
    void askingForARateBeforeAnyCardExisted() {
        RateCardBook book = new RateCardBook();
        book.file(aCard("C25A", "KWLO", JULY));
        assertThatThrownBy(() -> book.inForce("KWLO", JANUARY))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("no card in force");
        assertThatThrownBy(() -> book.inForce("KCRR", JULY))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("no card in force");
    }

    @Test
    void theBookPricesASpotFromStationDateDaypartClassAndLength() {
        RateCardBook book = new RateCardBook();
        book.file(aCard("C25A", "KWLO", JANUARY));
        assertThat(book.rate("KWLO", LocalDate.of(2025, 3, 3), "AMD", NOTICE, SIXTY))
                .isEqualTo(Money.dollars(84));
        assertThat(book.rate("KWLO", LocalDate.of(2025, 3, 3), "ROS", IMMEDIATE, THIRTY))
                .isEqualTo(Money.dollars(12));
    }

    @Test
    void aLengthWithNoFactorFiledIsRefusedRatherThanGuessed() {
        // A SpotLength cannot be a :25, so the refusal is asked of the factor lookup the quote
        // goes through.
        assertThatThrownBy(() -> Quote.lengthFactor(25))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("no length factor")
                .hasMessageContaining(":25");
    }
}
