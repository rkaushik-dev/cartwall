package io.github.rkaushikdev.cartwall.persistence;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.FIXED;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.aCard;
import static io.github.rkaushikdev.cartwall.Fixtures.aGrid;
import static io.github.rkaushikdev.cartwall.Fixtures.aLine;
import static io.github.rkaushikdev.cartwall.Fixtures.anOrder;
import static io.github.rkaushikdev.cartwall.domain.DaypartGrid.standardGrid;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Cluster;
import io.github.rkaushikdev.cartwall.domain.Credit;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.OrderError;
import io.github.rkaushikdev.cartwall.domain.RateCard;
import io.github.rkaushikdev.cartwall.domain.RateCardBook;
import io.github.rkaushikdev.cartwall.domain.Weekday;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepositoriesTest {

    private Cluster cluster;
    private AccountBook accounts;
    private UnitOfWork session;

    @BeforeEach
    void openASession() {
        cluster = Fixtures.cluster();
        accounts = Fixtures.accounts();
        session = TestDatabase.fresh().unitOfWork();
    }

    @AfterEach
    void closeTheSession() {
        session.close();
    }

    private StationRepository stations() {
        StationRepository repo = new StationRepository(session.entityManager());
        repo.saveCluster(cluster);
        session.commit();
        return repo;
    }

    private AccountRepository bookRepo() {
        stations();
        AccountRepository repo = new AccountRepository(session.entityManager());
        repo.saveBook(accounts);
        session.commit();
        return repo;
    }

    private OrderRepository orders() {
        bookRepo();
        return new OrderRepository(session.entityManager());
    }

    private static Cut aCut(String cart, String advertiser, int tenths) {
        return new Cut(cart, "HYVE2501", advertiser, THIRTY, new Duration(tenths), MONDAY);
    }

    @Test
    void aClusterGoesInAndComesBack() {
        Cluster back = stations().cluster();
        assertThat(back.size()).isEqualTo(cluster.size());
        assertThat(back.station("KWLO").frequency())
                .isEqualTo(cluster.station("KWLO").frequency());
        assertThat(back.loggingStation("KWLO-LP").call()).isEqualTo("KWLO");
    }

    @Test
    void savingAClusterTwiceDoesNotDuplicateIt() {
        StationRepository stations = stations();
        stations.saveCluster(cluster);
        session.commit();
        assertThat(stations.cluster().size()).isEqualTo(cluster.size());
    }

    @Test
    void askingForASignalThatIsNotOnFile() {
        StationRepository stations = stations();
        LookupFailure caught = catchThrowableOfType(LookupFailure.class, () -> stations.station("WHO"));
        assertThat(caught).hasMessageContaining("WHO is not on file");
        assertThat(caught.code()).isEqualTo("not_found");
    }

    @Test
    void aDaypartGridGoesInAndComesBack() {
        StationRepository stations = stations();
        stations.saveDayparts("KWLO", standardGrid());
        session.commit();
        DaypartGrid back = stations.dayparts("KWLO");
        assertThat(back.of("AMD").firstHour()).isEqualTo(6);
        assertThat(back.isWholeWeek()).isTrue();
    }

    @Test
    void savingADaypartGridReplacesTheOneThatWasThere() {
        StationRepository stations = stations();
        stations.saveDayparts("KWLO", standardGrid());
        session.commit();
        DaypartGrid trimmed = new DaypartGrid(standardGrid().dayparts().stream()
                .filter(daypart -> !daypart.code().equals("OVN"))
                .toList());
        stations.saveDayparts("KWLO", trimmed);
        session.commit();
        assertThat(stations.dayparts("KWLO").has("OVN")).isFalse();
        assertThat(stations.dayparts("KWLO").dayparts()).hasSize(8);
    }

    @Test
    void aProgrammingGridGoesInAndComesBackInOrder() {
        StationRepository stations = stations();
        ClockGrid grid = aGrid("KWLO");
        stations.saveGrid(grid);
        session.commit();
        ClockGrid back = stations.grid("KWLO", CARD_DATE);
        assertThat(back.assignments())
                .extracting(assignment -> assignment.label())
                .isEqualTo(grid.assignments().stream()
                        .map(assignment -> assignment.label())
                        .toList());
        assertThat(back.clockFor(Weekday.MON, 7).code()).isEqualTo("MUS");
        assertThat(new TreeSet<>(back.clocks().keySet())).containsExactly("FRM", "MUS", "TLK");
    }

    @Test
    void savingAGridTwiceReplacesIt() {
        StationRepository stations = stations();
        stations.saveGrid(aGrid("KWLO"));
        session.commit();
        stations.saveGrid(aGrid("KWLO", "TLK"));
        session.commit();
        ClockGrid back = stations.grid("KWLO", CARD_DATE);
        assertThat(back.assignments()).hasSize(1);
        assertThat(back.clockFor(Weekday.MON, 7).code()).isEqualTo("TLK");
    }

    @Test
    void askingForAGridThatWasNeverFiled() {
        StationRepository stations = stations();
        assertThatThrownBy(() -> stations.grid("KWLO", LocalDate.of(2030, 1, 1)))
                .isInstanceOf(LookupFailure.class)
                .hasMessageContaining("has no grid effective");
    }

    @Test
    void anAccountBookGoesInAndComesBackWhole() {
        AccountBook back = bookRepo().book();
        assertThat(back.advertisers()).isEqualTo(accounts.advertisers());
        assertThat(back.agencies()).isEqualTo(accounts.agencies());
    }

    @Test
    void savingTheBookTwiceDoesNotDuplicateTheConflictCodes() {
        AccountRepository bookRepo = bookRepo();
        bookRepo.saveBook(accounts);
        session.commit();
        assertThat(bookRepo.book().advertiser("HYVEE").categories())
                .isEqualTo(accounts.advertiser("HYVEE").categories());
    }

    @Test
    void anAccountThatChangesCreditIsWrittenOver() {
        AccountRepository bookRepo = bookRepo();
        AccountBook changed = new AccountBook();
        accounts.agencies().values().forEach(changed::addAgency);
        accounts.advertisers().values().stream()
                .map(advertiser -> advertiser.code().equals("HYVEE")
                        ? Advertiser.build("HYVEE", "Hy-Vee", "GROCERY,FUEL.GAS")
                                .withCredit(Credit.HOLD)
                        : advertiser)
                .forEach(changed::addAdvertiser);
        bookRepo.saveBook(changed);
        session.commit();
        assertThat(bookRepo.book().advertiser("HYVEE").credit()).isSameAs(Credit.HOLD);
    }

    @Test
    void aCutGoesInAndComesBack() {
        AccountRepository bookRepo = bookRepo();
        Cut cut = aCut("C1204", "HYVEE", 301);
        bookRepo.fileCut(cut);
        session.commit();
        assertThat(bookRepo.cut("C1204")).isEqualTo(cut);
    }

    @Test
    void aRedubReplacesTheCart() {
        AccountRepository bookRepo = bookRepo();
        for (int tenths : List.of(299, 302)) {
            bookRepo.fileCut(aCut("C1204", "HYVEE", tenths));
            session.commit();
        }
        assertThat(bookRepo.cut("C1204").actual()).isEqualTo(new Duration(302));
    }

    @Test
    void askingForACartNobodyDubbed() {
        AccountRepository bookRepo = bookRepo();
        assertThatThrownBy(() -> bookRepo.cut("C9999"))
                .isInstanceOf(LookupFailure.class)
                .hasMessageContaining("cart C9999 is not in the library");
    }

    @Test
    void theLibraryCanBeReadWholeOrForOneAdvertiser() {
        AccountRepository bookRepo = bookRepo();
        bookRepo.fileCut(aCut("C1204", "HYVEE", 300));
        bookRepo.fileCut(aCut("C1300", "SLUMB", 300));
        session.commit();
        assertThat(bookRepo.library().size()).isEqualTo(2);
        assertThat(bookRepo.library("HYVEE").size()).isEqualTo(1);
    }

    @Test
    void aRateCardGoesInAndComesBack() {
        stations();
        RateCardRepository repo = new RateCardRepository(session.entityManager());
        RateCard card = aCard("KWLO-25", "KWLO", 60, 32);
        repo.file(card);
        session.commit();
        assertThat(repo.card("KWLO", CARD_DATE)).isEqualTo(card);
    }

    @Test
    void refilingACardForTheSameDayReplacesIt() {
        stations();
        RateCardRepository repo = new RateCardRepository(session.entityManager());
        repo.file(aCard("KWLO-25A", "KWLO", 60, 32));
        session.commit();
        repo.file(aCard("KWLO-25B", "KWLO", 70, 36));
        session.commit();
        RateCard card = repo.card("KWLO", CARD_DATE);
        assertThat(card.code()).isEqualTo("KWLO-25B");
        assertThat(card.rate("AMD", FIXED, THIRTY)).isEqualTo(Money.dollars(70));
    }

    @Test
    void theCardBookComesBackInEffectiveOrder() {
        stations();
        RateCardRepository repo = new RateCardRepository(session.entityManager());
        repo.file(aCard("KWLO-25", "KWLO", 60, 32));
        repo.file(aCard("KCRR-25", "KCRR", 40, 22));
        session.commit();
        RateCardBook book = repo.book();
        assertThat(book.history("KWLO")).extracting(RateCard::code).containsExactly("KWLO-25");
        assertThat(book.inForce("KCRR", MONDAY).code()).isEqualTo("KCRR-25");
    }

    @Test
    void askingForACardThatWasNeverFiled() {
        stations();
        assertThatThrownBy(() -> new RateCardRepository(session.entityManager()).card("KWLO", CARD_DATE))
                .isInstanceOf(LookupFailure.class)
                .hasMessageContaining("has no card effective");
    }

    @Test
    void anOrderGoesInAndComesBackWhole() {
        OrderRepository repo = orders();
        Order order = anOrder()
                .lines(aLine(1).build(), aLine(2).station("KCRR").build())
                .build();
        repo.save(order);
        session.commit();
        assertThat(repo.get("25-1044")).isEqualTo(order);
        assertThat(repo.exists("25-1044")).isTrue();
        assertThat(repo.exists("25-9999")).isFalse();
    }

    @Test
    void savingAnOrderAgainReplacesItsLines() {
        OrderRepository repo = orders();
        repo.save(anOrder().lines(aLine(1).build(), aLine(2).build()).build());
        session.commit();
        repo.save(anOrder().lines(aLine(1).build()).build());
        session.commit();
        assertThat(repo.get("25-1044").size()).isEqualTo(1);
    }

    @Test
    void askingForAnOrderThatIsNotOnFile() {
        assertThatThrownBy(() -> new OrderRepository(session.entityManager()).get("25-9999"))
                .isInstanceOf(LookupFailure.class)
                .hasMessageContaining("order 25-9999 is not on file");
    }

    @Test
    void theOrdersOfOneAdvertiserComeBackInNumberOrder() {
        OrderRepository repo = orders();
        repo.save(anOrder().number("25-2000").build());
        repo.save(anOrder().number("25-1044").build());
        repo.save(anOrder().number("25-3000").advertiser("SLUMB").build());
        session.commit();
        assertThat(repo.forAdvertiser("HYVEE")).extracting(Order::number).containsExactly("25-1044", "25-2000");
        assertThat(repo.forAdvertiser("SLUMB")).extracting(Order::number).containsExactly("25-3000");
    }

    @Test
    void theOrdersRunningOnADayAreTheOnesWithAFlightOverIt() {
        OrderRepository repo = orders();
        Line away = new Line(
                1,
                "KWLO",
                "AMD",
                FIXED,
                THIRTY,
                Money.dollars(60),
                Flight.build(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 30), "M-F", 12));
        repo.save(anOrder().number("25-1044").build());
        repo.save(anOrder().number("25-2000").lines(away).build());
        session.commit();
        assertThat(repo.runningOn(MONDAY)).extracting(Order::number).containsExactly("25-1044");
        assertThat(repo.runningOn(LocalDate.of(2025, 9, 3)))
                .extracting(Order::number)
                .containsExactly("25-2000");
        assertThat(repo.runningOn(LocalDate.of(2025, 12, 25))).isEmpty();
    }

    @Test
    void theOrdersRunningInASpanIncludeAFlightThatTouchesNeitherEnd() {
        OrderRepository repo = orders();
        Line midweek = new Line(
                1,
                "KWLO",
                "AMD",
                FIXED,
                THIRTY,
                Money.dollars(60),
                Flight.build(LocalDate.of(2025, 9, 2), LocalDate.of(2025, 9, 4), "Tu-Th", 6));
        Line wholeWeek = new Line(
                1,
                "KWLO",
                "MID",
                FIXED,
                THIRTY,
                Money.dollars(40),
                Flight.build(LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 7), "M-Su", 7));
        repo.save(anOrder().number("25-1044").build());
        repo.save(anOrder().number("25-3000").lines(midweek).build());
        repo.save(anOrder().number("25-2000").lines(wholeWeek, aLine(2).build()).build());
        session.commit();
        LocalDate monday = LocalDate.of(2025, 9, 1);
        LocalDate sunday = LocalDate.of(2025, 9, 7);
        assertThat(repo.runningOn(monday)).extracting(Order::number).containsExactly("25-2000");
        assertThat(repo.runningOn(sunday)).extracting(Order::number).containsExactly("25-2000");
        assertThat(repo.runningBetween(monday, sunday))
                .extracting(Order::number)
                .containsExactly("25-2000", "25-3000");
        assertThat(repo.runningBetween(LocalDate.of(2025, 9, 4), LocalDate.of(2025, 9, 4)))
                .extracting(Order::number)
                .containsExactly("25-2000", "25-3000");
        assertThat(repo.runningBetween(MONDAY, LocalDate.of(2025, 9, 1)))
                .extracting(Order::number)
                .containsExactly("25-1044", "25-2000");
    }

    @Test
    void aSpanThatRunsBackwardsIsRefused() {
        assertThatThrownBy(() -> orders().runningBetween(LocalDate.of(2025, 9, 7), LocalDate.of(2025, 9, 1)))
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("runs backwards");
    }

    @Test
    void deletingAnOrderTakesItOffTheFile() {
        OrderRepository repo = orders();
        repo.save(anOrder().build());
        session.commit();
        repo.delete("25-1044");
        session.commit();
        assertThat(repo.exists("25-1044")).isFalse();
        assertThatThrownBy(() -> repo.delete("25-1044"))
                .isInstanceOf(LookupFailure.class)
                .hasMessageContaining("is not on file");
    }
}
