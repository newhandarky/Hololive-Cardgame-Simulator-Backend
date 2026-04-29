package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DownEventPreviewExtractorTest {

    private final DownEventPreviewExtractor extractor = new DownEventPreviewExtractor();

    @Test
    void extractDownEventPreviewShouldReturnTopLevelDeferredDownEvent() {
        Map<String, Object> downEvent = map(
            "triggered", true,
            "deferred", true,
            "downedCardInstanceId", 801L
        );

        Map<String, Object> preview = extractor.extractDownEventPreview(map("downEvent", downEvent));

        assertThat(preview).containsEntry("downedCardInstanceId", 801L);
    }

    @Test
    void extractDownEventPreviewShouldReturnNestedDeferredDownEvent() {
        Map<String, Object> downEvent = map(
            "triggered", "true",
            "deferred", "true",
            "downedCardInstanceId", 802L
        );

        Map<String, Object> preview = extractor.extractDownEventPreview(map(
            "executedEffects",
            List.of(map("effectType", "ART_DAMAGE", "downEvent", downEvent))
        ));

        assertThat(preview).containsEntry("downedCardInstanceId", 802L);
    }

    @Test
    void extractDownEventPreviewShouldIgnoreNonDeferredDownEvent() {
        Map<String, Object> preview = extractor.extractDownEventPreview(map(
            "downEvent",
            map("triggered", true, "deferred", false)
        ));

        assertThat(preview).isNull();
    }

    private Map<String, Object> map(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            value.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return value;
    }
}
