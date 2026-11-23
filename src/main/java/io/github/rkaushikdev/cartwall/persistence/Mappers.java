package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Agency;
import io.github.rkaushikdev.cartwall.domain.Assignment;
import io.github.rkaushikdev.cartwall.domain.Break;
import io.github.rkaushikdev.cartwall.domain.Category;
import io.github.rkaushikdev.cartwall.domain.Clock;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Cluster;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.CutLibrary;
import io.github.rkaushikdev.cartwall.domain.Daypart;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.Quote;
import io.github.rkaushikdev.cartwall.domain.RateCard;
import io.github.rkaushikdev.cartwall.domain.RateCardBook;
import io.github.rkaushikdev.cartwall.domain.Rotation;
import io.github.rkaushikdev.cartwall.domain.RotationKind;
import io.github.rkaushikdev.cartwall.domain.Slice;
import io.github.rkaushikdev.cartwall.domain.Station;
import io.github.rkaushikdev.cartwall.domain.Weekday;
import io.github.rkaushikdev.cartwall.persistence.models.AdvertiserRow;
import io.github.rkaushikdev.cartwall.persistence.models.AgencyRow;
import io.github.rkaushikdev.cartwall.persistence.models.AssignmentRow;
import io.github.rkaushikdev.cartwall.persistence.models.BreakRow;
import io.github.rkaushikdev.cartwall.persistence.models.CategoryRow;
import io.github.rkaushikdev.cartwall.persistence.models.ClockRow;
import io.github.rkaushikdev.cartwall.persistence.models.CutRow;
import io.github.rkaushikdev.cartwall.persistence.models.DaypartRow;
import io.github.rkaushikdev.cartwall.persistence.models.LineRow;
import io.github.rkaushikdev.cartwall.persistence.models.OrderRow;
import io.github.rkaushikdev.cartwall.persistence.models.QuoteRow;
import io.github.rkaushikdev.cartwall.persistence.models.RateCardRow;
import io.github.rkaushikdev.cartwall.persistence.models.RotationRow;
import io.github.rkaushikdev.cartwall.persistence.models.StationRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Rows to domain objects and back, one pair of functions per aggregate.
 *
 * <p>Nothing here touches the database. The repositories decide what to read and write; this
 * decides what a row means. Where a domain object refuses what a row holds, the refusal comes out
 * as the domain's own error, which is correct: both mean the same thing to whoever has to fix it.
 */
public final class Mappers {

    private Mappers() {}

    public static StationRow stationRow(Station station, String market) {
        StationRow row = new StationRow(
                station.call(),
                station.band(),
                station.frequency(),
                station.cityOfLicense(),
                station.service(),
                market);
        row.setDaytimer(station.daytimer());
        row.setCarries(station.carries().orElse(null));
        row.setCommercialLoad(station.commercialLoad());
        return row;
    }

    public static Station stationFrom(StationRow row) {
        return new Station(
                row.getCall(),
                row.getBand(),
                row.getFrequency(),
                row.getCityOfLicense(),
                row.getService(),
                row.isDaytimer(),
                Optional.ofNullable(row.getCarries()),
                row.getCommercialLoad());
    }

    public static List<StationRow> clusterRows(Cluster cluster) {
        return cluster.stations().stream()
                .map(station -> stationRow(station, cluster.market()))
                .toList();
    }

    /**
     * Rows in, cluster out, in the order given.
     *
     * <p>The order is not load bearing: the cluster resolves a simulcast by looking the parent up.
     * Keeping the given order means a round trip compares equal.
     */
    public static Cluster clusterFrom(List<StationRow> rows) {
        if (rows.isEmpty()) {
            return new Cluster("", List.of());
        }
        return new Cluster(
                rows.get(0).getMarket(), rows.stream().map(Mappers::stationFrom).toList());
    }

    public static DaypartRow daypartRow(Daypart daypart, String station) {
        return new DaypartRow(
                station,
                daypart.code(),
                daypart.name(),
                Weekday.showDays(daypart.days()),
                daypart.firstHour(),
                daypart.lastHour());
    }

    public static Daypart daypartFrom(DaypartRow row) {
        return new Daypart(
                row.getCode(), row.getName(), Weekday.parseDays(row.getDays()), row.getFirstHour(), row.getLastHour());
    }

    public static DaypartGrid daypartGridFrom(List<DaypartRow> rows) {
        return new DaypartGrid(rows.stream().map(Mappers::daypartFrom).toList());
    }

    public static BreakRow breakRow(Break stopset) {
        return new BreakRow(
                stopset.number(),
                stopset.kind(),
                stopset.position(),
                stopset.length(),
                stopset.units(),
                stopset.paid());
    }

