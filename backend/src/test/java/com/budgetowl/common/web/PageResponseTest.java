package com.budgetowl.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The collection envelope, including the rule that an oversized page is clamped, not refused. */
class PageResponseTest {

    private static final List<String> THIRTY =
            IntStream.range(0, 30).mapToObj(Integer::toString).toList();

    @Test
    void returnsEverythingWhenItFitsOnOnePage() {
        PageResponse<String> page = PageResponse.of(THIRTY, 0, 50);

        assertThat(page.content()).hasSize(30);
        assertThat(page.totalElements()).isEqualTo(30);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    void countsPagesWithoutFloatingPoint() {
        assertThat(PageResponse.of(THIRTY, 0, 7).totalPages()).isEqualTo(5);
        assertThat(PageResponse.of(THIRTY, 0, 30).totalPages()).isEqualTo(1);
        assertThat(PageResponse.of(List.of(), 0, 10).totalPages()).isZero();
    }

    @Test
    void clampsAnOversizedPageRatherThanRefusingIt() {
        assertThat(PageResponse.of(THIRTY, 0, 5_000).size()).isEqualTo(PageResponse.MAXIMUM_SIZE);
    }

    @Test
    void fallsBackToTheDefaultSizeForAMeaninglessOne() {
        assertThat(PageResponse.of(THIRTY, 0, 0).size()).isEqualTo(PageResponse.DEFAULT_SIZE);
        assertThat(PageResponse.of(THIRTY, 0, -4).size()).isEqualTo(PageResponse.DEFAULT_SIZE);
    }

    @Test
    void returnsAnEmptyPageBeyondTheEndRatherThanFailing() {
        PageResponse<String> page = PageResponse.of(THIRTY, 99, 10);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(30);
    }

    @Test
    void treatsANegativePageAsTheFirstOne() {
        assertThat(PageResponse.of(THIRTY, -3, 10).page()).isZero();
    }
}
