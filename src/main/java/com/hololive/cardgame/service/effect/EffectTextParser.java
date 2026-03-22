package com.hololive.cardgame.service.effect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 集中處理效果文字與 effect JSON 的基礎解析。
 *
 * <p>這個類別的責任刻意維持在「文字/JSON 轉換」這一層，不處理任何對戰規則判定、
 * 觸發資格驗證或資料庫更新。這樣拆分的目的有兩個：
 *
 * <p>1. 把 {@code MatchEffectService} 中大量重複的基礎解析工具抽離，降低主服務的噪音。
 * <p>2. 讓後續閱讀程式碼時，能先理解「文字如何被轉成可判斷資料」，再往上看規則流程。
 *
 * <p>多數方法都採保守策略：解析失敗時回傳 0、空字串、null 或空 JSON，而不是拋例外。
 * 這是因為目前效果系統仍大量依賴日文文案與歷史資料，若在基礎解析層直接中斷，會讓上層
 * 無法走既有 fallback 路徑，也會讓單一卡片文案問題擴散成整個 action 失敗。
 */
public class EffectTextParser {

    private final ObjectMapper objectMapper;

    public EffectTextParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 安全解析 effect JSON。
     *
     * <p>這裡不拋出例外，而是回傳 {@code null}，讓上層可以選擇走保守邏輯：
     *
     * <p>- 略過結構化欄位
     * <p>- 回退到 raw text 關鍵字推斷
     * <p>- 或直接把這個效果視為目前無法精準解析
     *
     * <p>這個設計是刻意的，因為目前專案同時面對結構化 JSON、歷史資料與原始文案三種來源。
     */
    public JsonNode parseEffectJson(String effectJson) {
        if (!StringUtils.hasText(effectJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(effectJson);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 安全序列化為 JSON 字串。
     *
     * <p>失敗時回傳空物件字串，而不是中斷流程。這樣做的原因是部分 payload 只是用來保留
     * 補充上下文或摘要資訊，序列化失敗不應直接破壞整個對戰流程。
     */
    public String toJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /**
     * 依欄位順序讀取整數。
     *
     * <p>上層會用這個方法定義「欄位優先序」，例如先讀結構化 value，再讀 cards，再讀 amount。
     * 若欄位是字串，也會先做全形數字正規化再嘗試轉整數。
     *
     * <p>回傳預設值而不是例外，是為了讓後續規則仍可繼續嘗試從 raw text 推斷。
     */
    public int extractInt(JsonNode node, int defaultValue, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return defaultValue;
        }
        for (String fieldName : fieldNames) {
            JsonNode valueNode = node.get(fieldName);
            if (valueNode == null || valueNode.isNull()) {
                continue;
            }
            if (valueNode.isInt() || valueNode.isLong()) {
                return valueNode.asInt();
            }
            if (valueNode.isTextual()) {
                try {
                    return Integer.parseInt(normalizeDigits(valueNode.asText()).trim());
                } catch (NumberFormatException ignored) {
                    // 這層只負責安全轉換，無法解析時交由後續欄位或 raw text fallback 處理。
                }
            }
        }
        return defaultValue;
    }

    /**
     * 從文字中擷取第一個正則整數群組。
     *
     * <p>這個方法只負責最基礎的 group(1) 整數抽取，不解釋語意。語意層例如「是傷害還是回復」
     * 仍由上層方法決定。
     */
    public int extractByPattern(String value, Pattern pattern) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 依欄位順序合併文字。
     *
     * <p>效果 JSON 常把規則文案拆在 `rawText`、`rawEffect`、`rawHeader` 等欄位。這裡統一把它們
     * 依順序串成單一文字來源，讓後續正則與關鍵字判斷只需要面對一份文本。
     *
     * <p>採用換行串接是刻意保留欄位之間的區隔，避免直接拼接後出現關鍵字黏在一起、導致誤判。
     */
    public String extractText(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (String fieldName : fieldNames) {
            JsonNode textNode = node.get(fieldName);
            if (textNode != null && textNode.isTextual() && StringUtils.hasText(textNode.asText())) {
                if (!merged.isEmpty()) {
                    merged.append('\n');
                }
                merged.append(textNode.asText());
            }
        }
        return merged.toString();
    }

    /**
     * 為 DAMAGE 類效果補上 defer flag。
     *
     * <p>目前某些 down event 需要先顯示互動確認，再延後執行真正的 down 結算。這個方法只負責
     * 在 effect JSON 上加旗標，不直接解釋規則意義，避免解析層與流程控制層混在一起。
     */
    public JsonNode withDeferDownEventFlag(JsonNode effectNode, boolean deferDownEvent) {
        if (!deferDownEvent) {
            return effectNode;
        }
        ObjectNode node;
        if (effectNode instanceof ObjectNode objectNode) {
            node = objectNode.deepCopy();
        } else {
            node = objectMapper.createObjectNode();
            if (effectNode != null && !effectNode.isNull()) {
                node.set("sourceEffect", effectNode);
            }
        }
        node.put("deferDownEvent", true);
        return node;
    }

    /**
     * 將全形數字轉成半形數字。
     *
     * <p>由於官方日文文案與資料匯入來源可能混用全形/半形數字，所有正則與數值解析前都應先做
     * 這一步，否則像「３枚」「６以上」這類文本會被當成無法判讀。
     */
    public String normalizeDigits(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c >= '０' && c <= '９') {
                builder.append((char) ('0' + (c - '０')));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * 正規化 effect type。
     *
     * <p>這裡只做最保守的字串整理：trim + upper case。刻意不在這層加入別名映射，因為別名是否
     * 視為同一種效果，屬於規則層決策，應由上層決定。
     */
    public String normalizeEffectType(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
