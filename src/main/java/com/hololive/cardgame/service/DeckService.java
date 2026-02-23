package com.hololive.cardgame.service;

import com.hololive.cardgame.dto.DeckCardResponse;
import com.hololive.cardgame.dto.DeckDetailResponse;
import com.hololive.cardgame.dto.DeckSummaryResponse;
import com.hololive.cardgame.dto.DeckValidationErrorResponse;
import com.hololive.cardgame.dto.DeckValidationResponse;
import com.hololive.cardgame.dto.StarterDeckPresetResponse;
import com.hololive.cardgame.entity.Card;
import com.hololive.cardgame.entity.DeckCardEntity;
import com.hololive.cardgame.entity.DeckEntity;
import com.hololive.cardgame.repository.CardRepository;
import com.hololive.cardgame.repository.DeckCardRepository;
import com.hololive.cardgame.repository.DeckRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeckService {

    private static final int REQUIRED_MAIN_DECK_SIZE = 50;
    private static final int REQUIRED_CHEER_DECK_SIZE = 20;
    private static final int MAIN_CARD_DUPLICATE_LIMIT = 4;
    private static final int OSHI_CARD_LIMIT = 1;
    private static final String DEFAULT_DECK_NAME = "預設牌組";
    private static final String PRESET_AUTO = "AUTO";
    private static final String PRESET_STARTER_ADVENT_SHIORI = "STARTER_ADVENT_SHIORI";
    private static final String PRESET_STARTER_ADVENT_BIJOU = "STARTER_ADVENT_BIJOU";
    private static final String PRESET_STARTER_JUSTICE_ERB = "STARTER_JUSTICE_ERB";
    private static final String PRESET_STARTER_JUSTICE_GIGI = "STARTER_JUSTICE_GIGI";
    private static final String DEFAULT_STARTER_PRESET = PRESET_STARTER_JUSTICE_ERB;
    private static final Set<String> ALLOWED_CARD_TYPES = Set.of("OSHI", "MEMBER", "SUPPORT", "CHEER");
    private static final Map<String, StarterDeckPreset> STARTER_DECK_PRESETS = createStarterDeckPresets();

    private final DeckRepository deckRepository;
    private final DeckCardRepository deckCardRepository;
    private final CardRepository cardRepository;

    public DeckService(
        DeckRepository deckRepository,
        DeckCardRepository deckCardRepository,
        CardRepository cardRepository
    ) {
        this.deckRepository = deckRepository;
        this.deckCardRepository = deckCardRepository;
        this.cardRepository = cardRepository;
    }

    @Transactional(readOnly = true)
    public List<DeckSummaryResponse> listDeckSummaries(Long userId) {
        return deckRepository.findByUserIdOrderByUpdatedAtDesc(userId)
            .stream()
            .map(this::toSummary)
            .toList();
    }

    @Transactional(readOnly = true)
    public DeckDetailResponse getDeckDetail(Long userId, Long deckId) {
        DeckEntity deck = getDeck(userId, deckId);
        return toDetail(deck);
    }

    @Transactional
    public DeckDetailResponse createDeck(Long userId, String requestedName) {
        String normalizedName = normalizeDeckName(requestedName);
        String finalName = ensureUniqueDeckName(userId, normalizedName, null);

        DeckEntity deck = new DeckEntity();
        deck.setUserId(userId);
        deck.setName(finalName);
        deck.setFormat("STANDARD");
        deck.setVersion(1);
        deck.setCreatedAt(LocalDateTime.now());
        deck.setUpdatedAt(LocalDateTime.now());

        boolean hasActiveDeck = deckRepository.findByUserIdAndActiveTrue(userId).isPresent();
        deck.setActive(!hasActiveDeck);

        DeckEntity saved = deckRepository.save(deck);
        return toDetail(saved);
    }

    @Transactional
    public DeckDetailResponse renameDeck(Long userId, Long deckId, String newName) {
        DeckEntity deck = getDeck(userId, deckId);
        String normalizedName = normalizeDeckName(newName);
        String finalName = ensureUniqueDeckName(userId, normalizedName, deck.getId());

        if (!deck.getName().equals(finalName)) {
            deck.setName(finalName);
            bumpVersion(deck);
            deckRepository.save(deck);
        }
        return toDetail(deck);
    }

    @Transactional
    public DeckDetailResponse activateDeck(Long userId, Long deckId) {
        DeckEntity targetDeck = getDeck(userId, deckId);
        List<DeckEntity> userDecks = deckRepository.findByUserIdOrderByUpdatedAtDesc(userId);

        LocalDateTime now = LocalDateTime.now();
        for (DeckEntity deck : userDecks) {
            boolean shouldActive = deck.getId().equals(targetDeck.getId());
            if (deck.isActive() != shouldActive) {
                deck.setActive(shouldActive);
                deck.setUpdatedAt(now);
                if (shouldActive) {
                    deck.setVersion(deck.getVersion() == null ? 1 : deck.getVersion() + 1);
                }
            }
        }

        deckRepository.saveAll(userDecks);
        return toDetail(targetDeck);
    }

    @Transactional(readOnly = true)
    public DeckValidationResponse validateDeck(Long userId, Long deckId) {
        DeckEntity deck = getDeck(userId, deckId);
        List<DeckCardEntity> deckCards = deckCardRepository.findByDeckIdOrderByCardIdAsc(deck.getId());
        return validateDeckCards(deckCards);
    }

    @Transactional(readOnly = true)
    public List<DeckCardResponse> getActiveDeckCards(Long userId) {
        DeckEntity activeDeck = getOrCreateActiveDeck(userId);
        return deckCardRepository.findByDeckIdOrderByCardIdAsc(activeDeck.getId())
            .stream()
            .map(card -> new DeckCardResponse(card.getCardId(), card.getCount()))
            .toList();
    }

    @Transactional
    public DeckCardResponse updateActiveDeckCard(Long userId, String cardId, int count) {
        DeckEntity activeDeck = getOrCreateActiveDeck(userId);
        return updateDeckCard(userId, activeDeck.getId(), cardId, count);
    }

    @Transactional
    public DeckCardResponse updateDeckCard(Long userId, Long deckId, String cardId, int count) {
        DeckEntity deck = getDeck(userId, deckId);
        String normalizedCardId = normalizeCardId(cardId);
        Card card = cardRepository.findById(normalizedCardId).orElseThrow(
            () -> new IllegalArgumentException("找不到卡片：" + normalizedCardId)
        );
        Set<String> unlimitedMainDeckCardIds = loadUnlimitedMainDeckCardIds();

        String cardType = normalizeCardType(card.getCardType());
        if (!ALLOWED_CARD_TYPES.contains(cardType)) {
            throw new IllegalArgumentException("卡片類型不支援：" + normalizedCardId);
        }
        if (count < 0) {
            throw new IllegalArgumentException("卡片張數不可小於 0");
        }
        if ("OSHI".equals(cardType) && count > OSHI_CARD_LIMIT) {
            throw new IllegalArgumentException("推し卡最多只能 1 張");
        }
        int mainCardDuplicateLimit = resolveMainCardDuplicateLimit(normalizedCardId, cardType, unlimitedMainDeckCardIds);
        if (!"CHEER".equals(cardType) && !"OSHI".equals(cardType) && count > mainCardDuplicateLimit) {
            throw new IllegalArgumentException("主牌卡片單卡上限為 " + mainCardDuplicateLimit + " 張");
        }
        if ("CHEER".equals(cardType) && count > REQUIRED_CHEER_DECK_SIZE) {
            throw new IllegalArgumentException("エール單卡上限為 20 張");
        }

        if (count == 0) {
            deckCardRepository.deleteByDeckIdAndCardId(deck.getId(), normalizedCardId);
            bumpVersion(deck);
            deckRepository.save(deck);
            return new DeckCardResponse(normalizedCardId, 0);
        }

        DeckCardEntity deckCard = deckCardRepository.findByDeckIdAndCardId(deck.getId(), normalizedCardId).orElse(null);
        if (deckCard == null) {
            deckCard = new DeckCardEntity();
            deckCard.setDeckId(deck.getId());
            deckCard.setCardId(normalizedCardId);
            deckCard.setCreatedAt(LocalDateTime.now());
        }
        deckCard.setCount(count);
        deckCard.setUpdatedAt(LocalDateTime.now());
        deckCardRepository.save(deckCard);

        bumpVersion(deck);
        deckRepository.save(deck);
        return new DeckCardResponse(normalizedCardId, count);
    }

    @Transactional
    public List<DeckCardResponse> setupQuickDeck(Long userId) {
        return setupQuickDeck(userId, null);
    }

    @Transactional
    public List<DeckCardResponse> setupQuickDeck(Long userId, String presetCode) {
        DeckEntity activeDeck = getOrCreateActiveDeck(userId);
        deckCardRepository.deleteByDeckId(activeDeck.getId());

        String normalizedPresetCode = normalizePresetCode(presetCode);
        if (PRESET_AUTO.equals(normalizedPresetCode)) {
            if (applyOfficialStarterPresetIfExists(userId, activeDeck.getId(), DEFAULT_STARTER_PRESET)) {
                return loadDeckCards(activeDeck.getId());
            }
            setupFallbackQuickDeck(userId, activeDeck.getId());
            return loadDeckCards(activeDeck.getId());
        }

        StarterDeckPreset preset = STARTER_DECK_PRESETS.get(normalizedPresetCode);
        if (preset != null) {
            applyPresetEntries(userId, activeDeck.getId(), preset.entries());
            return loadDeckCards(activeDeck.getId());
        }

        setupFallbackQuickDeck(userId, activeDeck.getId());
        return loadDeckCards(activeDeck.getId());
    }

    @Transactional(readOnly = true)
    public List<StarterDeckPresetResponse> listStarterDeckPresets() {
        List<StarterDeckPresetResponse> presets = new ArrayList<>();
        presets.add(new StarterDeckPresetResponse(PRESET_AUTO, "自動預設（官方）", "優先套用官方起始牌組，若卡片不足則回退一般測試牌組"));
        STARTER_DECK_PRESETS.values().forEach(preset ->
            presets.add(new StarterDeckPresetResponse(preset.code(), preset.name(), preset.description()))
        );
        return presets;
    }

    @Transactional(readOnly = true)
    public ActiveDeckForMatch loadValidatedActiveDeckForMatch(Long userId) {
        DeckEntity activeDeck = deckRepository.findByUserIdAndActiveTrue(userId)
            .orElseThrow(() -> new IllegalStateException("玩家 #" + userId + " 沒有啟用中的牌組"));
        List<DeckCardEntity> deckCards = deckCardRepository.findByDeckIdOrderByCardIdAsc(activeDeck.getId());
        DeckValidationResponse validation = validateDeckCards(deckCards);
        if (!validation.isValid()) {
            String firstError = validation.getErrors().isEmpty() ? "牌組驗證失敗" : validation.getErrors().get(0).getMessage();
            throw new IllegalStateException("玩家 #" + userId + " 牌組不合法：" + firstError);
        }

        Map<String, String> cardTypeMap = resolveCardTypeMap(deckCards);
        List<DeckCardEntry> entries = deckCards.stream()
            .map(card -> new DeckCardEntry(card.getCardId(), card.getCount(), cardTypeMap.get(card.getCardId())))
            .toList();

        return new ActiveDeckForMatch(activeDeck.getId(), entries, validation);
    }

    private DeckValidationResponse validateDeckCards(List<DeckCardEntity> deckCards) {
        if (deckCards.isEmpty()) {
            return new DeckValidationResponse(
                false,
                0,
                0,
                0,
                0,
                List.of(new DeckValidationErrorResponse("DECK_EMPTY", "牌組目前沒有卡片"))
            );
        }

        Map<String, String> cardTypeMap = resolveCardTypeMap(deckCards);
        Set<String> unlimitedMainDeckCardIds = loadUnlimitedMainDeckCardIds();
        List<DeckValidationErrorResponse> errors = new ArrayList<>();
        int totalCount = 0;
        int oshiCount = 0;
        int mainDeckCount = 0;
        int cheerDeckCount = 0;

        for (DeckCardEntity deckCard : deckCards) {
            String cardId = deckCard.getCardId();
            Integer count = deckCard.getCount();
            if (count == null || count <= 0) {
                errors.add(new DeckValidationErrorResponse("DECK_COUNT_INVALID", "卡片 " + cardId + " 張數需大於 0"));
                continue;
            }

            String type = cardTypeMap.get(cardId);
            if (type == null || !ALLOWED_CARD_TYPES.contains(type)) {
                errors.add(new DeckValidationErrorResponse("DECK_CARD_TYPE_INVALID", "卡片 " + cardId + " 類型無效"));
                continue;
            }

            if ("OSHI".equals(type) && count > OSHI_CARD_LIMIT) {
                errors.add(new DeckValidationErrorResponse("DECK_OSHI_LIMIT_EXCEEDED", "推し卡最多只能 1 張"));
            }
            int mainCardDuplicateLimit = resolveMainCardDuplicateLimit(cardId, type, unlimitedMainDeckCardIds);
            if (!"CHEER".equals(type) && !"OSHI".equals(type) && count > mainCardDuplicateLimit) {
                errors.add(
                    new DeckValidationErrorResponse(
                        "DECK_DUPLICATE_LIMIT_EXCEEDED",
                        "卡片 " + cardId + " 超過上限 " + mainCardDuplicateLimit + " 張"
                    )
                );
            }
            if ("CHEER".equals(type) && count > REQUIRED_CHEER_DECK_SIZE) {
                errors.add(new DeckValidationErrorResponse("DECK_CHEER_DUPLICATE_LIMIT_EXCEEDED", "卡片 " + cardId + " 超過上限 20 張"));
            }

            totalCount += count;
            if ("OSHI".equals(type)) {
                oshiCount += count;
            } else if ("CHEER".equals(type)) {
                cheerDeckCount += count;
            } else {
                mainDeckCount += count;
            }
        }

        if (oshiCount != 1) {
            errors.add(new DeckValidationErrorResponse("DECK_OSHI_COUNT_INVALID", "推し卡必須剛好 1 張（目前 " + oshiCount + " 張）"));
        }
        if (mainDeckCount != REQUIRED_MAIN_DECK_SIZE) {
            errors.add(
                new DeckValidationErrorResponse(
                    "DECK_MAIN_COUNT_INVALID",
                    "主牌庫必須剛好 " + REQUIRED_MAIN_DECK_SIZE + " 張（目前 " + mainDeckCount + " 張）"
                )
            );
        }
        if (cheerDeckCount != REQUIRED_CHEER_DECK_SIZE) {
            errors.add(
                new DeckValidationErrorResponse(
                    "DECK_CHEER_COUNT_INVALID",
                    "エール牌庫必須剛好 " + REQUIRED_CHEER_DECK_SIZE + " 張（目前 " + cheerDeckCount + " 張）"
                )
            );
        }

        return new DeckValidationResponse(
            errors.isEmpty(),
            totalCount,
            oshiCount,
            mainDeckCount,
            cheerDeckCount,
            errors
        );
    }

    private boolean applyOfficialStarterPresetIfExists(Long userId, Long deckId, String presetCode) {
        StarterDeckPreset preset = STARTER_DECK_PRESETS.get(presetCode);
        if (preset == null) {
            return false;
        }
        try {
            applyPresetEntries(userId, deckId, preset.entries());
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void applyPresetEntries(Long userId, Long deckId, Map<String, Integer> entries) {
        ensureCardsExist(entries.keySet());
        for (Map.Entry<String, Integer> entry : entries.entrySet()) {
            updateDeckCard(userId, deckId, entry.getKey(), entry.getValue());
        }
    }

    private void ensureCardsExist(Collection<String> cardIds) {
        List<String> normalizedCardIds = cardIds.stream().map(this::normalizeCardId).toList();
        Set<String> existingCardIds = cardRepository.findByCardIdIn(normalizedCardIds).stream()
            .map(Card::getCardId)
            .collect(Collectors.toSet());
        List<String> missingCardIds = normalizedCardIds.stream()
            .filter(cardId -> !existingCardIds.contains(cardId))
            .toList();
        if (!missingCardIds.isEmpty()) {
            throw new IllegalStateException("缺少卡片資料：" + String.join(", ", missingCardIds));
        }
    }

    private void setupFallbackQuickDeck(Long userId, Long deckId) {
        List<Card> oshiCards = cardRepository.findByCardTypeOrderByCardIdAsc("OSHI");
        if (oshiCards.isEmpty()) {
            throw new IllegalStateException("缺少 OSHI 卡片資料，無法建立測試牌組");
        }
        updateDeckCard(userId, deckId, oshiCards.get(0).getCardId(), 1);

        List<Card> mainCards = new ArrayList<>();
        mainCards.addAll(cardRepository.findByCardTypeOrderByCardIdAsc("MEMBER"));
        mainCards.addAll(cardRepository.findByCardTypeOrderByCardIdAsc("SUPPORT"));
        if (mainCards.size() * MAIN_CARD_DUPLICATE_LIMIT < REQUIRED_MAIN_DECK_SIZE) {
            throw new IllegalStateException("主牌卡片不足，無法湊滿 50 張測試牌組");
        }

        int remainingMain = REQUIRED_MAIN_DECK_SIZE;
        for (Card card : mainCards) {
            if (remainingMain <= 0) {
                break;
            }
            int assignCount = Math.min(MAIN_CARD_DUPLICATE_LIMIT, remainingMain);
            updateDeckCard(userId, deckId, card.getCardId(), assignCount);
            remainingMain -= assignCount;
        }
        if (remainingMain > 0) {
            throw new IllegalStateException("主牌卡片不足，無法湊滿 50 張測試牌組");
        }

        List<Card> cheerCards = cardRepository.findByCardTypeOrderByCardIdAsc("CHEER");
        if (cheerCards.isEmpty()) {
            throw new IllegalStateException("缺少 CHEER 卡片資料，無法建立測試牌組");
        }
        updateDeckCard(userId, deckId, cheerCards.get(0).getCardId(), REQUIRED_CHEER_DECK_SIZE);
    }

    private List<DeckCardResponse> loadDeckCards(Long deckId) {
        return deckCardRepository.findByDeckIdOrderByCardIdAsc(deckId)
            .stream()
            .map(card -> new DeckCardResponse(card.getCardId(), card.getCount()))
            .toList();
    }

    private int resolveMainCardDuplicateLimit(String cardId, String cardType, Set<String> unlimitedMainDeckCardIds) {
        if ("CHEER".equals(cardType) || "OSHI".equals(cardType)) {
            return MAIN_CARD_DUPLICATE_LIMIT;
        }
        return unlimitedMainDeckCardIds.contains(normalizeCardId(cardId))
            ? REQUIRED_MAIN_DECK_SIZE
            : MAIN_CARD_DUPLICATE_LIMIT;
    }

    private Set<String> loadUnlimitedMainDeckCardIds() {
        return cardRepository.findUnlimitedMainDeckCardIds().stream()
            .map(this::normalizeCardId)
            .collect(Collectors.toSet());
    }

    private String normalizePresetCode(String presetCode) {
        if (presetCode == null || presetCode.isBlank()) {
            return PRESET_AUTO;
        }
        String normalizedPreset = presetCode.trim().toUpperCase(Locale.ROOT);
        if (PRESET_AUTO.equals(normalizedPreset)) {
            return PRESET_AUTO;
        }
        if (STARTER_DECK_PRESETS.containsKey(normalizedPreset)) {
            return normalizedPreset;
        }
        throw new IllegalArgumentException("找不到預設牌組：" + presetCode);
    }

    private static Map<String, StarterDeckPreset> createStarterDeckPresets() {
        Map<String, StarterDeckPreset> presets = new LinkedHashMap<>();
        presets.put(
            PRESET_STARTER_ADVENT_SHIORI,
            new StarterDeckPreset(
                PRESET_STARTER_ADVENT_SHIORI,
                "スタートデッキ 青 魔法少女ホロウィッチ！（Shiori）",
                "官方產品預設：hSD12（Advent）",
                createStarterAdventEntries("HSD12-001")
            )
        );
        presets.put(
            PRESET_STARTER_ADVENT_BIJOU,
            new StarterDeckPreset(
                PRESET_STARTER_ADVENT_BIJOU,
                "スタートデッキ 青 魔法少女ホロウィッチ！（Bijou）",
                "官方產品預設：hSD12（Advent）",
                createStarterAdventEntries("HSD12-002")
            )
        );
        presets.put(
            PRESET_STARTER_JUSTICE_ERB,
            new StarterDeckPreset(
                PRESET_STARTER_JUSTICE_ERB,
                "スタートデッキ 黄 咲き誇る友情（Elizabeth）",
                "官方產品預設：hSD13（Justice）",
                createStarterJusticeEntries("HSD13-001")
            )
        );
        presets.put(
            PRESET_STARTER_JUSTICE_GIGI,
            new StarterDeckPreset(
                PRESET_STARTER_JUSTICE_GIGI,
                "スタートデッキ 黄 咲き誇る友情（Gigi）",
                "官方產品預設：hSD13（Justice）",
                createStarterJusticeEntries("HSD13-002")
            )
        );
        return Map.copyOf(presets);
    }

    private static Map<String, Integer> createStarterAdventEntries(String oshiCardId) {
        LinkedHashMap<String, Integer> cards = new LinkedHashMap<>();
        cards.put(oshiCardId, 1);
        cards.put("HSD12-003", 2);
        cards.put("HSD12-004", 2);
        cards.put("HSD12-005", 2);
        cards.put("HSD12-006", 2);
        cards.put("HSD12-007", 3);
        cards.put("HSD12-008", 2);
        cards.put("HSD12-009", 3);
        cards.put("HSD12-010", 3);
        cards.put("HSD12-011", 3);
        cards.put("HSD12-012", 2);
        cards.put("HSD12-013", 2);
        cards.put("HSD12-014", 2);
        cards.put("HSD12-015", 2);
        cards.put("HSD12-016", 2);
        cards.put("HBP04-050", 3);
        cards.put("HBP04-063", 3);
        cards.put("HSD01-016", 4);
        cards.put("HBP01-108", 1);
        cards.put("HBP04-096", 2);
        cards.put("HBP01-104", 2);
        cards.put("HBP02-077", 1);
        cards.put("HBP05-074", 2);
        cards.put("HY03-001", 10);
        cards.put("HY06-001", 10);
        return Map.copyOf(cards);
    }

    private static Map<String, Integer> createStarterJusticeEntries(String oshiCardId) {
        LinkedHashMap<String, Integer> cards = new LinkedHashMap<>();
        cards.put(oshiCardId, 1);
        cards.put("HSD13-003", 6);
        cards.put("HSD13-004", 2);
        cards.put("HSD13-005", 2);
        cards.put("HSD13-006", 2);
        cards.put("HSD13-007", 3);
        cards.put("HSD13-008", 4);
        cards.put("HSD13-009", 2);
        cards.put("HSD13-010", 2);
        cards.put("HSD13-011", 2);
        cards.put("HSD13-012", 2);
        cards.put("HSD13-013", 3);
        cards.put("HSD13-014", 2);
        cards.put("HSD13-015", 2);
        cards.put("HSD13-016", 2);
        cards.put("HSD13-017", 2);
        cards.put("HSD13-018", 2);
        cards.put("HSD01-016", 4);
        cards.put("HSD01-019", 1);
        cards.put("HBP01-104", 2);
        cards.put("HBP05-074", 2);
        cards.put("HBP03-088", 1);
        cards.put("HY03-001", 10);
        cards.put("HY06-001", 10);
        return Map.copyOf(cards);
    }

    private Map<String, String> resolveCardTypeMap(List<DeckCardEntity> deckCards) {
        List<String> cardIds = deckCards.stream().map(DeckCardEntity::getCardId).distinct().toList();
        Map<String, String> map = new LinkedHashMap<>();
        List<Card> cards = cardRepository.findByCardIdIn(cardIds);
        for (Card card : cards) {
            map.put(card.getCardId(), normalizeCardType(card.getCardType()));
        }
        return map;
    }

    private DeckEntity getDeck(Long userId, Long deckId) {
        return deckRepository.findByIdAndUserId(deckId, userId)
            .orElseThrow(() -> new IllegalArgumentException("找不到牌組：" + deckId));
    }

    private DeckEntity getOrCreateActiveDeck(Long userId) {
        DeckEntity activeDeck = deckRepository.findByUserIdAndActiveTrue(userId).orElse(null);
        if (activeDeck != null) {
            return activeDeck;
        }

        List<DeckEntity> decks = deckRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (!decks.isEmpty()) {
            DeckEntity firstDeck = decks.get(0);
            firstDeck.setActive(true);
            firstDeck.setUpdatedAt(LocalDateTime.now());
            return deckRepository.save(firstDeck);
        }

        DeckEntity deck = new DeckEntity();
        deck.setUserId(userId);
        deck.setName(ensureUniqueDeckName(userId, DEFAULT_DECK_NAME, null));
        deck.setFormat("STANDARD");
        deck.setActive(true);
        deck.setVersion(1);
        deck.setCreatedAt(LocalDateTime.now());
        deck.setUpdatedAt(LocalDateTime.now());
        return deckRepository.save(deck);
    }

    private DeckSummaryResponse toSummary(DeckEntity deck) {
        List<DeckCardEntity> cards = deckCardRepository.findByDeckIdOrderByCardIdAsc(deck.getId());
        int totalCards = cards.stream().mapToInt(card -> card.getCount() == null ? 0 : card.getCount()).sum();
        return new DeckSummaryResponse(
            deck.getId(),
            deck.getName(),
            deck.getFormat(),
            deck.isActive(),
            deck.getVersion(),
            totalCards,
            cards.size(),
            deck.getUpdatedAt()
        );
    }

    private DeckDetailResponse toDetail(DeckEntity deck) {
        List<DeckCardResponse> cards = deckCardRepository.findByDeckIdOrderByCardIdAsc(deck.getId())
            .stream()
            .map(card -> new DeckCardResponse(card.getCardId(), card.getCount()))
            .toList();
        int totalCards = cards.stream().mapToInt(card -> card.getCount() == null ? 0 : card.getCount()).sum();
        return new DeckDetailResponse(
            deck.getId(),
            deck.getName(),
            deck.getFormat(),
            deck.isActive(),
            deck.getVersion(),
            totalCards,
            cards.size(),
            deck.getUpdatedAt(),
            cards
        );
    }

    private String ensureUniqueDeckName(Long userId, String requestedName, Long currentDeckId) {
        String baseName = normalizeDeckName(requestedName);
        List<DeckEntity> userDecks = deckRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        Set<String> usedNames = userDecks.stream()
            .filter(deck -> currentDeckId == null || !deck.getId().equals(currentDeckId))
            .map(DeckEntity::getName)
            .collect(java.util.stream.Collectors.toSet());

        String candidate = baseName;
        int suffix = 2;
        while (usedNames.contains(candidate)) {
            candidate = baseName + " " + suffix++;
        }
        return candidate;
    }

    private String normalizeDeckName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("牌組名稱不可為空");
        }
        return raw.trim();
    }

    private String normalizeCardId(String cardId) {
        if (cardId == null || cardId.isBlank()) {
            throw new IllegalArgumentException("cardId 不可為空");
        }
        return cardId.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeCardType(String cardType) {
        if (cardType == null) {
            return null;
        }
        return cardType.trim().toUpperCase(Locale.ROOT);
    }

    private void bumpVersion(DeckEntity deck) {
        deck.setVersion(deck.getVersion() == null ? 1 : deck.getVersion() + 1);
        deck.setUpdatedAt(LocalDateTime.now());
    }

    private record StarterDeckPreset(
        String code,
        String name,
        String description,
        Map<String, Integer> entries
    ) {}

    public record DeckCardEntry(String cardId, Integer count, String cardType) {}

    public record ActiveDeckForMatch(Long deckId, List<DeckCardEntry> cards, DeckValidationResponse validation) {}
}
