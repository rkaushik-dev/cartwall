package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Daypart;
import io.github.rkaushikdev.cartwall.domain.Quote;
import io.github.rkaushikdev.cartwall.domain.RuleBook;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.domain.Weekday;
import io.github.rkaushikdev.cartwall.scheduling.Avail;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What is left to sell, which is the only report the sales manager opens.
 *
 * <p>By daypart, which prices the hole as well as counting it, because forty open units in
 * overnight and four in drive are not the same inventory; by hour, which is where a schedule will
 * jam; and by day. Dayparts overlap on purpose, so the daypart rows do not add up to the day. Value
 * is at card, for a thirty: a ceiling, not a forecast. Unless the seller names a class it is the
 * card's floor, the most preemptible class quoted; named, it is that class, and a daypart the card
 * does not sell at that class has a hole with no price on it.
 */
public final class Avails {

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEE MM/dd", Locale.ENGLISH);

    private Avails() {}

    /** {@code 12:00a}, {@code 7:00a}, {@code 1:00p}: the way the report prints an hour. */
    public static String hourLabel(int hour) {
        String meridiem = hour < 12 ? "a" : "p";
        int printed = hour % 12 == 0 ? 12 : hour % 12;
        return printed + ":00" + meridiem;
    }

    /**
     * One row per daypart the station sells, narrowest first.
     *
     * <p>Rows overlap. Morning drive is inside AAA is inside ROS, and adding the three together
     * counts the same unit three times.
     */
    public static List<AvailsRow> byDaypart(DayBoard board, RuleBook book) {
        return byDaypart(board, book, Optional.empty());
    }

    /** One row per daypart, the hole priced at {@code grade} rather than at the card's floor. */
    public static List<AvailsRow> byDaypart(DayBoard board, RuleBook book, ClassOfTime grade) {
        return byDaypart(board, book, Optional.of(grade));
    }

    private static List<AvailsRow> byDaypart(DayBoard board, RuleBook book, Optional<ClassOfTime> grade) {
        if (!board.station().equals(book.call())) {
            throw new ReportError(book.call() + "'s card was handed " + board.station() + "'s board");
        }
        Weekday weekday = Weekday.of(board.day());
        List<Daypart> narrowestFirst = book.dayparts().dayparts().stream()
                .sorted(Comparator.comparingInt(part -> part.cells().size()))
                .toList();
        List<AvailsRow> rows = new ArrayList<>();
        for (Daypart daypart : narrowestFirst) {
            int units = 0;
            int sold = 0;
            for (Avail avail : board.avails()) {
                if (daypart.holds(weekday, avail.startsAt())) {
                    units += avail.units();
                    sold += avail.usedUnits();
                }
            }
            if (units == 0) {
                continue;
            }
            Optional<Quote> quoted = grade.map(named -> book.card().find(daypart.code(), named))
                    .orElseGet(() -> book.card().floor(daypart.code()));
            Money each = quoted.map(quote -> quote.atLength(SpotLength.THIRTY)).orElse(Money.ZERO);
            rows.add(new AvailsRow(daypart.code(), units, sold, each.times(units - sold)));
        }
        return List.copyOf(rows);
    }

    /** One row per hour of the day the station is selling, in air order. */
    public static List<AvailsRow> byHour(DayBoard board) {
        List<AvailsRow> rows = new ArrayList<>();
        for (int hour : board.hours()) {
            List<Avail> avails = board.inHour(hour);
            int units = avails.stream().mapToInt(Avail::units).sum();
            int sold = avails.stream().mapToInt(Avail::usedUnits).sum();
            rows.add(new AvailsRow(hourLabel(hour), units, sold));
        }
        return List.copyOf(rows);
    }

    /** One row per date, in order. The boards must all be one station. */
    public static List<AvailsRow> byDay(List<DayBoard> boards) {
        Set<String> stations = new HashSet<>();
        boards.forEach(board -> stations.add(board.station()));
        if (stations.size() > 1) {
            throw new ReportError("a day report covers one station, not " + stations.size());
        }
        return boards.stream()
                .sorted(Comparator.comparing(DayBoard::day))
                .map(board -> new AvailsRow(DAY_LABEL.format(board.day()), board.totalUnits(), board.usedUnits()))
                .toList();
    }

    /** The three hours with the least left. */
    public static List<AvailsRow> thinnestHours(DayBoard board) {
        return thinnestHours(board, 3);
    }

    /** The hours with the least left, which is where a schedule will jam. */
    public static List<AvailsRow> thinnestHours(DayBoard board, int howMany) {
        return byHour(board).stream()
                .filter(row -> row.units() > 0)
                .sorted(Comparator.comparingInt(AvailsRow::open)
                        .thenComparingDouble(row -> -row.sellout())
                        .thenComparing(AvailsRow::label))
                .limit(howMany)
                .toList();
    }

    public static int weekOpenUnits(List<DayBoard> boards) {
        return boards.stream().mapToInt(DayBoard::freeUnits).sum();
    }

    /** What the open units in one daypart would fetch at card across a week. */
    public static Money weekValue(List<DayBoard> boards, Map<LocalDate, RuleBook> books, String daypart) {
        List<Money> amounts = new ArrayList<>();
        for (DayBoard board : boards) {
            RuleBook book = books.get(board.day());
            if (book == null) {
                throw new ReportError("no rules were given for " + board.day());
            }
            for (AvailsRow row : byDaypart(board, book)) {
                if (row.label().equals(daypart)) {
                    amounts.add(row.value());
                }
            }
        }
        return Money.total(amounts);
    }
}
