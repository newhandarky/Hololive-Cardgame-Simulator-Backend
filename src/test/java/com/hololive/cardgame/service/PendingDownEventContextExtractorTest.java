package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PendingDownEventContextExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PendingDownEventContextExtractor extractor = new PendingDownEventContextExtractor();

    @Test
    void extractDownEventContextShouldReturnNullForMissingObject() throws Exception {
        assertThat(extractor.extractDownEventContext(null)).isNull();
        assertThat(extractor.extractDownEventContext(objectMapper.readTree("{}"))).isNull();
        assertThat(extractor.extractDownEventContext(objectMapper.readTree("{\"downEvent\":[]}"))).isNull();
    }

    @Test
    void extractDownEventContextShouldPreserveKnownFields() throws Exception {
        Map<String, Object> downEvent = extractor.extractDownEventContext(
            objectMapper.readTree(
                """
                {
                  "downEvent": {
                    "downedOwnerUserId": "101",
                    "downedCardId": "hBP99-001",
                    "downedStageZone": " center ",
                    "turnNumber": "3",
                    "rawText": " down text ",
                    "requestedLifeLoss": 2
                  }
                }
                """
            )
        );

        assertThat(downEvent).containsEntry("downedOwnerUserId", 101L);
        assertThat(downEvent).containsEntry("downedCardId", "hBP99-001");
        assertThat(downEvent).containsEntry("downedStageZone", "center");
        assertThat(downEvent).containsEntry("turnNumber", 3);
        assertThat(downEvent).containsEntry("rawText", "down text");
        assertThat(downEvent).containsEntry("requestedLifeLoss", 2);
    }

    @Test
    void extractDownEventContextShouldUseDefaultsForInvalidOptionalFields() throws Exception {
        Map<String, Object> downEvent = extractor.extractDownEventContext(
            objectMapper.readTree(
                """
                {
                  "downEvent": {
                    "downedOwnerUserId": "bad",
                    "downedCardId": 10,
                    "downedStageZone": "",
                    "turnNumber": "bad",
                    "requestedLifeLoss": {}
                  }
                }
                """
            )
        );

        assertThat(downEvent).containsEntry("downedOwnerUserId", null);
        assertThat(downEvent).containsEntry("downedCardId", null);
        assertThat(downEvent).containsEntry("downedStageZone", null);
        assertThat(downEvent).containsEntry("turnNumber", 0);
        assertThat(downEvent).containsEntry("rawText", null);
        assertThat(downEvent).containsEntry("requestedLifeLoss", 0);
    }
}
