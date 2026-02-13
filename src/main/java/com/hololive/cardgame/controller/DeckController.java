package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.DeckCardResponse;
import com.hololive.cardgame.dto.UpdateDeckCardRequest;
import com.hololive.cardgame.entity.UserCard;
import com.hololive.cardgame.repository.CardRepository;
import com.hololive.cardgame.repository.UserCardRepository;
import com.hololive.cardgame.service.AuthUserResolver;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/decks")
public class DeckController {

    private final UserCardRepository userCardRepository;
    private final CardRepository cardRepository;
    private final AuthUserResolver authUserResolver;

    public DeckController(
        UserCardRepository userCardRepository,
        CardRepository cardRepository,
        AuthUserResolver authUserResolver
    ) {
        this.userCardRepository = userCardRepository;
        this.cardRepository = cardRepository;
        this.authUserResolver = authUserResolver;
    }

    @GetMapping("/me")
    public List<DeckCardResponse> getMyDeck() {
        Long userId = authUserResolver.currentUserId();
        return userCardRepository.findByUserIdOrderByCardIdAsc(userId)
            .stream()
            .map(DeckCardResponse::from)
            .toList();
    }

    @PutMapping("/me/cards/{cardId}")
    @ResponseStatus(HttpStatus.OK)
    public DeckCardResponse updateDeckCard(
        @PathVariable String cardId,
        @Valid @RequestBody UpdateDeckCardRequest request
    ) {
        String normalizedCardId = cardId.trim().toUpperCase();
        if (!cardRepository.existsById(normalizedCardId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到卡片：" + normalizedCardId);
        }

        Long userId = authUserResolver.currentUserId();
        int count = request.getCount();
        UserCard userCard = userCardRepository.findByUserIdAndCardId(userId, normalizedCardId).orElse(null);

        if (count == 0) {
            if (userCard != null) {
                userCardRepository.delete(userCard);
            }
            return new DeckCardResponse(normalizedCardId, 0);
        }

        if (userCard == null) {
            userCard = new UserCard();
            userCard.setUserId(userId);
            userCard.setCardId(normalizedCardId);
            userCard.setCreatedAt(LocalDateTime.now());
        }
        userCard.setCount(count);
        userCard.setUpdatedAt(LocalDateTime.now());
        return DeckCardResponse.from(userCardRepository.save(userCard));
    }
}
