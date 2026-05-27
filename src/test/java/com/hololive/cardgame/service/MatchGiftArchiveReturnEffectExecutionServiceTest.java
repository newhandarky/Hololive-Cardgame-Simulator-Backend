package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchGiftArchiveReturnEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchGiftArchiveReturnEffectExecutionService service = new MatchGiftArchiveReturnEffectExecutionService(
        jdbcTemplate,
        new EffectTextParser(objectMapper)
    );

    @Test
    @SuppressWarnings("unchecked")
    void executeShouldMoveFirstArchivedSupportCardFromLatestHoloxRevealToHand() {
        ObjectNode effectNode = objectMapper.createObjectNode();
        whenLatestHoloxPayload("""
            {
              "artName": "ホロックスロット",
              "holoxReveal": {
                "archivedSupportCardInstanceIds": [900, 901]
              }
            }
            """);
        when(
            jdbcTemplate.queryForObject(
                contains("SELECT COALESCE(MAX(order_index), 0) + 1"),
                eq(Integer.class),
                eq(1L),
                eq(10L),
                eq("HAND")
            )
        ).thenReturn(4);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(4), eq(900L), eq(1L), eq(10L))).thenReturn(1);
        when(jdbcTemplate.query(contains("SELECT card_id FROM match_cards"), any(ResultSetExtractor.class), eq(900L)))
            .thenReturn("SUPPORT-A");

        Map<String, Object> summary = service.executeReplaceArchiveWithHandEffect(
            1L,
            10L,
            "REPLACE_ARCHIVE_WITH_HAND",
            effectNode,
            100L
        );

        assertThat(summary)
            .containsEntry("effectType", "REPLACE_ARCHIVE_WITH_HAND")
            .containsEntry("applied", true)
            .containsEntry("movedCardInstanceId", 900L)
            .containsEntry("movedCardId", "SUPPORT-A")
            .containsEntry("movedCount", 1);
        assertThat(summary.get("candidateCardInstanceIds")).isEqualTo(List.of(900L, 901L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeShouldReturnNoOpWhenLatestHoloxRevealHasNoArchivedSupport() {
        ObjectNode effectNode = objectMapper.createObjectNode();
        whenLatestHoloxPayload("""
            {
              "artName": "ホロックスロット",
              "holoxReveal": {
                "archivedSupportCardInstanceIds": []
              }
            }
            """);

        Map<String, Object> summary = service.executeReplaceArchiveWithHandEffect(
            1L,
            10L,
            "REPLACE_ARCHIVE_WITH_HAND",
            effectNode,
            100L
        );

        assertThat(summary)
            .containsEntry("effectType", "REPLACE_ARCHIVE_WITH_HAND")
            .containsEntry("applied", false)
            .containsEntry("reason", "本次公開沒有支援卡可改為回手");
        assertThat(summary.get("candidateCardInstanceIds")).isEqualTo(List.of());
        verify(jdbcTemplate, never()).update(contains("UPDATE match_cards"), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private void whenLatestHoloxPayload(String payloadText) {
        when(
            jdbcTemplate.query(
                contains("payload ->> 'artName' = 'ホロックスロット'"),
                any(ResultSetExtractor.class),
                eq(1L),
                eq(10L),
                eq("100")
            )
        ).thenReturn(payloadText);
    }
}
