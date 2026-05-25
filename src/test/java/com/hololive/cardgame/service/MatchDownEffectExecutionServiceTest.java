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
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.AtomicAction;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchDownEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final GameActionExecutor gameActionExecutor = mock(GameActionExecutor.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private Long opponentUserId = 20L;
    private int currentTurn = 4;
    private boolean diceMatched = true;
    private List<Long> archivedCheerIds = List.of(301L);
    private List<Long> archivedSupportIds = List.of(401L);
    private List<Long> archivedStackIds = List.of(501L);
    private Map<String, Object> downEventSummary = new LinkedHashMap<>();
    private Long fallbackLostLifeId = null;

    private final MatchDownEffectExecutionService service = new MatchDownEffectExecutionService(
        jdbcTemplate,
        gameActionExecutor,
        effectTextParser,
        (rawText, effectNode, effectType) -> diceMatched,
        (matchId, userId) -> opponentUserId,
        matchId -> currentTurn,
        (matchId, holomemId, ownerUserId) -> archivedCheerIds,
        (matchId, holomemId, ownerUserId) -> archivedSupportIds,
        (matchId, holomemId, ownerUserId) -> archivedStackIds,
        (matchId, userId, downedOwnerUserId, downedCardId, turnNumber, applyDefaultLifeLoss, downedStageZone) -> downEventSummary,
        (matchId, ownerUserId) -> fallbackLostLifeId
    );

    @Test
    void executeDownNoLifeEffectShouldReturnNoOpWhenDiceConditionMisses() throws Exception {
        diceMatched = false;

        Map<String, Object> summary = service.executeDownNoLifeEffect(
            1L,
            10L,
            "DOWN_NO_LIFE",
            node("{\"rawText\":\"[サイコロ:1] 相手のバックホロメンをダウンする\"}")
        );

        assertThat(summary).containsEntry("effectType", "DOWN_NO_LIFE");
        assertThat(summary).containsEntry("applied", false);
        assertThat(summary).containsEntry("reason", "骰子條件未命中");
        verify(jdbcTemplate, never()).query(any(String.class), any(ResultSetExtractor.class), any());
    }

    @Test
    void executeDownNoLifeEffectShouldDownOpponentBackWithoutReducingLife() throws Exception {
        whenOpponentBackTarget(100L, 200L, 30);
        whenTargetCardId(200L, "DOWN_TARGET");
        downEventSummary = Map.of("lifeReduced", false, "lostLifeCardInstanceIds", List.of());

        Map<String, Object> summary = service.executeDownNoLifeEffect(
            1L,
            10L,
            "DOWN_NO_LIFE",
            node("{\"rawText\":\"相手のバックホロメンをダウンする\"}")
        );

        assertThat(summary).containsEntry("applied", true);
        assertThat(summary).containsEntry("downed", true);
        assertThat(summary).containsEntry("targetHolomemId", 100L);
        assertThat(summary).containsEntry("targetHolomemCardInstanceId", 200L);
        assertThat(summary).containsEntry("targetOwnerUserId", 20L);
        assertThat(summary).containsEntry("lifeReduced", false);
        assertThat(summary).containsEntry("archivedCheerCardInstanceIds", archivedCheerIds);
        assertThat(summary).containsEntry("archivedSupportCardInstanceIds", archivedSupportIds);
        assertThat(summary).containsEntry("archivedHolomemCardInstanceIds", archivedStackIds);
        assertThat(summary.get("downEvent")).isEqualTo(downEventSummary);
        verify(jdbcTemplate).update("DELETE FROM match_holomems WHERE id = ? AND match_id = ?", 100L, 1L);
    }

    @Test
    void executeDownExtraLifeEffectShouldApplyRequestedExtraLifeLoss() throws Exception {
        archivedStackIds = List.of(501L);
        whenOpponentBackTarget(100L, 200L, 0);
        whenTargetCardId(200L, "DOWN_TARGET");
        downEventSummary = Map.of("lifeReduced", false, "lostLifeCardInstanceIds", List.of());
        when(gameActionExecutor.execute(any(EffectContext.class), any())).thenReturn(
            List.of(ActionResult.success("REDUCE_LIFE", Map.of("lifeCardInstanceIds", List.of(701L, 702L))))
        );

        Map<String, Object> summary = service.executeDownExtraLifeEffect(
            1L,
            10L,
            "DOWN_EXTRA_LIFE",
            node("{\"rawText\":\"相手のバックホロメンをダウンし、ライフを2減らす\"}")
        );

        assertThat(summary).containsEntry("applied", true);
        assertThat(summary).containsEntry("downed", true);
        assertThat(summary).containsEntry("lifeReduced", true);
        assertThat(summary).containsEntry("lostLifeCardInstanceId", 701L);
        assertThat(summary).containsEntry("lostLifeCardInstanceIds", List.of(701L, 702L));
        assertThat(summary).containsEntry("extraLifeLossRequested", 2);
        assertThat(summary).containsEntry("extraLifeLossApplied", 2);
        verify(gameActionExecutor).execute(any(EffectContext.class), any(List.class));
    }

    @SuppressWarnings("unchecked")
    private void whenOpponentBackTarget(Long holomemId, Long matchCardId, int damageTaken) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("holomem_id", holomemId);
        target.put("match_card_id", matchCardId);
        target.put("damage_taken", damageTaken);
        when(
            jdbcTemplate.query(
                contains("FROM match_holomems h"),
                any(ResultSetExtractor.class),
                eq(1L),
                eq(20L),
                any(Boolean.class)
            )
        ).thenReturn(target);
    }

    @SuppressWarnings("unchecked")
    private void whenTargetCardId(Long matchCardId, String cardId) {
        when(
            jdbcTemplate.query(
                contains("FROM match_cards"),
                any(ResultSetExtractor.class),
                eq(1L),
                eq(matchCardId)
            )
        ).thenReturn(cardId);
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
