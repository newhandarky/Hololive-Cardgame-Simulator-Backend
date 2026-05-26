package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

final class MatchArtDownTriggeredEffectExecutionService {

    private final ObjectMapper objectMapper;
    private final EffectTextParser effectTextParser;
    private final RawTextExtractor rawTextExtractor;
    private final EffectTypesResolver effectTypesResolver;
    private final TargetTypeResolver targetTypeResolver;
    private final SupportEffectApplier supportEffectApplier;

    MatchArtDownTriggeredEffectExecutionService(
        ObjectMapper objectMapper,
        EffectTextParser effectTextParser,
        RawTextExtractor rawTextExtractor,
        EffectTypesResolver effectTypesResolver,
        TargetTypeResolver targetTypeResolver,
        SupportEffectApplier supportEffectApplier
    ) {
        this.objectMapper = objectMapper;
        this.effectTextParser = effectTextParser;
        this.rawTextExtractor = rawTextExtractor;
        this.effectTypesResolver = effectTypesResolver;
        this.targetTypeResolver = targetTypeResolver;
        this.supportEffectApplier = supportEffectApplier;
    }

    Map<String, Object> applyArtDownTriggeredEffects(
        Long matchId,
        Long userId,
        Long attackerCardInstanceId,
        String artEffectJsonText
    ) {
        String rawText = rawTextExtractor.extract(artEffectJsonText);
        String followupText = extractArtDownTriggeredClause(rawText);
        if (!StringUtils.hasText(followupText)) {
            return buildNoTriggeredArtEffectSummary(rawText, "藝能沒有擊倒後效果");
        }

        List<String> effectTypes = effectTypesResolver.resolve(followupText);
        if (effectTypes.isEmpty()) {
            return buildNoTriggeredArtEffectSummary(followupText, "無法解析藝能擊倒後效果類型");
        }

        ObjectNode effectNode = objectMapper.createObjectNode();
        effectNode.put("type", effectTypes.get(0));
        effectNode.set("effects", objectMapper.valueToTree(effectTypes));
        effectNode.put("rawText", followupText);

        Map<String, Object> summary = supportEffectApplier.apply(
            matchId,
            userId,
            effectTypes.get(0),
            effectTextParser.toJsonString(effectNode),
            targetTypeResolver.resolve(effectTypes.get(0)),
            null,
            attackerCardInstanceId
        );
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("triggerType", "ART_DOWNED_OPPONENT");
        wrapped.put("rawText", followupText);
        wrapped.putAll(summary);
        return wrapped;
    }

    private String extractArtDownTriggeredClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }
        String marker = "このアーツで相手のホロメンをダウンさせた時";
        int markerIndex = rawText.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        String clause = rawText.substring(markerIndex + marker.length()).trim();
        while (clause.startsWith("、") || clause.startsWith("。") || clause.startsWith("：") || clause.startsWith(":")) {
            clause = clause.substring(1).trim();
        }
        return StringUtils.hasText(clause) ? clause : null;
    }

    private Map<String, Object> buildNoTriggeredArtEffectSummary(String rawText, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("triggerType", "ART_DOWNED_OPPONENT");
        summary.put("rawText", rawText);
        summary.put("requestedEffects", List.of());
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        summary.put("skippedEffects", List.of());
        summary.put("applied", false);
        summary.put("reason", reason);
        return summary;
    }

    @FunctionalInterface
    interface RawTextExtractor {
        String extract(String effectJsonText);
    }

    @FunctionalInterface
    interface EffectTypesResolver {
        List<String> resolve(String rawText);
    }

    @FunctionalInterface
    interface TargetTypeResolver {
        String resolve(String effectType);
    }

    @FunctionalInterface
    interface SupportEffectApplier {
        Map<String, Object> apply(
            Long matchId,
            Long userId,
            String effectType,
            String effectJsonText,
            String targetType,
            List<Long> selectedCardInstanceIds,
            Long targetHolomemCardInstanceId
        );
    }
}
