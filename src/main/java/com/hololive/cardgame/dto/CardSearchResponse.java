package com.hololive.cardgame.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CardSearchResponse {

    private String cardId;
    private String name;
    private String cardType;
    private String rarity;
    private String imageUrl;
    private String cardNo;
    private String expansionCode;
    private String mainColor;
    private String levelType;
    private Integer life;
    private Integer hp;
    private List<String> tags;
    private Long selectedVariantId;
    private Integer variantCount;
}
