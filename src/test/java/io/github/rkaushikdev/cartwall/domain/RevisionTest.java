package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.Fixtures.aLine;
import static io.github.rkaushikdev.cartwall.Fixtures.anOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RevisionTest {

    private static Order rev1() {
        return anOrder()
                .lines(
                        aLine(1).build(),
                        aLine(2).daypart("PMD").rate("45.00").build(),
                        aLine(3).daypart("MID").rate("30.00").build())
                .build();
    }

    @Test
    void theSameOrderTwiceHasNoChanges() {
        Revision revision = Revision.between(rev1(), rev1());
        assertThat(revision.isEmpty()).isTrue();
        assertThat(revision.summary()).isEqualTo("no change");
        assertThat(revision.valueChange()).isEqualTo(Money.ZERO);
    }

    @Test
    void linesAddedDroppedAndChangedAreEachSaidOnce() {
        Order rev2 = anOrder()
                .lines(
                        aLine(1).rate("55.00").carts("C1204").build(),
                        aLine(3).daypart("MID")
                                .rate("30.00")
                                .flight(LocalDate.of(2025, 8, 4), LocalDate.of(2025, 9, 5), "M-F", 12)
                                .build(),
                        aLine(4).station("KOEL-FM").build())
                .build();
        Revision revision = Revision.between(rev1(), rev2);
        assertThat(revision.changes()).extracting(Revision.Change::line).containsExactly(1, 2, 3, 4);
        assertThat(revision.changes().get(0))
                .isEqualTo(new Revision.Changed(1, List.of("rate $60.00 to $55.00", "copy none to C1204")));
        assertThat(revision.changes().get(1)).isInstanceOf(Revision.Dropped.class);
        assertThat(revision.changes().get(2)).hasToString("line 3: flight 08/04-08/29 M-F 12x to 08/04-09/05 M-F 12x");
        assertThat(revision.changes().get(3)).isInstanceOf(Revision.Added.class);
        assertThat(revision.changes().get(3).toString()).startsWith("line 4 added: 4 KOEL-FM AMD");
        assertThat(revision.was()).isEqualTo(Money.dollars(2880 + 2160 + 1440));
        assertThat(revision.now()).isEqualTo(Money.dollars(48 * 55 + 60 * 30 + 2880));
        assertThat(revision.valueChange()).isEqualTo(revision.now().minus(revision.was()));
    }

    @Test
    void everyPartOfALineIsCompared() {
        Order before = anOrder().lines(aLine(1).build()).build();
        Order after = anOrder()
                .lines(aLine(1).station("KCRR")
                        .daypart("PMD")
                        .grade(ClassOfTime.NOTICE)
                        .length(SpotLength.SIXTY)
                        .flight(Flight.build(LocalDate.of(2025, 8, 4), LocalDate.of(2025, 8, 29), "M-F", 12)
                                .cappedAt(3))
                        .build())
                .build();
        Revision.Changed changed =
                (Revision.Changed) Revision.between(before, after).changes().get(0);
        assertThat(changed.what())
                .containsExactly(
                        "station KWLO to KCRR",
                        "daypart AMD to PMD",
                        "class fixed to preemptible with notice",
                        "length :30 to 1:00",
                        "flight 08/04-08/29 M-F 12x to 08/04-08/29 M-F 12x max 3/day");
    }

    @Test
    void theSummaryReadsAsOneLineForTheAuditTrail() {
        Order rev2 = anOrder().lines(aLine(1).rate("55.00").build()).build();
        assertThat(Revision.between(rev1(), rev2).summary())
                .isEqualTo("line 1: rate $60.00 to $55.00; line 2 dropped: 2 KWLO PMD :30 08/04-08/29 M-F 12x;"
                        + " line 3 dropped: 3 KWLO MID :30 08/04-08/29 M-F 12x");
    }

    @Test
    void twoDifferentOrdersAreNotARevision() {
        assertThatThrownBy(() ->
                        Revision.between(rev1(), anOrder().number("25-2000").build()))
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("cannot be revised into order 25-2000");
    }

    @Test
    void anOrderFindsALineOrSaysItHasNone() {
        assertThat(rev1().findLine(2)).map(Line::daypart).contains("PMD");
        assertThat(rev1().findLine(9)).isEmpty();
        assertThat(rev1().firstAir()).isEqualTo(LocalDate.of(2025, 8, 4));
        assertThat(rev1().lastAir()).isEqualTo(LocalDate.of(2025, 8, 29));
        assertThatThrownBy(() -> anOrder().lines().build().firstAir())
                .isInstanceOf(OrderError.class)
                .hasMessageContaining("has no lines");
    }
}
