package io.github.rkaushikdev.cartwall.reports;

import static io.github.rkaushikdev.cartwall.Fixtures.aLine;
import static io.github.rkaushikdev.cartwall.Fixtures.anOrder;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ConfirmationTest {

    private static Order aDealerOrder() {
        return anOrder()
                .number("25-1101")
                .advertiser("DEALF")
                .campaign("Fall Clearance")
                .status(OrderStatus.CONFIRMED)
                .lines(
                        aLine(1).build(),
                        aLine(2).daypart("PMD")
                                .grade(ClassOfTime.NOTICE)
                                .rate("45.00")
                                .flight(LocalDate.of(2025, 8, 7), LocalDate.of(2025, 8, 20), "M-F", 12)
                                .build())
                .build();
    }

    @Test
    void aDirectOrderIsBilledToTheAdvertiserWithNothingOff() {
        Confirmation confirmation = Confirmation.of(anOrder().build(), Fixtures.accounts());
        assertThat(confirmation.agency()).isEmpty();
        assertThat(confirmation.payer()).isEqualTo("HYVEE");
        assertThat(confirmation.gross()).isEqualTo(Money.dollars(2880));
        assertThat(confirmation.commission()).isEqualTo(Money.ZERO);
        assertThat(confirmation.net()).isEqualTo(Money.dollars(2880));
    }

    @Test
    void anAgencyOrderIsBilledToTheAgencyAtItsRate() {
        Confirmation confirmation = Confirmation.of(aDealerOrder(), Fixtures.accounts());
        assertThat(confirmation.agency()).map(placed -> placed.code()).contains("MOP");
        assertThat(confirmation.payer()).isEqualTo("MOP");
        assertThat(confirmation.commissionRate()).isEqualByComparingTo(new BigDecimal("0.15"));
        // 48 at $60 and 23 at $45.
        assertThat(confirmation.gross()).isEqualTo(Money.parse("3915.00"));
        assertThat(confirmation.commission()).isEqualTo(Money.parse("587.25"));
        assertThat(confirmation.net()).isEqualTo(Money.parse("3327.75"));
    }

    @Test
    void theStubWeeksAreTheOnesTheHouseRoundedDown() {
        Confirmation confirmation = Confirmation.of(aDealerOrder(), Fixtures.accounts());
        assertThat(confirmation.stubs())
                .containsExactly(
                        new Confirmation.Stub(2, new BroadcastWeek(LocalDate.of(2025, 8, 4)), 4, 12),
                        new Confirmation.Stub(2, new BroadcastWeek(LocalDate.of(2025, 8, 18)), 7, 12));
        assertThat(confirmation.stubs().get(0)).hasToString("line 2 week of 2025-08-04: 4 of 12, rounded down");
        assertThat(Confirmation.of(anOrder().build(), Fixtures.accounts()).stubs())
                .isEmpty();
    }

    @Test
    void theConfirmationSaysWhatEachBroadcastMonthWillBill() {
        String text = Confirmation.of(aDealerOrder(), Fixtures.accounts()).render();
        assertThat(text)
                .startsWith("ORDER CONFIRMATION\n\n")
                .contains("ORDER        25-1101\n")
                .contains("ADVERTISER   Dahl Ford (DEALF)\n")
                .contains("AGENCY       Mopar Media (MOP)\n")
                .contains("BILL TO      MOP\n")
                .contains("CAMPAIGN     Fall Clearance\n")
                .contains("SALESPERSON  DHOLM\n")
                .contains("WRITTEN      07/21/2025\n")
                .contains("STATUS       confirmed\n")
                .contains("FLIGHT       08/04/2025 to 08/29/2025\n")
                .contains("    1  KWLO     AMD   fixed                     :30  M-F 12x     48 at $60.00 = $2,880.00")
                .contains("    2  KWLO     PMD   preemptible with notice   :30  M-F 12x     23 at $45.00 = $1,035.00")
                .contains("STUB WEEKS\n  line 2 week of 2025-08-04: 4 of 12, rounded down\n")
                .contains("BY MONTH\n  2025-08       $3,195.00\n  2025-09         $720.00\n")
                .contains("GROSS        $3,915.00\n")
                .contains("COMMISSION   $587.25\n")
                .endsWith("NET          $3,327.75\n");
    }

    @Test
    void aDirectOrderWithNoCampaignLeavesTheLineOut() {
        String text = Confirmation.of(anOrder().build(), Fixtures.accounts()).render();
        assertThat(text)
                .contains("AGENCY       direct\n")
                .doesNotContain("CAMPAIGN")
                .doesNotContain("STUB WEEKS");
    }

    @Test
    void anOrderWithNoLinesYetStillConfirmsWhatThereIs() {
        String text =
                Confirmation.of(anOrder().lines().build(), Fixtures.accounts()).render();
        assertThat(text)
                .contains("  no lines entered\n")
                .doesNotContain("FLIGHT")
                .contains("GROSS        $0.00\n");
    }
}
