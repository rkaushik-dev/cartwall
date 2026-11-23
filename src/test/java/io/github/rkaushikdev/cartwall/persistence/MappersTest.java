package io.github.rkaushikdev.cartwall.persistence;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.aCard;
import static io.github.rkaushikdev.cartwall.Fixtures.aGrid;
import static io.github.rkaushikdev.cartwall.Fixtures.farmClock;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Agency;
import io.github.rkaushikdev.cartwall.domain.Assignment;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Clock;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Cluster;
import io.github.rkaushikdev.cartwall.domain.Credit;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.CutLibrary;
import io.github.rkaushikdev.cartwall.domain.Daypart;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.OrderStatus;
import io.github.rkaushikdev.cartwall.domain.RateCard;
import io.github.rkaushikdev.cartwall.domain.RateCardBook;
import io.github.rkaushikdev.cartwall.domain.Rotation;
import io.github.rkaushikdev.cartwall.domain.RotationKind;
import io.github.rkaushikdev.cartwall.domain.Slice;
import io.github.rkaushikdev.cartwall.domain.Source;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.domain.Station;
import io.github.rkaushikdev.cartwall.domain.Weekday;
import io.github.rkaushikdev.cartwall.persistence.models.AdvertiserRow;
import io.github.rkaushikdev.cartwall.persistence.models.AgencyRow;
import io.github.rkaushikdev.cartwall.persistence.models.AssignmentRow;
import io.github.rkaushikdev.cartwall.persistence.models.CategoryRow;
import io.github.rkaushikdev.cartwall.persistence.models.ClockRow;
import io.github.rkaushikdev.cartwall.persistence.models.DaypartRow;
import io.github.rkaushikdev.cartwall.persistence.models.LineRow;
import io.github.rkaushikdev.cartwall.persistence.models.OrderRow;
import io.github.rkaushikdev.cartwall.persistence.models.StationRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Round trips, compared whole. A field that stops being mapped fails here. */
class MappersTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;

    private static <T> List<T> reversed(List<T> items) {
        List<T> copy = new ArrayList<>(items);
        Collections.reverse(copy);
        return copy;
    }

    private static List<ClockRow> clockRows(ClockGrid grid) {
        return grid.clocks().values().stream()
                .map(clock -> Mappers.clockRow(clock, "KCNZ", CARD_DATE))
                .toList();
    }

    private static Line aLine(int number, List<String> carts) {
        return new Line(
                number,
                "KWLO",
                "AMD",
                FIXED,
                THIRTY,
                Money.dollars(60),
                Flight.build(MONDAY, LocalDate.of(2025, 8, 29), "M-F", 12),
                carts);
    }

    private static Line aLine(int number) {
        return aLine(number, List.of());
    }

    private static Line aLine() {
        return aLine(1);
    }

    @Test
    void aStationSurvivesTheRoundTripWhole() {
        Cluster cluster = Fixtures.cluster();
        for (Station station : cluster.stations()) {
            assertThat(Mappers.stationFrom(Mappers.stationRow(station, cluster.market())))
                    .isEqualTo(station);
        }
    }

    @Test
    void aClusterSurvivesTheRoundTripWhole() {
        Cluster cluster = Fixtures.cluster();
        assertThat(Mappers.clusterFrom(Mappers.clusterRows(cluster))).isEqualTo(cluster);
    }

    @Test
    void anEmptyClusterMapsToNothingAndBack() {
        assertThat(Mappers.clusterFrom(List.<StationRow>of()).size()).isZero();
    }

    @Test
    void aSimulcastResolvesHoweverTheRowsComeBack() {
        Cluster cluster = Fixtures.cluster();
        Cluster back = Mappers.clusterFrom(reversed(Mappers.clusterRows(cluster)));
        assertThat(back.loggingStation("KWLO-LP").call()).isEqualTo("KWLO");
        assertThat(back.size()).isEqualTo(cluster.size());
    }

    @Test
    void aDaypartSurvivesTheRoundTripWhole() {
        for (Daypart daypart : DaypartGrid.standardGrid().dayparts()) {
            assertThat(Mappers.daypartFrom(Mappers.daypartRow(daypart, "KWLO"))).isEqualTo(daypart);
        }
    }

    @Test
    void aDaypartGridSurvivesTheRoundTripWhole() {
        DaypartGrid grid = DaypartGrid.standardGrid();
        List<DaypartRow> rows = grid.dayparts().stream()
                .map(daypart -> Mappers.daypartRow(daypart, "KWLO"))
                .toList();
        assertThat(Mappers.daypartGridFrom(rows)).isEqualTo(grid);
    }

    @Test
    void aClockSurvivesTheRoundTripWithItsBreaks() {
        for (Clock clock : List.of(Clock.musicClock(), Clock.talkClock(), farmClock())) {
            assertThat(Mappers.clockFrom(Mappers.clockRow(clock, "KWLO", CARD_DATE)))
                    .isEqualTo(clock);
        }
    }

    @Test
    void aProgrammingGridSurvivesTheRoundTripInOrder() {
        ClockGrid grid = aGrid("KCNZ", "TLK");
        List<AssignmentRow> rows = Mappers.assignmentRows(grid);
        assertThat(rows)
                .extracting(AssignmentRow::getOrdinal)
                .containsExactlyElementsOf(
                        IntStream.range(0, grid.assignments().size()).boxed().toList());
        ClockGrid back = Mappers.clockGridFrom("KCNZ", CARD_DATE, clockRows(grid), rows);
        assertThat(back.assignments())
                .extracting(Assignment::label)
                .containsExactlyElementsOf(
                        grid.assignments().stream().map(Assignment::label).toList());
        Weekday anyDay = back.assignments().get(0).days().iterator().next();
        assertThat(back.clockFor(anyDay, 7).code()).isEqualTo("TLK");
    }

    @Test
    void theAssignmentOrderSurvivesEvenWhenTheRowsComeBackShuffled() {
        ClockGrid grid = aGrid("KCNZ", "TLK");
        grid.assign(new Assignment("farm block", grid.assignments().get(0).days(), 5, 7, "FRM", Source.LOCAL));
        List<AssignmentRow> rows = reversed(Mappers.assignmentRows(grid));
        ClockGrid back = Mappers.clockGridFrom("KCNZ", CARD_DATE, clockRows(grid), rows);
        assertThat(back.assignments()).extracting(Assignment::label).containsExactly("all week", "farm block");
    }

    @Test
    void anAgencySurvivesTheRoundTripWhole() {
        Agency house = new Agency("HSE", "House", Money.AGENCY_RATE, true);
        for (Agency agency : List.of(new Agency("MOP", "Mopar Media"), house)) {
            assertThat(Mappers.agencyFrom(Mappers.agencyRow(agency))).isEqualTo(agency);
        }
    }

    @Test
    void anAdvertiserSurvivesTheRoundTripWithAllItsCodes() {
        AccountBook accounts = Fixtures.accounts();
        for (String code : accounts.advertisers().keySet()) {
            Advertiser advertiser = accounts.advertiser(code);
            assertThat(Mappers.advertiserFrom(Mappers.advertiserRow(advertiser)))
                    .isEqualTo(advertiser);
        }
    }

    @Test
    void theRootOfEachCodeIsWrittenOutForTheIndex() {
        AdvertiserRow row = Mappers.advertiserRow(Fixtures.accounts().advertiser("HYVEE"));
        assertThat(row.getCategories().stream()
                        .sorted(Comparator.comparing(CategoryRow::getPath))
                        .toList())
                .extracting(CategoryRow::getPath, CategoryRow::getRoot)
                .containsExactly(tuple("FUEL.GAS", "FUEL"), tuple("GROCERY", "GROCERY"));
    }

    @Test
    void aWholeAccountBookSurvivesTheRoundTrip() {
        AccountBook accounts = Fixtures.accounts();
        List<AgencyRow> agencies =
                accounts.agencies().values().stream().map(Mappers::agencyRow).toList();
        List<AdvertiserRow> advertisers = accounts.advertisers().values().stream()
                .map(Mappers::advertiserRow)
                .toList();
        AccountBook back = Mappers.accountBookFrom(agencies, advertisers);
        assertThat(back.advertisers()).isEqualTo(accounts.advertisers());
        assertThat(back.agencies()).isEqualTo(accounts.agencies());
    }

    @Test
    void aChainStoreIsReadBackAfterItsParent() {
        List<AdvertiserRow> bookRows = List.of(
                Mappers.advertiserRow(
                        Advertiser.build("HYVEE1", "Hy-Vee Kimball", "GROCERY").withParent("HYVEE")),
                Mappers.advertiserRow(Advertiser.build("HYVEE", "Hy-Vee", "GROCERY")));
        AccountBook back = Mappers.accountBookFrom(List.of(), bookRows);
        assertThat(back.billingGroup("HYVEE1")).isEqualTo("HYVEE");
    }

    @Test
    void anAccountOnHoldStaysOnHold() {
        Advertiser advertiser = Advertiser.build("SLOW", "Slow Pay").withCredit(Credit.HOLD);
        assertThat(Mappers.advertiserFrom(Mappers.advertiserRow(advertiser)).credit())
                .isSameAs(Credit.HOLD);
    }

    @Test
    void aCutSurvivesTheRoundTripWithItsRunningTime() {
        Cut cut = new Cut(
                "C1204",
                "HYVE2501",
                "HYVEE",
                THIRTY,
                new Duration(301),
                MONDAY,
                Optional.of(LocalDate.of(2025, 9, 1)),
                true);
        assertThat(Mappers.cutFrom(Mappers.cutRow(cut))).isEqualTo(cut);
    }

    @Test
    void aCutLibrarySurvivesTheRoundTrip() {
        List<Cut> cuts = List.of(
                new Cut("C1204", "HYVE2501", "HYVEE", THIRTY, new Duration(300), MONDAY),
                new Cut("C1205", "HYVE2502", "HYVEE", new SpotLength(60), new Duration(600), MONDAY));
        CutLibrary library =
                Mappers.cutLibraryFrom(cuts.stream().map(Mappers::cutRow).toList());
        assertThat(library.size()).isEqualTo(2);
        assertThat(library.cut("C1205").length()).isEqualTo(new SpotLength(60));
    }

    @Test
    void aRateCardSurvivesTheRoundTripWhole() {
        RateCard card = aCard("KWLO-25", "KWLO", 60, 32);
        assertThat(Mappers.rateCardFrom(Mappers.rateCardRow(card))).isEqualTo(card);
    }

    @Test
    void aCardBookSurvivesTheRoundTripInEffectiveOrder() {
        List<RateCard> cards = List.of(aCard("KWLO-25A", "KWLO", 60, 32), aCard("KCRR-25", "KCRR", 40, 22));
        RateCardBook book = Mappers.rateCardBookFrom(
                cards.stream().map(Mappers::rateCardRow).toList());
        assertThat(book.inForce("KWLO", MONDAY).code()).isEqualTo("KWLO-25A");
        assertThat(book.inForce("KCRR", MONDAY).code()).isEqualTo("KCRR-25");
    }

    @Test
    void aLineSurvivesTheRoundTripWithItsFlight() {
        Line line = aLine();
        Line back = Mappers.lineFrom(Mappers.lineRow(line, ""));
        assertThat(back).isEqualTo(line);
        assertThat(back.flight().perWeek()).isEqualTo(12);
        assertThat(back.spots()).isEqualTo(line.spots());
    }

    @Test
    void aLineGoesDarkInTheSameWeeksAfterTheRoundTrip() {
        Line line = aLine().withCarts(List.of());
        Line dark = new Line(
                line.number(),
                line.station(),
                line.daypart(),
                line.grade(),
                line.length(),
                line.rate(),
                line.flight().goingDark(List.of(LocalDate.of(2025, 8, 11), LocalDate.of(2025, 8, 25))));
        LineRow row = Mappers.lineRow(dark, "");
        assertThat(row.getDarkWeeks()).isEqualTo("2025-08-11,2025-08-25");
        assertThat(Mappers.lineFrom(row)).isEqualTo(dark);
        assertThat(Mappers.lineFrom(row).spots()).isEqualTo(24);
        assertThat(Mappers.lineRow(line, "").getDarkWeeks()).isEmpty();
    }

    @Test
    void aLinesCartsSurviveTheRoundTripInOrder() {
        Line line = aLine(1, List.of("C1204", "C1205", "C1206"));
        assertThat(Mappers.lineFrom(Mappers.lineRow(line, "")).carts()).containsExactly("C1204", "C1205", "C1206");
    }

    @Test
    void aWeightedRotationSurvivesTheRoundTrip() {
        Rotation rotation = Rotation.weighted(new Slice("C1204", 3), new Slice("C1205", 1));
        LineRow row = Mappers.lineRow(aLine(), "", Optional.of(rotation));
        assertThat(Mappers.rotationFrom(row)).contains(rotation);
        assertThat(Mappers.rotationFrom(row).orElseThrow().kind()).isSameAs(RotationKind.WEIGHTED);
    }

    @Test
    void aLineWithNoCopyHasNoRotationToReadBack() {
        assertThat(Mappers.rotationFrom(Mappers.lineRow(aLine(), ""))).isEmpty();
    }

    @Test
    void theCardTheRateCameOffIsWrittenBesideIt() {
        assertThat(Mappers.lineRow(aLine(), "KWLO-25").getCardCode()).isEqualTo("KWLO-25");
    }

    @Test
    void anOrderSurvivesTheRoundTripWhole() {
        Order order = new Order(
                "25-1044",
                "HYVEE",
                LocalDate.of(2025, 7, 21),
                "DHOLM",
                List.of(aLine(1), aLine(2, List.of("C1204"))),
                OrderStatus.CONFIRMED,
                Optional.of("MOP"),
                "back to school");
        Order back = Mappers.orderFrom(Mappers.orderRow(order));
        assertThat(back).isEqualTo(order);
        assertThat(back.status()).isSameAs(OrderStatus.CONFIRMED);
        assertThat(back.campaign()).isEqualTo("back to school");
        assertThat(back.value()).isEqualTo(order.value());
    }

    @Test
    void eachLineCanNameTheCardItWasPricedFrom() {
        Order order = new Order("25-1044", "HYVEE", LocalDate.of(2025, 7, 21), "DHOLM", List.of(aLine(1), aLine(2)));
        OrderRow row = Mappers.orderRow(order, Map.of(1, "KWLO-25", 2, "KCRR-25"));
        assertThat(row.getLines()).extracting(LineRow::getCardCode).containsExactly("KWLO-25", "KCRR-25");
    }

    @Test
    void anEmptyOrderSurvivesTheRoundTrip() {
        Order order = new Order("25-1044", "HYVEE", LocalDate.of(2025, 7, 21), "DHOLM", List.of());
        assertThat(Mappers.orderFrom(Mappers.orderRow(order))).isEqualTo(order);
    }

    @ParameterizedTest
    @EnumSource(ClassOfTime.class)
    void everyClassOfTimeSurvivesTheRoundTrip(ClassOfTime grade) {
        Line line = new Line(
                1,
                "KWLO",
                "AMD",
                grade,
                THIRTY,
                Money.dollars(60),
                Flight.build(MONDAY, LocalDate.of(2025, 8, 29), "M-F", 12));
        assertThat(Mappers.lineFrom(Mappers.lineRow(line, "")).grade()).isSameAs(grade);
    }
}
