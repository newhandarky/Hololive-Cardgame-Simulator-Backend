package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchGiftReattachEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchCardSelectionRequestResolver cardSelectionRequestResolver = new MatchCardSelectionRequestResolver(
        effectTextParser
    );
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    void executeShouldReturnNoOpWhenDiceConditionMisses() {
        MatchGiftReattachEffectExecutionService service = newService(false);
        ObjectNode effectNode = reattachEffectNode("自分のステージのエール1枚を、このホロメンに付け替えられる。");

        Map<String, Object> summary = service.executeReattachEffect(
            1L,
            10L,
            "REATTACH",
            effectNode,
            "SELF",
            100L
        );

        assertThat(summary)
            .containsEntry("effectType", "REATTACH")
            .containsEntry("applied", false)
            .containsEntry("reason", "骰子條件未命中");
        verify(jdbcTemplate, never()).queryForList(any(String.class), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeShouldMoveAttachedStageCheerToTargetHolomem() {
        MatchGiftReattachEffectExecutionService service = newService(true);
        ObjectNode effectNode = reattachEffectNode("自分のステージのエール1枚を、このホロメンに付け替えられる。");
        when(
            jdbcTemplate.queryForList(
                contains("FROM match_holomem_cheers c"),
                eq(1L),
                eq(10L),
                eq(22L),
                eq(2)
            )
        ).thenReturn(List.of(
            Map.of(
                "cheer_row_id", 301L,
                "match_card_id", 401L,
                "cheer_card_id", "CHEER-A",
                "match_holomem_id", 11L
            )
        ));
        when(jdbcTemplate.update(contains("DELETE FROM match_holomem_cheers WHERE id = ?"), eq(301L))).thenReturn(1);
        when(
            jdbcTemplate.query(
                contains("INSERT INTO match_holomem_cheers"),
                any(ResultSetExtractor.class),
                eq(22L),
                eq(401L),
                eq("CHEER-A")
            )
        ).thenReturn(701L);

        Map<String, Object> summary = service.executeReattachEffect(
            1L,
            10L,
            "REATTACH",
            effectNode,
            "SELF",
            100L
        );

        assertThat(summary)
            .containsEntry("effectType", "REATTACH")
            .containsEntry("moveRequested", 1)
            .containsEntry("moveApplied", 1)
            .containsEntry("targetHolomemId", 22L)
            .containsEntry("targetHolomemCardInstanceId", 2200L)
            .containsEntry("sourceMode", "STAGE");
        assertThat(summary.get("movedCheerCardIds")).isEqualTo(List.of("CHEER-A"));
        assertThat(summary.get("movedCheerRowIds")).isEqualTo(List.of(701L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeShouldMoveStoredHolderArchiveCheerToTargetHolomem() {
        MatchGiftReattachEffectExecutionService service = newService(true);
        ObjectNode effectNode = reattachEffectNode("このホロメンのエール1枚を、自分のホロメンに付け替えられる。");
        effectNode.putArray("giftHolderAttachedCheerCardInstanceIds").add(401L);
        Map<String, Object> archivedCheerRow = new LinkedHashMap<>();
        archivedCheerRow.put("cheer_row_id", null);
        archivedCheerRow.put("match_card_id", 401L);
        archivedCheerRow.put("cheer_card_id", "CHEER-A");
        archivedCheerRow.put("match_holomem_id", null);
        archivedCheerRow.put("zone", "ARCHIVE");
        when(
            jdbcTemplate.query(
                contains("LEFT JOIN match_holomem_cheers"),
                any(ResultSetExtractor.class),
                eq(1L),
                eq(10L),
                eq(401L)
            )
        ).thenReturn(archivedCheerRow);
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(401L), eq(1L), eq(10L))).thenReturn(1);
        when(
            jdbcTemplate.query(
                contains("INSERT INTO match_holomem_cheers"),
                any(ResultSetExtractor.class),
                eq(22L),
                eq(401L),
                eq("CHEER-A")
            )
        ).thenReturn(702L);

        Map<String, Object> summary = service.executeReattachEffect(
            1L,
            10L,
            "REATTACH",
            effectNode,
            "SELF",
            100L
        );

        assertThat(summary)
            .containsEntry("effectType", "REATTACH")
            .containsEntry("moveRequested", 1)
            .containsEntry("moveApplied", 1)
            .containsEntry("targetHolomemId", 22L)
            .containsEntry("targetHolomemCardInstanceId", 2200L)
            .containsEntry("sourceMode", "ARCHIVE");
        assertThat(summary.get("movedCheerCardIds")).isEqualTo(List.of("CHEER-A"));
        assertThat(summary.get("movedCheerRowIds")).isEqualTo(List.of(702L));
    }

    private MatchGiftReattachEffectExecutionService newService(boolean diceHit) {
        return new MatchGiftReattachEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            cardSelectionRequestResolver,
            (rawText, effectNode, effectType) -> diceHit,
            this::noOpSummary,
            (matchId, userId) -> 20L,
            (matchId, ownerUserId, holderCardInstanceId, effectNode) -> 11L,
            (matchId, userId, targetType, targetHolomemCardInstanceId, rawText, preferSelfBackTarget, excludedHolomemId) -> 22L,
            (matchId, holomemId) -> 10L,
            (matchId, userId, targetHolomemCardInstanceId) -> 33L,
            this::cheerFromZone,
            matchHolomemId -> matchHolomemId == null ? null : matchHolomemId * 100L
        );
    }

    private Map<String, Object> noOpSummary(String effectType, JsonNode effectNode, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", false);
        summary.put("reason", reason);
        return summary;
    }

    private Map<String, Object> cheerFromZone(Long matchId, Long userId, String zone) {
        if (!"ARCHIVE".equals(zone) && !"CHEER_DECK".equals(zone)) {
            return null;
        }
        return Map.of(
            "id", 501L,
            "card_id", "CHEER-ZONE",
            "zone", zone
        );
    }

    private ObjectNode reattachEffectNode(String rawText) {
        ObjectNode effectNode = objectMapper.createObjectNode();
        effectNode.put("rawText", rawText);
        effectNode.put("value", 1);
        return effectNode;
    }
}
