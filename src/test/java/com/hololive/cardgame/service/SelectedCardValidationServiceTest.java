package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelectedCardValidationServiceTest {

    private final SelectedCardValidationService service = new SelectedCardValidationService();

    @Test
    void validateShouldSanitizeAndKeepFirstSelectionOrder() {
        List<Long> selected = service.validate(
            Arrays.asList(10L, null, -1L, 20L, 10L, 30L),
            1,
            3,
            List.of(10L, 20L, 30L)
        );

        assertThat(selected).containsExactly(10L, 20L, 30L);
    }

    @Test
    void validateShouldRejectSelectionBelowMinimum() {
        assertThatThrownBy(() -> service.validate(List.of(10L), 2, 3, List.of(10L, 20L, 30L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("選擇卡片數量不足，至少需要 2 張");
    }

    @Test
    void validateShouldRejectSelectionAboveMaximumAfterDeduplication() {
        assertThatThrownBy(() -> service.validate(List.of(10L, 20L, 30L), 0, 2, List.of(10L, 20L, 30L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("選擇卡片數量超過上限，最多只能選 2 張");
    }

    @Test
    void validateShouldRejectSelectionOutsideCandidates() {
        assertThatThrownBy(() -> service.validate(List.of(10L, 99L), 0, 2, List.of(10L, 20L, 30L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("選擇的卡片不在候選清單內: 99");
    }
}