    public static Break breakFrom(BreakRow row) {
        return new Break(
                row.getPosition(), row.getNumber(), row.getKind(), row.getLength(), row.getUnits(), row.isPaid());
    }

    public static ClockRow clockRow(Clock clock, String station, LocalDate effective) {
        ClockRow row = new ClockRow(station, clock.code(), clock.name(), effective);
        row.setBreaks(clock.breaks().stream().map(Mappers::breakRow).toList());
        return row;
    }

    public static Clock clockFrom(ClockRow row) {
        return Clock.build(
                row.getCode(),
                row.getName(),
                row.getBreaks().stream().map(Mappers::breakFrom).toList());
    }

    /** The stack, with its order written down. Ordinal is load bearing. */
    public static List<AssignmentRow> assignmentRows(ClockGrid grid) {
        List<AssignmentRow> rows = new ArrayList<>();
        List<Assignment> assignments = grid.assignments();
        for (int index = 0; index < assignments.size(); index++) {
            Assignment assignment = assignments.get(index);
            rows.add(new AssignmentRow(
                    grid.station(),
                    grid.effective(),
                    index,
                    assignment.label(),
                    Weekday.showDays(assignment.days()),
                    assignment.firstHour(),
                    assignment.lastHour(),
                    assignment.clock(),
                    assignment.source()));
        }
        return List.copyOf(rows);
    }

    public static Assignment assignmentFrom(AssignmentRow row) {
        return new Assignment(
                row.getLabel(),
                Weekday.parseDays(row.getDays()),
                row.getFirstHour(),
                row.getLastHour(),
                row.getClockCode(),
                row.getSource());
    }

    public static ClockGrid clockGridFrom(
            String station, LocalDate effective, List<ClockRow> clocks, List<AssignmentRow> assignments) {
        ClockGrid grid = new ClockGrid(station, effective);
        clocks.forEach(clock -> grid.fileClock(clockFrom(clock)));
        assignments.stream()
                .sorted(Comparator.comparingInt(AssignmentRow::getOrdinal))
                .forEach(row -> grid.assign(assignmentFrom(row)));
        return grid;
    }

    public static AgencyRow agencyRow(Agency agency) {
        return new AgencyRow(agency.code(), agency.name(), agency.rate(), agency.advertiserLiable());
    }

    public static Agency agencyFrom(AgencyRow row) {
        return new Agency(row.getCode(), row.getName(), row.getRate(), row.isAdvertiserLiable());
    }

    public static AdvertiserRow advertiserRow(Advertiser advertiser) {
        AdvertiserRow row = new AdvertiserRow(advertiser.code(), advertiser.name());
        row.setAgency(advertiser.agency().orElse(null));
        row.setParent(advertiser.parent().orElse(null));
        row.setCredit(advertiser.credit());
        row.setCategories(new TreeSet<>(advertiser.categories())
                .stream()
                        .map(code -> new CategoryRow(code.path(), code.parts().get(0)))
                        .toList());
        return row;
    }

    public static Advertiser advertiserFrom(AdvertiserRow row) {
        Set<Category> categories = new TreeSet<>();
        row.getCategories().forEach(entry -> categories.add(new Category(entry.getPath())));
        return new Advertiser(
                row.getCode(),
                row.getName(),
                categories,
                Optional.ofNullable(row.getAgency()),
                row.getCredit(),
                Optional.ofNullable(row.getParent()));
    }

    /** Parents before children, so the book's own checks can run. */
    public static AccountBook accountBookFrom(List<AgencyRow> agencies, List<AdvertiserRow> advertisers) {
        AccountBook book = new AccountBook();
        agencies.forEach(agency -> book.addAgency(agencyFrom(agency)));
        advertisers.stream()
                .sorted(Comparator.comparing((AdvertiserRow row) -> row.getParent() != null)
                        .thenComparing(AdvertiserRow::getCode))
                .forEach(row -> book.addAdvertiser(advertiserFrom(row)));
        return book;
    }

    public static CutRow cutRow(Cut cut) {
        return new CutRow(
                cut.cart(),
                cut.isci(),
                cut.advertiser(),
                cut.length(),
                cut.actual(),
                cut.firstAir(),
                cut.lastAir().orElse(null),
                cut.killed());
    }

    public static Cut cutFrom(CutRow row) {
        return new Cut(
                row.getCart(),
                row.getIsci(),
                row.getAdvertiser(),
                row.getLength(),
                row.getActual(),
                row.getFirstAir(),
                Optional.ofNullable(row.getLastAir()),
                row.isKilled());
    }

