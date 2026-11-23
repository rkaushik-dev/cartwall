package io.github.rkaushikdev.cartwall.api.routers;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.api.Http;
import io.github.rkaushikdev.cartwall.api.KeyRing;
import io.github.rkaushikdev.cartwall.api.TestService;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import java.util.ArrayList;
import java.util.HashMap;
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

@WebMvcTest
@Import({TestService.class, ApiAvailsTest.Keys.class})
class ApiAvailsTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {

        @Bean
        KeyRing keyRing() {
            return KeyRing.parse("eyre:reader:readerkey;tronan:traffic:traffickey");
        }
    }

    @Autowired
    private MockMvc mvc;

    private Http reader;
    private Http traffic;

    @BeforeEach
    void putTheClusterItsProgrammingAndACutOnFile() {
        TestDatabase.seeded(uow -> {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            for (String call : ApiLogsTest.LOGGED) {
                uow.cards().file(Fixtures.cards().inForce(call, CARD_DATE));
                uow.stations().saveDayparts(call, DaypartGrid.standardGrid());
                uow.stations().saveGrid(Fixtures.programming().get(call));
            }
            uow.accounts().fileCut(new Cut("C1204", "HYVE2501", "HYVEE", THIRTY, new Duration(300), MONDAY));
        });
        reader = new Http(mvc).as("readerkey");
        traffic = new Http(mvc).as("traffickey");
    }

    private void anOrder(int perWeek) {
        Map<String, Object> flight = new LinkedHashMap<>();
        flight.put("first", "2025-08-04");
        flight.put("last", "2025-08-08");
        flight.put("days", "M-F");
        flight.put("per_week", perWeek);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("number", 1);
        line.put("station", "KWLO");
        line.put("daypart", "AMD");
        line.put("grade", "fixed");
        line.put("length", ":30");
        line.put("rate", "60.00");
        line.put("carts", List.of("C1204"));
        line.put("flight", flight);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("number", "25-1044");
        order.put("advertiser", "HYVEE");
        order.put("written", "2025-07-21");
        order.put("salesperson", "DHOLM");
        order.put("lines", List.of(line));
        traffic.postJson("/orders", order);
        traffic.post("/orders/25-1044/confirm");
        traffic.post("/logs/build/2025-08-04");
    }

    private void anOrder() {
        anOrder(10);
    }

    private static Map<String, JsonNode> byLabel(JsonNode body) {
        Map<String, JsonNode> rows = new HashMap<>();
        body.get("rows").forEach(row -> rows.put(row.get("label").asText(), row));
        return rows;
    }

    @Test
    void readingAvailsNeedsOnlyAReader() {
        anOrder();
        assertThat(reader.get("/avails/KWLO/2025-08-04").status()).isEqualTo(200);
    }

    @Test
    void aDayWithNothingSoldIsOpenEverywhere() {
        traffic.post("/logs/build/2025-08-04");
        Map<String, JsonNode> rows =
                byLabel(reader.get("/avails/KWLO/2025-08-04").json());
        assertThat(rows.get("AMD").get("units").asInt()).isEqualTo(96);
        assertThat(rows.get("AMD").get("sold").asInt()).isZero();
        assertThat(rows.get("AMD").get("sellout").asDouble()).isZero();
        assertThat(rows.get("AMD").get("value").asText()).isEqualTo("3456.00");
    }

    @Test
    void theHolesCanBePricedAtTheClassTheSellerIsQuoting() {
        traffic.post("/logs/build/2025-08-04");
        Map<String, JsonNode> rows =
                byLabel(reader.get("/avails/KWLO/2025-08-04", "grade", "fixed").json());
        assertThat(rows.get("AMD").get("value").asText()).isEqualTo("5760.00");
        assertThat(rows.get("EVE").get("value").asText()).isEqualTo("0.00");
        assertThat(reader.get("/avails/KWLO/2025-08-04", "grade", "gold").status())
                .isEqualTo(422);
    }

    @Test
    void theResponseSaysTheRowsOverlap() {
        traffic.post("/logs/build/2025-08-04");
        JsonNode body = reader.get("/avails/KWLO/2025-08-04").json();
        assertThat(body.get("overlapping").isBoolean()).isTrue();
        assertThat(body.get("overlapping").asBoolean()).isTrue();
        List<String> labels = new ArrayList<>();
        body.get("rows").forEach(row -> labels.add(row.get("label").asText()));
        assertThat(labels.indexOf("AMD")).isLessThan(labels.indexOf("AAA"));
        assertThat(labels.indexOf("AAA")).isLessThan(labels.indexOf("ROS"));
    }

    @Test
    void aSoldUnitShowsInEveryDaypartThatCoversIt() {
        anOrder();
        Map<String, JsonNode> rows =
                byLabel(reader.get("/avails/KWLO/2025-08-04").json());
        assertThat(rows.get("AMD").get("sold").asInt()).isEqualTo(2);
        assertThat(rows.get("AAA").get("sold").asInt()).isEqualTo(2);
        assertThat(rows.get("ROS").get("sold").asInt()).isEqualTo(2);
        assertThat(rows.get("MID").get("sold").asInt()).isZero();
    }

    @Test
    void theHourReportRunsTheWholeDay() {
        traffic.post("/logs/build/2025-08-04");
        JsonNode body = reader.get("/avails/KWLO/2025-08-04/hours").json();
        assertThat(body.get("rows")).hasSize(24);
        assertThat(body.get("rows").get(0).get("label").asText()).isEqualTo("12:00a");
        assertThat(body.get("rows").get(7).get("label").asText()).isEqualTo("7:00a");
    }

    @Test
    void theThinnestHoursCanBeAskedForOnTheirOwn() {
        anOrder(20);
        JsonNode body =
                reader.get("/avails/KWLO/2025-08-04/hours", "thinnest", "3").json();
        assertThat(body.get("rows")).hasSize(3);
        assertThat(body.get("rows").get(0).get("open").asInt())
                .isLessThanOrEqualTo(body.get("rows").get(1).get("open").asInt());
    }

    @Test
    void askingForMoreThinnestHoursThanThereAreIsRefused() {
        traffic.post("/logs/build/2025-08-04");
        assertThat(reader.get("/avails/KWLO/2025-08-04/hours", "thinnest", "99").status())
                .isEqualTo(422);
    }

    @Test
    void aTranslatorReportsItsParentsInventory() {
        anOrder();
        assertThat(reader.get("/avails/KWLO-LP/2025-08-04")
                        .json()
                        .get("station")
                        .asText())
                .isEqualTo("KWLO");
    }

    @Test
    void aDayNobodyBuiltIsA404() {
        Http.Answer response = reader.get("/avails/KWLO/2025-08-04");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("not_found");
    }

    @Test
    void aWeekRollsTheDaysUpAndGivesOneRowEach() {
        anOrder();
        JsonNode body = reader.get("/avails/KWLO/week/2025-08-06").json();
        assertThat(body.get("week").asText()).isEqualTo("week of 2025-08-04");
        assertThat(body.get("first").asText()).isEqualTo("2025-08-04");
        assertThat(body.get("last").asText()).isEqualTo("2025-08-10");
        assertThat(body.get("days")).hasSize(7);
        assertThat(body.get("days").get(0).get("label").asText()).isEqualTo("Mon 08/04");
        assertThat(body.get("units").asInt()).isEqualTo(7 * 24 * 24);
        assertThat(body.get("sold").asInt()).isEqualTo(10);
        assertThat(body.get("open").asInt()).isEqualTo(body.get("units").asInt() - 10);
    }

    @Test
    void aWeekWithNoLogsBuiltIsA404() {
        Http.Answer response = reader.get("/avails/KWLO/week/2025-09-01");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("message").asText()).contains("no logs built");
    }

    @Test
    void aRequestWithNoKeyGetsNowhere() {
        anOrder();
        assertThat(new Http(mvc).get("/avails/KWLO/2025-08-04").status()).isEqualTo(401);
    }
}
