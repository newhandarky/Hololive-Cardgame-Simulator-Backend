package com.hololive.cardgame.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CardDetailResponse {

    private String cardId;
    private String name;
    private String cardType;
    private String rarity;
    private String imageUrl;
    private String cardNo;
    private String expansionCode;
    private String sourceUrl;
    private List<String> tags;
    private Long selectedVariantId;
    private List<CardVariantItem> variants;

    private String mainColor;
    private String subColor;
    private Integer life;

    private Integer hp;
    private String levelType;
    private Integer bloomLevel;
    private String passiveEffectJson;
    private String triggerCondition;

    private Boolean supportLimited;
    private String supportConditionType;
    private String supportConditionJson;
    private String supportEffectType;
    private String supportEffectJson;
    private String supportTargetType;

    private String cheerColor;

    private List<OshiSkillItem> oshiSkills;
    private List<MemberArtItem> memberArts;

    @Data
    @AllArgsConstructor
    public static class CardVariantItem {
        private Long id;
        private String variantCode;
        private String variantName;
        private String imageUrl;
        private Boolean isDefault;
    }

    @Data
    @AllArgsConstructor
    public static class OshiSkillItem {
        private String skillType;
        private String skillName;
        private String description;
        private Integer holopowerCost;
        private String effectJson;
    }

    @Data
    @AllArgsConstructor
    public static class MemberArtItem {
        private Integer orderIndex;
        private String name;
        private String description;
        private String costCheerJson;
        private String effectJson;
    }
}
