package io.github.rkaushikdev.cartwall.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountTest {

    private AccountBook book;

    @BeforeEach
    void openTheBook() {
        book = new AccountBook();
        book.addAgency(new Agency("MOP", "Mopar Media"));
        book.addAgency(new Agency("PAP", "Paperwork Only", new BigDecimal("0.15"), true));
        book.addAdvertiser(Advertiser.build("HYVEE", "Hy-Vee", "GROCERY,FUEL.GAS"));
        book.addAdvertiser(
                Advertiser.build("HYVEE1", "Hy-Vee Kimball", "GROCERY").withParent("HYVEE"));
        book.addAdvertiser(
                Advertiser.build("DEALF", "Dahl Ford", "AUTO.DOMESTIC.FORD").withAgency("MOP"));
        book.addAdvertiser(Advertiser.build("DEALG", "Dahl Auto Group", "AUTO").withAgency("PAP"));
        book.addAdvertiser(Advertiser.build("CASEY", "Caseys", "FUEL,GROCERY.CONV"));
    }

    @Test
    void aConflictCodeIsADottedPath() {
        Category code = new Category("AUTO.DOMESTIC.FORD");
        assertThat(code.parts()).containsExactly("AUTO", "DOMESTIC", "FORD");
        assertThat(code.depth()).isEqualTo(3);
        assertThat(code.parent()).contains(new Category("AUTO.DOMESTIC"));
        assertThat(new Category("AUTO").parent()).isEmpty();
        assertThat(code).hasToString("AUTO.DOMESTIC.FORD");
    }

    @Test
    void codesThatAreNotCodes() {
        for (String bad : List.of("", "auto", "AUTO.", ".AUTO", "AUTO..FORD", "AUTO FORD")) {
            assertThatThrownBy(() -> new Category(bad))
                    .isInstanceOf(AccountError.class)
                    .hasMessageContaining("not a conflict code");
        }
    }

    @Test
    void aBroadCodeCatchesEverythingUnderIt() {
        Category auto = new Category("AUTO");
        Category domestic = new Category("AUTO.DOMESTIC");
        Category ford = new Category("AUTO.DOMESTIC.FORD");
        assertThat(auto.covers(ford)).isTrue();
        assertThat(domestic.covers(ford)).isTrue();
        assertThat(ford.covers(auto)).isFalse();
        assertThat(auto.conflictsWith(ford)).isTrue();
        assertThat(ford.conflictsWith(auto)).isTrue();
    }

    @Test
    void aCodeDoesNotCatchASiblingByAccident() {
        assertThat(new Category("AUTO.DOMESTIC").conflictsWith(new Category("AUTO.IMPORT")))
                .isFalse();
        assertThat(new Category("AUTOMOTIVE").conflictsWith(new Category("AUTO")))
                .isFalse();
        assertThat(new Category("AUTO").conflictsWith(new Category("AUTO"))).isTrue();
    }

    @Test
    void anAgencyKeepsACommissionThatIsLessThanEverything() {
        assertThat(new Agency("MOP", "Mopar Media").rate()).isEqualByComparingTo("0.15");
        assertThat(new Agency("HSE", "House", new BigDecimal("0")).rate()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> new Agency("BAD", "All of it", new BigDecimal("1")))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("not a commission");
        assertThatThrownBy(() -> new Agency("MOP", ""))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("has no name");
        assertThatThrownBy(() -> new Agency("m", "Mopar Media"))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("not an account code");
    }

    @Test
    void advertisersCarryMoreThanOneConflictCode() {
        Advertiser grocer = Advertiser.build("HYVEE", "Hy-Vee", "GROCERY,FUEL.GAS,PHARM");
        assertThat(grocer.categories()).hasSize(3);
        assertThat(grocer.categories()).contains(new Category("FUEL.GAS"));
        assertThat(Advertiser.build("SOLO", "Solo", "").categories()).isEmpty();
    }

    @Test
    void twoAdvertisersConflictThroughTheBroadestCodeTheyShare() {
        Advertiser ford = Advertiser.build("DEALF", "Dahl Ford", "AUTO.DOMESTIC.FORD");
        Advertiser chevy = Advertiser.build("DEALC", "Community Chevrolet", "AUTO.DOMESTIC.CHEV");
        Advertiser toyota = Advertiser.build("DEALT", "Toyota of Waterloo", "AUTO.IMPORT.TOYO");
        Advertiser couch = Advertiser.build("FURN", "Slumberland", "FURNITURE");
        assertThat(ford.conflictsWith(chevy)).isFalse();
        assertThat(ford.conflictsWith(toyota)).isFalse();
        assertThat(ford.conflictsWith(couch)).isFalse();

        Advertiser broad = Advertiser.build("DEALG", "Dahl Auto Group", "AUTO");
        assertThat(broad.conflictsWith(ford)).isTrue();
        assertThat(broad.conflictsWith(toyota)).isTrue();
        assertThat(broad.sharedCategories(ford)).isEqualTo(Set.of(new Category("AUTO")));
    }

    @Test
    void anAdvertiserAlwaysConflictsWithItself() {
        Advertiser ford = Advertiser.build("DEALF", "Dahl Ford", "AUTO.DOMESTIC.FORD");
        assertThat(ford.conflictsWith(ford)).isTrue();
        assertThat(Advertiser.build("SOLO", "Solo").conflictsWith(Advertiser.build("SOLO", "Solo")))
                .isTrue();
    }

    @Test
    void anAdvertiserThatCannotBeSetUp() {
        assertThatThrownBy(() -> Advertiser.build("HYVEE", ""))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("has no name");
        assertThatThrownBy(() -> Advertiser.build("hyvee", "Hy-Vee"))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("not an account code");
        assertThatThrownBy(() -> Advertiser.build("HYVEE", "Hy-Vee").withParent("HYVEE"))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("its own parent");
    }

    @Test
    void theBookRefusesASecondAccountOnTheSameCode() {
        assertThatThrownBy(() -> book.addAdvertiser(Advertiser.build("HYVEE", "Hy-Vee again")))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("already on file");
        assertThatThrownBy(() -> book.addAgency(new Agency("MOP", "Mopar Media again")))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("already on file");
    }

    @Test
    void theBookRefusesAPointerAtSomethingThatIsNotThere() {
        assertThatThrownBy(
                        () -> book.addAdvertiser(Advertiser.build("NEW", "New").withAgency("NOPE")))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("which is not on file");
        assertThatThrownBy(
                        () -> book.addAdvertiser(Advertiser.build("NEW", "New").withParent("NOPE")))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("which is not on file");
    }

    @Test
    void lookingUpSomethingThatIsNotThereIsAMissNotBadInput() {
        NotOnFile caught = catchThrowableOfType(NotOnFile.class, () -> book.advertiser("NOPE"));
        assertThat(caught).hasMessageContaining("advertiser NOPE is not on file");
        assertThat(caught.code()).isEqualTo("no_such_account");
        assertThat(caught.context()).containsEntry("code", "NOPE");
        assertThatThrownBy(() -> book.agency("NOPE"))
                .isInstanceOf(NotOnFile.class)
                .hasMessageContaining("agency NOPE is not on file");
        // It is a lookup failure, so the http layer answers 404 rather than 422.
        assertThat(caught).isNotInstanceOf(AccountError.class);
    }

    @Test
    void directBusinessPaysNoCommission() {
        assertThat(book.commissionRate("HYVEE")).isEqualByComparingTo("0");
        assertThat(book.commissionRate("DEALF")).isEqualByComparingTo("0.15");
        assertThat(book.advertiser("HYVEE").isDirect()).isTrue();
        assertThat(book.advertiser("DEALF").isDirect()).isFalse();
    }

    @Test
    void theInvoiceGoesToTheAgencyUnlessTheAdvertiserStaysLiable() {
        assertThat(book.liableParty("HYVEE")).isEqualTo("HYVEE");
        assertThat(book.liableParty("DEALF")).isEqualTo("MOP");
        assertThat(book.liableParty("DEALG")).isEqualTo("DEALG");
    }

    @Test
    void moneyRollsUpToTheAccountThatOwnsTheChain() {
        assertThat(book.billingGroup("HYVEE1")).isEqualTo("HYVEE");
        assertThat(book.billingGroup("HYVEE")).isEqualTo("HYVEE");
        assertThat(book.billingGroup("CASEY")).isEqualTo("CASEY");
    }

    @Test
    void aHoldStopsAnAccountBeingScheduled() {
        book.addAdvertiser(Advertiser.build("SLOW", "Slow Pay").withCredit(Credit.HOLD));
        book.addAdvertiser(Advertiser.build("CASH", "Cash Up Front").withCredit(Credit.PREPAY));
        assertThat(book.maySchedule("HYVEE")).isTrue();
        assertThat(book.maySchedule("CASH")).isTrue();
        assertThat(book.maySchedule("SLOW")).isFalse();
    }

    @Test
    void aStoreIsHeldWhenTheChainItBuysUnderIsHeld() {
        book.replaceAdvertiser(book.advertiser("HYVEE").withCredit(Credit.HOLD));
        assertThat(book.advertiser("HYVEE1").credit()).isSameAs(Credit.OPEN);
        assertThat(book.standing("HYVEE1")).isSameAs(Credit.HOLD);
        assertThat(book.maySchedule("HYVEE1")).isFalse();
        assertThat(book.whyNotScheduled("HYVEE1")).contains("HYVEE1 is on credit hold through HYVEE");
        assertThat(book.whyNotScheduled("HYVEE")).contains("HYVEE is on credit hold");
        assertThat(book.whyNotScheduled("CASEY")).isEmpty();
    }

    @Test
    void theStrictestStandingInTheChainGoverns() {
        book.replaceAdvertiser(book.advertiser("HYVEE").withCredit(Credit.PREPAY));
        assertThat(book.standing("HYVEE1")).isSameAs(Credit.PREPAY);
        assertThat(book.maySchedule("HYVEE1")).isTrue();
        book.replaceAdvertiser(book.advertiser("HYVEE1").withCredit(Credit.HOLD));
        assertThat(book.standing("HYVEE1")).isSameAs(Credit.HOLD);
        assertThat(book.whyNotScheduled("HYVEE1")).contains("HYVEE1 is on credit hold");
        assertThat(book.standing("HYVEE")).isSameAs(Credit.PREPAY);
    }

    @Test
    void aChainThatLoopsIsRefusedWhereverItIsRead() {
        book.replaceAdvertiser(book.advertiser("HYVEE").withParent("HYVEE1"));
        assertThatThrownBy(() -> book.standing("HYVEE1"))
                .isInstanceOf(AccountError.class)
                .hasMessageContaining("HYVEE1 under HYVEE loops back on itself");
        assertThatThrownBy(() -> book.billingGroup("HYVEE")).isInstanceOf(AccountError.class);
    }

    @Test
    void theBookListsWhoAnAdvertiserMayNotSitBeside() {
        assertThat(book.conflicting("DEALF")).extracting(Advertiser::code).containsExactly("DEALG");
        assertThat(book.conflicting("HYVEE")).extracting(Advertiser::code).containsExactly("CASEY", "HYVEE1");
        assertThat(book.conflicting("CASEY")).extracting(Advertiser::code).containsExactly("HYVEE", "HYVEE1");
    }

    @Test
    void aGroceryConflictAndAFuelConflictAreBothReported() {
        Advertiser hyvee = book.advertiser("HYVEE");
        Advertiser casey = book.advertiser("CASEY");
        Set<String> shared =
                hyvee.sharedCategories(casey).stream().map(Category::toString).collect(Collectors.toSet());
        assertThat(shared).isEqualTo(Set.of("GROCERY", "FUEL"));
    }
}
