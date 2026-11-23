package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.billing.Detail;
import io.github.rkaushikdev.cartwall.billing.Invoice;
import io.github.rkaushikdev.cartwall.billing.LineHeading;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.logs.LogState;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceDetailRow;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceRow;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceSummaryRow;
import io.github.rkaushikdev.cartwall.persistence.models.SpotRow;
import io.github.rkaushikdev.cartwall.reconcile.Match;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The month end run: every order that aired something gets an invoice.
 *
 * <p>Only aired spots are billed: the run reads the posted as-run, not the log, so a day nobody has
 * reconciled contributes nothing. One invoice per order per month, once: a second run skips what
 * was invoiced and reports it, and never rewrites it (a correction is a credit and a re-raise).
 * Because it is once, an order is not invoiced while any day it ran on in the month is still
 * waiting for its as-run: billing it early would leave that day's spots unbillable for good.
 * The number comes off the counter in the same transaction, so a run that fails halfway leaves no
 * hole. An order that aired nothing gets no invoice rather than a zero one.
 */
public final class Invoicing {

    /** The series invoice numbers are drawn from. */
    public static final String SERIES = "INV";

    private static final String ALREADY_INVOICED = "already invoiced";

    private static final String AIRED_IN_MONTH = " from SpotRow s join s.log l where s.aired = true"
            + " and l.state = :aired and l.day >= :first and l.day <= :last";

    private Invoicing() {}

    /** Every spot of one order that went out in one broadcast month, in air order. */
    public static List<SpotRow> airedIn(UnitOfWork uow, String order, BroadcastMonth month) {
        return uow.entityManager()
                .createQuery(
                        "select s" + AIRED_IN_MONTH + " and s.orderNumber = :order order by l.day, s.at", SpotRow.class)
                .setParameter("aired", LogState.AIRED)
                .setParameter("first", month.start())
                .setParameter("last", month.end())
                .setParameter("order", order)
                .getResultList();
    }

    /** Order numbers with at least one aired spot in the month, in order. */
    public static List<String> ordersWithAir(UnitOfWork uow, BroadcastMonth month) {
        return uow
                .entityManager()
                .createQuery("select distinct s.orderNumber" + AIRED_IN_MONTH, String.class)
                .setParameter("aired", LogState.AIRED)
                .setParameter("first", month.start())
                .setParameter("last", month.end())
                .getResultList()
                .stream()
                .sorted()
                .toList();
    }

    /** The invoice already raised for one order and one month, if there is one. */
    public static Optional<InvoiceRow> existing(UnitOfWork uow, String order, BroadcastMonth month) {
        return uow.entityManager()
                .createQuery(
                        "select i from InvoiceRow i where i.orderNumber = :order and i.year = :year and i.month = :month",
                        InvoiceRow.class)
                .setParameter("order", order)
                .setParameter("year", month.year())
                .setParameter("month", month.month())
                .getResultStream()
                .findFirst();
    }

    /** The invoice for one order and one month, unnumbered and unwritten. */
    public static Invoice buildInvoice(UnitOfWork uow, String orderNumber, BroadcastMonth month, LocalDate raisedOn) {
        Order order = uow.orders().get(orderNumber);
        AccountBook accounts = uow.accounts().book();
        Map<Integer, Money> rates = new HashMap<>();
        Map<Integer, LineHeading> places = new HashMap<>();
        for (Line line : order.lines()) {
            rates.put(line.number(), line.rate());
            places.put(line.number(), new LineHeading(line.station(), line.daypart()));
        }

        List<Detail> details = new ArrayList<>();
        for (SpotRow spot : airedIn(uow, orderNumber, month)) {
            Money rate = rates.get(spot.getLine());
            if (rate == null) {
                throw new InvoicingError(
                        orderNumber + " aired a line " + spot.getLine() + " that is not on the order",
                        Details.of("order", orderNumber, "line", spot.getLine()));
            }
            AirTime at = spot.getAiredAt() != null ? spot.getAiredAt() : spot.getAt();
            details.add(new Detail(
                    spot.getLog().getDay(),
                    at.twelveHour(),
                    spot.getLine(),
                    spot.getLength(),
                    rate,
                    spot.getCart(),
                    drifted(at, spot.getAt())));
        }

        Invoice invoice = new Invoice(
                "unnumbered",
                orderNumber,
                order.advertiser(),
                accounts.liableParty(order.advertiser()),
                month,
                accounts.commissionRate(order.advertiser()),
                details,
                List.of(),
                Optional.of(raisedOn));
        invoice = invoice.withSummaries(Invoice.summarise(details, places));
        invoice.checkAddsUp();
        return invoice;
    }

    /** Whether a spot ran far enough from its logged time to say so on paper. */
    public static boolean drifted(AirTime aired, AirTime logged) {
        AirTime earlier = aired.compareTo(logged) <= 0 ? aired : logged;
        AirTime later = aired.compareTo(logged) <= 0 ? logged : aired;
        return later.minus(earlier).compareTo(Match.ON_TIME) > 0;
    }

