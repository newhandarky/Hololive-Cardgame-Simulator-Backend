package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AttackDamageApplicationResult(
    Map<String, Object> artSummary,
    Long lostLifeCardInstanceId
) {
    public AttackDamageApplicationResult {
        artSummary = artSummary == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(artSummary));
    }

    public boolean hasLifeLoss() {
        return lostLifeCardInstanceId != null && lostLifeCardInstanceId > 0;
    }

    public boolean isPrevented() {
        return "ART_DAMAGE_PREVENTED".equals(asText(artSummary.get("effectType")));
    }

    public boolean isFallbackLifeLoss() {
        return "ART_DAMAGE_FALLBACK".equals(asText(artSummary.get("effectType")));
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
