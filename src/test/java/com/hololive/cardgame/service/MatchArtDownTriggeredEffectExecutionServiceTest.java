package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchArtDownTriggeredEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    void applyShouldReturnNoOpWhenArtHasNoDownTriggeredText() {
        MatchArtDownTriggeredEffectExecutionService service = service(
            effectJsonText -> "このホロメンのエール1枚につき、このアーツ+20。",
            followupText -> List.of("ADD_CHEER"),
            effectType -> "SELF",
            (matchId, userId, effectType, effectJsonText, targetType, supportCardInstanceId, sourceCardInstanceId) ->
                Map.of("applied", true)
        );

        Map<String, Object> summary = service.applyArtDownTriggeredEffects(
            100L,
            20L,
            901L,
            "{\"rawEffect\":\"このホロメンのエール1枚につき、このアーツ+20。\"}"
        );

        assertThat(summary)
            .containsEntry("triggerType", "ART_DOWNED_OPPONENT")
            .containsEntry("rawText", "このホロメンのエール1枚につき、このアーツ+20。")
            .containsEntry("applied", false)
            .containsEntry("reason", "藝能沒有擊倒後效果");
        assertThat(summary.get("requestedEffects")).isEqualTo(List.of());
        assertThat(summary.get("executedEffects")).isEqualTo(List.of());
        assertThat(summary.get("unsupportedEffects")).isEqualTo(List.of());
        assertThat(summary.get("skippedEffects")).isEqualTo(List.of());
    }

    @Test
    void applyShouldReturnNoOpWhenFollowupEffectTypeCannotBeResolved() {
        MatchArtDownTriggeredEffectExecutionService service = service(
            effectJsonText -> "このアーツで相手のホロメンをダウンさせた時、未対応の効果。",
            followupText -> List.of(),
            effectType -> "SELF",
            (matchId, userId, effectType, effectJsonText, targetType, supportCardInstanceId, sourceCardInstanceId) ->
                Map.of("applied", true)
        );

        Map<String, Object> summary = service.applyArtDownTriggeredEffects(100L, 20L, 901L, "{}");

        assertThat(summary)
            .containsEntry("triggerType", "ART_DOWNED_OPPONENT")
            .containsEntry("rawText", "未対応の効果。")
            .containsEntry("applied", false)
            .containsEntry("reason", "無法解析藝能擊倒後效果類型");
    }

    @Test
    void applyShouldWrapSupportEffectSummaryForDownTriggeredFollowup() {
        List<Map<String, Object>> calls = new ArrayList<>();
        MatchArtDownTriggeredEffectExecutionService service = service(
            effectJsonText -> "このアーツで相手のホロメンをダウンさせた時、自分のエールデッキから、エール1枚をこのホロメンに送る。",
            followupText -> List.of("ADD_CHEER"),
            effectType -> "SELF",
            (matchId, userId, effectType, effectJsonText, targetType, supportCardInstanceId, sourceCardInstanceId) -> {
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("matchId", matchId);
                call.put("userId", userId);
                call.put("effectType", effectType);
                call.put("effectJsonText", effectJsonText);
                call.put("targetType", targetType);
                call.put("supportCardInstanceId", supportCardInstanceId);
                call.put("sourceCardInstanceId", sourceCardInstanceId);
                calls.add(call);
                return Map.of(
                    "effectType", "ADD_CHEER",
                    "applied", true,
                    "executedEffects", List.of(Map.of("effectType", "ADD_CHEER"))
                );
            }
        );

        Map<String, Object> summary = service.applyArtDownTriggeredEffects(100L, 20L, 901L, "{}");

        assertThat(summary)
            .containsEntry("triggerType", "ART_DOWNED_OPPONENT")
            .containsEntry("rawText", "自分のエールデッキから、エール1枚をこのホロメンに送る。")
            .containsEntry("effectType", "ADD_CHEER")
            .containsEntry("applied", true);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0))
            .containsEntry("matchId", 100L)
            .containsEntry("userId", 20L)
            .containsEntry("effectType", "ADD_CHEER")
            .containsEntry("targetType", "SELF")
            .containsEntry("supportCardInstanceId", null)
            .containsEntry("sourceCardInstanceId", 901L);
        assertThat((String) calls.get(0).get("effectJsonText"))
            .contains("\"type\":\"ADD_CHEER\"")
            .contains("\"effects\":[\"ADD_CHEER\"]")
            .contains("自分のエールデッキから、エール1枚をこのホロメンに送る。");
    }

    private MatchArtDownTriggeredEffectExecutionService service(
        MatchArtDownTriggeredEffectExecutionService.RawTextExtractor rawTextExtractor,
        MatchArtDownTriggeredEffectExecutionService.EffectTypesResolver effectTypesResolver,
        MatchArtDownTriggeredEffectExecutionService.TargetTypeResolver targetTypeResolver,
        MatchArtDownTriggeredEffectExecutionService.SupportEffectApplier supportEffectApplier
    ) {
        return new MatchArtDownTriggeredEffectExecutionService(
            objectMapper,
            effectTextParser,
            rawTextExtractor,
            effectTypesResolver,
            targetTypeResolver,
            supportEffectApplier
        );
    }
}
