package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GiftTriggerPendingPayloadBuilderTest {

    private final GiftTriggerPendingPayloadBuilder builder = new GiftTriggerPendingPayloadBuilder();

    @Test
    void buildGiftTriggerPayloadsShouldReturnEmptyForMissingTriggers() {
        assertThat(builder.buildGiftTriggerPayloads(null)).isEmpty();
        assertThat(builder.buildGiftTriggerPayloads(List.of())).isEmpty();
        assertThat(builder.buildGiftTriggerPayloads(List.of(Map.of()))).isEmpty();
    }

    @Test
    void buildGiftTriggerPayloadsShouldNormalizeKnownFields() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("triggerType", " collab ");
        trigger.put("sourceCardInstanceId", "101");
        trigger.put("triggerTargetCardInstanceId", 102);
        trigger.put("giftHolderHolomemId", 201);
        trigger.put("giftHolderCardInstanceId", "202");
        trigger.put("giftHolderCardId", "HBP99-001");
        trigger.put("giftHolderZone", "center");
        trigger.put("giftHolderAttachedCheerCardInstanceIds", List.of(301, "302", 302, 0, "bad"));
        trigger.put("giftHolderAttachedCheerCardIds", List.of(" red ", "RED", "", "blue"));
        trigger.put("giftHolderStackCardInstanceIds", List.of("401", 401, -1));
        trigger.put("giftHolderStackCardIds", List.of(" debut ", "SPOT"));
        trigger.put("selectionRequired", "true");
        trigger.put("selectionEffectType", "DRAW");
        trigger.put("selectionMinSelect", "1");
        trigger.put("selectionMaxSelect", 2);
        trigger.put("selectionCandidateCardInstanceIds", List.of("501", 502, 501));
        trigger.put("rawText", "gift text");

        List<Map<String, Object>> payloads = builder.buildGiftTriggerPayloads(
            List.of(trigger)
        );

        assertThat(payloads).hasSize(1);
        Map<String, Object> payload = payloads.get(0);
        assertThat(payload).containsEntry("triggerType", "COLLAB");
        assertThat(payload).containsEntry("sourceCardInstanceId", 101L);
        assertThat(payload).containsEntry("triggerTargetCardInstanceId", 102L);
        assertThat(payload).containsEntry("giftHolderHolomemId", 201L);
        assertThat(payload).containsEntry("giftHolderCardInstanceId", 202L);
        assertThat(payload).containsEntry("giftHolderCardId", "HBP99-001");
        assertThat(payload).containsEntry("giftHolderZone", "center");
        assertThat(payload).containsEntry("giftHolderAttachedCheerCardInstanceIds", List.of(301L, 302L));
        assertThat(payload).containsEntry("giftHolderAttachedCheerCardIds", List.of("RED", "BLUE"));
        assertThat(payload).containsEntry("giftHolderStackCardInstanceIds", List.of(401L));
        assertThat(payload).containsEntry("giftHolderStackCardIds", List.of("DEBUT", "SPOT"));
        assertThat(payload).containsEntry("selectionRequired", true);
        assertThat(payload).containsEntry("selectionEffectType", "DRAW");
        assertThat(payload).containsEntry("selectionMinSelect", 1);
        assertThat(payload).containsEntry("selectionMaxSelect", 2);
        assertThat(payload).containsEntry("selectionCandidateCardInstanceIds", List.of(501L, 502L));
        assertThat(payload).containsEntry("rawText", "gift text");
    }

    @Test
    void buildGiftTriggerPayloadsShouldUseFallbacksForInvalidValues() {
        Map<String, Object> payload = builder.buildGiftTriggerPayloads(
            List.of(
                Map.of(
                    "sourceCardInstanceId",
                    "bad",
                    "selectionRequired",
                    1,
                    "selectionMinSelect",
                    "bad",
                    "giftHolderAttachedCheerCardInstanceIds",
                    "bad"
                )
            )
        ).get(0);

        assertThat(payload).containsEntry("sourceCardInstanceId", null);
        assertThat(payload).containsEntry("selectionRequired", false);
        assertThat(payload).containsEntry("selectionMinSelect", 0);
        assertThat(payload).containsEntry("giftHolderAttachedCheerCardInstanceIds", List.of());
    }
}
