package io.github.rkaushikdev.cartwall.logs;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.logs.Approval.missingCopy;
import static io.github.rkaushikdev.cartwall.logs.Approval.problems;
import static io.github.rkaushikdev.cartwall.logs.Approval.warnings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.CutLibrary;
import io.github.rkaushikdev.cartwall.scheduling.Avail;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalTest {

    private static final LocalDateTime SIGNED = LocalDateTime.of(2025, 8, 1, 16, 30);
    private static final LocalDateTime SENT = LocalDateTime.of(2025, 8, 1, 17, 5);

    private Map<String, ClockGrid> programming;
    private ClockGrid grid;
    private DayBoard board;
    private LogRegister register;

    @BeforeEach
    void layOutMonday() {
        programming = Fixtures.programming();
        grid = programming.get("KWLO");
        board = DayBoard.build(grid, MONDAY);
        register = new LogRegister();
    }

    private void put(int hour, int number, String cart, int line) {
        board.avail(hour, number).add(new Placement("25-1044", line, "HYVEE", THIRTY, ClassOfTime.FIXED, cart, false));
    }

    private void put(int hour, int number) {
        put(hour, number, "C1204", 1);
    }

    private DayLog log() {
        return DayLog.build(grid, board);
    }

    @Test
    void anEmptyLogHasNothingWrongWithIt() {
        assertThat(problems(log())).isEmpty();
    }

    @Test
    void aSpotWithNoCartStopsTheLog() {
        put(7, 2, "", 1);
        List<Problem> found = problems(log());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).code()).isEqualTo("missing_copy");
        assertThat(found.get(0).detail()).contains("25-1044-1");
        assertThat(found.get(0).toString()).contains("7:10:00a");
    }

    @Test
    void everySpotWithoutCopyIsListedOncePerOrderLine() {
        put(7, 2, "", 1);
        put(8, 2, "", 1);
        put(9, 2, "", 2);
        put(9, 3, "C1204", 3);
        DayLog log = log();
        assertThat(problems(log)).hasSize(3);
        assertThat(missingCopy(log)).containsExactly("25-1044-1", "25-1044-2");
    }

    private static CutLibrary aLibrary() {
        CutLibrary library = new CutLibrary();
        library.file(new Cut("C1204", "HYVE0001", "HYVEE", THIRTY, Duration.ofSeconds(30), MONDAY.minusDays(14)));
        library.file(new Cut("C1205", "HYVE0002", "HYVEE", THIRTY, Duration.ofSeconds(30), MONDAY.minusDays(14))
                .killedAfter(MONDAY.minusDays(1)));
        library.file(new Cut("C1206", "HYVE0003", "HYVEE", THIRTY, Duration.ofSeconds(30), MONDAY.plusDays(3)));
        library.file(new Cut("C1207", "HYVE0004", "HYVEE", THIRTY, Duration.ofSeconds(30), MONDAY.minusDays(7))
                .killedAfter(MONDAY.plusDays(4)));
        return library;
    }

    @Test
    void copyOnFileAndInsideItsDatesIsNoProblem() {
        put(7, 2, "C1204", 1);
        put(7, 3, "C1207", 2);
        assertThat(problems(log(), aLibrary())).isEmpty();
    }

    @Test
    void copyOutsideItsAirDatesStopsTheLog() {
        put(7, 2, "C1205", 1);
        put(7, 3, "C1206", 2);
        put(8, 2, "C9999", 3);
        put(8, 3, "", 4);
        List<Problem> found = problems(log(), aLibrary());
        assertThat(found)
                .extracting(Problem::code)
                .containsExactly("missing_copy", "copy_out_of_dates", "copy_out_of_dates", "copy_not_on_file");
        assertThat(found.get(1).detail()).isEqualTo("25-1044-1 plays C1205, which runs 2025-07-21 to 2025-08-03");
        assertThat(found.get(2).detail()).isEqualTo("25-1044-2 plays C1206, which runs 2025-08-07");
        assertThat(found.get(3).detail()).isEqualTo("25-1044-3 plays C9999, which nobody has filed");
        assertThat(found.get(3).where()).isEqualTo("8:10:00a");
    }

    @Test
    void copyRunningOutThisWeekIsAWarningNamedOnce() {
        put(7, 2, "C1207", 1);
        put(9, 2, "C1207", 1);
        put(10, 2, "C1204", 2);
        List<Problem> notes = warnings(log(), aLibrary());
        assertThat(notes).extracting(Problem::code).containsExactly("unsold", "copy_expiring");
        assertThat(notes.get(1).where()).isEqualTo("C1207");
        assertThat(notes.get(1).detail()).isEqualTo("HYVEE's :30 runs 2025-07-28 to 2025-08-08");
        assertThat(problems(log(), aLibrary())).isEmpty();
    }

    @Test
    void copyThatRunsOutLaterThanAWeekOrNeverIsNotWorthMentioning() {
        CutLibrary library = new CutLibrary();
        library.file(new Cut("C1204", "HYVE0001", "HYVEE", THIRTY, Duration.ofSeconds(30), MONDAY)
                .killedAfter(MONDAY.plusDays(Approval.EXPIRY_WARNING_DAYS)));
        put(7, 2, "C1204", 1);
        assertThat(warnings(log(), library)).extracting(Problem::code).containsExactly("unsold");
    }

    @Test
    void unsoldUnitsAreAWarningAndNotAProblem() {
        put(7, 2);
        DayLog log = log();
        assertThat(problems(log)).isEmpty();
        List<Problem> said = warnings(log);
        assertThat(said).hasSize(1);
        assertThat(said.get(0).code()).isEqualTo("unsold");
        assertThat(said.get(0).toString()).contains("units did not sell");
    }

    @Test
    void aFullDayWarnsAboutNothing() {
        for (Avail avail : board.avails()) {
            for (int line = 0; line < avail.units(); line++) {
                put(avail.hour(), avail.number(), "C1204", line + 1);
            }
        }
        assertThat(warnings(log())).isEmpty();
    }

    @Test
    void aNewStationDayStartsInDraft() {
        LogStatus status = register.status("KWLO", MONDAY);
        assertThat(status.state()).isSameAs(LogState.DRAFT);
        assertThat(status.revision()).isZero();
        assertThat(register.mayEdit("KWLO", MONDAY)).isTrue();
        assertThat(status).hasToString("KWLO 2025-08-04 draft");
    }

    @Test
    void approvingRecordsWhoSignedItAndWhen() {
        put(7, 2);
        LogStatus status = register.approve(log(), "T. Ronan", SIGNED);
        assertThat(status.state()).isSameAs(LogState.APPROVED);
        assertThat(status.approvedBy()).isEqualTo("T. Ronan");
        assertThat(status.approvedAt()).contains(SIGNED);
        assertThat(register.mayEdit("KWLO", MONDAY)).isFalse();
    }

    @Test
    void aLogWithProblemsIsNotSigned() {
        put(7, 2, "", 1);
        LogNotReady caught = catchThrowableOfType(LogNotReady.class, () -> register.approve(log(), "T. Ronan", SIGNED));
        assertThat(caught).hasMessageContaining("1 problem");
        assertThat(caught.code()).isEqualTo("log_not_ready");
        assertThat(caught.context().get("problems"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .first()
                .asString()
                .contains("missing_copy");
        assertThat(register.status("KWLO", MONDAY).state()).isSameAs(LogState.DRAFT);
    }

    @Test
    void aLogHasToBeSignedByAPerson() {
        assertThatThrownBy(() -> register.approve(log(), "", SIGNED))
                .isInstanceOf(ApprovalError.class)
                .hasMessageContaining("approved by somebody");
    }

    @Test
    void aLogIsNotSignedTwice() {
        DayLog log = log();
        register.approve(log, "T. Ronan", SIGNED);
        assertThatThrownBy(() -> register.approve(log, "T. Ronan", SIGNED))
                .isInstanceOf(LogFrozen.class)
                .hasMessageContaining("already approved");
    }

    @Test
    void theStatesOnlyRunForward() {
        DayLog log = log();
        assertThatThrownBy(() -> register.release("KWLO", MONDAY, SENT))
                .isInstanceOf(LogFrozen.class)
                .hasMessageContaining("is draft and cannot be released");
        register.approve(log, "T. Ronan", SIGNED);
        assertThatThrownBy(() -> register.markAired("KWLO", MONDAY))
                .isInstanceOf(LogFrozen.class)
                .hasMessageContaining("did not go to air from here");
        register.release("KWLO", MONDAY, SENT);
        assertThat(register.status("KWLO", MONDAY).releasedAt()).contains(SENT);
        register.markAired("KWLO", MONDAY);
        assertThat(register.status("KWLO", MONDAY).state()).isSameAs(LogState.AIRED);
    }

    @Test
    void aReleasedLogCannotBeEditedInPlace() {
        register.approve(log(), "T. Ronan", SIGNED);
        register.release("KWLO", MONDAY, SENT);
        LogFrozen caught = catchThrowableOfType(LogFrozen.class, () -> register.checkEditable("KWLO", MONDAY));
        assertThat(caught).hasMessageContaining("is released at revision 0");
        assertThat(caught.context()).containsEntry("revision", 0);
    }

    @Test
    void aDraftLogCanBeEdited() {
        register.checkEditable("KWLO", MONDAY);
        assertThat(register.mayEdit("KWLO", MONDAY)).isTrue();
    }

    @Test
    void revisingAReleasedLogPutsItBackInDraftOneHigher() {
        register.approve(log(), "T. Ronan", SIGNED);
        register.release("KWLO", MONDAY, SENT);
        LogStatus status = register.revise("KWLO", MONDAY);
        assertThat(status.state()).isSameAs(LogState.DRAFT);
        assertThat(status.revision()).isEqualTo(1);
        assertThat(status.approvedBy()).isEmpty();
        assertThat(status.approvedAt()).isEmpty();
        assertThat(status.releasedAt()).isEmpty();
        assertThat(register.mayEdit("KWLO", MONDAY)).isTrue();
        assertThat(status).hasToString("KWLO 2025-08-04 draft rev 1");
    }

    @Test
    void aRevisionHasToBeApprovedAndReleasedAgain() {
        DayLog log = log();
        register.approve(log, "T. Ronan", SIGNED);
        register.release("KWLO", MONDAY, SENT);
        register.revise("KWLO", MONDAY);
        register.approve(log, "T. Ronan", SIGNED);
        LogStatus status = register.release("KWLO", MONDAY, SENT);
        assertThat(status.state()).isSameAs(LogState.RELEASED);
        assertThat(status.revision()).isEqualTo(1);
    }

    @Test
    void aLogThatNeverWentOutDoesNotNeedARevision() {
        assertThatThrownBy(() -> register.revise("KWLO", MONDAY))
                .isInstanceOf(ApprovalError.class)
                .hasMessageContaining("does not need a revision");
        register.approve(log(), "T. Ronan", SIGNED);
        assertThatThrownBy(() -> register.revise("KWLO", MONDAY))
                .isInstanceOf(ApprovalError.class)
                .hasMessageContaining("does not need a revision");
    }

    @Test
    void anAiredLogCanStillBeRevisedBecauseTheBillingReadsIt() {
        register.approve(log(), "T. Ronan", SIGNED);
        register.release("KWLO", MONDAY, SENT);
        register.markAired("KWLO", MONDAY);
        assertThat(register.revise("KWLO", MONDAY).revision()).isEqualTo(1);
    }

    @Test
    void theRegisterListsWhatHasGoneOutAndWhatIsStillWaiting() {
        for (String call : List.of("KWLO", "KCRR")) {
            ClockGrid each = programming.get(call);
            register.approve(DayLog.build(each, DayBoard.build(each, MONDAY)), "T. Ronan", SIGNED);
        }
        register.release("KWLO", MONDAY, SENT);
        register.status("KXEL", MONDAY);
        assertThat(register.released()).extracting(LogStatus::station).containsExactly("KWLO");
        assertThat(register.waiting()).extracting(LogStatus::station).containsExactly("KXEL");
        assertThat(register.size()).isEqualTo(3);
    }
}
