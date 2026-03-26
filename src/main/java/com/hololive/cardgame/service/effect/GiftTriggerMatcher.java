package com.hololive.cardgame.service.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 集中處理 Gift 觸發文案的基礎 matcher。
 *
 * <p>這個類別只負責「文字層」與「已知 source/holder 資訊」的比對，不直接碰資料庫，也不負責：
 *
 * <p>- turn-based 限次
 * <p>- performance snapshot
 * <p>- 真正效果執行
 *
 * <p>這樣切分的理由是，Gift trigger 的判斷其實可分成兩層：
 *
 * <p>1. 純文字與來源條件是否對得上
 * <p>2. 目前對戰狀態是否允許觸發
 *
 * <p>本類別處理第 1 層，讓 `MatchEffectService` 可以把較需要 DB 上下文的部分保留在自己身上。
 */
public class GiftTriggerMatcher {

    private static final Pattern NAME_TOKEN_PATTERN = Pattern.compile("〈([^〉]+)〉");
    private static final Pattern SOURCE_NAME_TOKEN_PATTERN = Pattern.compile("〈([^〉]+)〉が");

    /**
     * 判斷 Gift 原文是否宣告了對應 trigger type。
     *
     * <p>這裡只看文案本身，不碰 holder 與 source context。原因是 trigger type 是第一層粗篩，
     * 先把完全不相關的 Gift 排除，再進入後續更細的條件判斷，可以降低後面規則檢查的噪音。
     */
    public boolean matchesGiftTriggerType(String giftText, String triggerType) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        return switch (triggerType) {
            case "ART_USED" -> (giftText.contains("アーツ")
                && (giftText.contains("使った時") || giftText.contains("公開した時")))
                || (giftText.contains("特殊ダメージ") && giftText.contains("与えた時"));
            case "DAMAGE_RECEIVED" -> giftText.contains("ダメージを受ける時");
            case "OPPONENT_DOWNED" -> giftText.contains("ダウン") && giftText.contains("時") && !giftText.contains("このホロメンがダウンした時");
            case "SELF_DOWNED" -> giftText.contains("このホロメンがダウンした時")
                || (giftText.contains("ダウンした時")
                && !giftText.contains("ダウンさせた時")
                && !giftText.contains("相手のホロメンがダウンした時"));
            case "ALLY_DOWNED" -> giftText.contains("ダウンした時")
                && !giftText.contains("このホロメンがダウンした時")
                && !giftText.contains("ダウンさせた時")
                && !giftText.contains("相手のホロメンがダウンした時");
            case "COLLAB" -> giftText.contains("コラボした時");
            case "BATON_TOUCH_BACK" -> giftText.contains("バトンタッチ") && giftText.contains("バックポジションに移動した時");
            case "PERFORMANCE_START_SELF" -> giftText.contains("自分のパフォーマンスステップが開始する時");
            case "MAIN_STEP_SELF" -> giftText.contains("自分のメインステップ");
            case "PERFORMANCE_START_OPPONENT" -> giftText.contains("相手のパフォーマンスステップが開始する時");
            case "PERFORMANCE_END_SELF" -> giftText.contains("自分のパフォーマンスステップが終了する時");
            case "PERFORMANCE_END_OPPONENT" -> giftText.contains("相手のパフォーマンスステップが終了する時");
            case "STAGE_ENTER" -> giftText.contains("ステージに出た時");
            default -> false;
        };
    }

    /**
     * 檢查 Gift 持有者站位是否符合文案限制。
     *
     * <p>這裡處理的是像「センターポジション限定」「コラボポジション限定」這種 holder restriction。
     * 它和 source card 在哪裡是兩件事，所以獨立成 matcher，避免之後把持有者限制和來源限制混在一起。
     */
    public boolean matchesGiftHolderZoneRestriction(String giftText, String holderZone) {
        if (!StringUtils.hasText(giftText)) {
            return true;
        }
        if (giftText.contains("センターポジション・コラボポジション限定")) {
            return "CENTER".equals(holderZone) || "COLLAB".equals(holderZone);
        }
        boolean centerLimited = giftText.contains("センターポジション限定");
        boolean collabLimited = giftText.contains("コラボポジション限定");
        boolean backLimited = giftText.contains("バックポジション限定");
        if (centerLimited || collabLimited || backLimited) {
            return (centerLimited && "CENTER".equals(holderZone))
                || (collabLimited && "COLLAB".equals(holderZone))
                || (backLimited && "BACK".equals(holderZone));
        }
        return true;
    }

    /**
     * 檢查文案是否對來源 Holomem 的 level 有限制。
     *
     * <p>這一層只負責文字和來源等級的對應，不處理「這張卡是不是從哪一層 Bloom 上來」這種更高階
     * 的規則推斷。那種屬於流程狀態，而不是純文字條件。
     */
    public boolean matchesGiftStageEnterSourceLevelCondition(String giftText, String sourceLevelType) {
        if (!StringUtils.hasText(giftText)) {
            return true;
        }
        List<String> allowedLevels = new ArrayList<>();
        if (giftText.contains("Debutホロメン")) {
            allowedLevels.add("DEBUT");
        }
        if (giftText.contains("Spotホロメン")) {
            allowedLevels.add("SPOT");
        }
        if (giftText.contains("1stホロメン")) {
            allowedLevels.add("FIRST");
        }
        if (giftText.contains("2ndホロメン")) {
            allowedLevels.add("SECOND");
        }
        if (giftText.contains("Buzzホロメン")) {
            allowedLevels.add("BUZZ");
        }
        if (allowedLevels.isEmpty()) {
            return true;
        }
        return allowedLevels.contains(normalizeLevelType(sourceLevelType));
    }

    /**
     * 檢查文案中的名稱 token 是否與來源卡名相符。
     *
     * <p>目前 Gift 常見的寫法是 `〈某某〉がダウンした時` 這類文案，因此先從尖括號抽 token，再對
     * source card name 做寬鬆包含比對。採用 contains 而不是完全相等，是為了兼容副標題、版本名
     * 或資料表名稱格式差異。
     */
    public boolean matchesGiftDownedSourceNameCondition(String giftText, String sourceCardName) {
        List<String> nameTokens = extractNameTokens(giftText);
        if (nameTokens.isEmpty()) {
            return true;
        }
        if (!StringUtils.hasText(sourceCardName)) {
            return false;
        }
        String normalizedName = sourceCardName.toLowerCase(Locale.ROOT);
        for (String token : nameTokens) {
            if (normalizedName.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 檢查 Gift 文案是否明確把 `〈名稱〉` 綁在「觸發來源」上。
     *
     * <p>這和一般的 `extractNameTokens(...)` 不同。像 `HBP06-084` 的：
     *
     * <p>- `自分のステージの〈博衣こより〉1人のアーツ+20`
     *
     * <p>這裡的 `〈博衣こより〉` 是效果目標，不是觸發來源。如果仍用寬鬆的「只要看見尖括號名稱就當作
     * source condition」邏輯，會把這類 Gift 誤判成只有來源卡名也叫 `博衣こより` 才能觸發。
     *
     * <p>因此這個 matcher 只接受 `〈名稱〉が...` 這種明確把名稱掛在主詞上的寫法。沒有這種寫法時，
     * 視為文案沒有對來源卡名加限制。
     */
    public boolean matchesGiftExplicitSourceNameCondition(String giftText, String sourceCardName) {
        List<String> nameTokens = extractExplicitSourceNameTokens(giftText);
        if (nameTokens.isEmpty()) {
            return true;
        }
        if (!StringUtils.hasText(sourceCardName)) {
            return false;
        }
        String normalizedName = sourceCardName.toLowerCase(Locale.ROOT);
        for (String token : nameTokens) {
            if (normalizedName.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 從 Gift 文案抽出 `〈名稱〉` token。
     *
     * <p>這個 token 抽取會去重，避免同一段文案被重複寫入名稱條件，讓後續比對與除錯都更單純。
     */
    public List<String> extractNameTokens(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return List.of();
        }
        Matcher matcher = NAME_TOKEN_PATTERN.matcher(rawText);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!StringUtils.hasText(token)) {
                continue;
            }
            String normalized = token.trim();
            if (!normalized.isEmpty() && !tokens.contains(normalized)) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    /**
     * 從 `〈名稱〉が...` 結構抽出明確屬於觸發來源主詞的名稱 token。
     */
    public List<String> extractExplicitSourceNameTokens(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return List.of();
        }
        Matcher matcher = SOURCE_NAME_TOKEN_PATTERN.matcher(rawText);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!StringUtils.hasText(token)) {
                continue;
            }
            String normalized = token.trim();
            if (!normalized.isEmpty() && !tokens.contains(normalized)) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    /**
     * 正規化 level token。
     *
     * <p>這裡只做 null-safe trim + upper，因為 level 的完整語意仍由上層決定。
     */
    private String normalizeLevelType(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
