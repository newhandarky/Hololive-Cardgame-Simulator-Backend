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
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchArchiveBloomEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final SearchCriteriaParser searchCriteriaParser = new SearchCriteriaParser(jdbcTemplate, effectTextParser);
    private final MatchEffectSearchService searchService = new MatchEffectSearchService(jdbcTemplate, effectTextParser);
    private final MatchArchiveBloomEffectExecutionService service = new MatchArchiveBloomEffectExecutionService(
        jdbcTemplate,
        effectTextParser,
        searchCriteriaParser,
        searchService
    );

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeBloomFromArchiveEffectShouldBloomMatchingStageTargetWithArchiveCard() throws Exception {
        whenCurrentTurn(4);
        whenTargetCandidates(
            List.of(targetCandidate(501L, 101L, "TARGET_DEBUT", "測試目標", "DEBUT", 20, 3, "#FLOW"))
        );
        whenArchiveBloomCard(202L, "TARGET_FIRST", "FIRST", 160, 1, 20);
        when(jdbcTemplate.update(contains("AND zone = 'ARCHIVE'"), eq(202L), eq(1L), eq(10L))).thenReturn(1);
        when(jdbcTemplate.query(
            contains("SELECT match_card_id FROM match_holomems"),
            any(ResultSetExtractor.class),
            eq(501L)
        )).thenReturn(202L);
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(stack_order)"), eq(Integer.class), eq(501L))).thenReturn(1);

        Map<String, Object> summary = service.executeBloomFromArchiveEffect(
            1L,
            10L,
            "BLOOM_FROM_ARCHIVE",
            node("{\"searchCriteria\":{\"cardType\":\"MEMBER\",\"level\":\"DEBUT\",\"tag\":\"#FLOW\"}}")
        );

        assertThat(summary)
            .containsEntry("effectType", "BLOOM_FROM_ARCHIVE")
            .containsEntry("applied", true)
            .containsEntry("targetHolomemId", 501L)
            .containsEntry("targetHolomemCardInstanceId", 202L)
            .containsEntry("bloomCardInstanceId", 202L)
            .containsEntry("bloomCardId", "TARGET_FIRST")
            .containsEntry("bloomLevelType", "FIRST");
        verify(jdbcTemplate).update(
            contains("UPDATE match_holomems"),
            eq(202L),
            eq("TARGET_FIRST"),
            eq("FIRST"),
            eq(4),
            eq(501L),
            eq(1L),
            eq(10L)
        );
        verify(jdbcTemplate).update(
            contains("INSERT INTO match_holomem_stack_cards"),
            eq(501L),
            eq(202L),
            eq(1)
        );
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeBloomFromArchiveEffectShouldNoOpWhenTargetAlreadyBloomedThisTurn() throws Exception {
        whenCurrentTurn(4);
        whenTargetCandidates(
            List.of(targetCandidate(501L, 101L, "TARGET_DEBUT", "測試目標", "DEBUT", 0, 4, "#FLOW"))
        );

        Map<String, Object> summary = service.executeBloomFromArchiveEffect(
            1L,
            10L,
            "BLOOM_FROM_ARCHIVE",
            node("{\"searchCriteria\":{\"cardType\":\"MEMBER\",\"level\":\"DEBUT\",\"tag\":\"#FLOW\"},\"rawText\":\"Archive Bloom\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "BLOOM_FROM_ARCHIVE")
            .containsEntry("applied", false)
            .containsEntry("reason", "沒有可從 Archive 進行 Bloom 的目標")
            .containsEntry("rawText", "Archive Bloom");
        verify(jdbcTemplate, never()).update(contains("AND zone = 'ARCHIVE'"), any(), any(), any());
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeBloomFromArchiveEffectShouldNoOpWhenArchiveBloomCardMissing() throws Exception {
        whenCurrentTurn(4);
        whenTargetCandidates(
            List.of(targetCandidate(501L, 101L, "TARGET_DEBUT", "測試目標", "DEBUT", 0, null, "#FLOW"))
        );
        when(jdbcTemplate.query(
            contains("FROM match_cards mc"),
            any(ResultSetExtractor.class),
            eq(1L),
            eq(10L),
            eq("測試目標"),
            eq(0),
            eq(0)
        )).thenReturn(null);

        Map<String, Object> summary = service.executeBloomFromArchiveEffect(
            1L,
            10L,
            "BLOOM_FROM_ARCHIVE",
            node("{\"searchCriteria\":{\"cardType\":\"MEMBER\",\"level\":\"DEBUT\",\"tag\":\"#FLOW\"}}")
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "Archive 中找不到可用的 Bloom 卡");
        verify(jdbcTemplate, never()).update(contains("AND zone = 'ARCHIVE'"), any(), any(), any());
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeBloomFromArchiveEffectShouldNoOpWhenArchiveMoveFails() throws Exception {
        whenCurrentTurn(4);
        whenTargetCandidates(
            List.of(targetCandidate(501L, 101L, "TARGET_DEBUT", "測試目標", "DEBUT", 0, null, "#FLOW"))
        );
        whenArchiveBloomCard(202L, "TARGET_FIRST", "FIRST", 160, 1, 0);
        when(jdbcTemplate.update(contains("AND zone = 'ARCHIVE'"), eq(202L), eq(1L), eq(10L))).thenReturn(0);

        Map<String, Object> summary = service.executeBloomFromArchiveEffect(
            1L,
            10L,
            "BLOOM_FROM_ARCHIVE",
            node("{\"searchCriteria\":{\"cardType\":\"MEMBER\",\"level\":\"DEBUT\",\"tag\":\"#FLOW\"}}")
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "Archive Bloom 移動卡片失敗");
        verify(jdbcTemplate, never()).update(contains("UPDATE match_holomems"), any(), any(), any(), any(), any(), any(), any());
    }

    private void whenCurrentTurn(int turnNumber) {
        when(jdbcTemplate.query(
            contains("SELECT turn_number"),
            any(ResultSetExtractor.class),
            eq(1L)
        )).thenReturn(turnNumber);
    }

    private void whenTargetCandidates(List<Map<String, Object>> candidates) {
        when(jdbcTemplate.query(
            contains("FROM match_holomems"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq("DEBUT"),
            eq("DEBUT"),
            eq(""),
            eq(""),
            eq("#FLOW"),
            eq("#FLOW")
        )).thenReturn(candidates);
    }

    private void whenArchiveBloomCard(Long instanceId, String cardId, String levelType, int hp, int bloomLevel, int minHp) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("card_instance_id", instanceId);
        row.put("card_id", cardId);
        row.put("level_type", levelType);
        row.put("hp", hp);
        row.put("bloom_level", bloomLevel);
        when(jdbcTemplate.query(
            contains("FROM match_cards mc"),
            any(ResultSetExtractor.class),
            eq(1L),
            eq(10L),
            eq("測試目標"),
            eq(0),
            eq(minHp)
        )).thenReturn(row);
    }

    private Map<String, Object> targetCandidate(
        Long holomemId,
        Long matchCardId,
        String cardId,
        String name,
        String currentLevel,
        int damageTaken,
        Integer lastBloomTurn,
        String tag
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("holomem_id", holomemId);
        row.put("match_card_id", matchCardId);
        row.put("card_id", cardId);
        row.put("current_level", currentLevel);
        row.put("damage_taken", damageTaken);
        row.put("last_bloom_turn", lastBloomTurn);
        row.put("is_rested", false);
        row.put("card_type", "MEMBER");
        row.put("level_type", currentLevel);
        row.put("name", name);
        row.put("tags_json", "[\"" + tag + "\"]");
        row.put("main_color", "RED");
        row.put("sub_color", null);
        row.put("remain_hp", 100);
        return row;
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
