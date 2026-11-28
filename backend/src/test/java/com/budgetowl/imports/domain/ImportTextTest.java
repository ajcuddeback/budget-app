package com.budgetowl.imports.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The normaliser every stored value from a file goes through (rule 4, "After decoding"). */
class ImportTextTest {

    @Test
    void stripsTheBidiOverrideThatWouldDisguiseAPayee() {
        // U+202E RIGHT-TO-LEFT OVERRIDE makes "evil" display reversed beside the amount.
        assertThat(ImportText.normalise("‮live‬ Bank")).isEqualTo("live Bank");
        assertThat(ImportText.normalise("⁦Tesco⁩")).isEqualTo("Tesco");
    }

    @Test
    void keepsTheMarksRightToLeftNamesNeed() {
        assertThat(ImportText.normalise("‏שלום‎")).isEqualTo("‏שלום‎");
    }

    @Test
    void composesSoOneNameTypedTwoWaysIsOnePayee() {
        assertThat(ImportText.normalise("Café")).isEqualTo("Café");
    }

    @Test
    void removesControlAndZeroWidthCharactersAndCannotForgeALogLine() {
        assertThat(ImportText.normalise("SHOP\r\nINFO fake log line\u0000\u0007​﻿"))
                .isEqualTo("SHOP INFO fake log line");
        assertThat(ImportText.normalise("a\u0085b\u009Bc")).isEqualTo("a bc");
    }

    @Test
    void collapsesAndTrimsWhitespace() {
        assertThat(ImportText.normalise("  BLUE \t   BOTTLE  ")).isEqualTo("BLUE BOTTLE");
    }

    @Test
    void aPayeeOfOnlyControlCharactersIsNoPayee() {
        assertThat(ImportText.normalise("\u0001‮​")).isEmpty();
    }

    @Test
    void cutsAtACodePointBoundaryNeverThroughASurrogatePair() {
        String emoji = "😀";
        String text = "a".repeat(79) + emoji;

        String cut = ImportText.cut(text, 80);

        assertThat(cut).isEqualTo("a".repeat(79));
        assertThat(ImportText.cut("short", 80)).isEqualTo("short");
    }
}
