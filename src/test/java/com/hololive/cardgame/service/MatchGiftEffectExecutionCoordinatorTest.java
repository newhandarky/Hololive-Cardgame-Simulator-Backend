package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftExecutionSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchGiftEffectExecutionCoordinatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final MatchGiftEffectExecutionCoordinator coordinator =
        new MatchGiftEffectExecutionCoordinator(effectTextParser, new MatchEffectTypeInferenceService(effectTextParser));

    @Test
    void executeShouldRunResolvedEffectsAfterSequentialCostIsSatisfied() {
        List<String> calls = new ArrayList<>();
        GiftExecutionSummary summary = coordinator.execute(
            "手札1枚をアーカイブする：自分のデッキを1枚引く。",
            giftNode("手札1枚をアーカイブする：自分のデッキを1枚引く。"),
            (effectType, giftNode) -> {
                calls.add(effectType);
                return row("effectType", effectType, "applied", true);
            }
        );

        assertThat(calls).containsExactly("DISCARD_HAND", "DRAW");
        assertThat(summary.requestedEffects()).containsExactly("DISCARD_HAND", "DRAW");
        assertThat(summary.executedEffects())
            .containsExactly(
                row("effectType", "DISCARD_HAND", "applied", true),
                row("effectType", "DRAW", "applied", true)
            );
        assertThat(summary.unsupportedEffects()).isEmpty();
        assertThat(summary.skippedEffects()).isEmpty();
    }

    @Test
    void executeShouldSkipResolvedEffectsWhenSequentialCostIsNotSatisfied() {
        GiftExecutionSummary summary = coordinator.execute(
            "手札1枚をアーカイブする：自分のデッキを1枚引く。",
            giftNode("手札1枚をアーカイブする：自分のデッキを1枚引く。"),
            (effectType, giftNode) -> row("effectType", effectType, "applied", false)
        );

        assertThat(summary.requestedEffects()).containsExactly("DISCARD_HAND", "DRAW");
        assertThat(summary.executedEffects())
            .containsExactly(
                row("effectType", "DISCARD_HAND", "applied", false),
                row("effectType", "DRAW", "applied", false, "skipped", true, "reason", "前置成本未支付")
            );
        assertThat(summary.skippedEffects())
            .containsExactly(row("effectType", "DRAW", "applied", false, "skipped", true, "reason", "前置成本未支付"));
    }

    @Test
    void executeShouldTreatUnsupportedEffectAsSkippedSummary() {
        GiftExecutionSummary summary = coordinator.execute(
            "まだ未対応の効果。",
            giftNode("まだ未対応の効果。"),
            (effectType, giftNode) -> {
                throw new UnsupportedOperationException("unsupported");
            }
        );

        assertThat(summary.requestedEffects()).containsExactly("UNIMPLEMENTED");
        assertThat(summary.unsupportedEffects()).containsExactly("UNIMPLEMENTED");
        assertThat(summary.executedEffects())
            .containsExactly(row("effectType", "UNIMPLEMENTED", "applied", false, "skipped", true, "reason", "UNSUPPORTED_EFFECT"));
        assertThat(summary.skippedEffects())
            .containsExactly(row("effectType", "UNIMPLEMENTED", "applied", false, "skipped", true, "reason", "UNSUPPORTED_EFFECT"));
    }

    @Test
    void executeShouldUseResolvedClauseWhenColonPrefixIsNotMeaningfulCost() {
        List<String> calls = new ArrayList<>();
        GiftExecutionSummary summary = coordinator.execute(
            "相手のホロメンがダウンした時に使える：自分のデッキを1枚引く。",
            giftNode("相手のホロメンがダウンした時に使える：自分のデッキを1枚引く。"),
            (effectType, giftNode) -> {
                calls.add(effectType);
                return row("effectType", effectType, "applied", true);
            }
        );

        assertThat(calls).containsExactly("DRAW");
        assertThat(summary.requestedEffects()).containsExactly("DRAW");
        assertThat(summary.executedEffects()).containsExactly(row("effectType", "DRAW", "applied", true));
        assertThat(summary.skippedEffects()).isEmpty();
    }

    private ObjectNode giftNode(String rawText) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("rawText", rawText);
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
