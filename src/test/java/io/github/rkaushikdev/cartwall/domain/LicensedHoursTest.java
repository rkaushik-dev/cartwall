package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.domain.Break.local;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LicensedHoursTest {

    /** KXEL's table: 1540 kHz, Waterloo. Short in December, long in June. */
    static LicensedHours kxel() {
        return LicensedHours.of(
                "7:45a-4:45p",
                "7:15a-5:30p",
                "7:00a-6:15p",
                "6:15a-7:45p",
                "5:45a-8:15p",
                "5:30a-8:45p",
                "5:30a-8:45p",
                "6:00a-8:15p",
                "6:30a-7:30p",
                "7:00a-6:30p",
                "6:45a-4:45p",
                "7:30a-4:30p");
    }

    @Test
    void theWindowComesOffTheWallCalendarMonth() {
        LicensedHours hours = kxel();
        assertThat(hours.on(LocalDate.of(2025, 12, 1)))
                .isEqualTo(new LicensedHours.Window(AirTime.at(7, 30), AirTime.at(16, 30)));
        assertThat(hours.on(LocalDate.of(2025, 6, 30)).signOff()).isEqualTo(AirTime.at(20, 45));
        assertThat(hours.on(LocalDate.of(2025, 12, 29)).signOff())
                .as("broadcast January 2026 opens on 12/29, the licence is still in December")
                .isEqualTo(AirTime.at(16, 30));
        assertThat(hours.on(LocalDate.of(2025, 1, 6))).hasToString("7:45:00a-4:45:00p");
    }

    @Test
    void aBreakHasToBeWhollyInsideTheWindow() {
        LicensedHours.Window december = LicensedHours.Window.parse("7:30a-4:30p");
        assertThat(december.holds(AirTime.at(7, 30), Duration.ofSeconds(180))).isTrue();
        assertThat(december.holds(AirTime.at(7, 29, 59), Duration.ofSeconds(10)))
                .isFalse();
        assertThat(december.holds(AirTime.at(16, 27), Duration.ofSeconds(180))).isTrue();
        assertThat(december.holds(AirTime.at(16, 28), Duration.ofSeconds(180)))
                .as("a stopset that straddles sign-off is dead air")
                .isFalse();
        assertThat(december.holds(AirTime.at(16, 30), Duration.ofSeconds(10))).isFalse();
    }

    @Test
    void theLicenceKeepsBreaksByWhereTheyFallInTheHour() {
        LicensedHours hours = kxel();
        LocalDate july = LocalDate.of(2025, 7, 14);
        assertThat(hours.keeps(july, 5, local(2, 22, 180, 6))).isFalse();
        assertThat(hours.keeps(july, 5, local(3, 38, 180, 6))).isTrue();
        assertThat(hours.keeps(july, 20, local(4, 38, 180, 6))).isTrue();
        assertThat(hours.keeps(july, 20, local(5, 50, 180, 6))).isFalse();
    }

    @Test
    void aLicenceThatCannotBeRead() {
        assertThatThrownBy(() -> LicensedHours.of("7:45a-4:45p"))
                .isInstanceOf(StationError.class)
                .hasMessageContaining("twelve months, not 1");
        assertThatThrownBy(() -> LicensedHours.Window.parse("7:45a"))
                .isInstanceOf(StationError.class)
                .hasMessageContaining("is not a sign-on and a sign-off");
        assertThatThrownBy(() -> LicensedHours.Window.parse("4:45p-7:45a"))
                .isInstanceOf(StationError.class)
                .hasMessageContaining("before signing on at 4:45:00p");
        assertThatThrownBy(() -> new LicensedHours(List.of()))
                .isInstanceOf(StationError.class)
                .hasMessageContaining("not 0");
    }

    @Test
    void aGridHeldToTheLicenceDropsTheBreaksTheTransmitterIsDownFor() {
        ClockGrid grid = new ClockGrid("KXEL", LocalDate.of(2025, 1, 1));
        grid.fileClock(Clock.musicClock());
        grid.assign(Assignment.build("all week", "M-Su", 0, 24, "MUS"));
        LocalDate december = LocalDate.of(2025, 12, 1);
        assertThat(grid.breaksOn(december)).hasSize(24);

        grid.license(kxel());
        assertThat(grid.licence()).isPresent();
        var onAir = grid.breaksOn(december);
        assertThat(onAir.firstKey()).isEqualTo(7);
        assertThat(onAir.get(7)).extracting(Break::number).containsExactly(4, 5);
        assertThat(onAir.lastKey()).isEqualTo(16);
        assertThat(onAir.get(16)).extracting(Break::number).containsExactly(1, 2, 3);
        assertThat(onAir).doesNotContainKeys(0, 6, 17, 23);
    }

    @Test
    void aPreemptedHourIsNotOnTheAirWhateverTheLicenceSays() {
        ClockGrid grid = new ClockGrid("KXEL", LocalDate.of(2025, 1, 1));
        grid.fileClock(Clock.musicClock());
        grid.assign(Assignment.build("all week", "M-Su", 0, 24, "MUS"));
        grid.assign(Assignment.preempt("high school football", "F", 12, 16));
        grid.license(kxel());
        assertThat(grid.breaksOn(LocalDate.of(2025, 9, 5))).doesNotContainKeys(12, 13, 14, 15);
        assertThat(grid.breaksOn(LocalDate.of(2025, 9, 5))).containsKeys(11, 16);
    }
}
