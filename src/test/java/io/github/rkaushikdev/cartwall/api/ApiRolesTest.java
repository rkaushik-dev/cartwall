package io.github.rkaushikdev.cartwall.api;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.services.Audit;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every route behind the role it needs, and every write on the trail.
 *
 * <p>The role table below is the interface. A route that changes which role it needs changes what
 * six people can do on a Monday morning, so it is a decision that gets made here and not in an
 * annotation somebody skims past.
 */
@WebMvcTest
@Import({TestService.class, ApiRolesTest.Keys.class})
class ApiRolesTest {

    static final String RING =
            "eyre:reader:readerkey;dholm:seller:sellerkey;tronan:traffic:traffickey;mstone:manager:managerkey";

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {

        @Bean
        KeyRing keyRing() {
            return KeyRing.parse(RING);
        }
    }

    @Autowired
    private MockMvc mvc;

    private Database engine;
    private Http reader;
    private Http seller;
    private Http traffic;
    private Http manager;

    @BeforeEach
    void putTheClusterOnFile() {
        engine = TestDatabase.seeded(uow -> {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            for (String call : List.of("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ")) {
                uow.cards().file(Fixtures.cards().inForce(call, CARD_DATE));
                uow.stations().saveDayparts(call, DaypartGrid.standardGrid());
                uow.stations().saveGrid(Fixtures.programming().get(call));
            }
        });
        Http client = new Http(mvc);
        reader = client.as("readerkey");
        seller = client.as("sellerkey");
        traffic = client.as("traffickey");
        manager = client.as("managerkey");
    }

    static Map<String, Object> aLine() {
        Map<String, Object> flight = new LinkedHashMap<>();
        flight.put("first", "2025-08-04");
        flight.put("last", "2025-08-08");
        flight.put("days", "M-F");
        flight.put("per_week", 10);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("number", 1);
        line.put("station", "KWLO");
        line.put("daypart", "AMD");
        line.put("grade", "fixed");
        line.put("length", ":30");
        line.put("rate", "60.00");
        line.put("flight", flight);
        return line;
    }

