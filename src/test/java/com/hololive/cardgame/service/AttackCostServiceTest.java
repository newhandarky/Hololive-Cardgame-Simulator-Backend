package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AttackCostServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AttackCostService service = new AttackCostService(jdbcTemplate, new ObjectMapper());

    @Test
    void parseCostShouldNormalizePositiveEntriesAndIgnoreInvalidValues() {
        Map<String, Integer> result = service.parseCost("""
            {"red": 1, "COLORLESS": 2, "blue": 0, "green": -1}
            """);

        assertThat(result).containsEntry("RED", 1).containsEntry("COLORLESS", 2).hasSize(2);
    }

    @Test
    void parseCostShouldReturnEmptyWhenJsonIsMalformed() {
        Map<String, Integer> result = service.parseCost("{not-json");

        assertThat(result).isEmpty();
    }

    @Test
    void applyReductionShouldNotAllowNegativeCost() {
        Map<String, Integer> result = service.applyReduction(
            Map.of("RED", 1, "COLORLESS", 2),
            Map.of("RED", 3, "COLORLESS", 1)
        );

        assertThat(result).containsExactlyEntriesOf(Map.of("COLORLESS", 1));
    }

    @Test
    void resolvePaymentShouldPaySpecificColorBeforeColorless() {
        when(jdbcTemplate.queryForList(anyString(), eq(901L))).thenReturn(List.of(
            cheer(1L, 701L, "red-cheer", "RED"),
            cheer(2L, 702L, "blue-cheer", "BLUE"),
            cheer(3L, 703L, "green-cheer", "GREEN")
        ));
        AttackCostPaymentContext context = AttackCostPaymentContext.preview(
            100L,
            10L,
            901L,
            Map.of("RED", 1, "COLORLESS", 1),
            Map.of()
        );

        AttackCostPaymentResult result = service.resolvePayment(context);

        assertThat(result.required()).containsEntry("RED", 1).containsEntry("COLORLESS", 1).hasSize(2);
        assertThat(result.paid()).containsEntry("RED", 1).containsEntry("BLUE", 1).hasSize(2);
        assertThat(result.paidCheerCardIds()).containsExactly("red-cheer", "blue-cheer");
        assertThat(result.paidCheerCardInstanceIds()).containsExactly(701L, 702L);
        assertThat(result.paidColors()).containsExactly("RED", "BLUE");
        assertThat(result.consumed()).isFalse();
    }

    @Test
    void resolvePaymentShouldRejectWhenSpecificColorIsMissing() {
        when(jdbcTemplate.queryForList(anyString(), eq(901L))).thenReturn(List.of(
            cheer(1L, 701L, "blue-cheer", "BLUE")
        ));
        AttackCostPaymentContext context = AttackCostPaymentContext.preview(
            100L,
            10L,
            901L,
            Map.of("RED", 1),
            Map.of()
        );

        assertThatThrownBy(() -> service.resolvePayment(context))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("需要 RED Cheer");
    }

    @Test
    void resolvePaymentShouldReturnZeroSummaryForEmptyCost() {
        AttackCostPaymentContext context = AttackCostPaymentContext.preview(
            100L,
            10L,
            901L,
            Map.of(),
            Map.of()
        );

        AttackCostPaymentResult result = service.resolvePayment(context);

        assertThat(result.requiredTotal()).isZero();
        assertThat(result.paidTotal()).isZero();
        assertThat(result.toPaymentSummary()).containsEntry("consumed", false);
    }

    private Map<String, Object> cheer(Long rowId, Long cardInstanceId, String cardId, String color) {
        return Map.of(
            "cheer_row_id",
            rowId,
            "match_card_id",
            cardInstanceId,
            "cheer_card_id",
            cardId,
            "color",
            color
        );
    }
}
