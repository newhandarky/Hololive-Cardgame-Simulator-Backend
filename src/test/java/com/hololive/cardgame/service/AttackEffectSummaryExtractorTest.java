package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackEffectSummaryExtractorTest {

    private final AttackEffectSummaryExtractor extractor = new AttackEffectSummaryExtractor();

    @Test
    void extractExecutedEffectSummariesShouldCopyMapEntriesAndStringifyKeys() {
        Map<Object, Object> effect = new LinkedHashMap<>();
        effect.put("effectType", "OSHI_SELF_DOWNED");
        effect.put(10, "numeric-key");
        effect.put(null, "ignored");

        List<Map<String, Object>> result = extractor.extractExecutedEffectSummaries(
            Map.of("executedEffects", List.of(effect))
        );

        assertThat(result).containsExactly(Map.of(
            "effectType", "OSHI_SELF_DOWNED",
            "10", "numeric-key"
        ));
    }

    @Test
    void extractExecutedEffectSummariesShouldIgnoreMissingOrNonMapEffects() {
        assertThat(extractor.extractExecutedEffectSummaries(null)).isEmpty();
        assertThat(extractor.extractExecutedEffectSummaries(Map.of())).isEmpty();
        assertThat(extractor.extractExecutedEffectSummaries(Map.of("executedEffects", List.of("ignored")))).isEmpty();
    }
}
