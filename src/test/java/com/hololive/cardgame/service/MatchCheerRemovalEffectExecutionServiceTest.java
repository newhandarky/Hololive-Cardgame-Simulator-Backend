package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchCheerRemovalEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbcTemplate;
    private MatchCheerRemovalEffectExecutionService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new MatchCheerRemovalEffectExecutionService(
            jdbcTemplate,
            new EffectTextParser(objectMapper),
            (matchId, userId, targetType, targetCardInstanceId, defaultOpponent) -> 10L,
            (matchId, holomemId) -> 1L,
            holomemId -> 100L
        );
    }

    @Test
    void executeRemoveCheerEffectShouldRemoveAttachedCheerToArchive() throws Exception {
        whenAttachedCheerRows(List.of(Map.of("id", 20L, "cheer_card_id", "YELLOW_CHEER", "match_card_id", 30L)));
        when(jdbcTemplate.update(eq("DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?"), eq(20L), eq(10L)))
            .thenReturn(1);
        whenNextArchiveOrder(5);
        whenArchiveUpdate(30L, 5, 1L, 1);

        Map<String, Object> summary = service.executeRemoveCheerEffect(
            1L,
            1L,
            "REMOVE_CHEER",
            node("{\"value\":1}"),
            "SELF",
            100L
        );

        assertThat(summary.get("effectType")).isEqualTo("REMOVE_CHEER");
        assertThat(summary.get("targetHolomemId")).isEqualTo(10L);
        assertThat(summary.get("targetHolomemCardInstanceId")).isEqualTo(100L);
        assertThat(summary.get("removeRequested")).isEqualTo(1);
        assertThat(summary.get("removeApplied")).isEqualTo(1);
        assertThat(summary.get("removedCheerCardIds")).isEqualTo(List.of("YELLOW_CHEER"));
        assertThat(summary.get("archivedCheerCardInstanceIds")).isEqualTo(List.of(30L));
    }

    @Test
    void executeRemoveCheerEffectShouldUseRawTextCountAndApplyAvailableOnly() throws Exception {
        whenAttachedCheerRows(List.of(Map.of("id", 20L, "cheer_card_id", "BLUE_CHEER", "match_card_id", 30L)));
        when(jdbcTemplate.update(eq("DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?"), eq(20L), eq(10L)))
            .thenReturn(1);
        whenNextArchiveOrder(7);
        whenArchiveUpdate(30L, 7, 1L, 1);

        Map<String, Object> summary = service.executeRemoveCheerEffect(
            1L,
            1L,
            "REMOVE_CHEER",
            node("{\"rawText\":\"このホロメンのエール2枚をアーカイブできる。\"}"),
            "SELF",
            100L
        );

        assertThat(summary.get("removeRequested")).isEqualTo(2);
        assertThat(summary.get("removeApplied")).isEqualTo(1);
    }

    @Test
    void executeRemoveStageCheerEffectShouldRemoveFromAnyOwnStageHolomem() throws Exception {
        whenStageCheerRows(List.of(Map.of("id", 21L, "cheer_card_id", "RED_CHEER", "match_holomem_id", 11L, "match_card_id", 31L)));
        when(jdbcTemplate.update(eq("DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?"), eq(21L), eq(11L)))
            .thenReturn(1);
        whenNextArchiveOrder(8);
        whenArchiveUpdate(31L, 8, 1L, 1);

        Map<String, Object> summary = service.executeRemoveStageCheerEffect(
            1L,
            1L,
            "REMOVE_STAGE_CHEER",
            node("{\"amount\":1}")
        );

        assertThat(summary.get("effectType")).isEqualTo("REMOVE_STAGE_CHEER");
        assertThat(summary.get("removeRequested")).isEqualTo(1);
        assertThat(summary.get("removeApplied")).isEqualTo(1);
        assertThat(summary.get("removedCheerCardIds")).isEqualTo(List.of("RED_CHEER"));
        assertThat(summary.get("sourceHolomemIds")).isEqualTo(List.of(11L));
        assertThat(summary.get("archivedCheerCardInstanceIds")).isEqualTo(List.of(31L));
    }

    @Test
    void executeRemoveStageCheerEffectShouldFallbackToStageCardByCardIdWhenMatchCardIdMissing() throws Exception {
        whenStageCheerRows(List.of(Map.of("id", 21L, "cheer_card_id", "RED_CHEER", "match_holomem_id", 11L)));
        when(jdbcTemplate.update(eq("DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?"), eq(21L), eq(11L)))
            .thenReturn(1);
        whenResolveStageCardByCardId("RED_CHEER", 31L);
        whenNextArchiveOrder(8);
        whenArchiveUpdate(31L, 8, 1L, 1);

        Map<String, Object> summary = service.executeRemoveStageCheerEffect(
            1L,
            1L,
            "REMOVE_STAGE_CHEER",
            node("{}")
        );

        assertThat(summary.get("removeApplied")).isEqualTo(1);
        assertThat(summary.get("archivedCheerCardInstanceIds")).isEqualTo(List.of(31L));
    }

    @Test
    void executeRemoveCheerEffectShouldKeepSummaryWhenDeleteFails() throws Exception {
        whenAttachedCheerRows(List.of(Map.of("id", 20L, "cheer_card_id", "YELLOW_CHEER", "match_card_id", 30L)));
        when(jdbcTemplate.update(eq("DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?"), eq(20L), eq(10L)))
            .thenReturn(0);

        Map<String, Object> summary = service.executeRemoveCheerEffect(
            1L,
            1L,
            "REMOVE_CHEER",
            node("{}"),
            "SELF",
            100L
        );

        assertThat(summary.get("removeApplied")).isEqualTo(0);
        assertThat(summary.get("removedCheerCardIds")).isEqualTo(List.of());
        assertThat(summary.get("archivedCheerCardInstanceIds")).isEqualTo(List.of());
    }

    private void whenAttachedCheerRows(List<Map<String, Object>> rows) {
        when(jdbcTemplate.queryForList(
            anyString(),
            eq(10L),
            any(Integer.class)
        )).thenReturn(rows);
    }

    private void whenStageCheerRows(List<Map<String, Object>> rows) {
        when(jdbcTemplate.queryForList(
            anyString(),
            eq(1L),
            eq(1L),
            any(Integer.class)
        )).thenReturn(rows);
    }

    private void whenNextArchiveOrder(int order) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(1L), eq("ARCHIVE"))).thenReturn(order);
    }

    private void whenArchiveUpdate(Long cardInstanceId, int order, Long ownerUserId, int updated) {
        when(jdbcTemplate.update(anyString(), eq(order), eq(cardInstanceId), eq(1L), eq(ownerUserId))).thenReturn(updated);
    }

    private void whenResolveStageCardByCardId(String cardId, Long cardInstanceId) throws Exception {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq(1L), eq(1L), eq(cardId))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultSetExtractor<Long> extractor = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getLong("id")).thenReturn(cardInstanceId);
            return extractor.extractData(rs);
        });
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
