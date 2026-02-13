package com.hololive.cardgame.dto;

import com.hololive.cardgame.entity.UserCard;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeckCardResponse {
    private String cardId;
    private Integer count;

    public static DeckCardResponse from(UserCard userCard) {
        return new DeckCardResponse(userCard.getCardId(), userCard.getCount());
    }
}
