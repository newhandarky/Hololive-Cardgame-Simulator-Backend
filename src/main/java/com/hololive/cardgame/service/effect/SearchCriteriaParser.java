package com.hololive.cardgame.service.effect;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

/**
 * 專責把 effect JSON / rawText 轉成 {@link SearchCriteria}。
 *
 * <p>搜尋效果目前同時支援兩種來源：
 *
 * <p>1. 結構化欄位，例如 `searchCriteria.cardType`
 * <p>2. 原始日文文案，例如 `#3期生のDebutホロメン`
 *
 * <p>這個 parser 的任務是把兩者整合成同一個條件模型，並且清楚定義優先序：
 *
 * <p>- 先讀結構化欄位
 * <p>- 缺值時再從 raw text 補推斷
 *
 * <p>這樣做可以讓後續 effect execution 不需要再重覆理解文案細節，只需要使用結果。
 */
public class SearchCriteriaParser {

    private static final Pattern TAG_PATTERN = Pattern.compile(
        "#([\\p{L}\\p{N}_'\\-]+?)(?=(?:を|が|に|で|と|へ|や|も|、|。|\\s|$))"
    );
    private static final Pattern NAME_TOKEN_PATTERN = Pattern.compile("〈([^〉]+)〉");
    private static final Pattern QUOTED_NAME_PATTERN = Pattern.compile("「([^」]+)」");

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;

