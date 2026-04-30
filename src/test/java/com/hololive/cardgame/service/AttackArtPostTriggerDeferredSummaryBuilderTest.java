package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackArtPostTriggerDeferredSummaryBuilderTest {

    private final AttackArtPostTriggerDeferredSummaryBuilder builder =
        new AttackArtPostTriggerDeferredSummaryBuilder();

    @Test
    void buildShouldReturnNonDeferredSummaryWhenNoTriggerExists() {
        Map<String, Object> summary = builder.build(null, null);

        assertThat(summary)
            .containsEntry("sourceActionType", "ATTACK_ART_POST_TRIGGER")
            .containsEntry("deferred", false)
            .containsEntry("triggeredGifts", List.of())
            .containsEntry("downEvent", null)
            .containsEntry("triggerSections", List.of())
            .containsEntry("requestedEffects", List.of())
            .containsEntry("executedEffects", List.of())
            .containsEntry("unsupportedEffects", List.of());
    }

    @Test
    void buildShouldIncludeGiftAndDownEventSections() {
        Map<String, Object> gift = map(
            "requestedEffects", List.of("draw", "DRAW", " heal "),
            "triggerType", "art_used",
            "giftHolderCardId", "hBP01-001",
            "rawText", "gift text"
        );
        Map<String, Object> downEvent = map(
            "requestedLifeLoss", "1",
            "downedCardId", "hBP02-041",
            "rawText", "down text"
        );

        Map<String, Object> summary = builder.build(List.of(gift), downEvent);

        assertThat(summary)
            .containsEntry("sourceActionType", "ATTACK_ART_POST_TRIGGER")
            .containsEntry("deferred", true)
            .containsEntry("triggeredGifts", List.of(gift))
            .containsEntry("downEvent", downEvent)
            .containsEntry("requestedEffects", List.of("DRAW", "HEAL", "DOWN_EVENT"));
        assertThat((List<?>) summary.get("triggerSections"))
            .hasSize(2)
            .satisfiesExactly(
                section -> assertThat(asStringObjectMap(section))
                    .containsEntry("sectionType", "DOWN_EVENT")
                    .containsEntry("title", "Down Event")
                    .containsEntry("requestedLifeLoss", 1)
                    .containsEntry("downedCardId", "hBP02-041")
                    .containsEntry("rawText", "down text"),
                section -> {
                    Map<String, Object> giftSection = asStringObjectMap(section);
                    assertThat(giftSection)
                        .containsEntry("sectionType", "GIFT")
                        .containsEntry("title", "Gift")
                        .containsEntry("count", 1);
                    assertThat((List<?>) giftSection.get("items"))
                        .singleElement()
                        .satisfies(item -> assertThat(asStringObjectMap(item))
                            .containsEntry("triggerType", "ART_USED")
                            .containsEntry("giftHolderCardId", "hBP01-001")
                            .containsEntry("rawText", "gift text")
                            .containsEntry("requestedEffects", List.of("DRAW", "HEAL")));
                }
            );
    }

    @Test
    void buildShouldNotDuplicateDownEventRequestedEffect() {
        Map<String, Object> gift = map("requestedEffects", List.of("DOWN_EVENT", "draw"));

        Map<String, Object> summary = builder.build(List.of(gift), map("requestedLifeLoss", 1));

        assertThat(summary)
            .containsEntry("requestedEffects", List.of("DOWN_EVENT", "DRAW"));
    }

    private Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringObjectMap(Object value) {
        return (Map<String, Object>) value;
    }
}
