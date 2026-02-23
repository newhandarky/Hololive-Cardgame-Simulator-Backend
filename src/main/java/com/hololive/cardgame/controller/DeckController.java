package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.DeckCardResponse;
import com.hololive.cardgame.dto.CreateDeckRequest;
import com.hololive.cardgame.dto.DeckDetailResponse;
import com.hololive.cardgame.dto.DeckSummaryResponse;
import com.hololive.cardgame.dto.DeckValidationResponse;
import com.hololive.cardgame.dto.StarterDeckPresetResponse;
import com.hololive.cardgame.dto.UpdateDeckCardRequest;
import com.hololive.cardgame.dto.UpdateDeckMetaRequest;
import com.hololive.cardgame.service.AuthUserResolver;
import com.hololive.cardgame.service.DeckService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/decks")
public class DeckController {

    private final DeckService deckService;
    private final AuthUserResolver authUserResolver;

    public DeckController(
        DeckService deckService,
        AuthUserResolver authUserResolver
    ) {
        this.deckService = deckService;
        this.authUserResolver = authUserResolver;
    }

    @GetMapping("/me/list")
    public List<DeckSummaryResponse> listMyDecks() {
        Long userId = authUserResolver.currentUserId();
        return deckService.listDeckSummaries(userId);
    }

    @PostMapping("/me")
    @ResponseStatus(HttpStatus.CREATED)
    public DeckDetailResponse createDeck(@Valid @RequestBody CreateDeckRequest request) {
        Long userId = authUserResolver.currentUserId();
        try {
            return deckService.createDeck(userId, request.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/me/{deckId}")
    public DeckDetailResponse getMyDeckDetail(@PathVariable Long deckId) {
        Long userId = authUserResolver.currentUserId();
        try {
            return deckService.getDeckDetail(userId, deckId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PatchMapping("/me/{deckId}")
    public DeckDetailResponse renameDeck(@PathVariable Long deckId, @Valid @RequestBody UpdateDeckMetaRequest request) {
        Long userId = authUserResolver.currentUserId();
        try {
            return deckService.renameDeck(userId, deckId, request.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/me/{deckId}/activate")
    public DeckDetailResponse activateDeck(@PathVariable Long deckId) {
        Long userId = authUserResolver.currentUserId();
        try {
            return deckService.activateDeck(userId, deckId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/me/{deckId}/validate")
    public DeckValidationResponse validateDeck(@PathVariable Long deckId) {
        Long userId = authUserResolver.currentUserId();
        try {
            return deckService.validateDeck(userId, deckId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/me")
    public List<DeckCardResponse> getMyDeck() {
        Long userId = authUserResolver.currentUserId();
        return deckService.getActiveDeckCards(userId);
    }

    @GetMapping("/starter-presets")
    public List<StarterDeckPresetResponse> listStarterDeckPresets() {
        return deckService.listStarterDeckPresets();
    }

    @PutMapping("/me/cards/{cardId}")
    @ResponseStatus(HttpStatus.OK)
    public DeckCardResponse updateDeckCard(
        @PathVariable String cardId,
        @Valid @RequestBody UpdateDeckCardRequest request
    ) {
        Long userId = authUserResolver.currentUserId();
        try {
            return deckService.updateActiveDeckCard(userId, cardId, request.getCount());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/me/{deckId}/cards/{cardId}")
    @ResponseStatus(HttpStatus.OK)
    public DeckCardResponse updateDeckCardInSpecificDeck(
        @PathVariable Long deckId,
        @PathVariable String cardId,
        @Valid @RequestBody UpdateDeckCardRequest request
    ) {
        Long userId = authUserResolver.currentUserId();
        try {
            return deckService.updateDeckCard(userId, deckId, cardId, request.getCount());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 一鍵建立本地最小可測牌組，方便新帳號直接測試 Start Match。
     */
    @PostMapping("/me/quick-setup")
    @ResponseStatus(HttpStatus.OK)
    public List<DeckCardResponse> setupQuickDeck(@RequestParam(required = false) String preset) {
        Long userId = authUserResolver.currentUserId();
        try {
            return deckService.setupQuickDeck(userId, preset);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
