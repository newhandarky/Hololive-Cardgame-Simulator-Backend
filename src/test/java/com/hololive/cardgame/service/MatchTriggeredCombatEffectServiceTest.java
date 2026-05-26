package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchTriggeredCombatEffectServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    void applyArtDownTriggeredEffectsShouldUseInjectedArtDownExecutor() {
        MatchArtDownTriggeredEffectExecutionService artDownTriggeredEffectExecutionService =
            new MatchArtDownTriggeredEffectExecutionService(
                objectMapper,
                effectTextParser,
                ignored -> "このアーツで相手のホロメンをダウンさせた時、自分のデッキから1枚引く。",
                ignored -> List.of("DRAW"),
                ignored -> "SELF",
                (matchId, userId, effectType, effectJsonText, targetType, selectedCardInstanceIds, targetHolomemCardInstanceId) -> {
                    assertThat(matchId).isEqualTo(100L);
                    assertThat(userId).isEqualTo(20L);
                    assertThat(effectType).isEqualTo("DRAW");
                    assertThat(effectJsonText).contains("\"type\":\"DRAW\"");
                    assertThat(targetType).isEqualTo("SELF");
                    assertThat(selectedCardInstanceIds).isNull();
                    assertThat(targetHolomemCardInstanceId).isEqualTo(901L);
                    return row(
                        "requestedEffects", List.of("DRAW"),
                        "executedEffects", List.of(row("effectType", "DRAW", "applied", true)),
                        "unsupportedEffects", List.of(),
                        "skippedEffects", List.of(),
                        "applied", true
                    );
                }
            );
        MatchTriggeredCombatEffectService service = new MatchTriggeredCombatEffectService(
            null,
            artDownTriggeredEffectExecutionService
        );

        Map<String, Object> summary = service.applyArtDownTriggeredEffects(
            100L,
            20L,
            901L,
            "{\"rawText\":\"ignored\"}"
        );

        assertThat(summary)
            .containsEntry("triggerType", "ART_DOWNED_OPPONENT")
            .containsEntry("rawText", "自分のデッキから1枚引く。")
            .containsEntry("requestedEffects", List.of("DRAW"))
            .containsEntry("applied", true);
        assertThat(summary.get("executedEffects")).isEqualTo(List.of(row("effectType", "DRAW", "applied", true)));
    }

    private Map<String, Object> row(Object... entries) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            row.put((String) entries[i], entries[i + 1]);
        }
        return row;
    }
}
