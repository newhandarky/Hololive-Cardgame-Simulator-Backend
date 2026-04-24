package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.AdminCreateCardRequest;
import com.hololive.cardgame.dto.CardResponse;
import com.hololive.cardgame.entity.Card;
import com.hololive.cardgame.service.AuthUserResolver;
import com.hololive.cardgame.service.CardAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/card-admin")
public class CardAdminController {

    private final CardAdminService cardAdminService;
    private final AuthUserResolver authUserResolver;

    /**
     * 卡片後台 API 控制器。
     */
    public CardAdminController(CardAdminService cardAdminService, AuthUserResolver authUserResolver) {
        this.cardAdminService = cardAdminService;
        this.authUserResolver = authUserResolver;
    }

    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * 建立新卡片（含子表資料）。
     */
    public CardResponse createCard(@RequestBody AdminCreateCardRequest request) {
        try {
            Long actorUserId = authUserResolver.currentUserId();
            Card card = cardAdminService.createCard(actorUserId, request);
            return CardResponse.from(card);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
