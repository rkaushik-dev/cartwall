package io.github.rkaushikdev.cartwall.reports;

import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.Candidacy;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Election;
import io.github.rkaushikdev.cartwall.domain.Race;
import io.github.rkaushikdev.cartwall.domain.Use;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PoliticalFileTest {

    private static final Race RACE = new Race("Iowa House 63", Election.GENERAL, LocalDate.of(2026, 11, 3));
    private static final LocalDate ASKED = LocalDate.of(2026, 9, 8);

    static Candidacy aCandidacy(String candidate, String authorised) {
        return new Candidacy(candidate, RACE, "Merrick for Iowa", authorised);
    }

    static Candidacy aCandidacy() {
        return aCandidacy("R. Merrick", "Merrick for Iowa");
    }

    static Bought aBought(int spots, String rate, String daypart) {
        return new Bought("KWLO", daypart, ClassOfTime.IMMEDIATE, THIRTY, Money.parse(rate), spots);
    }

    static Bought aBought(int spots, String rate) {
        return aBought(spots, rate, "AMD");
    }

    static Bought aBought() {
        return aBought(12, "18.00");
    }

    static FileEntry accepted(Candidacy candidacy, Use use, String order, List<Bought> bought, LocalDate requested) {
        return new FileEntry(requested, candidacy, use, true, order, "", bought);
    }

    static FileEntry accepted(Candidacy candidacy, String order, List<Bought> bought) {
        return accepted(candidacy, Use.CANDIDATE, order, bought, ASKED);
    }

    static FileEntry accepted(List<Bought> bought) {
        return accepted(aCandidacy(), "25-1044", bought);
    }

    static FileEntry accepted() {
        return accepted(List.of(aBought()));
    }

    static FileEntry rejected(String disposition) {
        return new FileEntry(ASKED, aCandidacy(), Use.CANDIDATE, false, "", disposition, List.of());
    }

    @Test
    void anAcceptedEntryAddsUpWhatWasBought() {
        FileEntry entry = accepted(List.of(aBought(12, "18.00"), aBought(6, "12.00", "MID")));
        assertThat(entry.spots()).isEqualTo(18);
        assertThat(entry.value()).isEqualTo(Money.dollars(12 * 18 + 6 * 12));
        assertThat(entry.isComplete()).isTrue();
    }

    @Test
    void anEntryReadsAsASentence() {
        assertThat(accepted()).hasToString("09/08/2026 R. Merrick for Iowa House 63 accepted 12 spots $216.00");
        assertThat(rejected("no inventory in drive").toString()).contains("rejected 0 spots $0.00");
    }

    @Test
    void aCandidateEntryWithoutTheDisclaimerIsNotFiled() {
        FileEntry entry = accepted(aCandidacy("R. Merrick", ""), "25-1044", List.of(aBought()));
        assertThat(entry.isComplete()).isFalse();
        assertThat(entry.missing()).contains("the authorised by disclaimer");
        assertThatThrownBy(entry::render)
                .isInstanceOf(FileError.class)
                .hasMessageContaining("missing the authorised by disclaimer");
    }

    @Test
    void anIssueEntryDoesNotNeedTheCandidateDisclaimer() {
        FileEntry entry = accepted(aCandidacy("R. Merrick", ""), Use.ISSUE, "25-1044", List.of(aBought()), ASKED);
        assertThat(entry.isComplete()).isTrue();
    }

    @Test
    void anAcceptedEntryNeedsAnOrderAndWhatWasBought() {
        assertThat(accepted(aCandidacy(), "", List.of(aBought())).missing()).contains("the order number");
        assertThat(accepted(List.of()).missing()).contains("what was bought");
    }

    @Test
    void aRejectedEntryNeedsAReason() {
        assertThat(rejected("").missing()).contains("why it was turned down");
        assertThat(rejected("sold out").isComplete()).isTrue();
    }

    @Test
    void aRejectedEntryNeedsNeitherAnOrderNorAPurchase() {
        FileEntry entry = rejected("sold out");
        assertThat(entry.missing()).isEmpty();
        assertThat(entry.spots()).isZero();
        assertThat(entry.value()).isEqualTo(new Money(0));
    }

    @Test
    void aRenderedEntryCarriesEverythingTheRuleAsksFor() {
        FileEntry entry = accepted();
        entry.noteAir(new Aired(LocalDate.of(2026, 10, 1), "7:10:04a", "KWLO"), LocalDate.of(2026, 10, 2));
        assertThat(entry.render())
                .contains("CANDIDATE    R. Merrick")
                .contains("OFFICE       Iowa House 63")
                .contains("ELECTION     general 11/03/2026")
                .contains("COMMITTEE    Merrick for Iowa")
                .contains("USE          candidate")
                .contains("DISPOSITION  accepted")
                .contains("ORDER        25-1044")
                .contains("AUTHORISED   Merrick for Iowa")
                .contains("BOUGHT       KWLO AMD immediately preemptible :30 12 at $18.00")
                .contains("TOTAL        $216.00")
                .contains("AIRED        10/01/2026 7:10:04a KWLO");
    }

    @Test
    void aRejectedEntryRendersTheReasonAndNothingAboutMoney() {
        assertThat(rejected("no drive time left").render())
                .contains("DISPOSITION  rejected")
                .contains("NOTE         no drive time left")
                .doesNotContain("TOTAL")
                .doesNotContain("BOUGHT");
    }

    @Test
    void airTimesAreAddedAsTheAsRunComesIn() {
        FileEntry entry = accepted();
        assertThat(entry.aired()).isEmpty();
        assertThat(entry.touched()).isEmpty();
        entry.noteAir(new Aired(LocalDate.of(2026, 10, 1), "7:10:04a", "KWLO"), LocalDate.of(2026, 10, 2));
        entry.noteAir(new Aired(LocalDate.of(2026, 10, 2), "9:38:00a", "KWLO"), LocalDate.of(2026, 10, 3));
        assertThat(entry.aired()).hasSize(2);
        assertThat(entry.touched()).contains(LocalDate.of(2026, 10, 3));
    }

    @Test
    void theFileKeepsEntriesInTheOrderTheyWereRequested() {
        PoliticalFile file = new PoliticalFile();
        LocalDate later = LocalDate.of(2026, 9, 20);
        file.file(accepted(aCandidacy(), Use.CANDIDATE, "25-1044", List.of(aBought()), later));
        file.file(accepted());
        file.file(accepted(aCandidacy("A. Hollis", "Merrick for Iowa"), "25-1044", List.of(aBought())));
        assertThat(file.entries())
                .extracting(
                        row -> List.<Object>of(row.requested(), row.candidacy().candidate()))
                .containsExactly(
                        List.of(ASKED, "A. Hollis"), List.of(ASKED, "R. Merrick"), List.of(later, "R. Merrick"));
        assertThat(file.size()).isEqualTo(3);
    }

    @Test
    void theFileFindsACandidateAndARace() {
        PoliticalFile file = new PoliticalFile();
        file.file(accepted());
        file.file(accepted(aCandidacy("A. Hollis", "Merrick for Iowa"), "25-1044", List.of(aBought())));
        assertThat(file.forCandidate("R. Merrick")).hasSize(1);
        assertThat(file.forRace("Iowa House 63")).hasSize(2);
        assertThat(file.forCandidate("NOBODY")).isEmpty();
    }

    @Test
    void theFileListsWhatSomebodyHasToChase() {
        PoliticalFile file = new PoliticalFile();
        file.file(accepted());
        file.file(accepted(aCandidacy("A. Hollis", "Merrick for Iowa"), "", List.of(aBought())));
        assertThat(file.incomplete())
                .extracting(row -> row.candidacy().candidate())
                .containsExactly("A. Hollis");
    }

    @Test
    void theFileListsWhatWasTurnedDown() {
        PoliticalFile file = new PoliticalFile();
        file.file(accepted());
        file.file(rejected("sold out"));
        assertThat(file.rejected()).hasSize(1);
    }

    @Test
    void theFileTotalsSpendByCandidateBiggestFirst() {
        PoliticalFile file = new PoliticalFile();
        file.file(accepted(List.of(aBought(12, "18.00"))));
        file.file(accepted(aCandidacy("A. Hollis", "Merrick for Iowa"), "25-1044", List.of(aBought(30, "18.00"))));
        file.file(rejected("sold out"));
        Map<String, Money> tally = file.spendByCandidate();
        assertThat(tally.keySet()).containsExactly("A. Hollis", "R. Merrick");
        assertThat(tally.get("A. Hollis")).isEqualTo(Money.dollars(540));
    }

    @Test
    void renderingTheWholeFileLeavesTheIncompleteEntriesOut() {
        PoliticalFile file = new PoliticalFile();
        file.file(accepted());
        file.file(accepted(aCandidacy("A. Hollis", "Merrick for Iowa"), "", List.of(aBought())));
        assertThat(file.renderAll()).contains("R. Merrick").doesNotContain("A. Hollis");
    }

    private static FileEntry airedOn(Candidacy candidacy, Use use, LocalDate... days) {
        FileEntry entry = accepted(candidacy, use, "26-0400", List.of(aBought()), ASKED);
        for (LocalDate day : days) {
            entry.noteAir(new Aired(day, "7:10:04a", "KWLO"), day.plusDays(1));
        }
        return entry;
    }

    @Test
    void anOpponentHasAWeekFromEachUseToAskForEqualTime() {
        PoliticalFile file = new PoliticalFile();
        file.file(airedOn(aCandidacy(), Use.CANDIDATE, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5)));
        Candidacy hollis = aCandidacy("A. Hollis", "Hollis for Iowa");
        List<PoliticalFile.Opening> open = file.equalOpportunities(hollis, LocalDate.of(2026, 10, 8));
        assertThat(open)
                .extracting(PoliticalFile.Opening::closes)
                .containsExactly(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 12));
        assertThat(open.get(0).opponent()).isEqualTo("R. Merrick");
        assertThat(open.get(0).order()).isEqualTo("26-0400");
        assertThat(open.get(0).use().day()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(file.equalOpportunities(hollis, LocalDate.of(2026, 10, 9)))
                .extracting(PoliticalFile.Opening::closes)
                .containsExactly(LocalDate.of(2026, 10, 12));
        assertThat(file.equalOpportunities(hollis, LocalDate.of(2026, 10, 13))).isEmpty();
    }

    @Test
    void aUseOpensNothingBeforeItAirsOrForTheCandidateWhoMadeIt() {
        PoliticalFile file = new PoliticalFile();
        file.file(airedOn(aCandidacy(), Use.CANDIDATE, LocalDate.of(2026, 10, 5)));
        assertThat(file.equalOpportunities(aCandidacy("A. Hollis", "x"), LocalDate.of(2026, 10, 4)))
                .isEmpty();
        assertThat(file.equalOpportunities(aCandidacy(), LocalDate.of(2026, 10, 6)))
                .isEmpty();
        file.file(accepted());
        assertThat(file.equalOpportunities(aCandidacy("A. Hollis", "x"), LocalDate.of(2026, 10, 6)))
                .hasSize(1);
    }

    @Test
    void issueAdvertisingAndOtherRacesOpenNoEqualTime() {
        PoliticalFile file = new PoliticalFile();
        file.file(airedOn(aCandidacy(), Use.ISSUE, LocalDate.of(2026, 10, 5)));
        Race senate = new Race("Iowa Senate 32", Election.GENERAL, LocalDate.of(2026, 11, 3));
        file.file(airedOn(
                new Candidacy("J. Coyle", senate, "Coyle for Senate", "Coyle for Senate"),
                Use.CANDIDATE,
                LocalDate.of(2026, 10, 5)));
        Race primary = new Race("Iowa House 63", Election.PRIMARY, LocalDate.of(2026, 6, 2));
        file.file(airedOn(
                new Candidacy("P. Ames", primary, "Ames for Iowa", "Ames for Iowa"),
                Use.CANDIDATE,
                LocalDate.of(2026, 10, 5)));
        assertThat(file.equalOpportunities(aCandidacy("A. Hollis", "x"), LocalDate.of(2026, 10, 6)))
                .isEmpty();
        assertThat(file.rejected()).isEmpty();
    }

    @Test
    void equalSpendIsListedInNameOrder() {
        PoliticalFile file = new PoliticalFile();
        file.file(accepted(aCandidacy("R. Merrick", "Merrick for Iowa"), "25-1044", List.of(aBought(10, "18.00"))));
        file.file(accepted(aCandidacy("A. Hollis", "Merrick for Iowa"), "25-1045", List.of(aBought(10, "18.00"))));
        file.file(accepted(aCandidacy("C. Brandt", "Merrick for Iowa"), "25-1046", List.of(aBought(11, "18.00"))));
        assertThat(file.spendByCandidate().keySet()).containsExactly("C. Brandt", "A. Hollis", "R. Merrick");
    }
}
