package io.github.rkaushikdev.cartwall.playout;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.TimeError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading back what the automation says it actually played.
 *
 * <p>The as-run file is a different format from the log file and that is not an oversight: the
 * as-run export was added a decade later by different people, and it is comma separated with a
 * header row. Two formats is annoying and pretending they are one is worse, so they stay apart.
 *
 * <p>The backup playout writes the same facts its own way: tab separated, its own names for the
 * columns in its own order, US dates and lengths in minutes and seconds. A night on the backup
 * still has to be posted, so the header is read for what each column is called rather than
 * checked against one spelling, and the file is tab separated when its header is.
 */
public final class AsRunFile {

    /** The header the automation writes. Read, and checked, never assumed. */
    public static final List<String> HEADER = List.of("date", "time", "cart", "title", "length", "status", "reference");

    /** What the backup playout calls each column, and the column it means. */
    static final Map<String, String> ALIASES = Map.of(
            "air date", "date",
            "air time", "time",
            "start", "time",
            "description", "title",
            "duration", "length",
            "dur", "length",
            "result", "status",
            "traffic id", "reference",
            "ref", "reference");

    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ROOT);

    private static final DateTimeFormatter NAMED = DateTimeFormatter.ofPattern("yyMMdd", Locale.ROOT);

    private AsRunFile() {}

    /** The whole file, in the order the automation wrote it. */
    public static List<AsRun> parse(String text) {
        String firstLine =
                text.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        List<List<String>> records = Csv.records(text, firstLine.indexOf('\t') >= 0 ? '\t' : ',');
        if (records.isEmpty()) {
            throw new AsRunError("the as-run file is empty");
        }
        List<String> header = records.get(0).stream().map(AsRunFile::columnName).toList();
        if (!header.containsAll(HEADER) || header.stream().distinct().count() != header.size()) {
            throw new AsRunError(
                    "the as-run header is " + String.join(", ", header) + ", expected " + String.join(", ", HEADER),
                    Details.of("header", header));
        }
        List<AsRun> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < header.size() && index < record.size(); index++) {
                row.put(header.get(index), record.get(index));
            }
            rows.add(parseLine(row));
        }
        return List.copyOf(rows);
    }

    /** What a header cell names, in the main automation's words. */
    static String columnName(String written) {
        String name = written.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return ALIASES.getOrDefault(name, name);
    }

    /** One line, by column name. A short line is missing the columns it did not reach. */
    public static AsRun parseLine(Map<String, String> row) {
        List<String> missing =
                HEADER.stream().filter(name -> row.get(name) == null).toList();
        if (!missing.isEmpty()) {
            throw new AsRunError(
                    "the as-run line is missing " + String.join(", ", missing), Details.of("missing", missing));
        }
        LocalDate day = date(row.get("date"));
        AirTime at;
        try {
            at = AirTime.parse(row.get("time"));
        } catch (TimeError notATime) {
            throw new AsRunError("'" + row.get("time") + "' is not an air time");
        }
        return new AsRun(
                day,
                at,
                row.get("cart").strip(),
                row.get("title").strip(),
                length(row.get("length")),
                Played.written(row.get("status")),
                row.get("reference").strip());
    }

    /** The name the watch folder writes the as-run under, such as {@code KWLO250804.ASR}. */
    public static String filename(String station, LocalDate day) {
        if (station.isEmpty()) {
            throw new AsRunError("an as-run file needs a station");
        }
        return station.replace("-", "") + NAMED.format(day) + ".ASR";
    }

    /** The lines that carry one of our references. The rest is programming. */
    public static List<AsRun> spotsOnly(List<AsRun> rows) {
        return rows.stream().filter(row -> row.key().isPresent()).toList();
    }

    /** How many lines of each status, by status name. */
    public static Map<Played, Integer> byStatus(List<AsRun> rows) {
        Map<Played, Integer> tally = new EnumMap<>(Played.class);
        rows.forEach(row -> tally.merge(row.status(), 1, Integer::sum));
        Map<Played, Integer> ordered = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    /** A wall clock moment, for anything that wants to sort across days. */
    public static LocalDateTime stamp(LocalDate day, AirTime at) {
        return day.atTime(at.hour(), at.minute(), at.second());
    }

    /** {@code 2025-08-04} from the automation, {@code 08/04/2025} from the backup. */
    static LocalDate date(String text) {
        String cleaned = text.strip();
        try {
            return cleaned.contains("/") ? LocalDate.parse(cleaned, US_DATE) : LocalDate.parse(cleaned);
        } catch (DateTimeParseException notADate) {
            throw new AsRunError("'" + text + "' is not a date");
        }
    }

    /**
     * The automation writes seconds with one decimal, or bare tenths; the backup writes minutes and
     * seconds, {@code 0:30.0}.
     */
    static Duration length(String text) {
        String cleaned = text.strip();
        if (cleaned.isEmpty()) {
            throw new AsRunError("an as-run line has no length");
        }
        if (cleaned.contains(":")) {
            try {
                return Duration.parse(cleaned);
            } catch (TimeError notALength) {
                throw new AsRunError("'" + text + "' is not a length");
            }
        }
        int point = cleaned.indexOf('.');
        if (point >= 0) {
            String whole = cleaned.substring(0, point);
            String tenth = cleaned.substring(point + 1);
            if (!isDigits(whole) || tenth.length() != 1 || !isDigits(tenth)) {
                throw new AsRunError("'" + text + "' is not a length");
            }
            return new Duration(Integer.parseInt(whole) * 10 + Integer.parseInt(tenth));
        }
        if (!isDigits(cleaned)) {
            throw new AsRunError("'" + text + "' is not a length");
        }
        return new Duration(Integer.parseInt(cleaned));
    }

    private static boolean isDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isDigit);
    }
}
