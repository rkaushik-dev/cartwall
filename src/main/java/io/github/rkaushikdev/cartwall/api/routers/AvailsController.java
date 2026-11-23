package io.github.rkaushikdev.cartwall.api.routers;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.api.Amounts;
import io.github.rkaushikdev.cartwall.api.Requires;
import io.github.rkaushikdev.cartwall.api.Role;
import io.github.rkaushikdev.cartwall.api.Work;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.RuleBook;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.reports.Avails;
import io.github.rkaushikdev.cartwall.reports.AvailsRow;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.services.Houses;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is left to sell, for the sellers' laptops. Every route is a read behind the reader role.
 *
 * <p>The daypart rows overlap and do not add up, because morning drive is inside AAA is inside
 * ROS. The response says so in a field rather than in a comment, because a client building a chart
 * out of this would otherwise stack them.
 */
@RestController
@RequestMapping("/avails")
@Tag(name = "avails")
public class AvailsController {

    private final Work work;

    public AvailsController(Work work) {
        this.work = work;
    }

    /** One row of inventory. */
    public record RowOut(String label, int units, int sold, int open, double sellout, String value) {}

    /** A day by daypart. The rows overlap: morning drive is inside AAA is inside ROS. */
    public record DaypartsOut(String station, LocalDate day, boolean overlapping, List<RowOut> rows) {}

    /** A day hour by hour. */
    public record HoursOut(String station, LocalDate day, List<RowOut> rows) {}

    /** A broadcast week, one row a day. */
    public record WeekOut(
            String station,
            String week,
            LocalDate first,
            LocalDate last,
            int units,
            int sold,
            int open,
            double sellout,
            List<RowOut> days) {}

    /** What is open on one day, one row per daypart, narrowest first. */
    @GetMapping("/{station}/{day}")
    @Requires(Role.READER)
    public DaypartsOut byDaypart(
            @PathVariable String station,
            @PathVariable LocalDate day,
            @Parameter(description = "Price the holes at this class rather than the card's floor")
                    @RequestParam(required = false)
                    ClassOfTime grade) {
        return work.apply(uow -> {
            House house = Houses.houseFrom(uow, Optional.of(day));
            String call = house.loggingCall(station);
            DayBoard board = new LogRepository(uow.entityManager())
                    .board(house.programming().get(call), day);
            RuleBook book = house.book(call, day);
            List<AvailsRow> rows = grade == null ? Avails.byDaypart(board, book) : Avails.byDaypart(board, book, grade);
            return new DaypartsOut(call, day, true, rows(rows));
        });
    }

    /** What is open hour by hour, or only the hours a schedule will jam in. */
    @GetMapping("/{station}/{day}/hours")
    @Requires(Role.READER)
    public HoursOut byHour(
            @PathVariable String station,
            @PathVariable LocalDate day,
            @Parameter(description = "Only the N thinnest hours")
                    @RequestParam(defaultValue = "0")
                    @Min(value = 0, message = "Input should be greater than or equal to 0")
                    @Max(value = 24, message = "Input should be less than or equal to 24")
                    int thinnest) {
        return work.apply(uow -> {
            House house = Houses.houseFrom(uow, Optional.of(day));
            String call = house.loggingCall(station);
            DayBoard board = new LogRepository(uow.entityManager())
                    .board(house.programming().get(call), day);
            List<AvailsRow> rows = thinnest > 0 ? Avails.thinnestHours(board, thinnest) : Avails.byHour(board);
            return new HoursOut(call, day, rows(rows));
        });
    }

    /** One row a day for the broadcast week {@code weekOf} falls in. */
    @GetMapping("/{station}/week/{weekOf}")
    @Requires(Role.READER)
    public WeekOut byWeek(@PathVariable String station, @PathVariable LocalDate weekOf) {
        return work.apply(uow -> {
            BroadcastWeek week = BroadcastWeek.of(weekOf);
            House house = Houses.houseFrom(uow, Optional.of(week.start()));
            String call = house.loggingCall(station);
            ClockGrid grid = house.programming().get(call);
            LogRepository logs = new LogRepository(uow.entityManager());

            List<DayBoard> boards = week.days().stream()
                    .filter(day -> logs.exists(call, day))
                    .map(day -> logs.board(grid, day))
                    .toList();
            if (boards.isEmpty()) {
                throw new LookupFailure(
                        call + " has no logs built for " + week, Details.of("station", call, "week", week.toString()));
            }
            List<AvailsRow> rows = Avails.byDay(boards);
            int units = rows.stream().mapToInt(AvailsRow::units).sum();
            int sold = rows.stream().mapToInt(AvailsRow::sold).sum();
            return new WeekOut(
                    call,
                    week.toString(),
                    week.start(),
                    week.end(),
                    units,
                    sold,
                    units - sold,
                    units == 0 ? 0.0 : rounded((double) sold / units),
                    rows(rows));
        });
    }

    private static List<RowOut> rows(List<AvailsRow> rows) {
        return rows.stream()
                .map(row -> new RowOut(
                        row.label(),
                        row.units(),
                        row.sold(),
                        row.open(),
                        rounded(row.sellout()),
                        Amounts.amount(row.value())))
                .toList();
    }

    /** To four places, a tie going to the even digit, as the exact value of the double has it. */
    static double rounded(double fraction) {
        return new BigDecimal(fraction).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }
}
