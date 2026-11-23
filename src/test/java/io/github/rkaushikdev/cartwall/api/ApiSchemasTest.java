package io.github.rkaushikdev.cartwall.api;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.api.Schemas.LineIn;
import io.github.rkaushikdev.cartwall.api.Schemas.LineOut;
import io.github.rkaushikdev.cartwall.api.Schemas.OrderIn;
import io.github.rkaushikdev.cartwall.api.Schemas.OrderOut;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.OrderStatus;
import io.github.rkaushikdev.cartwall.domain.SpotError;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ApiSchemasTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final ObjectMapper MAPPER = Json.mapper();

    private static ValidatorFactory validators;
    private static Validator validator;

    @BeforeAll
    static void openAValidator() {
        validators = Validation.buildDefaultValidatorFactory();
        validator = validators.getValidator();
    }

    @AfterAll
    static void closeTheValidator() {
        validators.close();
    }

    /** A line body the way a client writes one, with {@code changes} laid over the defaults. */
    static Map<String, Object> numberedLineBody(int number, Object... changes) {
        Map<String, Object> flight = new LinkedHashMap<>();
        flight.put("first", "2025-08-04");
        flight.put("last", "2025-08-29");
        flight.put("days", "M-F");
        flight.put("per_week", 12);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", number);
        body.put("station", "KWLO");
        body.put("daypart", "AMD");
        body.put("grade", "fixed");
        body.put("length", ":30");
        body.put("rate", "60.00");
        body.put("flight", flight);
        for (int at = 0; at + 1 < changes.length; at += 2) {
            body.put((String) changes[at], changes[at + 1]);
        }
        return body;
    }

    static Map<String, Object> aLineBody(Object... changes) {
        return numberedLineBody(1, changes);
    }

    static Map<String, Object> anOrderBody(Object... changes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", "25-1044");
        body.put("advertiser", "HYVEE");
        body.put("written", "2025-07-21");
        body.put("salesperson", "DHOLM");
        body.put("lines", List.of(aLineBody()));
        for (int at = 0; at + 1 < changes.length; at += 2) {
            body.put((String) changes[at], changes[at + 1]);
        }
        return body;
    }

    private static LineIn lineIn(Map<String, Object> body) {
        return BodyRejected.read(MAPPER, validator, body, LineIn.class);
    }

    private static OrderIn orderIn(Map<String, Object> body) {
        return BodyRejected.read(MAPPER, validator, body, OrderIn.class);
    }

    private static String why(BodyRejected rejected) {
        return rejected.problems().get(0).why();
    }

    private static Line aLine(int number, Money rate, List<String> carts) {
        return new Line(
                number,
                "KWLO",
                "AMD",
                FIXED,
                THIRTY,
                rate,
                Flight.build(MONDAY, LocalDate.of(2025, 8, 29), "M-F", 12),
                carts);
    }

    private static Line aLine() {
        return aLine(1, Money.dollars(60), List.of());
    }

    @Test
    void moneyCrossesAsAStringInDollars() {
        LineIn line = lineIn(aLineBody("rate", "60.00"));
        assertThat(line.rate()).isEqualTo("60.00");
        assertThat(Amounts.moneyOf(line.rate())).isEqualTo(Money.dollars(60));
    }

    @Test
    void aJsonNumberForMoneyIsRefusedLoudly() {
        assertThatThrownBy(() -> lineIn(aLineBody("rate", 60.0)))
                .isInstanceOfSatisfying(BodyRejected.class, rejected -> assertThat(why(rejected))
                        .contains("send money as a string"));
    }

    @Test
    void aWholeDollarIntegerIsAcceptedAndNormalised() {
        assertThat(lineIn(aLineBody("rate", 60)).rate()).isEqualTo("60.00");
        assertThat(lineIn(aLineBody("rate", new BigDecimal("60.5"))).rate()).isEqualTo("60.50");
    }

    @Test
    void somethingThatIsNotAnAmount() {
        assertThatThrownBy(() -> lineIn(aLineBody("rate", "sixty dollars")))
                .isInstanceOfSatisfying(BodyRejected.class, rejected -> assertThat(why(rejected))
                        .contains("is not an amount"));
        assertThatThrownBy(() -> lineIn(aLineBody("rate", null)))
                .isInstanceOfSatisfying(BodyRejected.class, rejected -> assertThat(why(rejected))
                        .contains("is not an amount"));
    }

    @Test
    void anAmountWithACommaOrASignIsStillAnAmount() {
        assertThat(Amounts.moneyOf(lineIn(aLineBody("rate", "$1,250.00")).rate()))
                .isEqualTo(Money.dollars(1250));
    }

    @Test
    void aLengthCrossesTheWayTheLogPrintsIt() {
        assertThat(lineIn(aLineBody("length", ":30")).length()).isEqualTo(":30");
        assertThat(lineIn(aLineBody("length", "1:00")).length()).isEqualTo("1:00");
        assertThat(lineIn(aLineBody("length", 30)).length()).isEqualTo(":30");
        assertThat(Amounts.lengthOf(":60")).isEqualTo(SIXTY);
    }

    @Test
    void aLengthThatIsNotALengthIsRefusedAtTheBoundary() {
        assertThatThrownBy(() -> lineIn(aLineBody("length", null))).isInstanceOf(BodyRejected.class);
        LineIn body = lineIn(aLineBody("length", ":25"));
        assertThatThrownBy(body::toDomain).isInstanceOf(SpotError.class).hasMessageContaining("not sold here");
    }

    @Test
    void aLineBodyTurnsIntoALine() {
        Line line = lineIn(aLineBody()).toDomain();
        assertThat(line).isInstanceOf(Line.class);
        assertThat(line.rate()).isEqualTo(Money.dollars(60));
        assertThat(line.length()).isEqualTo(THIRTY);
        assertThat(line.grade()).isSameAs(FIXED);
        assertThat(line.spots()).isEqualTo(48);
    }

    @Test
    void aFlightNeedsAtLeastOneAWeek() {
        Map<String, Object> body = aLineBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> flight = (Map<String, Object>) body.get("flight");
        flight.put("per_week", 0);
        assertThatThrownBy(() -> lineIn(body)).isInstanceOf(BodyRejected.class);
    }

    @Test
    void aLineNumberBelowOneIsRefused() {
        assertThatThrownBy(() -> lineIn(numberedLineBody(0))).isInstanceOf(BodyRejected.class);
    }

    @Test
    void aFieldNobodyDeclaredIsAnErrorAndNotAShrug() {
        assertThatThrownBy(() -> lineIn(aLineBody("colour", "blue")))
                .isInstanceOfSatisfying(BodyRejected.class, rejected -> assertThat(why(rejected))
                        .contains("Extra inputs are not permitted"));
        assertThatThrownBy(() -> orderIn(anOrderBody("notes", "call me")))
                .isInstanceOfSatisfying(BodyRejected.class, rejected -> assertThat(why(rejected))
                        .contains("Extra inputs are not permitted"));
    }

    @Test
    void anOrderBodyTurnsIntoAnOrder() {
        OrderIn order =
                orderIn(anOrderBody("lines", List.of(numberedLineBody(1), numberedLineBody(2, "station", "KCRR"))));
        Order domain = order.toDomain();
        assertThat(domain).isInstanceOf(Order.class);
        assertThat(domain.size()).isEqualTo(2);
        assertThat(domain.status()).isSameAs(OrderStatus.PENDING);
        assertThat(domain.value()).isEqualTo(Money.dollars(60 * 96));
    }

    @Test
    void anOrderNeedsANumberThatIsNotBlank() {
        assertThatThrownBy(() -> orderIn(anOrderBody("number", "   ")))
                .isInstanceOfSatisfying(BodyRejected.class, rejected -> assertThat(why(rejected))
                        .contains("an order needs a number"));
    }

    @Test
    void anOrderWithNoLinesIsALegitimateBody() {
        Order order = orderIn(anOrderBody("lines", List.of())).toDomain();
        assertThat(order.size()).isZero();
    }

    @Test
    void aLineComesBackWithItsMoneyAsStrings() {
        LineOut out = LineOut.of(aLine());
        assertThat(out.rate()).isEqualTo("60.00");
        assertThat(out.value()).isEqualTo("2880.00");
        assertThat(out.length()).isEqualTo(":30");
        assertThat(out.spots()).isEqualTo(48);
    }

    @Test
    void aFlightBodyCanNameTheWeeksItGoesDark() {
        Map<String, Object> flight = new LinkedHashMap<>();
        flight.put("first", "2025-08-04");
        flight.put("last", "2025-08-29");
        flight.put("days", "M-F");
        flight.put("per_week", 12);
        flight.put("dark", List.of("2025-08-13"));
        Line line = lineIn(aLineBody("flight", flight)).toDomain();
        assertThat(line.flight().dark()).containsExactly(new BroadcastWeek(LocalDate.of(2025, 8, 11)));
        assertThat(line.spots()).isEqualTo(36);
        LineOut out = LineOut.of(line);
        assertThat(out.flight().dark()).containsExactly(LocalDate.of(2025, 8, 11));
        assertThat(out.flight().spots()).isEqualTo(36);
        assertThat(LineOut.of(aLine()).flight().dark()).isEmpty();
    }

    @Test
    void aFlightBodyCanCapTheSpotsADay() {
        Map<String, Object> flight = new LinkedHashMap<>();
        flight.put("first", "2025-08-04");
        flight.put("last", "2025-08-29");
        flight.put("days", "M-F");
        flight.put("per_week", 12);
        flight.put("daily_cap", 3);
        Line line = lineIn(aLineBody("flight", flight)).toDomain();
        assertThat(line.flight().dailyCap()).isEqualTo(3);
        assertThat(LineOut.of(line).flight().dailyCap()).isEqualTo(3);
        assertThat(lineIn(aLineBody()).toDomain().flight().isCapped()).isFalse();
    }

    @Test
    void aLineComesBackWithItsFlightWrittenOut() {
        LineOut out = LineOut.of(aLine());
        assertThat(out.flight().days()).isEqualTo("M-F");
        assertThat(out.flight().perWeek()).isEqualTo(12);
        assertThat(out.flight().spots()).isEqualTo(48);
        assertThat(out.flight().first()).isEqualTo(MONDAY);
    }

    @Test
    void aLinesCartsComeBackInOrder() {
        assertThat(LineOut.of(aLine(1, Money.dollars(60), List.of("C1204", "C1205")))
                        .carts())
                .containsExactly("C1204", "C1205");
    }

    @Test
    void anOrderComesBackWithItsTotalAsAString() {
        Order order = new Order(
                "25-1044",
                "HYVEE",
                LocalDate.of(2025, 7, 21),
                "DHOLM",
                List.of(aLine(), aLine(2, Money.parse("32.50"), List.of())),
                OrderStatus.PENDING,
                Optional.of("MOP"),
                "back to school");
        OrderOut out = OrderOut.of(order);
        assertThat(out.value()).isEqualTo("4440.00");
        assertThat(out.spots()).isEqualTo(96);
        assertThat(out.campaign()).isEqualTo("back to school");
        assertThat(out.agency()).contains("MOP");
        assertThat(out.lines()).extracting(LineOut::number).containsExactly(1, 2);
    }

    @Test
    void theSerialisedBodyHasNoJsonNumbersWhereMoneyBelongs() {
        Order order = new Order("25-1044", "HYVEE", LocalDate.of(2025, 7, 21), "DHOLM", List.of(aLine()));
        JsonNode body = MAPPER.valueToTree(OrderOut.of(order));
        assertThat(body.get("value").isTextual()).isTrue();
        assertThat(body.get("lines").get(0).get("rate").isTextual()).isTrue();
        assertThat(body.get("lines").get(0).get("spots").isInt()).isTrue();
    }

    @Test
    void theAnnotationIsReusableWhereverAnAmountAppears() {
        List<Class<?>> rates = new ArrayList<>();
        for (Class<?> shape : List.of(LineIn.class, LineOut.class)) {
            Arrays.stream(shape.getRecordComponents())
                    .filter(component -> component.getName().equals("rate"))
                    .map(RecordComponent::getType)
                    .forEach(rates::add);
        }
        assertThat(rates).containsExactly(String.class, String.class);
    }
}
