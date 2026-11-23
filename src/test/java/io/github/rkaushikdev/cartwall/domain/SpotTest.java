package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SpotTest {

    private static final LocalDate JANUARY = LocalDate.of(2025, 1, 6);
    private static final LocalDate MARCH = LocalDate.of(2025, 3, 3);

    /** The cut most of these tests file, with the one thing each test cares about changed. */
    static final class ACut {
        private String cart = "C1204";
        private String isci = "HYVE2501";
        private String advertiser = "HYVEE";
        private SpotLength length = THIRTY;
        private Duration actual;
        private LocalDate firstAir = JANUARY;
        private LocalDate lastAir;

        ACut cart(String cart) {
            this.cart = cart;
            return this;
        }

        ACut isci(String isci) {
            this.isci = isci;
            return this;
        }

        ACut advertiser(String advertiser) {
            this.advertiser = advertiser;
            return this;
        }

        ACut length(SpotLength length) {
            this.length = length;
            return this;
        }

        ACut actual(Duration actual) {
            this.actual = actual;
            return this;
        }

        ACut firstAir(LocalDate firstAir) {
            this.firstAir = firstAir;
            return this;
        }

        ACut lastAir(LocalDate lastAir) {
            this.lastAir = lastAir;
            return this;
        }

        Cut build() {
            return new Cut(
                    cart,
                    isci,
                    advertiser,
                    length,
                    actual != null ? actual : length.air(),
                    firstAir,
                    Optional.ofNullable(lastAir),
                    false);
        }
    }

    static ACut aCut() {
        return new ACut();
    }

    @Test
    void onlyTheLengthsOnTheCardCanBeSold() {
        assertThat(new SpotLength(30).seconds()).isEqualTo(30);
        assertThat(SpotLength.parse(":60")).isEqualTo(SIXTY);
        assertThat(SpotLength.parse("1:00")).isEqualTo(SIXTY);
        for (int bad : List.of(25, 35, 0, 300)) {
            assertThatThrownBy(() -> new SpotLength(bad))
                    .isInstanceOf(SpotError.class)
                    .hasMessageContaining("is not sold here");
        }
    }

    @Test
    void aUnitIsAThirtyAndAnythingShorterStillCostsOne() {
        assertThat(new SpotLength(10).units()).isEqualTo(1);
        assertThat(new SpotLength(15).units()).isEqualTo(1);
        assertThat(new SpotLength(30).units()).isEqualTo(1);
        assertThat(new SpotLength(45).units()).isEqualTo(2);
        assertThat(new SpotLength(60).units()).isEqualTo(2);
        assertThat(new SpotLength(120).units()).isEqualTo(4);
    }

    @Test
    void lengthsPrintAndSortTheWayTheLogShowsThem() {
        assertThat(THIRTY).hasToString(":30");
        assertThat(SIXTY).hasToString("1:00");
        assertThat(new SpotLength(90)).hasToString("1:30");
        assertThat(Stream.of(SIXTY, THIRTY, new SpotLength(10)).sorted().findFirst())
                .contains(new SpotLength(10));
    }

    @Test
    void aCutMayRunHalfASecondEitherSideOfItsSoldLength() {
        assertThat(THIRTY.fits(new Duration(300))).isTrue();
        assertThat(THIRTY.fits(new Duration(295))).isTrue();
        assertThat(THIRTY.fits(new Duration(305))).isTrue();
        assertThat(THIRTY.fits(new Duration(294))).isFalse();
        assertThat(THIRTY.fits(new Duration(306))).isFalse();
    }

    @Test
    void aCutThatRunsLongIsRefusedWhenItIsFiled() {
        assertThat(aCut().actual(new Duration(304)).build().isLong()).isTrue();
        assertThat(aCut().actual(new Duration(296)).build().isShort()).isTrue();
        assertThatThrownBy(() -> aCut().actual(new Duration(320)).build())
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("runs :32 against a :30");
    }

    @Test
    void cartNumbersAndIsciCodesHaveAShape() {
        assertThat(aCut().cart("C1204").build().cart()).isEqualTo("C1204");
        assertThat(aCut().cart("H900").build().cart()).isEqualTo("H900");
        for (String bad : List.of("1204", "CC1204", "C12", "c1204")) {
            assertThatThrownBy(() -> aCut().cart(bad).build())
                    .isInstanceOf(SpotError.class)
                    .hasMessageContaining("not a cart number");
        }
        for (String bad : List.of("HYV2501", "hyve2501", "HYVE", "HYVE250120255")) {
            assertThatThrownBy(() -> aCut().isci(bad).build())
                    .isInstanceOf(SpotError.class)
                    .hasMessageContaining("not an ISCI code");
        }
    }

    @Test
    void anAdIdIsFiledAlongsideTheOldIsciCodes() {
        Cut adId = aCut().isci("HYVE0025000").build();
        assertThat(adId.code().kind()).isSameAs(CopyCode.Kind.AD_ID);
        assertThat(adId.code().prefix()).isEqualTo("HYVE");
        assertThat(aCut().build().code().kind()).isSameAs(CopyCode.Kind.ISCI);
        assertThat(CopyCode.parse("HYVE2501")).hasToString("HYVE2501");
        assertThat(CopyCode.parse("MOPA25F0Q7Z").kind()).isSameAs(CopyCode.Kind.AD_ID);
    }

    @Test
    void aTelevisionVersionOfAnAdIdIsNotAudio() {
        assertThatThrownBy(() -> aCut().isci("HYVE0025000H").build())
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("high definition television version");
        assertThatThrownBy(() -> CopyCode.parse("HYVE0025000D"))
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("the 3D television version");
        assertThatThrownBy(() -> CopyCode.parse("HYVE0025000X"))
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("version mark Ad-ID does not issue");
    }

    @Test
    void aCodeThatIsNeitherIsRefusedNamingBoth() {
        for (String bad : List.of("HYVE250", "HYVE25012", "HYVE25A1", "HYVE-025000", "")) {
            assertThatThrownBy(() -> CopyCode.parse(bad))
                    .isInstanceOf(SpotError.class)
                    .hasMessageContaining("is not an ISCI code or an Ad-ID");
        }
        assertThatThrownBy(() -> CopyCode.parse(null)).isInstanceOf(SpotError.class);
        assertThatThrownBy(() -> new CopyCode("HYVE0025000", CopyCode.Kind.ISCI))
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("is not an ISCI code");
        assertThatThrownBy(() -> new CopyCode("HYVE2501", CopyCode.Kind.AD_ID))
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("is not an Ad-ID");
    }

    @Test
    void theLibraryFindsEverythingOnOneAgencysPrefix() {
        CutLibrary library = new CutLibrary();
        library.file(
                aCut().cart("C1300").advertiser("DEALF").isci("MOPA0000101").build());
        library.file(aCut().cart("C1204").build());
        library.file(aCut().cart("C1250").advertiser("DEALC").isci("MOPA2502").build());
        assertThat(library.withPrefix("MOPA")).extracting(Cut::cart).containsExactly("C1250", "C1300");
        assertThat(library.withPrefix("ZZZZ")).isEmpty();
    }

    @Test
    void aCutHasToBelongToSomebody() {
        assertThatThrownBy(() -> aCut().advertiser("").build())
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("names no advertiser");
    }

    @Test
    void aCutCannotBeKilledBeforeItStarts() {
        assertThatThrownBy(() -> aCut().firstAir(MARCH).lastAir(JANUARY).build())
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("killed before it starts");
    }

    @Test
    void aCutRunsBetweenItsDatesAndNotOutsideThem() {
        Cut cut = aCut().firstAir(JANUARY).lastAir(LocalDate.of(2025, 1, 31)).build();
        assertThat(cut.runsOn(LocalDate.of(2025, 1, 5))).isFalse();
        assertThat(cut.runsOn(JANUARY)).isTrue();
        assertThat(cut.runsOn(LocalDate.of(2025, 1, 31))).isTrue();
        assertThat(cut.runsOn(LocalDate.of(2025, 2, 1))).isFalse();
    }

    @Test
    void aCutWithNoKillDateRunsUntilSomebodyStopsIt() {
        assertThat(aCut().build().runsOn(LocalDate.of(2030, 1, 1))).isTrue();
    }

    @Test
    void filingTheSameCartTwiceIsRefused() {
        CutLibrary library = new CutLibrary();
        library.file(aCut().build());
        assertThatThrownBy(() -> library.file(aCut().build()))
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("already filed");
        assertThat(library.size()).isEqualTo(1);
        assertThat(library.contains("C1204")).isTrue();
    }

    @Test
    void aRedubReplacesTheCartInPlace() {
        CutLibrary library = new CutLibrary();
        library.file(aCut().actual(new Duration(299)).build());
        library.refile(aCut().actual(new Duration(301)).build());
        assertThat(library.cut("C1204").actual()).isEqualTo(new Duration(301));
        assertThat(library.size()).isEqualTo(1);
        assertThatThrownBy(() -> library.refile(aCut().cart("C9999").build()))
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("never been filed");
    }

    @Test
    void askingForACartNobodyDubbed() {
        assertThatThrownBy(() -> new CutLibrary().cut("C1204"))
                .isInstanceOf(SpotError.class)
                .hasMessageContaining("not in the library");
    }

    @Test
    void killingACutLeavesItOnFileAndStopsItRunning() {
        CutLibrary library = new CutLibrary();
        library.file(aCut().build());
        Cut killed = library.kill("C1204", LocalDate.of(2025, 2, 28));
        assertThat(killed.killed()).isTrue();
        assertThat(killed.lastAir()).contains(LocalDate.of(2025, 2, 28));
        assertThat(killed.runsOn(LocalDate.of(2025, 3, 1))).isFalse();
        // It still ran in February, and the affidavit has to be able to say so.
        assertThat(killed.runsOn(LocalDate.of(2025, 2, 1))).isTrue();
        assertThat(library.cut("C1204").killed()).isTrue();
        assertThat(library.size()).isEqualTo(1);
    }

    @Test
    void theLibraryListsWhatCanRunOnADayInCartOrder() {
        CutLibrary library = new CutLibrary();
        library.file(aCut().cart("C1300").advertiser("FAREWAY").isci("FARE2502").build());
        library.file(aCut().cart("C1204").build());
        library.file(aCut().cart("C1205").firstAir(MARCH).build());
        assertThat(library.running(JANUARY)).extracting(Cut::cart).containsExactly("C1204", "C1300");
        assertThat(library.running(MARCH)).extracting(Cut::cart).containsExactly("C1204", "C1205", "C1300");
        assertThat(library.running(JANUARY, "HYVEE")).extracting(Cut::cart).containsExactly("C1204");
    }

    @Test
    void theLibraryListsEverythingOneAdvertiserEverHad() {
        CutLibrary library = new CutLibrary();
        library.file(aCut().cart("C1204").build());
        library.file(aCut().cart("C1205").build());
        library.kill("C1205", LocalDate.of(2025, 1, 20));
        library.file(aCut().cart("C1300").advertiser("FAREWAY").isci("FARE2502").build());
        assertThat(library.forAdvertiser("HYVEE")).extracting(Cut::cart).containsExactly("C1204", "C1205");
        assertThat(library.forAdvertiser("NOBODY")).isEmpty();
    }

    @Test
    void whichLengthsAnAdvertiserCanActuallyBeScheduledIn() {
        CutLibrary library = new CutLibrary();
        library.file(aCut().cart("C1204").length(THIRTY).build());
        library.file(
                aCut().cart("C1205").length(SIXTY).actual(new Duration(600)).build());
        library.file(aCut().cart("C1206")
                .length(new SpotLength(15))
                .actual(new Duration(150))
                .build());
        library.kill("C1206", LocalDate.of(2025, 1, 10));
        assertThat(library.lengthsOnHand("HYVEE", MARCH)).isEqualTo(Set.of(THIRTY, SIXTY));
        assertThat(library.lengthsOnHand("HYVEE", JANUARY)).isEqualTo(Set.of(THIRTY, SIXTY, new SpotLength(15)));
        assertThat(library.lengthsOnHand("NOBODY", MARCH)).isEmpty();
    }
}
