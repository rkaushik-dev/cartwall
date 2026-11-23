package io.github.rkaushikdev.cartwall.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Credit;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreditReviewTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 11, 3);

    private static Statement owing(String payer, int daysOld, String amount) {
        return new Statement(
                payer,
                TODAY,
                List.of(new Item("25-1001", "25-1044", TODAY.minusDays(daysOld), Money.parse(amount), Money.ZERO)));
    }

    @Test
    void theAgingDecidesTheStanding() {
        assertThat(CreditReview.recommend(owing("HYVEE", 10, "300.00"))).isSameAs(Credit.OPEN);
        assertThat(CreditReview.recommend(owing("HYVEE", 65, "300.00"))).isSameAs(Credit.OPEN);
        assertThat(CreditReview.recommend(owing("HYVEE", 90, "300.00"))).isSameAs(Credit.PREPAY);
        assertThat(CreditReview.recommend(owing("HYVEE", 120, "300.00"))).isSameAs(Credit.HOLD);
        assertThat(CreditReview.recommend(new Statement("HYVEE", TODAY, List.of())))
                .isSameAs(Credit.OPEN);
    }

    @Test
    void anAgencyThatStopsPayingTakesEveryClientItBuysForWithIt() {
        AccountBook accounts = Fixtures.accounts();
        List<CreditReview.Change> changes = CreditReview.review(
                List.of(owing("MOP", 130, "4200.00"), owing("HYVEE", 95, "300.00"), owing("CRS", 5, "900.00")),
                accounts);
        assertThat(changes).extracting(CreditReview.Change::advertiser).containsExactly("DEALC", "DEALF", "HYVEE");
        CreditReview.Change chevy = changes.get(0);
        assertThat(chevy.payer()).isEqualTo("MOP");
        assertThat(chevy.standing()).isSameAs(Credit.OPEN);
        assertThat(chevy.recommended()).isSameAs(Credit.HOLD);
        assertThat(chevy.oldest()).isSameAs(Bucket.OVER_120);
        assertThat(chevy.pastDue()).isEqualTo(Money.dollars(4200));
        assertThat(chevy).hasToString("DEALC open to hold, MOP has $4,200.00 past due, the oldest at 120 plus");
        assertThat(changes.get(2).recommended()).isSameAs(Credit.PREPAY);
    }

    @Test
    void aReviewNeverLoosensAStanding() {
        AccountBook accounts = Fixtures.accounts();
        accounts.replaceAdvertiser(accounts.advertiser("HYVEE").withCredit(Credit.HOLD));
        accounts.replaceAdvertiser(accounts.advertiser("VGM").withCredit(Credit.PREPAY));
        List<CreditReview.Change> changes =
                CreditReview.review(List.of(owing("HYVEE", 95, "300.00"), owing("VGM", 10, "100.00")), accounts);
        assertThat(changes).isEmpty();
        assertThat(Credit.HOLD.atLeast(Credit.PREPAY)).isSameAs(Credit.HOLD);
        assertThat(Credit.OPEN.atLeast(Credit.PREPAY)).isSameAs(Credit.PREPAY);
        assertThat(Credit.PREPAY.stricterThan(Credit.OPEN)).isTrue();
        assertThat(Credit.PREPAY.stricterThan(Credit.HOLD)).isFalse();
    }

    @Test
    void applyingTheReviewPutsTheStandingsOnFile() {
        AccountBook accounts = Fixtures.accounts();
        CreditReview.apply(CreditReview.review(List.of(owing("MOP", 130, "4200.00")), accounts), accounts);
        assertThat(accounts.advertiser("DEALF").credit()).isSameAs(Credit.HOLD);
        assertThat(accounts.maySchedule("DEALC")).isFalse();
        assertThat(accounts.advertiser("HYVEE").credit()).isSameAs(Credit.OPEN);
    }

    @Test
    void aPayerWithTwoStatementsInOneReviewIsRefused() {
        assertThatThrownBy(() -> CreditReview.review(
                        List.of(owing("MOP", 130, "1.00"), owing("MOP", 5, "1.00")), Fixtures.accounts()))
                .isInstanceOf(StatementError.class)
                .hasMessageContaining("MOP has two statements");
    }

    @Test
    void aStatementKnowsItsOldestBucket() {
        assertThat(owing("MOP", 95, "1.00").oldestBucket()).contains(Bucket.NINETY);
        assertThat(new Statement("MOP", TODAY, List.of()).oldestBucket()).isEmpty();
    }
}