    public SearchCriteriaParser(JdbcTemplate jdbcTemplate, EffectTextParser effectTextParser) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
    }

    /**
     * 解析單一效果節點中的 search criteria。
     *
     * <p>入口只暴露一個方法，讓上層不用知道 parser 內部還分成：
     *
     * <p>- 單一節點解析
     * <p>- allOf / anyOf 子條件解析
     * <p>- raw text fallback 推斷
     */
    public SearchCriteria resolveSearchCriteria(JsonNode effectNode) {
        JsonNode criteriaNode = effectNode == null ? null : effectNode.get("searchCriteria");
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        return resolveSearchCriteriaNode(criteriaNode, rawText, true);
    }

    /**
     * 依資料庫目前已知 tag 清單，從文案中推斷最可能的 tag。
     *
     * <p>這個方法被設計成 public，是因為 Gift 條件與 Search 條件都會用到同樣的推斷邏輯。
     * 抽出後可避免同一個「從已知 tag 字典做最長匹配」規則散落在多個地方。
     */
    public String resolveTagFromKnownTags(String rawText) {
        if (!StringUtils.hasText(rawText) || !rawText.contains("#")) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT t.tag
            FROM (
                SELECT DISTINCT jsonb_array_elements_text(COALESCE(tags_json, '[]'::jsonb)) AS tag
                FROM cards
                WHERE tags_json IS NOT NULL
            ) t
            WHERE ? LIKE '%' || t.tag || '%'
            ORDER BY POSITION(t.tag IN ?), LENGTH(t.tag) DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("tag") : null,
            rawText,
            rawText
        );
    }

    /**
     * 將單一 JSON 條件節點轉成 SearchCriteria。
     *
     * <p>若 `allowRawInference=true`，代表目前正在解析最外層條件，可以接受從 raw text 補齊缺欄。
     * 若是子條件 `allOf/anyOf`，則只吃明確欄位，避免把同一段 raw text 重覆套到每個子條件。
     */
    private SearchCriteria resolveSearchCriteriaNode(JsonNode criteriaNode, String rawText, boolean allowRawInference) {
        String cardType = normalizeCardType(readText(criteriaNode, "cardType"));
        String levelType = normalizeLevelType(readText(criteriaNode, "level", "levelType"));
        String tag = readText(criteriaNode, "tag");
        String nameContains = readText(criteriaNode, "nameContains");
        String color = normalizeColorType(readText(criteriaNode, "color", "mainColor", "cheerColor"));
        Boolean rested = readBoolean(criteriaNode, "rested", "isRested", "requireRested", "mustBeRested");
        Boolean active = readBoolean(criteriaNode, "active", "isActive", "mustBeActive");
        if (rested == null && active != null) {
            // active/rested 在資料來源中是兩種不同寫法，這裡統一折算為 rested。
            rested = !active;
        }
        Integer minRemainHp = extractNullableInt(
            criteriaNode,
            "minRemainHp",
            "remainHpMin",
            "remainingHpMin",
            "minHp",
            "hpMin",
            "hpAtLeast"
        );
        Integer maxRemainHp = extractNullableInt(
            criteriaNode,
            "maxRemainHp",
            "remainHpMax",
            "remainingHpMax",
            "maxHp",
            "hpMax",
            "hpAtMost"
        );
        List<SearchCriteria> allOf = resolveCriteriaList(criteriaNode == null ? null : criteriaNode.get("allOf"), rawText);
        List<SearchCriteria> anyOf = resolveCriteriaList(criteriaNode == null ? null : criteriaNode.get("anyOf"), rawText);

        if (!allowRawInference) {
            return new SearchCriteria(cardType, levelType, tag, nameContains, color, rested, minRemainHp, maxRemainHp, allOf, anyOf);
        }

        if (!StringUtils.hasText(cardType)) {
            boolean mentionsSupportSubtype = rawText.contains("サポート")
                || rawText.contains("ツール")
                || rawText.contains("イベント")
                || rawText.contains("ファン")
                || rawText.contains("マスコット")
                || rawText.contains("アイテム")
                || rawText.contains("スタッフ");
            // 這裡故意把 SUPPORT 判定放在 MEMBER 前面。
            //
            // 原因是像 `自分のホロメンがダウンした時、カード名に「パソコン」を含むアイテム1枚を...`
            // 這種文案同時會出現 `ホロメン` 與 `アイテム`：
            //
            // - `ホロメン` 是觸發條件的一部分
            // - `アイテム` 才是實際搜尋/回收的目標類型
            //
            // 若先看到 `ホロメン` 就決定為 MEMBER，後面的 RETURN_TO_HAND / SEARCH 候選就會整批抓錯。
            if (mentionsSupportSubtype) {
                cardType = "SUPPORT";
            } else if (rawText.contains("エール")) {
                cardType = "CHEER";
            } else if (rawText.contains("ホロメン")) {
                cardType = "MEMBER";
            }
        }
        if (!StringUtils.hasText(levelType)) {
            if (rawText.contains("Debut")) {
                levelType = "DEBUT";
            } else if (rawText.contains("1st")) {
                levelType = "FIRST";
            } else if (rawText.contains("2nd")) {
                levelType = "SECOND";
            } else if (rawText.contains("Buzz")) {
                levelType = "BUZZ";
            } else if (rawText.contains("Spot")) {
                levelType = "SPOT";
            }
        }
        if (!StringUtils.hasText(tag)) {
            tag = resolveTagFromKnownTags(rawText);
        }
        if (!StringUtils.hasText(tag)) {
            Matcher matcher = TAG_PATTERN.matcher(rawText);
            if (matcher.find()) {
                tag = "#" + matcher.group(1);
            }
        }
        if (!StringUtils.hasText(nameContains)) {
            nameContains = resolveNameContainsFromSearchTarget(rawText);
        }
        if (!StringUtils.hasText(nameContains)) {
            Matcher nameTokenMatcher = NAME_TOKEN_PATTERN.matcher(rawText);
            if (nameTokenMatcher.find()) {
                nameContains = nameTokenMatcher.group(1).trim();
            }
        }
        if (!StringUtils.hasText(nameContains)) {
            Matcher quotedNameMatcher = QUOTED_NAME_PATTERN.matcher(rawText);
            if (quotedNameMatcher.find()) {
                // 官方文案除了 〈名稱〉 之外，也常用「名稱を含む」描述部分卡名比對。
                // 這裡把日文引號也納入，讓像「パソコン」を含むアイテム 這種條件能落到同一套
                // nameContains 規則，而不是另外在 execution 層硬寫特例。
                nameContains = quotedNameMatcher.group(1).trim();
            }
        }
        if (!StringUtils.hasText(color)) {
            color = normalizeColorType(resolveCheerColorFilter(rawText));
        }
        return new SearchCriteria(cardType, levelType, tag, nameContains, color, rested, minRemainHp, maxRemainHp, allOf, anyOf);
    }

    /**
     * 解析 allOf / anyOf 子條件集合。
     *
     * <p>子條件不會再次對同一段 raw text 做推斷，避免一段文案被擴散成每個 child 都帶同一個隱含條件。
     */
    private List<SearchCriteria> resolveCriteriaList(JsonNode criteriaArrayNode, String rawText) {
        if (criteriaArrayNode == null || !criteriaArrayNode.isArray() || criteriaArrayNode.isEmpty()) {
            return List.of();
        }
        List<SearchCriteria> criteriaList = new ArrayList<>();
        for (JsonNode child : criteriaArrayNode) {
            if (child == null || child.isNull()) {
                continue;
            }
            criteriaList.add(resolveSearchCriteriaNode(child, rawText, false));
        }
        return criteriaList;
    }

    /**
     * 從真正的搜尋句段抓名稱條件，而不是盲目取第一個 `〈...〉`。
     *
     * <p>像 `HBP05-035` 這種文案同時有：
     *
     * <p>- 觸發條件：`自分の〈さくらみこ〉がダウンした時`
     * <p>- 搜尋目標：`自分のデッキから、〈み俺恥〉1枚を公開し、手札に加える`
     *
     * <p>若 parser 只抓第一個名稱 token，就會把觸發條件中的角色名誤判成搜尋目標。
     * 這裡先鎖定「從哪個來源區拿牌」那一段，再抽出真正要搜尋的名稱。
     */
    private String resolveNameContainsFromSearchTarget(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }
        String normalized = effectTextParser.normalizeDigits(rawText);
        Matcher matcher = Pattern.compile(
            "(?:デッキ|アーカイブ|ホロパワー)から[^。]*?〈([^〉]+)〉\\s*\\d+枚?"
        ).matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String readText(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private Boolean readBoolean(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isInt() || value.isLong()) {
                return value.asInt() != 0;
            }
            if (value.isTextual()) {
                String normalized = value.asText().trim().toLowerCase(Locale.ROOT);
                if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
                    return true;
                }
                if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
                    return false;
                }
            }
        }
        return null;
    }

    private Integer extractNullableInt(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return null;
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
                    return Integer.parseInt(effectTextParser.normalizeDigits(valueNode.asText()).trim());
                } catch (NumberFormatException ignored) {
                    // 解析失敗時保留 null，讓上層知道這不是有效條件。
                }
            }
        }
        return null;
    }

    private String normalizeCardType(String cardType) {
        String normalized = normalizeToken(cardType);
        if ("MEMBER".equals(normalized) || "SUPPORT".equals(normalized) || "CHEER".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private String normalizeColorType(String color) {
        String normalized = normalizeToken(color);
        return switch (normalized) {
            case "RED", "BLUE", "GREEN", "WHITE", "PURPLE", "YELLOW", "COLORLESS" -> normalized;
            default -> "";
        };
    }

    private String normalizeLevelType(String levelType) {
        String normalized = normalizeToken(levelType);
        return switch (normalized) {
            case "DEBUT", "FIRST", "SECOND", "SPOT", "BUZZ" -> normalized;
            case "1ST" -> "FIRST";
            case "2ND" -> "SECOND";
            default -> "";
        };
    }

    private String resolveCheerColorFilter(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        if (rawText.contains("赤")) {
            return "RED";
        }
        if (rawText.contains("青")) {
            return "BLUE";
        }
        if (rawText.contains("緑")) {
            return "GREEN";
        }
        if (rawText.contains("白")) {
            return "WHITE";
        }
        if (rawText.contains("紫")) {
            return "PURPLE";
        }
        if (rawText.contains("黄")) {
            return "YELLOW";
        }
        return "";
    }

    private String normalizeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