    /** Write an invoice down, exactly as it stands. */
    public static InvoiceRow store(UnitOfWork uow, Invoice invoice) {
        InvoiceRow row = new InvoiceRow(
                invoice.number(),
                invoice.order(),
                invoice.advertiser(),
                invoice.payer(),
                invoice.month().year(),
                invoice.month().month(),
                invoice.raised().orElseGet(LocalDate::now),
                invoice.gross(),
                invoice.commission(),
                invoice.net());
        row.setSpots(invoice.spots());
        row.setCommissionRate(invoice.commissionRate());
        row.setDetails(invoice.details().stream()
                .map(detail -> new InvoiceDetailRow(
                        detail.day(),
                        AirTime.parse(detail.at()),
                        detail.line(),
                        detail.length(),
                        detail.rate(),
                        detail.cart(),
                        detail.moved()))
                .toList());
        row.setSummaries(invoice.summaries().stream()
                .map(summary -> new InvoiceSummaryRow(
                        summary.line(),
                        summary.station(),
                        summary.daypart(),
                        summary.length(),
                        summary.rate(),
                        summary.spots(),
                        summary.gross()))
                .toList());
        uow.entityManager().persist(row);
        uow.entityManager().flush();
        return row;
    }

    /**
     * Every station day in the month that carries one of the order's spots and has not been
     * posted as aired yet, written {@code KWLO 2025-08-12}, in day order.
     */
    public static List<String> waitingOn(UnitOfWork uow, String order, BroadcastMonth month) {
        return uow
                .entityManager()
                .createQuery(
                        "select distinct l.day, l.station from SpotRow s join s.log l where s.orderNumber = :order"
                                + " and l.state <> :aired and l.day >= :first and l.day <= :last order by l.day, l.station",
                        Object[].class)
                .setParameter("order", order)
                .setParameter("aired", LogState.AIRED)
                .setParameter("first", month.start())
                .setParameter("last", month.end())
                .getResultList()
                .stream()
                .map(row -> row[1] + " " + row[0])
                .toList();
    }

    /** Number and store the invoice for one order. Refuses a second one, or one still waiting on an as-run. */
    public static InvoiceRow raiseOne(UnitOfWork uow, String order, BroadcastMonth month, LocalDate raisedOn) {
        Map<String, Object> where = Details.of("order", order, "month", month.toString());
        holdUp(uow, order, month).ifPresent(why -> {
            throw why.equals(ALREADY_INVOICED)
                    ? new AlreadyInvoiced(order + " already has an invoice for " + month, where)
                    : new InvoicingError(order + " is " + why, where);
        });
        Invoice invoice = buildInvoice(uow, order, month, raisedOn);
        if (invoice.isEmpty()) {
            throw new InvoicingError(order + " aired nothing in " + month, where);
        }
        return numberAndStore(uow, invoice, raisedOn);
    }

    /** Raise every invoice the month has earned. */
    public static RunReport runMonth(UnitOfWork uow, BroadcastMonth month, LocalDate raisedOn) {
        RunReport report = new RunReport(month);
        HouseLog.event("invoice.run.started", field("month", month.toString()), field("raised", raisedOn.toString()));
        for (String order : ordersWithAir(uow, month)) {
            Optional<String> held = holdUp(uow, order, month);
            if (held.isPresent()) {
                report.skipped.add(new Skipped(order, held.get()));
                HouseLog.warn(
                        "invoice.skipped",
                        field("order", order),
                        field("month", month.toString()),
                        field("why", held.get()));
                continue;
            }
            Invoice invoice = buildInvoice(uow, order, month, raisedOn);
            if (invoice.isEmpty()) {
                report.nothingAired.add(order);
                continue;
            }
            InvoiceRow stored = numberAndStore(uow, invoice, raisedOn);
            report.raised.add(stored.getNumber());
            HouseLog.event(
                    "invoice.raised",
                    field("invoice", stored.getNumber()),
                    field("order", order),
                    field("payer", invoice.payer()),
                    field("spots", invoice.spots()),
                    field("gross", invoice.gross().toString()),
                    field("net", invoice.net().toString()));
        }
        HouseLog.event(
                "invoice.run.finished",
                field("month", month.toString()),
                field("raised", report.count()),
                field("skipped", report.skipped.size()));
        return report;
    }

    /** Why the order cannot be invoiced for the month yet, or nothing when it can. */
    private static Optional<String> holdUp(UnitOfWork uow, String order, BroadcastMonth month) {
        if (existing(uow, order, month).isPresent()) {
            return Optional.of(ALREADY_INVOICED);
        }
        List<String> waiting = waitingOn(uow, order, month);
        if (!waiting.isEmpty()) {
            return Optional.of("waiting on the as-run for " + String.join(", ", waiting));
        }
        return Optional.empty();
    }

    private static InvoiceRow numberAndStore(UnitOfWork uow, Invoice invoice, LocalDate raisedOn) {
        return store(uow, invoice.withNumber(new Numbering(uow.entityManager()).next(SERIES, raisedOn.getYear())));
    }

    /** An order the run passed over, and why. */
    public record Skipped(String order, String why) {}

    /** What one month end run did. */
    public static final class RunReport {

        private final BroadcastMonth month;
        private final List<String> raised = new ArrayList<>();
        private final List<Skipped> skipped = new ArrayList<>();
        private final List<String> nothingAired = new ArrayList<>();

        public RunReport(BroadcastMonth month) {
            this.month = month;
        }

        public BroadcastMonth month() {
            return month;
        }

        /** The invoice numbers raised, in the order they were drawn. */
        public List<String> raised() {
            return List.copyOf(raised);
        }

        public List<Skipped> skipped() {
            return List.copyOf(skipped);
        }

        public List<String> nothingAired() {
            return List.copyOf(nothingAired);
        }

        public int count() {
            return raised.size();
        }

        @Override
        public String toString() {
            return month + ": " + count() + " raised, " + skipped.size() + " already done, " + nothingAired.size()
                    + " with nothing to bill";
        }
    }
}