    public static CutLibrary cutLibraryFrom(List<CutRow> rows) {
        CutLibrary library = new CutLibrary();
        rows.forEach(row -> library.file(cutFrom(row)));
        return library;
    }

    public static RateCardRow rateCardRow(RateCard card) {
        RateCardRow row = new RateCardRow(card.code(), card.station(), card.effective());
        row.setQuotes(card.quotes().stream()
                .map(quote -> new QuoteRow(quote.daypart(), quote.grade(), quote.thirty()))
                .toList());
        return row;
    }

    public static RateCard rateCardFrom(RateCardRow row) {
        return new RateCard(
                row.getCode(),
                row.getStation(),
                row.getEffective(),
                row.getQuotes().stream()
                        .map(quote -> new Quote(quote.getDaypart(), quote.getGrade(), quote.getThirty()))
                        .toList());
    }

    public static RateCardBook rateCardBookFrom(List<RateCardRow> rows) {
        RateCardBook book = new RateCardBook();
        rows.forEach(row -> book.file(rateCardFrom(row)));
        return book;
    }

    /** A line with its carts as an even rotation. */
    public static LineRow lineRow(Line line, String cardCode) {
        return lineRow(line, cardCode, Optional.empty());
    }

    /** A line with the rotation given, or its carts as an even rotation when there is none. */
    public static LineRow lineRow(Line line, String cardCode, Optional<Rotation> rotation) {
        LineRow row = new LineRow(
                line.number(),
                line.station(),
                line.daypart(),
                line.grade(),
                line.length(),
                line.rate(),
                line.flight().first(),
                line.flight().last(),
                Weekday.showDays(line.flight().days()),
                line.flight().perWeek());
        row.setCardCode(cardCode);
        row.setDarkWeeks(line.flight().dark().stream()
                .map(week -> week.start().toString())
                .collect(Collectors.joining(",")));
        List<Slice> carts = rotation.map(Rotation::slices)
                .orElseGet(() -> line.carts().stream().map(Slice::new).toList());
        RotationKind kind = rotation.map(Rotation::kind).orElse(RotationKind.EVEN);
        List<RotationRow> rotationRows = new ArrayList<>();
        for (int index = 0; index < carts.size(); index++) {
            Slice piece = carts.get(index);
            rotationRows.add(new RotationRow(index, piece.cart(), piece.weight(), kind));
        }
        row.setRotation(rotationRows);
        return row;
    }

    public static Line lineFrom(LineRow row) {
        return new Line(
                row.getNumber(),
                row.getStation(),
                row.getDaypart(),
                row.getGrade(),
                row.getLength(),
                row.getRate(),
                new Flight(
                        row.getFirstAir(),
                        row.getLastAir(),
                        Weekday.parseDays(row.getDays()),
                        row.getPerWeek(),
                        Arrays.stream(row.getDarkWeeks().split(","))
                                .filter(monday -> !monday.isEmpty())
                                .map(monday -> new BroadcastWeek(LocalDate.parse(monday)))
                                .collect(Collectors.toSet())),
                row.getRotation().stream().map(RotationRow::getCart).toList());
    }

    /** The line's rotation, or nothing when no copy has been dubbed against it yet. */
    public static Optional<Rotation> rotationFrom(LineRow row) {
        if (row.getRotation().isEmpty()) {
            return Optional.empty();
        }
        RotationKind kind = row.getRotation().get(0).getKind();
        return Optional.of(new Rotation(
                row.getRotation().stream()
                        .map(piece -> new Slice(piece.getCart(), piece.getWeight()))
                        .toList(),
                kind));
    }

    /** An order with no card codes recorded. */
    public static OrderRow orderRow(Order order) {
        return orderRow(order, Map.of());
    }

    /** An order, with the card each line's rate came off, by line number. */
    public static OrderRow orderRow(Order order, Map<Integer, String> cards) {
        OrderRow row = new OrderRow(order.number(), order.advertiser(), order.written(), order.salesperson());
        row.setAgency(order.agency().orElse(null));
        row.setCampaign(order.campaign());
        row.setStatus(order.status());
        row.setLines(order.lines().stream()
                .map(line -> lineRow(line, cards.getOrDefault(line.number(), "")))
                .toList());
        return row;
    }

    public static Order orderFrom(OrderRow row) {
        return new Order(
                row.getNumber(),
                row.getAdvertiser(),
                row.getWritten(),
                row.getSalesperson(),
                row.getLines().stream().map(Mappers::lineFrom).toList(),
                row.getStatus(),
                Optional.ofNullable(row.getAgency()),
                row.getCampaign());
    }
}
