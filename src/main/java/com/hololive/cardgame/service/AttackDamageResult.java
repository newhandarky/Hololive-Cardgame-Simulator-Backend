package com.hololive.cardgame.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AttackDamageResult(
    int baseDamage,
    int attachedSupportArtBonus,
    int artTextDamageBonus,
    int holoxRevealArtBonus,
    int passiveGiftArtBonus,
    int turnArtDamageModifier,
    String criticalColor,
    int criticalBonus,
    boolean criticalApplied,
    int turnIncomingDamageReduction,
    int passiveGiftIncomingDamageReduction,
    int attachedSupportIncomingDamageReduction,
    int incomingDamageReduction,
    int totalDamage
) {
    public Map<String, Object> toPayloadFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("artBaseDamage", baseDamage);
        fields.put("attachedSupportArtBonus", attachedSupportArtBonus);
        fields.put("artTextDamageBonus", artTextDamageBonus);
        fields.put("holoxRevealArtBonus", holoxRevealArtBonus);
        fields.put("passiveGiftArtBonus", passiveGiftArtBonus);
        fields.put("turnArtDamageModifier", turnArtDamageModifier);
        fields.put("criticalColor", criticalColor);
        fields.put("criticalBonus", criticalBonus);
        fields.put("criticalApplied", criticalApplied);
        fields.put("turnIncomingDamageReduction", turnIncomingDamageReduction);
        fields.put("passiveGiftIncomingDamageReduction", passiveGiftIncomingDamageReduction);
        fields.put("attachedSupportIncomingDamageReduction", attachedSupportIncomingDamageReduction);
        fields.put("incomingDamageReduction", incomingDamageReduction);
        fields.put("artTotalDamage", totalDamage);
        return Collections.unmodifiableMap(fields);
    }
}
