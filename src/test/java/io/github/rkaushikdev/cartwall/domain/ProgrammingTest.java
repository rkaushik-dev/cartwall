package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.domain.Clock.musicClock;
import static io.github.rkaushikdev.cartwall.domain.Clock.talkClock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProgrammingTest {

    private static final Weekday MON = Weekday.MON;
    private static final Weekday SAT = Weekday.SAT;
    private static final Weekday FRI = Weekday.FRI;
    private static final LocalDate JANUARY = LocalDate.of(2025, 1, 1);

    private ClockGrid grid;

    @BeforeEach
    void fileTheGrid() {
        grid = new ClockGrid("KWLO", JANUARY);
        grid.fileClock(musicClock());
        grid.fileClock(talkClock());
        grid.assign(Assignment.build("all week", "M-Su", 0, 24, "MUS"));
        grid.assign(Assignment.build("farm block", "M-F", 5, 6, "TLK"));
        grid.assign(Assignment.preempt("friday football", "F", 19, 23));
    }

    @Test
    void anAssignmentThatCannotBeFiled() {
        assertThatThrownBy(() -> Assignment.build("", "M-F", 6, 10, "MUS"))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("needs a label");
        assertThatThrownBy(() -> new Assignment("all week", Set.of(), 6, 10, "MUS", Source.LOCAL))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("runs on no days");
        assertThatThrownBy(() -> Assignment.build("backwards", "M-F", 10, 6, "MUS"))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("not an hour span");
        assertThatThrownBy(() -> Assignment.build("too late", "M-F", 20, 25, "MUS"))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("not an hour span");
    }

    @Test
    void aSellingAssignmentNeedsAClockAndAPreemptionMustNotHaveOne() {
        assertThatThrownBy(() -> Assignment.build("nameless", "M-F", 6, 10, ""))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("names no clock");
        assertThatThrownBy(() -> Assignment.build("football", "F", 19, 23, "MUS", Source.PREEMPTED))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("preempted but names clock");
    }

    @Test
    void anAssignmentCoversTheHoursItWasWrittenFor() {
        Assignment farm = Assignment.build("farm block", "M-F", 5, 6, "TLK");
        assertThat(farm.covers(MON, 5)).isTrue();
        assertThat(farm.covers(MON, 6)).isFalse();
        assertThat(farm.covers(SAT, 5)).isFalse();
        assertThat(farm.hours()).isEqualTo(5);
        assertThat(farm).hasToString("farm block M-F 5-6 TLK");
        assertThat(Assignment.preempt("football", "F", 19, 23)).hasToString("football F 19-23 preempted");
    }

    @Test
    void aGridWillNotTakeAClockItDoesNotHave() {
        assertThatThrownBy(() -> grid.assign(Assignment.build("sports", "Sa", 12, 16, "SPT")))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("which is not filed");
    }

    @Test
    void aGridWillNotFileTheSameClockTwice() {
        assertThatThrownBy(() -> grid.fileClock(musicClock()))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("already filed");
    }

    @Test
    void theLastAssignmentFiledIsTheOneThatWins() {
        assertThat(grid.clockFor(MON, 7).code()).isEqualTo("MUS");
        assertThat(grid.clockFor(MON, 5).code()).isEqualTo("TLK");
        assertThat(grid.clockFor(SAT, 5).code()).isEqualTo("MUS");
    }

    @Test
    void orderMattersAndAGridReadBackwardsSchedulesIntoABallgame() {
        ClockGrid backwards = new ClockGrid("KWLO", JANUARY);
        backwards.fileClock(musicClock());
        backwards.assign(Assignment.preempt("friday football", "F", 19, 23));
        backwards.assign(Assignment.build("all week", "M-Su", 0, 24, "MUS"));
        assertThat(backwards.sells(FRI, 20)).isTrue();
    }

    @Test
    void aPreemptedHourHasNoClockAndSaysWhy() {
        assertThat(grid.sells(FRI, 20)).isFalse();
        assertThat(grid.sells(FRI, 18)).isTrue();
        assertThat(grid.sells(FRI, 23)).isTrue();
        assertThat(grid.sells(SAT, 20)).isTrue();
        assertThatThrownBy(() -> grid.clockFor(FRI, 20))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("preempted F at 20 for friday football");
    }

    @Test
    void anHourNobodyProgrammedIsAGapAndNotAGuess() {
        ClockGrid thin = new ClockGrid("KCRR", JANUARY);
        thin.fileClock(musicClock());
        thin.assign(Assignment.build("weekdays", "M-F", 6, 19, "MUS"));
        assertThat(thin.isComplete()).isFalse();
        assertThat(thin.gaps()).contains(new Cell(MON, 5));
        assertThat(thin.gaps()).contains(new Cell(SAT, 12));
        assertThat(thin.gaps()).doesNotContain(new Cell(MON, 7));
        assertThatThrownBy(() -> thin.slot(MON, 5))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("nothing programmed M at 5");
        assertThat(thin.sells(MON, 5)).isFalse();
    }

    @Test
    void aFullGridReportsNoGaps() {
        assertThat(grid.isComplete()).isTrue();
        assertThat(grid.gaps()).isEmpty();
    }

    @Test
    void askingAboutAnHourThatIsNotAnHour() {
        assertThatThrownBy(() -> grid.slot(MON, 24))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("hour 24 is not in the day");
        assertThatThrownBy(() -> grid.slot(MON, -1))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("hour -1 is not in the day");
    }

    @Test
    void theGridCountsTheHoursItActuallySells() {
        // 168 hours less the four preempted on Friday night.
        assertThat(grid.sellingHours()).isEqualTo(164);
    }

    @Test
    void theGridAddsUpEveryLocalUnitItPutsOnTheAir() {
        ClockGrid plain = new ClockGrid("KCNZ", JANUARY);
        plain.fileClock(musicClock());
        plain.assign(Assignment.build("all week", "M-Su", 0, 24, "MUS"));
        assertThat(plain.unitsAWeek()).isEqualTo(168 * 24);
    }

    @Test
    void theFarmBlockAndTheBallgameBothChangeTheWeeklyUnitCount() {
        // 164 selling hours: five of them are talk at 30 units, the rest music at 24.
        assertThat(grid.unitsAWeek()).isEqualTo(5 * 30 + 159 * 24);
    }

    @Test
    void theWeekIsAHundredAndSixtyEightCellsMondayMidnightFirst() {
        assertThat(Cell.week()).hasSize(Daypart.CELLS_IN_WEEK).isSorted().doesNotHaveDuplicates();
        assertThat(Cell.week().get(0)).isEqualTo(new Cell(MON, 0));
        assertThat(Cell.week().get(167)).isEqualTo(new Cell(Weekday.SUN, 23));
    }

    @Test
    void theGridCountsWhatEachDaypartHasToSellInAWeek() {
        Map<String, Integer> inventory = grid.inventory(DaypartGrid.standardGrid());
        assertThat(inventory.keySet()).containsExactly("AMD", "MID", "PMD", "EVE", "SAT", "SUN", "OVN", "AAA", "ROS");
        assertThat(inventory.get("AMD")).isEqualTo(20 * 24);
        // Four hours of Friday night are football.
        assertThat(inventory.get("EVE")).isEqualTo(21 * 24);
        // The 5a hour on weekdays is the farm block, on the talk clock.
        assertThat(inventory.get("OVN")).isEqualTo(5 * 30 + 37 * 24);
        assertThat(inventory.get("ROS")).isEqualTo(grid.unitsAWeek());
    }

    @Test
    void aDaypartInsideTheBallgameHasNothingToSell() {
        DaypartGrid football = new DaypartGrid(List.of(
                Daypart.build("FBL", "friday football", "F", 19, 23),
                Daypart.build("ROS", "everything", "M-Su", 0, 24)));
        assertThat(grid.inventory(football)).containsEntry("FBL", 0).containsEntry("ROS", 5 * 30 + 159 * 24);
    }

    @Test
    void aGridWithHolesCountsOnlyWhatIsProgrammed() {
        ClockGrid thin = new ClockGrid("KCRR", JANUARY);
        thin.fileClock(musicClock());
        thin.assign(Assignment.build("weekdays", "M-F", 6, 19, "MUS"));
        assertThat(thin.gaps()).hasSize(168 - 65).first().isEqualTo(new Cell(MON, 0));
        assertThat(thin.sellingHours()).isEqualTo(65);
        assertThat(thin.inventory(DaypartGrid.standardGrid()))
                .containsEntry("AAA", 65 * 24)
                .containsEntry("SAT", 0)
                .containsEntry("OVN", 0);
    }
}
