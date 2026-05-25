package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchCheerDeckReturnEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchCardSelectionRequestResolver requestResolver = new MatchCardSelectionRequestResolver(effectTextParser);
    private final MatchCheerDeckReturnEffectExecutionService service = new MatchCheerDeckReturnEffectExecutionService(
        jdbcTemplate,
        effectTextParser,
        requestResolver
    );

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeReturnCheerToDeckBottomEffectShouldReturnArchiveCheerToCheerDeckBottom() throws Exception {
        whenArchiveCandidates(List.of(archiveCheer(101L, "CHEER_RED", "RED")), "", 1);
        whenNextCheerDeckOrder(7);
        when(jdbcTemplate.update(
            contains("AND zone = ?"),
            eq(7),
            eq(101L),
            eq(1L),
            eq(10L),
            eq("ARCHIVE")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeReturnCheerToDeckBottomEffect(
            1L,
            10L,
            "RETURN_CHEER_TO_DECK_BOTTOM",
            node("{\"rawText\":\"自分のアーカイブのエール1枚をエールデッキの下に戻せる。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "RETURN_CHEER_TO_DECK_BOTTOM")
            .containsEntry("sourceZone", "ARCHIVE")
            .containsEntry("returnRequested", 1)
            .containsEntry("returnApplied", 1)
            .containsEntry("colorFilter", "");
        assertThat(summary.get("returnedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(101L);
        assertThat(summary.get("returnedCardIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly("CHEER_RED");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeReturnCheerToDeckBottomEffectShouldUseColorFilterAndRequestedAmount() throws Exception {
        whenArchiveCandidates(List.of(archiveCheer(201L, "CHEER_RED_A", "RED")), "RED", 2);
        whenNextCheerDeckOrder(3);
        when(jdbcTemplate.update(
            contains("AND zone = ?"),
            eq(3),
            eq(201L),
            eq(1L),
            eq(10L),
            eq("ARCHIVE")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeReturnCheerToDeckBottomEffect(
            1L,
            10L,
            "RETURN_CHEER_TO_DECK_BOTTOM",
            node("{\"amount\":2,\"rawText\":\"自分のアーカイブの赤エール2枚をエールデッキの下に戻せる。\"}")
        );

        assertThat(summary)
            .containsEntry("returnRequested", 2)
            .containsEntry("returnApplied", 1)
            .containsEntry("colorFilter", "RED");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeReturnCheerToDeckBottomEffectShouldReturnStageAttachedCheerAndDeleteAttachmentRow() throws Exception {
        whenStageCandidates(List.of(stageCheer(301L, 401L, "CHEER_BLUE", "BLUE")), "", 1);
        whenNextCheerDeckOrder(9);
        when(jdbcTemplate.update(
            contains("AND zone = ?"),
            eq(9),
            eq(401L),
            eq(1L),
            eq(10L),
            eq("STAGE")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeReturnCheerToDeckBottomEffect(
            1L,
            10L,
            "RETURN_CHEER_TO_DECK_BOTTOM",
            node("{\"rawText\":\"自分のステージのエール1枚をエールデッキの下に戻せる。\"}")
        );

        assertThat(summary)
            .containsEntry("sourceZone", "STAGE")
            .containsEntry("returnRequested", 1)
            .containsEntry("returnApplied", 1);
        assertThat(summary.get("returnedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(401L);
        verify(jdbcTemplate).update(contains("DELETE FROM match_holomem_cheers"), eq(301L));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeReturnCheerToDeckBottomEffectShouldFallbackStageCardInstanceWhenAttachmentHasNoMatchCardId() throws Exception {
        whenStageCandidates(List.of(stageCheer(501L, null, "CHEER_GREEN", "GREEN")), "", 1);
        when(jdbcTemplate.query(
            contains("AND zone = 'STAGE'"),
            any(ResultSetExtractor.class),
            eq(1L),
            eq(10L),
            eq("CHEER_GREEN")
        )).thenReturn(601L);
        whenNextCheerDeckOrder(4);
        when(jdbcTemplate.update(
            contains("AND zone = ?"),
            eq(4),
            eq(601L),
            eq(1L),
            eq(10L),
            eq("STAGE")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeReturnCheerToDeckBottomEffect(
            1L,
            10L,
            "RETURN_CHEER_TO_DECK_BOTTOM",
            node("{\"rawText\":\"自分のステージのエール1枚をエールデッキの下に戻せる。\"}")
        );

        assertThat(summary)
            .containsEntry("returnApplied", 1);
        assertThat(summary.get("returnedCardInstanceIds"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly(601L);
        verify(jdbcTemplate).update(contains("DELETE FROM match_holomem_cheers"), eq(501L));
    }

    private void whenArchiveCandidates(List<Map<String, Object>> candidates, String color, int limit) {
        when(jdbcTemplate.query(
            contains("FROM match_cards mc"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq(color),
            eq(color),
            eq(limit)
        )).thenReturn(candidates);
    }

    private void whenStageCandidates(List<Map<String, Object>> candidates, String color, int limit) {
        when(jdbcTemplate.query(
            contains("FROM match_holomem_cheers hc"),
            any(RowMapper.class),
            eq(1L),
            eq(10L),
            eq(color),
            eq(color),
            eq(limit)
        )).thenReturn(candidates);
    }

    private void whenNextCheerDeckOrder(int order) {
        when(jdbcTemplate.queryForObject(
            contains("COALESCE(MAX(order_index)"),
            eq(Integer.class),
            eq(1L),
            eq(10L),
            eq("CHEER_DECK")
        )).thenReturn(order);
    }

    private Map<String, Object> archiveCheer(Long id, String cardId, String color) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("card_id", cardId);
        row.put("color", color);
        return row;
    }

    private Map<String, Object> stageCheer(Long cheerRowId, Long matchCardId, String cardId, String color) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cheer_row_id", cheerRowId);
        row.put("match_card_id", matchCardId);
        row.put("card_id", cardId);
        row.put("color", color);
        return row;
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
