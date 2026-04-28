package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PendingGiftTriggerContextExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PendingGiftTriggerContextExtractor extractor = new PendingGiftTriggerContextExtractor();

    @Test
    void extractGiftTriggerContextsShouldReturnEmptyForMissingArray() throws Exception {
        assertThat(extractor.extractGiftTriggerContexts(null)).isEmpty();
        assertThat(extractor.extractGiftTriggerContexts(objectMapper.readTree("{}"))).isEmpty();
        assertThat(extractor.extractGiftTriggerContexts(objectMapper.readTree("{\"giftTriggers\":{}}"))).isEmpty();
    }

    @Test
    void extractGiftTriggerContextsShouldSkipNonObjectItemsAndPreserveKnownFields() throws Exception {
        List<Map<String, Object>> triggers = extractor.extractGiftTriggerContexts(
            objectMapper.readTree(
                """
                {
                  "giftTriggers": [
                    "invalid",
                    {
                      "triggerType": "COLLAB",
                      "sourceCardInstanceId": "101",
                      "triggerTargetCardInstanceId": 102,
                      "giftHolderHolomemId": 201,
                      "giftHolderCardInstanceId": "202",
                      "giftHolderCardId": "HBP99-001",
                      "giftHolderZone": "center",
                      "giftHolderAttachedCheerCardInstanceIds": [301, "302", 302, 0, "bad"],
                      "giftHolderAttachedCheerCardIds": [" red ", "RED", "", "blue"],
                      "giftHolderStackCardInstanceIds": ["401", 401, -1],
                      "giftHolderStackCardIds": [" debut ", "SPOT"],
                      "selectionRequired": "true",
                      "selectionEffectType": "DRAW",
                      "selectionMinSelect": "1",
                      "selectionMaxSelect": 2,
                      "selectionCandidateCardInstanceIds": ["501", 502, 501],
                      "rawText": " gift text "
                    }
                  ]
                }
                """
            )
        );

        assertThat(triggers).hasSize(1);
        Map<String, Object> trigger = triggers.get(0);
        assertThat(trigger).containsEntry("triggerType", "COLLAB");
        assertThat(trigger).containsEntry("sourceCardInstanceId", 101L);
        assertThat(trigger).containsEntry("triggerTargetCardInstanceId", 102L);
        assertThat(trigger).containsEntry("giftHolderHolomemId", 201L);
        assertThat(trigger).containsEntry("giftHolderCardInstanceId", 202L);
        assertThat(trigger).containsEntry("giftHolderCardId", "HBP99-001");
        assertThat(trigger).containsEntry("giftHolderZone", "center");
        assertThat(trigger).containsEntry("giftHolderAttachedCheerCardInstanceIds", List.of(301L, 302L));
        assertThat(trigger).containsEntry("giftHolderAttachedCheerCardIds", List.of("RED", "BLUE"));
        assertThat(trigger).containsEntry("giftHolderStackCardInstanceIds", List.of(401L));
        assertThat(trigger).containsEntry("giftHolderStackCardIds", List.of("DEBUT", "SPOT"));
        assertThat(trigger).containsEntry("selectionRequired", true);
        assertThat(trigger).containsEntry("selectionEffectType", "DRAW");
        assertThat(trigger).containsEntry("selectionMinSelect", 1L);
        assertThat(trigger).containsEntry("selectionMaxSelect", 2L);
        assertThat(trigger).containsEntry("selectionCandidateCardInstanceIds", List.of(501L, 502L));
        assertThat(trigger).containsEntry("rawText", "gift text");
    }

    @Test
    void extractGiftTriggerContextsShouldUseDefaultsForInvalidOptionalFields() throws Exception {
        List<Map<String, Object>> triggers = extractor.extractGiftTriggerContexts(
            objectMapper.readTree(
                """
                {
                  "giftTriggers": [
                    {
                      "triggerType": 10,
                      "sourceCardInstanceId": "bad",
                      "selectionRequired": 1,
                      "giftHolderAttachedCheerCardInstanceIds": {},
                      "giftHolderAttachedCheerCardIds": {}
                    }
                  ]
                }
                """
            )
        );

        assertThat(triggers).hasSize(1);
        Map<String, Object> trigger = triggers.get(0);
        assertThat(trigger).containsEntry("triggerType", null);
        assertThat(trigger).containsEntry("sourceCardInstanceId", null);
        assertThat(trigger).containsEntry("selectionRequired", false);
        assertThat(trigger).containsEntry("giftHolderAttachedCheerCardInstanceIds", List.of());
        assertThat(trigger).containsEntry("giftHolderAttachedCheerCardIds", List.of());
    }
}
