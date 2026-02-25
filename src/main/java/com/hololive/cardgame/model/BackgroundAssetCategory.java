package com.hololive.cardgame.model;

import java.util.Locale;

public enum BackgroundAssetCategory {
    FIELD,
    CARD;

    public static BackgroundAssetCategory parse(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("category 不可為空，僅支援 FIELD 或 CARD");
        }
        try {
            return BackgroundAssetCategory.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("category 僅支援 FIELD 或 CARD");
        }
    }
}