    static Map<String, Object> anOrder() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("number", "25-1044");
        order.put("advertiser", "HYVEE");
        order.put("written", "2025-07-21");
        order.put("salesperson", "DHOLM");
        order.put("lines", List.of(aLine()));
        return order;
    }

    private List<Audit.Entry> about(String kind, String reference) {
        try (UnitOfWork uow = engine.unitOfWork()) {
            return Audit.about(uow.entityManager(), kind, reference);
        }
    }

    private int today() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            return Audit.onDay(uow.entityManager(), LocalDate.now()).size();
        }
    }

    private Http.Answer sign(Http who, String action) {
        return who.postJson("/logs/KWLO/2025-08-04/" + action, Map.of("who", "T. Ronan"));
    }

    @Test
    void readingAnOrderNeedsOnlyAReader() {
        seller.postJson("/orders", anOrder());
        assertThat(reader.get("/orders/25-1044").status()).isEqualTo(200);
        assertThat(reader.get("/orders", "advertiser", "HYVEE").status()).isEqualTo(200);
    }

    @Test
    void writingAnOrderNeedsASeller() {
        assertThat(reader.postJson("/orders", anOrder()).status()).isEqualTo(403);
        assertThat(seller.postJson("/orders", anOrder()).status()).isEqualTo(201);
    }

    @Test
    void confirmingAndCancellingNeedASeller() {
        seller.postJson("/orders", anOrder());
        assertThat(reader.post("/orders/25-1044/confirm").status()).isEqualTo(403);
        assertThat(seller.post("/orders/25-1044/confirm").status()).isEqualTo(200);
        assertThat(seller.post("/orders/25-1044/cancel").status()).isEqualTo(200);
    }

    @Test
    void buildingAWeekNeedsTraffic() {
        assertThat(seller.post("/logs/build/2025-08-04").status()).isEqualTo(403);
        assertThat(traffic.post("/logs/build/2025-08-04").status()).isEqualTo(200);
    }

    @Test
    void readingALogNeedsOnlyAReader() {
        traffic.post("/logs/build/2025-08-04");
        assertThat(reader.get("/logs/KWLO/2025-08-04").status()).isEqualTo(200);
        assertThat(reader.get("/logs/KWLO/2025-08-04/ready").status()).isEqualTo(200);
    }

    @Test
    void signingAndReleasingALogNeedTraffic() {
        traffic.post("/logs/build/2025-08-04");
        assertThat(sign(seller, "approve").status()).isEqualTo(403);
        assertThat(sign(traffic, "approve").status()).isEqualTo(200);
        assertThat(sign(traffic, "release").status()).isEqualTo(200);
    }

    @Test
    void revisingAReleasedLogNeedsAManager() {
        traffic.post("/logs/build/2025-08-04");
        sign(traffic, "approve");
        sign(traffic, "release");
        assertThat(traffic.post("/logs/KWLO/2025-08-04/revise").status()).isEqualTo(403);
        assertThat(manager.post("/logs/KWLO/2025-08-04/revise").status()).isEqualTo(200);
    }

    @Test
    void writingAnOrderLandsOnTheTrailWithAName() {
        seller.postJson("/orders", anOrder());
        List<Audit.Entry> trail = about("order", "25-1044");
        assertThat(trail).extracting(Audit.Entry::action).containsExactly("order.written");
        assertThat(trail.get(0).actor()).isEqualTo("dholm");
        assertThat(trail.get(0).detail()).contains("advertiser=HYVEE");
    }

    @Test
    void theWholeLifeOfAnOrderIsOnTheTrail() {
        seller.postJson("/orders", anOrder());
        seller.putJson("/orders/25-1044", anOrder());
        seller.post("/orders/25-1044/confirm");
        seller.post("/orders/25-1044/cancel");
        assertThat(about("order", "25-1044"))
                .extracting(Audit.Entry::action)
                .containsExactly("order.written", "order.rewritten", "order.confirmed", "order.cancelled");
    }

    @Test
    void aRewriteSaysOnTheTrailWhatChanged() {
        seller.postJson("/orders", anOrder());
        Map<String, Object> cheaper = anOrder();
        Map<String, Object> line = aLine();
        line.put("rate", "55.00");
        cheaper.put("lines", List.of(line));
        seller.putJson("/orders/25-1044", cheaper);
        Audit.Entry rewritten = about("order", "25-1044").get(1);
        assertThat(rewritten.action()).isEqualTo("order.rewritten");
        assertThat(rewritten.detail()).contains("line 1: rate $60.00 to $55.00").contains("-$50.00");
    }

    @Test
    void aRefusedWriteLeavesNothingOnTheTrail() {
        reader.postJson("/orders", anOrder());
        assertThat(about("order", "25-1044")).isEmpty();
    }

    @Test
    void aFailedWriteRollsTheTrailBackWithIt() {
        Map<String, Object> body = anOrder();
        Map<String, Object> line = new LinkedHashMap<>(aLine());
        line.put("length", ":25");
        body.put("lines", List.of(line));
        assertThat(seller.postJson("/orders", body).status()).isEqualTo(422);
        assertThat(about("order", "25-1044")).isEmpty();
    }

    @Test
    void signingALogRecordsWhoPressedTheButtonAndWhoSignedIt() {
        traffic.post("/logs/build/2025-08-04");
        sign(traffic, "approve");
        List<Audit.Entry> trail = about("log", "KWLO 2025-08-04");
        assertThat(trail.get(0).action()).isEqualTo("log.approved");
        assertThat(trail.get(0).actor()).isEqualTo("tronan");
        assertThat(trail.get(0).detail()).contains("signed_by=\"T. Ronan\"");
    }

    @Test
    void aBuildIsRecordedAgainstTheWeek() {
        traffic.post("/logs/build/2025-08-04");
        List<Audit.Entry> trail = about("week", "week of 2025-08-04");
        assertThat(trail.get(0).action()).isEqualTo("log.built");
        assertThat(trail.get(0).detail()).contains("written=35");
    }

    @Test
    void readingWritesNothingToTheTrail() {
        seller.postJson("/orders", anOrder());
        int before = today();
        reader.get("/orders/25-1044");
        reader.get("/orders", "advertiser", "HYVEE");
        assertThat(today()).isEqualTo(before);
    }
}
