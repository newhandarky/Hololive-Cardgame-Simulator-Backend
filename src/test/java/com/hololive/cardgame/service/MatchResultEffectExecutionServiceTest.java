package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchResultEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    void executeMatchResultEffectShouldResolveStructuredWinAsActorWin() throws Exception {
        MatchResultEffectExecutionService service = service(20L);

        Map<String, Object> summary = service.executeMatchResultEffect(
            1L,
            10L,
            "MATCH_RESULT",
            node("{\"type\":\"MATCH_RESULT\",\"result\":\"WIN\",\"reason\":\"CARD_EFFECT_WIN\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "MATCH_RESULT")
            .containsEntry("applied", true);
        assertThat(matchResult(summary))
            .containsEntry("draw", false)
            .containsEntry("winnerUserId", 10L)
            .containsEntry("loserUserId", 20L)
            .containsEntry("reason", "CARD_EFFECT_WIN");
    }

    @Test
    void executeMatchResultEffectShouldResolveStructuredLoseAsOpponentWin() throws Exception {
        MatchResultEffectExecutionService service = service(20L);

        Map<String, Object> summary = service.executeMatchResultEffect(
            1L,
            10L,
            "MATCH_RESULT",
            node("{\"type\":\"MATCH_RESULT\",\"result\":\"LOSE\"}")
        );

        assertThat(matchResult(summary))
            .containsEntry("draw", false)
            .containsEntry("winnerUserId", 20L)
            .containsEntry("loserUserId", 10L)
            .containsEntry("reason", "CARD_EFFECT_LOSE");
    }

    @Test
    void executeMatchResultEffectShouldResolveStructuredDrawWithoutWinnerOrLoser() throws Exception {
        MatchResultEffectExecutionService service = service(20L);

        Map<String, Object> summary = service.executeMatchResultEffect(
            1L,
            10L,
            "MATCH_RESULT",
            node("{\"type\":\"MATCH_RESULT\",\"result\":\"DRAW\"}")
        );

        assertThat(matchResult(summary))
            .containsEntry("draw", true)
            .containsEntry("winnerUserId", null)
            .containsEntry("loserUserId", null)
            .containsEntry("reason", "CARD_EFFECT_DRAW");
    }

    @Test
    void executeMatchResultEffectShouldResolveWinnerAndLoserSideTokens() throws Exception {
        MatchResultEffectExecutionService service = service(20L);

        Map<String, Object> summary = service.executeMatchResultEffect(
            1L,
            10L,
            "MATCH_RESULT",
            node("{\"type\":\"MATCH_RESULT\",\"winner\":\"OPPONENT\",\"loser\":\"SELF\",\"reason\":\"CUSTOM_RESULT\"}")
        );

        assertThat(matchResult(summary))
            .containsEntry("draw", false)
            .containsEntry("winnerUserId", 20L)
            .containsEntry("loserUserId", 10L)
            .containsEntry("reason", "CUSTOM_RESULT");
    }

    @Test
    void executeMatchResultEffectShouldReturnNoOpWhenOpponentIsMissingForWin() throws Exception {
        MatchResultEffectExecutionService service = service(null);

        Map<String, Object> summary = service.executeMatchResultEffect(
            1L,
            10L,
            "MATCH_RESULT",
            node("{\"type\":\"MATCH_RESULT\",\"result\":\"WIN\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "MATCH_RESULT")
            .containsEntry("applied", false)
            .containsEntry("reason", "MATCH_RESULT 無法解析出勝負結果");
    }

    @Test
    void executeMatchResultEffectShouldResolveRawTextWinFallback() throws Exception {
        MatchResultEffectExecutionService service = service(20L);

        Map<String, Object> summary = service.executeMatchResultEffect(
            1L,
            10L,
            "MATCH_RESULT",
            node("{\"rawText\":\"あなたはこのゲームに勝利する。\"}")
        );

        assertThat(matchResult(summary))
            .containsEntry("draw", false)
            .containsEntry("winnerUserId", 10L)
            .containsEntry("loserUserId", 20L)
            .containsEntry("reason", "CARD_EFFECT_WIN");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> matchResult(Map<String, Object> summary) {
        return (Map<String, Object>) summary.get("matchResult");
    }

    private MatchResultEffectExecutionService service(Long opponentUserId) {
        return new MatchResultEffectExecutionService(
            effectTextParser,
            (matchId, userId) -> opponentUserId
        );
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
