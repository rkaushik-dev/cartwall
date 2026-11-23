package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Credit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The business office's monthly look at who may keep placing, read off the statements.
 *
 * <p>The aging decides it: anything past a hundred and twenty days and the account is held, so the
 * log builder stops placing it; anything past ninety and new business is cash in advance. The
 * standing follows the payer. When an agency is the one not paying, every advertiser it buys for
 * moves with it, because the station's money is with the agency whichever client the spots were
 * for. A review only recommends; it never loosens a standing, which is a decision for a person.
 */
public final class CreditReview {

    private CreditReview() {}

    /**
     * One advertiser whose standing the statements say should be stricter than it is.
     *
     * @param advertiser the account code
     * @param payer whose statement it is judged by
     * @param standing what it is on file as
     * @param recommended what the aging says it should be
     * @param oldest the oldest bucket the payer has money in
     * @param pastDue what the payer has at thirty days or more
     */
    public record Change(
            String advertiser, String payer, Credit standing, Credit recommended, Bucket oldest, Money pastDue) {

        @Override
        public String toString() {
            return advertiser + " " + standing.value() + " to " + recommended.value() + ", " + payer + " has " + pastDue
                    + " past due, the oldest at " + oldest.label();
        }
    }

    /** What the aging on one statement says the payer's standing should be. */
    public static Credit recommend(Statement statement) {
        Map<Bucket, Money> aging = statement.aging();
        if (!aging.get(Bucket.OVER_120).isZero()) {
            return Credit.HOLD;
        }
        if (!aging.get(Bucket.NINETY).isZero()) {
            return Credit.PREPAY;
        }
        return Credit.OPEN;
    }

    /** Every advertiser to move, the most past due first, then by code. Payers with no statement are left alone. */
    public static List<Change> review(List<Statement> statements, AccountBook accounts) {
        Map<String, Statement> byPayer = statements.stream()
                .collect(Collectors.toMap(Statement::payer, Function.identity(), (one, two) -> {
                    throw new StatementError(one.payer() + " has two statements in one review");
                }));
        List<Change> changes = new ArrayList<>();
        for (Advertiser advertiser : accounts.advertisers().values()) {
            String payer = accounts.liableParty(advertiser.code());
            Statement statement = byPayer.get(payer);
            if (statement == null) {
                continue;
            }
            Credit recommended = recommend(statement);
            if (recommended.stricterThan(advertiser.credit())) {
                changes.add(new Change(
                        advertiser.code(),
                        payer,
                        advertiser.credit(),
                        recommended,
                        statement.oldestBucket().orElseThrow(),
                        statement.pastDue()));
            }
        }
        changes.sort(Comparator.comparing(Change::pastDue).reversed().thenComparing(Change::advertiser));
        return List.copyOf(changes);
    }

    /** Put every recommended standing on file. */
    public static void apply(List<Change> changes, AccountBook accounts) {
        for (Change change : changes) {
            Advertiser advertiser = accounts.advertiser(change.advertiser());
            accounts.replaceAdvertiser(advertiser.withCredit(advertiser.credit().atLeast(change.recommended())));
        }
    }
}
