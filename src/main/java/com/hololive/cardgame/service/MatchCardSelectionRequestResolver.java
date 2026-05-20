package com.hololive.cardgame.service;

import static com.hololive.cardgame.service.MatchEffectValueHelper.extractEffectNodeLongList;
import static com.hololive.cardgame.service.MatchEffectValueHelper.readText;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class MatchCardSelectionRequestResolver {

    private static final Pattern SEARCH_RANGE_PATTERN = Pattern.compile("(\\d+)\\s*[~〜～]\\s*(\\d+)\\s*枚");
    private static final Pattern SEARCH_COUNT_PATTERN = Pattern.compile("(\\d+)\\s*枚");
    private static final Pattern SEARCH_LOOK_TOP_COUNT_PATTERN = Pattern.compile("デッキの上から\\s*(\\d+)\\s*枚を見る");

    private final EffectTextParser effectTextParser;

    MatchCardSelectionRequestResolver(EffectTextParser effectTextParser) {
        this.effectTextParser = effectTextParser;
    }

    int resolveSearchCount(JsonNode effectNode) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        Matcher range = SEARCH_RANGE_PATTERN.matcher(text);
        if (range.find()) {
            try {
                return Integer.parseInt(range.group(2));
            } catch (NumberFormatException ignored) {
                // 解析失敗時回退到下一規則
            }
        }
        int count = effectTextParser.extractByPattern(text, SEARCH_COUNT_PATTERN);
        if (count > 0) {
            return count;
        }
        return text.contains("手札に加える") ? 1 : 0;
    }

    int resolveSearchLookTopCount(JsonNode effectNode, String rawText) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "lookTopCount", "lookCount", "peekCount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = effectTextParser.normalizeDigits(rawText);
        Matcher matcher = SEARCH_LOOK_TOP_COUNT_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    String resolveSearchSourceZone(JsonNode effectNode, String rawText) {
        String explicit = effectTextParser.normalizeEffectType(readText(effectNode, "searchSourceZone", "sourceZone", "searchFromZone"));
        if ("DECK".equals(explicit) || "ARCHIVE".equals(explicit) || "HOLOPOWER".equals(explicit) || "HAND".equals(explicit)) {
            return explicit;
        }
        String text = effectTextParser.normalizeDigits(rawText);
        if (
            text.contains("ホロパワー")
                && (text.contains("見る") || text.contains("見"))
                && text.contains("手札に加える")
        ) {
            return "HOLOPOWER";
        }
        if ((text.contains("アーカイブから") || text.contains("アーカイブにある")) && text.contains("手札に加える")) {
            return "ARCHIVE";
        }
        return "DECK";
    }

    int resolveActionCount(JsonNode effectNode, String fallbackToken, int defaultValue) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        Matcher range = SEARCH_RANGE_PATTERN.matcher(text);
        if (range.find()) {
            try {
                return Integer.parseInt(range.group(2));
            } catch (NumberFormatException ignored) {
                // 解析失敗時回退到下一規則
            }
        }
        int count = effectTextParser.extractByPattern(text, SEARCH_COUNT_PATTERN);
        if (count > 0) {
            return count;
        }
        if (StringUtils.hasText(fallbackToken) && text.contains(fallbackToken)) {
            return 1;
        }
        return defaultValue;
    }

    String resolveReturnToHandSourceZone(JsonNode effectNode, String rawText) {
        return usesGiftHolderStackSnapshotForReturnToHand(effectNode, rawText) ? "GIFT_HOLDER_STACK" : "ARCHIVE";
    }

    boolean usesGiftHolderStackSnapshotForReturnToHand(JsonNode effectNode, String rawText) {
        return StringUtils.hasText(rawText)
            && rawText.contains("重なっているホロメン")
            && !extractEffectNodeLongList(effectNode, "giftHolderStackCardInstanceIds").isEmpty();
    }
}
