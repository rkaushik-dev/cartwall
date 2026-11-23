package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;

/**
 * One makegood offered against one debt.
 *
 * <p>A makegood has to be worth what was lost. A drive time spot is not made good with an
 * overnight one, and an offer cheaper than what was missed is one a client is entitled to refuse,
 * so it is marked short rather than quietly booked. One that runs in the next broadcast month is
 * allowed, and says so, because the miss has been billed by then.
 *
 * @param debt the spot owed
 * @param day the day offered
 * @param daypart the daypart offered
 * @param rate what the offered spot is worth
 * @param state where the offer stands
 * @param isShort set when the offer is worth less than what it replaces
 */
public record Makegood(Debt debt, LocalDate day, String daypart, Money rate, Offer state, boolean isShort) {

    public Makegood {
        if (day.isBefore(debt.day())) {
            throw new MakegoodError(
                    "a makegood for " + debt.day() + " cannot run on " + day,
                    Details.of("owed", debt.day().toString(), "offered", day.toString()));
        }
    }

    /** Propose one spot back. Marked short when it is worth less. */
    public static Makegood offer(Debt debt, LocalDate day, String daypart, Money rate) {
        if (daypart == null || daypart.isEmpty()) {
            throw new MakegoodError("a makegood needs a daypart");
        }
        return new Makegood(debt, day, daypart, rate, Offer.OPEN, rate.compareTo(debt.rate()) < 0);
    }

    /** What the offer falls short of the spot it replaces by, never negative. */
    public Money shortfall() {
        Money gap = debt.rate().minus(rate);
        return gap.cents() > 0 ? gap : Money.ZERO;
    }

    /**
     * Whether the makegood runs in a later broadcast month than the spot it replaces.
     *
     * <p>The month the spot was owed in has already been invoiced by then, so the agency sees a
     * miss on one invoice and the makegood on the next, and plenty of buyers will not accept that.
     */
    public boolean crossesMonth() {
        return !BroadcastMonth.of(day).equals(BroadcastMonth.of(debt.day()));
    }

    public Makegood withState(Offer state) {
        return new Makegood(debt, day, daypart, rate, state, isShort);
    }

    @Override
    public String toString() {
        String note = (isShort ? " short" : "") + (crossesMonth() ? " next month" : "");
        return debt.order() + "-" + debt.line() + " offered " + daypart + " on " + day + " at " + rate + note + " ("
                + state.value() + ")";
    }
}
