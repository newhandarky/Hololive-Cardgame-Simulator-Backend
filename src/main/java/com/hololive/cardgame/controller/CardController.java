package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.CardDetailResponse;
import com.hololive.cardgame.dto.CardSearchResponse;
import com.hololive.cardgame.service.CardCatalogQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardCatalogQueryService cardCatalogQueryService;

    public CardController(CardCatalogQueryService cardCatalogQueryService) {
        this.cardCatalogQueryService = cardCatalogQueryService;
    }

    @GetMapping
    public List<CardSearchResponse> listCards(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String rarity,
        @RequestParam(required = false) String color,
        @RequestParam(required = false) String levelType,
        @RequestParam(required = false) String expansionCode,
        @RequestParam(required = false) List<String> tags,
        @RequestParam(required = false) Boolean hasImage,
        @RequestParam(required = false, defaultValue = "cardNo") String sort
    ) {
        return cardCatalogQueryService.searchCards(
            keyword,
            type,
            rarity,
            color,
            levelType,
            expansionCode,
            tags,
            hasImage,
            sort
        );
    }

    @GetMapping("/tags")
    public List<String> listAvailableTags() {
        return cardCatalogQueryService.getAvailableTags();
    }

    @GetMapping("/{cardId}")
    public CardDetailResponse getCardDetail(@PathVariable String cardId) {
        try {
            return cardCatalogQueryService.getCardDetail(cardId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }
}
