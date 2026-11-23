package io.github.rkaushikdev.cartwall.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.api.Amounts.AmountJson;
import io.github.rkaushikdev.cartwall.api.Amounts.LengthJson;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.OrderStatus;
import io.github.rkaushikdev.cartwall.domain.Weekday;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The order shapes that go over the wire.
 *
 * <p>The shapes are deliberately not the domain objects. An {@link Order} knows how to confirm
 * itself and an {@link OrderOut} does not, and a shape that grew methods would be a second domain
 * model in a place nobody looks for one. Amounts and lengths cross as strings; see {@link Amounts}.
 */
public final class Schemas {

    /** What a field left out of a body is told. */
    static final String REQUIRED = "Field required";

    private Schemas() {}

    /** A flight as a body carries it. */
    public record FlightIn(
            @NotNull(message = REQUIRED) LocalDate first,
            @NotNull(message = REQUIRED) LocalDate last,
            @NotNull(message = REQUIRED) String days,
            @NotNull(message = REQUIRED)
                    @Min(value = 1, message = "Input should be greater than or equal to 1")
                    @Max(value = 500, message = "Input should be less than or equal to 500")
                    Integer perWeek,
            List<LocalDate> dark,
            @Min(value = 0, message = "Input should be greater than or equal to 0") Integer dailyCap) {

        public FlightIn {
            dark = dark == null ? List.of() : List.copyOf(dark);
            dailyCap = dailyCap == null ? 0 : dailyCap;
        }

        /** The flight, dark in the week holding each date given, capped at so many a day. */
        public Flight toDomain() {
            return Flight.build(first, last, days, perWeek).goingDark(dark).cappedAt(dailyCap);
        }
    }

    /** One line of an order as a body carries it. */
    public record LineIn(
            @NotNull(message = REQUIRED) @Min(value = 1, message = "Input should be greater than or equal to 1")
                    Integer number,
            @NotNull(message = REQUIRED) String station,
            @NotNull(message = REQUIRED) String daypart,
            @NotNull(message = REQUIRED) ClassOfTime grade,
            @NotNull(message = REQUIRED) @JsonDeserialize(using = LengthJson.class) String length,
            @NotNull(message = REQUIRED) @JsonDeserialize(using = AmountJson.class) String rate,
            @NotNull(message = REQUIRED) @Valid FlightIn flight,
            List<String> carts) {

        public LineIn {
            carts = carts == null ? List.of() : List.copyOf(carts);
        }

        public Line toDomain() {
            return new Line(
                    number,
                    station,
                    daypart,
                    grade,
                    Amounts.lengthOf(length),
                    Amounts.moneyOf(rate),
                    flight.toDomain(),
                    carts);
        }
    }

    /** An order as a body carries it. */
    public record OrderIn(
            @NotNull(message = REQUIRED)
                    @Pattern(regexp = "(?s).*\\S.*", message = "Value error, an order needs a number")
                    String number,
            @NotNull(message = REQUIRED) String advertiser,
            @NotNull(message = REQUIRED) LocalDate written,
            @NotNull(message = REQUIRED) String salesperson,
            String agency,
            String campaign,
            List<@Valid LineIn> lines) {

        public OrderIn {
            campaign = campaign == null ? "" : campaign;
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        public Order toDomain() {
            return new Order(
                    number,
                    advertiser,
                    written,
                    salesperson,
                    lines.stream().map(LineIn::toDomain).toList(),
                    OrderStatus.PENDING,
                    Optional.ofNullable(agency),
                    campaign);
        }
    }

    /** A flight as a response writes it out. */
    public record FlightOut(
            LocalDate first, LocalDate last, String days, int perWeek, int spots, List<LocalDate> dark, int dailyCap) {}

    /** One line of an order as a response writes it out, its money as strings. */
    public record LineOut(
            int number,
            String station,
            String daypart,
            ClassOfTime grade,
            String length,
            String rate,
            String value,
            int spots,
            FlightOut flight,
            List<String> carts) {

        public static LineOut of(Line line) {
            return new LineOut(
                    line.number(),
                    line.station(),
                    line.daypart(),
                    line.grade(),
                    line.length().toString(),
                    Amounts.amount(line.rate()),
                    Amounts.amount(line.value()),
                    line.spots(),
                    new FlightOut(
                            line.flight().first(),
                            line.flight().last(),
                            Weekday.showDays(line.flight().days()),
                            line.flight().perWeek(),
                            line.flight().spots(),
                            line.flight().dark().stream()
                                    .map(BroadcastWeek::start)
                                    .toList(),
                            line.flight().dailyCap()),
                    line.carts());
        }
    }

    /** An order as a response writes it out, its total as a string. */
    public record OrderOut(
            String number,
            String advertiser,
            Optional<String> agency,
            LocalDate written,
            String salesperson,
            String campaign,
            OrderStatus status,
            int spots,
            String value,
            List<LineOut> lines) {

        public static OrderOut of(Order order) {
            return new OrderOut(
                    order.number(),
                    order.advertiser(),
                    order.agency(),
                    order.written(),
                    order.salesperson(),
                    order.campaign(),
                    order.status(),
                    order.spots(),
                    Amounts.amount(order.value()),
                    order.lines().stream().map(LineOut::of).toList());
        }
    }
}
