package com.hololive.cardgame.dto;

import lombok.Data;

@Data
public class AdminCreateCardRequest {
    // 共用欄位
    private String cardId;
    private String name;
    private String rarity;
    private String imageUrl;
    private String cardType;

    // OSHI 專用
    private Integer life;
    private String mainColor;
    private String subColor;

    // MEMBER 專用
    private Integer hp;
    private String levelType;
    private Integer bloomLevel;
    private String passiveEffectJson;
    private String triggerCondition;

    // SUPPORT 專用
    private Boolean limited;
    private String conditionType;
    private String conditionJson;
    private String effectType;
    private String effectJson;
    private String targetType;

    // CHEER 專用
    private String color;
}
