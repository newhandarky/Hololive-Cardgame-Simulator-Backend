package com.hololive.cardgame.service.effect;

import java.util.List;

/**
 * 統一描述「可檢索卡片條件」的模型。
 *
 * <p>這個模型被抽成獨立型別，目的不是單純把 record 搬出大檔，而是把「條件資料本身」和
 * 「條件如何被解析」以及「條件如何被使用」分開：
 *
 * <p>1. {@link SearchCriteriaParser} 專責建立它
 * <p>2. `MatchEffectService` 專責拿它去過濾候選卡與建立摘要
 *
 * <p>這樣後續要擴充搜尋條件時，不需要再把 parser、executor、summary builder 一起改在同一個超大檔裡。
 */
public record SearchCriteria(
    String cardType,
    String levelType,
    String tag,
    String nameContains,
    String color,
    Boolean rested,
    Integer minRemainHp,
    Integer maxRemainHp,
    List<SearchCriteria> allOf,
    List<SearchCriteria> anyOf
) {

    /**
     * 建立 SearchCriteria 時先做欄位正規化。
     *
     * <p>這裡只做最基本的 null-safe trim 與子條件不可變包裝，不在模型層加入規則推斷，
     * 讓模型保持單純、可預期。
     */
    public SearchCriteria {
        cardType = normalizeToken(cardType);
        levelType = normalizeToken(levelType);
        tag = normalizeToken(tag);
        nameContains = normalizeToken(nameContains);
        color = normalizeToken(color);
        allOf = allOf == null ? List.of() : List.copyOf(allOf);
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
    }

    /**
     * 建立簡化版條件。
     *
     * <p>目前有不少舊流程只需要最常見的四欄位，保留這個建構子可以避免在大量既有呼叫點
     * 重覆傳遞空條件。
     */
    public SearchCriteria(String cardType, String levelType, String tag, String nameContains) {
        this(cardType, levelType, tag, nameContains, "", null, null, null, List.of(), List.of());
    }

    /**
     * 建立完全空條件。
     *
     * <p>空條件代表「不限制內容」，而不是解析失敗。這兩者在語意上不同，因此保留明確的工廠方法。
     */
    public static SearchCriteria empty() {
        return new SearchCriteria("", "", "", "", "", null, null, null, List.of(), List.of());
    }

    /**
     * 判斷是否完全沒有任何限制。
     */
    public boolean isEmpty() {
        return cardType.isEmpty()
            && levelType.isEmpty()
            && tag.isEmpty()
            && nameContains.isEmpty()
            && color.isEmpty()
            && rested == null
            && minRemainHp == null
            && maxRemainHp == null
            && allOf.isEmpty()
            && anyOf.isEmpty();
    }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.trim();
    }
}
