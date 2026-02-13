package com.hololive.cardgame.dto;

import com.hololive.cardgame.entity.Card;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CardResponse {
    private String cardId;
    private String name;
    private String cardType;
    private String rarity;
    private String imageUrl;

    public static CardResponse from(Card card) {
        return new CardResponse(
            card.getCardId(),
            card.getName(),
            card.getCardType(),
            card.getRarity(),
            card.getImageUrl()
        );
    }
}
