package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackPostTriggerSectionBuilderTest {

    private final AttackPostTriggerSectionBuilder builder = new AttackPostTriggerSectionBuilder();

    @Test
    void buildAttackArtPostTriggerSectionsShouldReturnEmptyForNoTriggers() {
        assertThat(builder.buildAttackArtPostTriggerSections(List.of(), null)).isEmpty();
        assertThat(builder.buildAttackArtPostTriggerSections(null, Map.of())).isEmpty();
    }

    @Test
    void buildAttackArtPostTriggerSectionsShouldBuildDownEventBeforeGiftSection() {
        List<Map<String, Object>> sections = builder.buildAttackArtPostTriggerSections(
            List.of(
                Map.of(
                    "triggerType",
                    " collab ",
                    "giftHolderCardId",
                    "HBP99-001",
                    "rawText",
                    "gift text",
                    "requestedEffects",
                    List.of(" draw ", "DRAW", "", "damage")
                )
            ),
            Map.of(
                "requestedLifeLoss",
                "2",
                "downedCardId",
                "HBP99-002",
                "rawText",
                "down text"
            )
        );

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0))
            .containsEntry("sectionType", "DOWN_EVENT")
            .containsEntry("title", "Down Event")
            .containsEntry("requestedLifeLoss", 2)
            .containsEntry("downedCardId", "HBP99-002")
            .containsEntry("rawText", "down text");

        assertThat(sections.get(1))
            .containsEntry("sectionType", "GIFT")
            .containsEntry("title", "Gift")
            .containsEntry("count", 1);
        assertThat(firstItem(sections.get(1)))
            .containsEntry("triggerType", "COLLAB")
            .containsEntry("giftHolderCardId", "HBP99-001")
            .containsEntry("rawText", "gift text")
            .containsEntry("requestedEffects", List.of("DRAW", "DAMAGE"));
    }

    @Test
    void buildAttackArtPostTriggerSectionsShouldSkipEmptyGiftItemsAndUseFallbacks() {
        List<Map<String, Object>> sections = builder.buildAttackArtPostTriggerSections(
            List.of(Map.of(), Map.of("triggerType", 10, "requestedEffects", "bad")),
            Map.of("requestedLifeLoss", "bad")
        );

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0))
            .containsEntry("sectionType", "DOWN_EVENT")
            .containsEntry("requestedLifeLoss", 0)
            .containsEntry("downedCardId", null)
            .containsEntry("rawText", null);
        assertThat(sections.get(1)).containsEntry("count", 1);
        assertThat(firstItem(sections.get(1)))
            .containsEntry("triggerType", "10")
            .containsEntry("requestedEffects", List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstItem(Map<String, Object> section) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) section.get("items");
        assertThat(items).hasSize(1);
        return items.get(0);
    }
}
