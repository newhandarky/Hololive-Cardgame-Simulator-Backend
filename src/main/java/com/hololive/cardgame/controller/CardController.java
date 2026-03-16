package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.CardDetailResponse;
import com.hololive.cardgame.dto.CardSearchResponse;
import com.hololive.cardgame.dto.UpdatePreferredVariantRequest;
import com.hololive.cardgame.service.CardCatalogQueryService;
import com.hololive.cardgame.service.AuthUserResolver;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardCatalogQueryService cardCatalogQueryService;
    private final AuthUserResolver authUserResolver;

    /**
     * 卡片圖鑑 API 控制器，提供卡片查詢與變體偏好設定。
     */
    public CardController(CardCatalogQueryService cardCatalogQueryService, AuthUserResolver authUserResolver) {
        this.cardCatalogQueryService = cardCatalogQueryService;
        this.authUserResolver = authUserResolver;
    }

    @GetMapping
    /**
     * 依條件查詢卡片列表。
     */
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
        Long userId = authUserResolver.currentUserId();
        return cardCatalogQueryService.searchCards(
            userId,
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
    /**
     * 取得目前卡表可用的所有標籤。
     */
    public List<String> listAvailableTags() {
        return cardCatalogQueryService.getAvailableTags();
    }

    @GetMapping("/{cardId}")
    /**
     * 取得單一卡片完整詳情。
     */
    public CardDetailResponse getCardDetail(@PathVariable String cardId) {
        try {
            Long userId = authUserResolver.currentUserId();
            return cardCatalogQueryService.getCardDetail(cardId, userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @PutMapping("/{cardId}/preferred-variant")
    /**
     * 設定目前使用者對指定卡片的偏好圖像變體。
     */
    public CardDetailResponse updatePreferredVariant(
        @PathVariable String cardId,
        @RequestBody UpdatePreferredVariantRequest request
    ) {
        try {
            Long userId = authUserResolver.currentUserId();
            cardCatalogQueryService.setPreferredVariant(userId, cardId, request == null ? null : request.getVariantId());
            return cardCatalogQueryService.getCardDetail(cardId, userId);
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("找不到卡片")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
}
