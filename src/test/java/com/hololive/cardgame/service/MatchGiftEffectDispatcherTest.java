package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchGiftEffectDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchGiftEffectDispatcher.GiftEffectHandlers handlers = mock(MatchGiftEffectDispatcher.GiftEffectHandlers.class);

    private final MatchGiftEffectDispatcher dispatcher = new MatchGiftEffectDispatcher(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new MatchResultEffectExecutionService(effectTextParser, (matchId, userId) -> 20L),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new MatchEffectTypeInferenceService(effectTextParser),
        handlers
    );

    @Test
    void executeShouldRouteMatchResultToMatchResultExecutionService() {
        ObjectNode giftNode = giftNode();

        Map<String, Object> actual = dispatcher.execute(1L, 10L, 100L, 200L, "WIN", giftNode);

        assertThat(actual)
            .containsEntry("effectType", "WIN")
            .containsEntry("applied", true);
        assertThat((Map<String, Object>) actual.get("matchResult"))
            .containsEntry("winnerUserId", 10L)
            .containsEntry("loserUserId", 20L)
            .containsEntry("reason", "CARD_EFFECT_WIN");
    }

    @Test
    void executeShouldRouteDamageToHighCouplingHandlerWithInferredTargetType() {
        ObjectNode giftNode = giftNode();
        Map<String, Object> expected = row("effectType", "DAMAGE", "damageApplied", 50);
        when(handlers.executeDamageEffect(1L, 10L, "DAMAGE", giftNode, "ENEMY", 200L)).thenReturn(expected);

        Map<String, Object> actual = dispatcher.execute(1L, 10L, 100L, 200L, "DAMAGE", giftNode);

        assertThat(actual).isEqualTo(expected);
        verify(handlers).executeDamageEffect(1L, 10L, "DAMAGE", giftNode, "ENEMY", 200L);
    }

    @Test
    void executeShouldRouteUnimplementedToNoOpHandler() {
        ObjectNode giftNode = giftNode();
        Map<String, Object> expected = row("effectType", "UNIMPLEMENTED", "applied", false);
        when(handlers.executeNoOpEffect("UNIMPLEMENTED", giftNode, "尚未支援的 GIFT 效果")).thenReturn(expected);

        Map<String, Object> actual = dispatcher.execute(1L, 10L, 100L, 200L, "UNIMPLEMENTED", giftNode);

        assertThat(actual).isEqualTo(expected);
        verify(handlers).executeNoOpEffect("UNIMPLEMENTED", giftNode, "尚未支援的 GIFT 效果");
    }

    @Test
    void executeShouldThrowUnsupportedOperationForUnknownEffect() {
        assertThatThrownBy(() -> dispatcher.execute(1L, 10L, 100L, 200L, "UNKNOWN_EFFECT", giftNode()))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessage("UNSUPPORTED_GIFT_EFFECT");
    }

    private ObjectNode giftNode() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("rawText", "gift text");
        return node;
    }

    private Map<String, Object> row(Object... entries) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            row.put((String) entries[i], entries[i + 1]);
        }
        return row;
    }
}
