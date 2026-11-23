package io.github.rkaushikdev.cartwall.playout;

import static io.github.rkaushikdev.cartwall.playout.AsRunFile.byStatus;
import static io.github.rkaushikdev.cartwall.playout.AsRunFile.filename;
import static io.github.rkaushikdev.cartwall.playout.AsRunFile.parse;
import static io.github.rkaushikdev.cartwall.playout.AsRunFile.spotsOnly;
import static io.github.rkaushikdev.cartwall.playout.AsRunFile.stamp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AsrunTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final String HEADER = "date,time,cart,title,length,status,reference";

    static String aFile(String... lines) {
        List<String> all = new ArrayList<>();
        all.add(HEADER);
        all.addAll(List.of(lines));
        return String.join("\n", all) + "\n";
    }

    /** One as-run line; every column can be swapped out. */
    static final class ALine {
        private String at = "07:10:00";
        private String cart = "C1204";
        private String length = "30.0";
        private String status = "played";
        private String reference = "25-1044-1";

        ALine at(String at) {
            this.at = at;
            return this;
        }

        ALine cart(String cart) {
            this.cart = cart;
            return this;
        }

        ALine length(String length) {
            this.length = length;
            return this;
        }

        ALine status(String status) {
            this.status = status;
            return this;
        }

        ALine reference(String reference) {
            this.reference = reference;
            return this;
        }

        @Override
        public String toString() {
            return "2025-08-04," + at + "," + cart + ",Hy-Vee sale," + length + "," + status + "," + reference;
        }
    }

    static ALine aLine() {
        return new ALine();
    }

    private static AsRun only(ALine line) {
        return parse(aFile(line.toString())).get(0);
    }

    @Test
    void aFileReadsBackLineByLine() {
        List<AsRun> rows = parse(aFile(
                aLine().toString(),
                aLine().at("09:38:00").reference("25-1044-2").toString()));
        assertThat(rows).hasSize(2);
        AsRun first = rows.get(0);
        assertThat(first.day()).isEqualTo(MONDAY);
        assertThat(first.at()).isEqualTo(AirTime.at(7, 10));
        assertThat(first.cart()).isEqualTo("C1204");
        assertThat(first.title()).isEqualTo("Hy-Vee sale");
        assertThat(first.length()).isEqualTo(new Duration(300));
        assertThat(first.status()).isSameAs(Played.PLAYED);
        assertThat(first.reference()).isEqualTo("25-1044-1");
    }

    @Test
    void theHeaderIsCheckedRatherThanAssumed() {
        assertThatThrownBy(() -> parse("date,time,cart\n2025-08-04,07:10:00,C1204\n"))
                .isInstanceOf(AsRunError.class)
                .hasMessageContaining("the as-run header is");
        assertThatThrownBy(() -> parse("")).isInstanceOf(AsRunError.class).hasMessageContaining("is empty");
    }

    @Test
    void aHeaderWithOddSpacingOrCaseIsStillTheHeader() {
        String text = "Date, Time ,CART,Title,Length,Status,Reference\n" + aLine() + "\n";
        assertThat(parse(text)).hasSize(1);
    }

    @Test
    void theStatusesTheAutomationWritesAllMap() {
        Map<String, Played> written = Map.of(
                "play", Played.PLAYED,
                "PLAYED", Played.PLAYED,
                "ok", Played.PLAYED,
                "skip", Played.SKIPPED,
                "error", Played.ERROR,
                "trunc", Played.TRUNCATED);
        written.forEach((text, expected) ->
                assertThat(only(aLine().status(text)).status()).isSameAs(expected));
    }

    @Test
    void aStatusNobodyHasSeenIsKeptRatherThanDropped() {
        AsRun row = only(aLine().status("deferred"));
        assertThat(row.status()).isSameAs(Played.UNKNOWN);
        assertThat(row.aired()).isFalse();
    }

    @Test
    void onlyPlayedAndTruncatedCountAsHavingGoneOut() {
        assertThat(Played.PLAYED.aired()).isTrue();
        assertThat(Played.TRUNCATED.aired()).isTrue();
        assertThat(Played.SKIPPED.aired()).isFalse();
        assertThat(Played.ERROR.aired()).isFalse();
        assertThat(Played.UNKNOWN.aired()).isFalse();
    }

    @Test
    void aLengthComesInAsSecondsAndATenthOrAsBareTenths() {
        assertThat(only(aLine().length("30.0")).length()).isEqualTo(new Duration(300));
        assertThat(only(aLine().length("29.7")).length()).isEqualTo(new Duration(297));
        assertThat(only(aLine().length("300")).length()).isEqualTo(new Duration(300));
        assertThat(only(aLine().length("0.0")).length()).isEqualTo(new Duration(0));
    }

    @Test
    void aLengthThatIsNotALength() {
        for (String bad : List.of("thirty", "30.00", "30.", "-30", "")) {
            assertThatThrownBy(() -> only(aLine().length(bad))).isInstanceOf(AsRunError.class);
        }
    }

    @Test
    void aDateOrATimeThatIsNotOne() {
        assertThatThrownBy(() -> parse(aFile("not a date,07:10:00,C1204,t,30.0,played,25-1044-1")))
                .isInstanceOf(AsRunError.class)
                .hasMessageContaining("is not a date");
        assertThatThrownBy(() -> only(aLine().at("half seven")))
                .isInstanceOf(AsRunError.class)
                .hasMessageContaining("is not an air time");
    }

    @Test
    void aReferenceReadsBackAsAnOrderAndALine() {
        assertThat(only(aLine().reference("25-1044-1")).key()).contains(new LineKey("25-1044", 1));
        assertThat(only(aLine().reference("25-1044-12")).key()).contains(new LineKey("25-1044", 12));
    }

    @Test
    void aLineThatIsNotOursHasNoKey() {
        assertThat(only(aLine().reference("")).key()).isEmpty();
        assertThat(only(aLine().reference("LEGAL ID")).key()).isEmpty();
        assertThat(only(aLine().reference("MUSIC")).key()).isEmpty();
    }

    @Test
    void theProgrammingLinesAreFilteredOutByReference() {
        List<AsRun> rows = parse(aFile(
                aLine().reference("LEGAL ID").cart("LID01").toString(),
                aLine().at("07:10:30").reference("25-1044-1").toString(),
                aLine().at("07:11:00").reference("").cart("SONG").toString()));
        assertThat(rows).hasSize(3);
        List<AsRun> ours = spotsOnly(rows);
        assertThat(ours).hasSize(1);
        assertThat(ours.get(0).key()).contains(new LineKey("25-1044", 1));
    }

    @Test
    void driftIsMeasuredAgainstWhatTheLogSaid() {
        AsRun row = only(aLine().at("07:10:04"));
        assertThat(row.driftFrom(AirTime.at(7, 10))).isEqualTo(Duration.ofSeconds(4));
        assertThat(row.driftFrom(AirTime.at(7, 10, 8))).isEqualTo(Duration.ofSeconds(4));
        assertThat(row.driftFrom(AirTime.at(7, 10, 4))).isEqualTo(new Duration(0));
    }

    @Test
    void aRunIsCountedByStatusForTheMorningReport() {
        List<AsRun> rows = parse(aFile(
                aLine().reference("25-1044-1").toString(),
                aLine().at("08:10:00").reference("25-1044-2").status("skip").toString(),
                aLine().at("09:10:00").reference("25-1044-3").status("error").toString(),
                aLine().at("10:10:00").reference("25-1044-4").toString()));
        assertThat(byStatus(rows)).isEqualTo(Map.of(Played.ERROR, 1, Played.PLAYED, 2, Played.SKIPPED, 1));
        assertThat(byStatus(rows).keySet()).containsExactly(Played.ERROR, Played.PLAYED, Played.SKIPPED);
    }

    @Test
    void aLineReadsTheWayTheOperatorWouldSayIt() {
        assertThat(only(aLine().status("skip"))).hasToString("7:10:00a C1204 skipped");
    }

    @Test
    void theFileIsNamedTheWayTheWatchFolderWritesIt() {
        assertThat(filename("KWLO", MONDAY)).isEqualTo("KWLO250804.ASR");
        assertThat(filename("KOEL-FM", MONDAY)).isEqualTo("KOELFM250804.ASR");
        assertThatThrownBy(() -> filename("", MONDAY))
                .isInstanceOf(AsRunError.class)
                .hasMessageContaining("needs a station");
    }

    @Test
    void aRowCanBeStampedWithAWallClockMomentForSorting() {
        AsRun row = only(aLine().at("07:10:04"));
        assertThat(stamp(row.day(), row.at())).isEqualTo(LocalDateTime.of(2025, 8, 4, 7, 10, 4));
    }

    @Test
    void aFileWithAHeaderAndNothingElseReadsAsNothing() {
        assertThat(parse(HEADER + "\n")).isEmpty();
    }

    @Test
    void aLineMissingAColumnSaysWhichOne() {
        String text = "date,time,cart,title,length,status,reference\n2025-08-04,07:10:00,C1204\n";
        assertThatThrownBy(() -> parse(text))
                .isInstanceOf(AsRunError.class)
                .hasMessageContaining("is missing title, length, status, reference");
    }

    /** A night on the backup playout: its own column names, its own order, tabs between. */
    static String aBackupFile(String... lines) {
        List<String> all = new ArrayList<>();
        all.add("Air Date\tAir Time\tTraffic ID\tCart\tDescription\tResult\tDuration");
        all.addAll(List.of(lines));
        return String.join("\r\n", all) + "\r\n";
    }

    @Test
    void theBackupPlayoutsFileReadsLikeTheMainOne() {
        List<AsRun> rows = parse(aBackupFile(
                "08/04/2025\t07:10:00\t25-1044-1\tC1204\tHy-Vee sale\tPLAYED\t0:30.0",
                "08/04/2025\t09:38:00\t25-1044-2\tC1205\t\"Hy-Vee\tfuel\"\tSKIP\t1:00"));
        assertThat(rows).hasSize(2);
        AsRun first = rows.get(0);
        assertThat(first.day()).isEqualTo(MONDAY);
        assertThat(first.at()).isEqualTo(AirTime.at(7, 10));
        assertThat(first.cart()).isEqualTo("C1204");
        assertThat(first.length()).isEqualTo(new Duration(300));
        assertThat(first.status()).isSameAs(Played.PLAYED);
        assertThat(first.key()).contains(new LineKey("25-1044", 1));
        assertThat(rows.get(1).title()).isEqualTo("Hy-Vee\tfuel");
        assertThat(rows.get(1).length()).isEqualTo(Duration.ofSeconds(60));
        assertThat(rows.get(1).aired()).isFalse();
    }

    @Test
    void theColumnsCanComeInAnyOrderButEachOnlyOnce() {
        String reordered = "reference,cart,date,time,title,status,length,station\n"
                + "25-1044-1,C1204,2025-08-04,07:10:00,Hy-Vee sale,played,30.0,KWLO\n";
        assertThat(parse(reordered)).extracting(AsRun::cart).containsExactly("C1204");
        assertThatThrownBy(() -> parse("date,time,cart,title,length,status,reference,ref\n"))
                .isInstanceOf(AsRunError.class)
                .hasMessageContaining(
                        "the as-run header is date, time, cart, title, length, status, reference, reference");
    }

    @Test
    void aHeaderCellIsReadForWhatItNames() {
        assertThat(AsRunFile.columnName("  Air   Time ")).isEqualTo("time");
        assertThat(AsRunFile.columnName("DUR")).isEqualTo("length");
        assertThat(AsRunFile.columnName("Cart")).isEqualTo("cart");
        assertThat(AsRunFile.columnName("Station")).isEqualTo("station");
    }

    @Test
    void aBackupDateOrLengthThatIsNotOne() {
        assertThatThrownBy(() -> parse(aBackupFile("13/45/2025\t07:10:00\t25-1044-1\tC1204\tx\tPLAYED\t0:30.0")))
                .isInstanceOf(AsRunError.class)
                .hasMessageContaining("'13/45/2025' is not a date");
        assertThatThrownBy(() -> parse(aBackupFile("08/04/2025\t07:10:00\t25-1044-1\tC1204\tx\tPLAYED\t0:3x")))
                .isInstanceOf(AsRunError.class)
                .hasMessageContaining("'0:3x' is not a length");
    }

    @Test
    void aTabInsideACommaFileIsJustPartOfTheField() {
        List<List<String>> records = Csv.records("a,\"b,c\",d\te\n\n\"say \"\"hi\"\"\",x\n");
        assertThat(records).containsExactly(List.of("a", "b,c", "d\te"), List.of("say \"hi\"", "x"));
        assertThat(Csv.records("a\t\"b\tc\"\td\n", '\t')).containsExactly(List.of("a", "b\tc", "d"));
    }
}
