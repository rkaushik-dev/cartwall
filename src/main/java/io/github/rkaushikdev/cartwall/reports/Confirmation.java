package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Agency;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.Revision;
import io.github.rkaushikdev.cartwall.domain.Weekday;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The order confirmation: what the station has agreed to run, sent back to whoever placed it.
 *
 * <p>The agency checks it against its own buy before the flight starts, which is the cheapest time
 * there will ever be to find a mistake. So it says everything the invoice will later rely on: the
 * lines as entered, the stub weeks the house rounded down and the weeks the buy goes dark, what
 * each broadcast month will bill, and gross, commission and net at the rate the account carries
 * today.
 *
 * @param order the order being confirmed
 * @param advertiser whose order it is
 * @param agency the agency that placed it, when there is one
 * @param payer whose name the invoices will carry
 * @param commissionRate what comes off the gross
 */
public record Confirmation(
        Order order, Advertiser advertiser, Optional<Agency> agency, String payer, BigDecimal commissionRate) {

    /**
     * One week of one line where the flight does not run every day it was sold for.
     *
     * @param line the order line
     * @param week the broadcast week
     * @param count spots ordered in it, rounded down
     * @param full what a whole week of the line carries
     */
    public record Stub(int line, BroadcastWeek week, int count, int full) {

        @Override
        public String toString() {
            return "line " + line + " " + week + ": " + count + " of " + full + ", rounded down";
        }
    }

    /** The confirmation for {@code order}, with the account as it stands on file. */
    public static Confirmation of(Order order, AccountBook accounts) {
        Advertiser advertiser = accounts.advertiser(order.advertiser());
        Optional<Agency> agency = order.agency().or(advertiser::agency).map(accounts::agency);
        return new Confirmation(
                order,
                advertiser,
                agency,
                accounts.liableParty(advertiser.code()),
                accounts.commissionRate(advertiser.code()));
    }

    public Money gross() {
        return order.value();
    }

    public Money commission() {
        return Money.commission(gross(), commissionRate);
    }

    public Money net() {
        return Money.toNet(gross(), commissionRate);
    }

    /** Every week a line runs short of its full count, line by line, in week order. */
    public List<Stub> stubs() {
        return order.lines().stream()
                .flatMap(line -> line.flight().weeks().stream()
                        .filter(line.flight()::isStub)
                        .map(week -> new Stub(
                                line.number(),
                                week,
                                line.flight().countIn(week),
                                line.flight().perWeek())))
                .toList();
    }

    /** One row per line that goes dark, naming the weeks it bought nothing in. */
    public List<String> darkWeeks() {
        return order.lines().stream()
                .filter(line -> !line.flight().dark().isEmpty())
                .map(line -> "line " + line.number() + " dark " + line.flight().showDark())
                .toList();
    }

    /** The document, as it is faxed or mailed back. */
    public String render() {
        return render(Optional.empty());
    }

    /**
     * The document sent back after a rewrite: the same confirmation, headed as a revision, with
     * what changed from {@code previous} listed before the lines.
     */
    public String revising(Order previous) {
        return render(Optional.of(Revision.between(previous, order)));
    }

    private String render(Optional<Revision> revision) {
        Form form = new Form(revision.isPresent() ? "REVISED ORDER CONFIRMATION" : "ORDER CONFIRMATION")
                .field("ORDER", order.number())
                .field("ADVERTISER", advertiser.name() + " (" + advertiser.code() + ")")
                .field(
                        "AGENCY",
                        agency.map(placed -> placed.name() + " (" + placed.code() + ")")
                                .orElse("direct"))
                .field("BILL TO", payer)
                .fieldIfAny("CAMPAIGN", order.campaign())
                .field("SALESPERSON", order.salesperson())
                .field("WRITTEN", Ran.SWORN_DATE.format(order.written()))
                .field("STATUS", order.status().value());
        if (!order.lines().isEmpty()) {
            form.field(
                    "FLIGHT",
                    Ran.SWORN_DATE.format(order.firstAir()) + " to " + Ran.SWORN_DATE.format(order.lastAir()));
        }
        revision.ifPresent(changed -> form.blank()
                .text("CHANGES")
                .rows(changed.changes(), "nothing changed")
                .field("VALUE", changed.was() + " to " + changed.now()));
        form.blank().rows(order.lines().stream().map(Confirmation::lineRow).toList(), "no lines entered");
        List<Stub> stubs = stubs();
        if (!stubs.isEmpty()) {
            form.blank().text("STUB WEEKS").rows(stubs, "");
        }
        List<String> dark = darkWeeks();
        if (!dark.isEmpty()) {
            form.blank().text("DARK WEEKS").rows(dark, "");
        }
        form.blank().text("BY MONTH");
        for (Map.Entry<BroadcastMonth, Money> month : order.valueByMonth().entrySet()) {
            form.row("%-10s %12s".formatted(month.getKey(), month.getValue()));
        }
        return form.blank()
                .field("GROSS", gross())
                .field("COMMISSION", commission())
                .field("NET", net())
                .render();
    }

    private static String lineRow(Line line) {
        String schedule =
                Weekday.showDays(line.flight().days()) + " " + line.flight().perWeek() + "x";
        return "%3d  %-8s %-5s %-24s %4s  %-9s %4d at %s = %s"
                .formatted(
                        line.number(),
                        line.station(),
                        line.daypart(),
                        line.grade().value(),
                        line.length(),
                        schedule,
                        line.spots(),
                        line.rate(),
                        line.value());
    }
}
