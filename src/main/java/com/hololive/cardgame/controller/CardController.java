package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.CardResponse;
import com.hololive.cardgame.entity.Card;
import com.hololive.cardgame.repository.CardRepository;
import java.util.List;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardRepository cardRepository;

    public CardController(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    @GetMapping
    public List<CardResponse> listCards(
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String keyword
    ) {
        List<Card> cards;
        if (StringUtils.hasText(type)) {
            cards = cardRepository.findByCardTypeOrderByCardIdAsc(type.trim().toUpperCase());
        } else if (StringUtils.hasText(keyword)) {
            cards = cardRepository.findByNameContainingIgnoreCaseOrderByCardIdAsc(keyword.trim());
        } else {
            cards = cardRepository.findAllByOrderByCardIdAsc();
        }
        return cards.stream().map(CardResponse::from).toList();
    }
}
