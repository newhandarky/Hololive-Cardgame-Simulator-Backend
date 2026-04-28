package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GiftTriggerActionPayloadExtractorTest {

    private final GiftTriggerActionPayloadExtractor extractor = new GiftTriggerActionPayloadExtractor();

    @Test
    void extractTriggeredGiftPayloadsShouldReturnEmptyForNullSummary() {
        assertThat(extractor.extractTriggeredGiftPayloads(null)).isEmpty();
    }

    @Test
    void extractTriggeredGiftPayloadsShouldReturnEmptyForUnsupportedSourceActionType() {
        Map<String, Object> effectSummary = Map.of(
            "sourceActionType",
            "SUPPORT",
            "triggeredGifts",
            List.of(Map.of("giftCardId", 1))
        );

        assertThat(extractor.extractTriggeredGiftPayloads(effectSummary)).isEmpty();
    }

    @Test
    void extractTriggeredGiftPayloadsShouldReadDirectGiftTriggeredGiftsAndNormalizeMapKeys() {
        Map<Object, Object> giftPayload = new LinkedHashMap<>();
        giftPayload.put("giftCardId", 10);
        giftPayload.put(200, "numeric-key");
        giftPayload.put(null, "ignored");
        Map<String, Object> effectSummary = Map.of(
            "sourceActionType",
            " gift ",
            "triggeredGifts",
            List.of(giftPayload, "not-a-map")
        );

        List<Map<String, Object>> payloads = extractor.extractTriggeredGiftPayloads(effectSummary);

        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0)).containsEntry("giftCardId", 10).containsEntry("200", "numeric-key");
        assertThat(payloads.get(0)).doesNotContainKey(null);
    }

    @Test
    void extractTriggeredGiftPayloadsShouldReadNestedCollabGiftTriggeredGifts() {
        Map<String, Object> effectSummary = Map.of(
            "sourceActionType",
            "COLLAB",
            "triggeredGifts",
            List.of(Map.of("triggerType", "WRONG")),
            "gift",
            Map.of("triggeredGifts", List.of(Map.of("triggerType", "COLLAB", "giftCardId", 20)))
        );

        assertThat(extractor.extractTriggeredGiftPayloads(effectSummary))
            .containsExactly(Map.of("triggerType", "COLLAB", "giftCardId", 20));
    }

    @Test
    void extractTriggeredGiftPayloadsShouldReadNestedAttackArtPostTriggerGiftTriggeredGifts() {
        Map<String, Object> effectSummary = Map.of(
            "sourceActionType",
            "ATTACK_ART_POST_TRIGGER",
            "gift",
            Map.of("triggeredGifts", List.of(Map.of("triggerType", "ATTACK_ART", "giftCardId", 30)))
        );

        assertThat(extractor.extractTriggeredGiftPayloads(effectSummary))
            .containsExactly(Map.of("triggerType", "ATTACK_ART", "giftCardId", 30));
    }

    @Test
    void extractTriggeredGiftPayloadsShouldReturnEmptyWhenNestedGiftHasNoTriggeredGiftList() {
        Map<String, Object> missingNestedGift = Map.of("sourceActionType", "COLLAB");
        Map<String, Object> emptyNestedGift = Map.of("sourceActionType", "COLLAB", "gift", Map.of());
        Map<String, Object> nonListNestedGift = Map.of(
            "sourceActionType",
            "ATTACK_ART_POST_TRIGGER",
            "gift",
            Map.of("triggeredGifts", Map.of("giftCardId", 40))
        );

        assertThat(extractor.extractTriggeredGiftPayloads(missingNestedGift)).isEmpty();
        assertThat(extractor.extractTriggeredGiftPayloads(emptyNestedGift)).isEmpty();
        assertThat(extractor.extractTriggeredGiftPayloads(nonListNestedGift)).isEmpty();
    }
}
