package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchDeckSnapshotEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.entity.MatchPlayerEntity;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.model.LobbyPlayer;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchDeckSnapshotRepository;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import com.hololive.cardgame.repository.UserRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class LobbyMatchService {

    private static final String ROOM_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ROOM_CODE_LENGTH = 6;
    private static final int INITIAL_HAND_SIZE = 7;
    private static final int REQUIRED_MAIN_DECK_SIZE = 50;
    private static final int REQUIRED_CHEER_DECK_SIZE = 20;
    private static final int DEFAULT_OSHI_LIFE = 5;
    private static final String HARD_NPC_LINE_USER_ID = "npc-hard-v1";
    private static final String HARD_NPC_DISPLAY_NAME = "Hard NPC";

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final MatchActionRepository matchActionRepository;
    private final MatchDeckSnapshotRepository matchDeckSnapshotRepository;
    private final DeckService deckService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public LobbyMatchService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        MatchActionRepository matchActionRepository,
        MatchDeckSnapshotRepository matchDeckSnapshotRepository,
        DeckService deckService,
        UserRepository userRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.matchActionRepository = matchActionRepository;
        this.matchDeckSnapshotRepository = matchDeckSnapshotRepository;
        this.deckService = deckService;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LobbyMatch createMatch(Long hostUserId) {
        MatchEntity match = new MatchEntity();
        match.setRoomCode(generateRoomCode());
        match.setPlayerAId(hostUserId);
        match.setLobbyStatus(LobbyMatchStatus.WAITING.name());
        match.setStatus("active");
        match.setTurnNumber(1);
        match.setCurrentPhase(MatchPhase.RESET.name());
        match.setCreatedAt(LocalDateTime.now());
        match.setUpdatedAt(LocalDateTime.now());
        match = matchRepository.save(match);

        createMatchPlayer(match.getId(), hostUserId, false);
        return toModel(match, matchPlayerRepository.findByMatchIdOrderByIdAsc(match.getId()));
    }

    @Transactional
    public LobbyMatch createAndStartHardNpcMatch(Long hostUserId) {
        Long hardNpcUserId = ensureHardNpcUser();
        if (hostUserId.equals(hardNpcUserId)) {
            throw new IllegalStateException("Hard NPC 使用者不可建立對戰");
        }
        deckService.bootstrapStarterDecksForUser(hardNpcUserId);

        LobbyMatch created = createMatch(hostUserId);
        LobbyMatch joined = joinMatch(created.getRoomCode(), hardNpcUserId);
        setReady(joined.getId(), hostUserId, true);
        setReady(joined.getId(), hardNpcUserId, true);
        return startMatch(joined.getId(), hostUserId);
    }

    @Transactional
    public LobbyMatch joinMatch(String roomCode, Long userId) {
        MatchEntity match = getByRoomCodeForUpdate(roomCode);
        List<MatchPlayerEntity> players = matchPlayerRepository.findByMatchIdOrderByIdAsc(match.getId());

        boolean alreadyInRoom = players.stream().anyMatch(player -> player.getUserId().equals(userId));
        if (alreadyInRoom) {
            return toModel(match, players);
        }

        if (players.size() >= 2) {
            throw new IllegalStateException("房間已滿");
        }

        if (match.getPlayerAId().equals(userId)) {
            throw new IllegalStateException("你已是房主，無法再次加入");
        }

        match.setPlayerBId(userId);
        touchUpdatedAt(match);
        matchRepository.save(match);

        createMatchPlayer(match.getId(), userId, false);
        players = matchPlayerRepository.findByMatchIdOrderByIdAsc(match.getId());
        refreshStatus(match, players);
        return toModel(match, players);
    }

    @Transactional(readOnly = true)
    public LobbyMatch getMatch(Long matchId) {
        MatchEntity match = getMatchEntity(matchId);
        List<MatchPlayerEntity> players = matchPlayerRepository.findByMatchIdOrderByIdAsc(matchId);
        return toModel(match, players);
    }

    @Transactional
    public LobbyMatch setReady(Long matchId, Long userId, boolean ready) {
        MatchEntity match = getMatchEntityForUpdate(matchId);
        MatchPlayerEntity player = matchPlayerRepository.findByMatchIdAndUserId(matchId, userId)
            .orElseThrow(() -> new IllegalArgumentException("你不在此房間中"));

        player.setReady(ready);
        player.setUpdatedAt(LocalDateTime.now());
        matchPlayerRepository.save(player);

        List<MatchPlayerEntity> players = matchPlayerRepository.findByMatchIdOrderByIdAsc(matchId);
        refreshStatus(match, players);
        return toModel(match, players);
    }

    @Transactional
    public LobbyMatch startMatch(Long matchId, Long userId) {
        MatchEntity match = getMatchEntityForUpdate(matchId);
        List<MatchPlayerEntity> players = matchPlayerRepository.findByMatchIdOrderByIdAsc(matchId);

        if (!match.getPlayerAId().equals(userId)) {
            throw new IllegalStateException("只有房主可以開始對戰");
        }
        if (players.size() != 2 || match.getPlayerBId() == null) {
            throw new IllegalStateException("人數不足，無法開始");
        }

        boolean allReady = players.stream().allMatch(MatchPlayerEntity::isReady);
        if (!allReady) {
            throw new IllegalStateException("尚有玩家未準備完成");
        }

        Map<Long, DeckService.ActiveDeckForMatch> activeDeckByUserId = new LinkedHashMap<>();
        for (MatchPlayerEntity player : players) {
            activeDeckByUserId.put(
                player.getUserId(),
                deckService.loadValidatedActiveDeckForMatch(player.getUserId())
            );
        }

        match.setLobbyStatus(LobbyMatchStatus.STARTED.name());
        match.setCurrentTurnPlayerId(match.getPlayerAId());
        match.setTurnNumber(1);
        match.setCurrentPhase(MatchPhase.RESET.name());
        match.setStartedAt(LocalDateTime.now());
        touchUpdatedAt(match);
        matchRepository.save(match);

        initializeMatchRuntime(match, players, activeDeckByUserId);
        saveDeckSnapshots(match.getId(), activeDeckByUserId);

        appendAction(match, userId, "START_MATCH", "{\"source\":\"host\",\"initialized\":true}", 1);
        return toModel(match, players);
    }

    @Transactional
    public LobbyMatch endTurn(Long matchId, Long userId) {
        MatchEntity match = getMatchEntityForUpdate(matchId);
        List<MatchPlayerEntity> players = matchPlayerRepository.findByMatchIdOrderByIdAsc(matchId);

        if (!LobbyMatchStatus.STARTED.name().equals(match.getLobbyStatus())) {
            throw new IllegalStateException("對戰尚未開始");
        }
        if (!matchPlayerRepository.existsByMatchIdAndUserId(matchId, userId)) {
            throw new IllegalArgumentException("你不在此房間中");
        }
        if (match.getCurrentTurnPlayerId() == null || !match.getCurrentTurnPlayerId().equals(userId)) {
            throw new IllegalStateException("現在不是你的回合");
        }

        Long opponentId = resolveOpponent(match, userId);
        int turnNumber = Optional.ofNullable(match.getTurnNumber()).orElse(1);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(
                java.util.Map.of("fromUserId", userId, "toUserId", opponentId)
            );
        } catch (Exception e) {
            payload = "{\"fromUserId\":" + userId + ",\"toUserId\":" + opponentId + "}";
        }
        appendAction(match, userId, "END_TURN", payload, turnNumber);

        match.setCurrentTurnPlayerId(opponentId);
        match.setTurnNumber(turnNumber + 1);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(match);
        matchRepository.save(match);

        return toModel(match, players);
    }

    @Transactional(readOnly = true)
    public boolean isUserInMatch(Long matchId, Long userId) {
        return matchPlayerRepository.existsByMatchIdAndUserId(matchId, userId);
    }

    private void refreshStatus(MatchEntity match, List<MatchPlayerEntity> players) {
        if (LobbyMatchStatus.STARTED.name().equals(match.getLobbyStatus())) {
            return;
        }
        boolean allReady = players.size() == 2 && players.stream().allMatch(MatchPlayerEntity::isReady);
        match.setLobbyStatus(allReady ? LobbyMatchStatus.READY.name() : LobbyMatchStatus.WAITING.name());
        touchUpdatedAt(match);
        matchRepository.save(match);
    }

    private MatchEntity getByRoomCodeForUpdate(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            throw new IllegalArgumentException("roomCode 不可為空");
        }
        String normalized = roomCode.trim().toUpperCase(Locale.ROOT);
        return matchRepository.findByRoomCode(normalized)
            .map(match -> getMatchEntityForUpdate(match.getId()))
            .orElseThrow(() -> new IllegalArgumentException("找不到房間"));
    }

    private MatchEntity getMatchEntity(Long matchId) {
        return matchRepository.findById(matchId)
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));
    }

    private MatchEntity getMatchEntityForUpdate(Long matchId) {
        return matchRepository.findByIdForUpdate(matchId)
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));
    }

    private String generateRoomCode() {
        String roomCode;
        do {
            StringBuilder builder = new StringBuilder(ROOM_CODE_LENGTH);
            for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
                int index = random.nextInt(ROOM_CODE_CHARS.length());
                builder.append(ROOM_CODE_CHARS.charAt(index));
            }
            roomCode = builder.toString();
        } while (matchRepository.existsByRoomCode(roomCode));

        return roomCode;
    }

    private void createMatchPlayer(Long matchId, Long userId, boolean ready) {
        MatchPlayerEntity entity = new MatchPlayerEntity();
        entity.setMatchId(matchId);
        entity.setUserId(userId);
        entity.setReady(ready);
        entity.setSpSkillUsed(false);
        entity.setSkillUsedThisTurn(false);
        entity.setMulliganUsed(false);
        entity.setMulliganDone(false);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        matchPlayerRepository.save(entity);
    }

    /**
     * 對戰開始時初始化每位玩家的牌區快照資料（DECK/HAND/LIFE/CHEER_DECK）。
     */
    private void initializeMatchRuntime(
        MatchEntity match,
        List<MatchPlayerEntity> players,
        Map<Long, DeckService.ActiveDeckForMatch> activeDeckByUserId
    ) {
        // 若重複觸發初始化（例如本地測試重跑），先清空舊的卡片實例資料。
        jdbcTemplate.update(
            "DELETE FROM match_holomem_cheers WHERE match_holomem_id IN (SELECT id FROM match_holomems WHERE match_id = ?)",
            match.getId()
        );
        jdbcTemplate.update("DELETE FROM match_holomems WHERE match_id = ?", match.getId());
        jdbcTemplate.update("DELETE FROM match_holopower WHERE match_id = ?", match.getId());
        jdbcTemplate.update("DELETE FROM match_cards WHERE match_id = ?", match.getId());

        for (MatchPlayerEntity player : players) {
            DeckService.ActiveDeckForMatch activeDeck = activeDeckByUserId.get(player.getUserId());
            if (activeDeck == null) {
                throw new IllegalStateException("玩家 #" + player.getUserId() + " 缺少牌組快照資料");
            }
            initializePlayerRuntime(match.getId(), player, activeDeck);
        }
    }

    private void initializePlayerRuntime(
        Long matchId,
        MatchPlayerEntity matchPlayer,
        DeckService.ActiveDeckForMatch activeDeck
    ) {
        List<String> oshiCandidates = new ArrayList<>();
        List<String> mainDeck = new ArrayList<>();
        List<String> cheerDeck = new ArrayList<>();

        for (DeckService.DeckCardEntry deckCard : activeDeck.cards()) {
            String cardType = deckCard.cardType();
            if (!StringUtils.hasText(cardType)) {
                continue;
            }
            int count = deckCard.count() == null ? 0 : deckCard.count();
            if ("OSHI".equals(cardType)) {
                oshiCandidates.add(deckCard.cardId());
            } else if ("CHEER".equals(cardType)) {
                for (int i = 0; i < count; i++) {
                    cheerDeck.add(deckCard.cardId());
                }
            } else {
                for (int i = 0; i < count; i++) {
                    mainDeck.add(deckCard.cardId());
                }
            }
        }

        if (oshiCandidates.isEmpty()) {
            throw new IllegalStateException("玩家 #" + matchPlayer.getUserId() + " 沒有推し卡，無法開始對戰");
        }
        if (mainDeck.size() != REQUIRED_MAIN_DECK_SIZE) {
            throw new IllegalStateException(
                "玩家 #" + matchPlayer.getUserId() + " 主牌庫必須剛好 " + REQUIRED_MAIN_DECK_SIZE + " 張"
            );
        }
        if (cheerDeck.size() != REQUIRED_CHEER_DECK_SIZE) {
            throw new IllegalStateException(
                "玩家 #" + matchPlayer.getUserId() + " エール牌庫必須剛好 " + REQUIRED_CHEER_DECK_SIZE + " 張"
            );
        }

        Collections.shuffle(mainDeck, random);
        Collections.shuffle(cheerDeck, random);

        String oshiCardId = oshiCandidates.get(0);
        int oshiLife = resolveOshiLife(oshiCardId);
        if (cheerDeck.size() < oshiLife) {
            throw new IllegalStateException(
                "玩家 #" + matchPlayer.getUserId() + " エール牌庫不足以設置 LIFE（需要 " + oshiLife + " 張）"
            );
        }

        int handSize = Math.min(INITIAL_HAND_SIZE, mainDeck.size());
        List<String> handCards = new ArrayList<>(mainDeck.subList(0, handSize));
        List<String> deckCards = new ArrayList<>(mainDeck.subList(handSize, mainDeck.size()));
        List<String> lifeCards = new ArrayList<>(cheerDeck.subList(0, oshiLife));
        List<String> cheerDeckCards = new ArrayList<>(cheerDeck.subList(oshiLife, cheerDeck.size()));

        // OSHI 在對戰初始化時也落地成 match_cards 實例，供前端與 action pipeline 取得真實 instanceId。
        batchInsertMatchCards(matchId, matchPlayer.getUserId(), List.of(oshiCardId), "OSHI", false);
        batchInsertMatchCards(matchId, matchPlayer.getUserId(), deckCards, "DECK", true);
        batchInsertMatchCards(matchId, matchPlayer.getUserId(), handCards, "HAND", false);
        batchInsertMatchCards(matchId, matchPlayer.getUserId(), lifeCards, "LIFE", true);
        batchInsertMatchCards(matchId, matchPlayer.getUserId(), cheerDeckCards, "CHEER_DECK", true);

        matchPlayer.setOshiCardId(oshiCardId);
        matchPlayer.setCurrentLife(oshiLife);
        matchPlayer.setMulliganUsed(false);
        matchPlayer.setMulliganDone(false);
        matchPlayer.setUpdatedAt(LocalDateTime.now());
        matchPlayerRepository.save(matchPlayer);
    }

    private void saveDeckSnapshots(
        Long matchId,
        Map<Long, DeckService.ActiveDeckForMatch> activeDeckByUserId
    ) {
        matchDeckSnapshotRepository.deleteByMatchId(matchId);
        for (Map.Entry<Long, DeckService.ActiveDeckForMatch> entry : activeDeckByUserId.entrySet()) {
            Long userId = entry.getKey();
            DeckService.ActiveDeckForMatch activeDeck = entry.getValue();

            String snapshotJson;
            try {
                snapshotJson = objectMapper.writeValueAsString(
                    Map.of(
                        "deckId", activeDeck.deckId(),
                        "validation", activeDeck.validation(),
                        "cards", activeDeck.cards()
                    )
                );
            } catch (Exception e) {
                snapshotJson = "{\"deckId\":" + activeDeck.deckId() + "}";
            }

            MatchDeckSnapshotEntity snapshot = new MatchDeckSnapshotEntity();
            snapshot.setMatchId(matchId);
            snapshot.setUserId(userId);
            snapshot.setDeckId(activeDeck.deckId());
            snapshot.setSnapshotJson(snapshotJson);
            snapshot.setCreatedAt(LocalDateTime.now());
            matchDeckSnapshotRepository.save(snapshot);
        }
    }

    private void batchInsertMatchCards(
        Long matchId,
        Long ownerUserId,
        List<String> cardIds,
        String zone,
        boolean faceDown
    ) {
        int order = 1;
        for (String cardId : cardIds) {
            jdbcTemplate.update(
                """
                INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                matchId,
                ownerUserId,
                cardId,
                zone,
                order++,
                faceDown
            );
        }
    }

    private int resolveOshiLife(String oshiCardId) {
        Integer life = jdbcTemplate.query(
            "SELECT life FROM oshi_cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getInt("life") : null,
            oshiCardId
        );
        if (life == null || life <= 0) {
            return DEFAULT_OSHI_LIFE;
        }
        return life;
    }

    private void appendAction(
        MatchEntity match,
        Long userId,
        String actionType,
        String payload,
        int turnNumber
    ) {
        MatchActionEntity action = new MatchActionEntity();
        action.setMatchId(match.getId());
        action.setUserId(userId);
        action.setActionType(actionType);
        action.setPayload(payload);
        action.setTurnNumber(turnNumber);
        action.setActionOrder(matchActionRepository.findMaxActionOrderByTurn(match.getId(), turnNumber) + 1);
        action.setExecutedAt(LocalDateTime.now());
        matchActionRepository.save(action);
    }

    private LobbyMatch toModel(MatchEntity entity, List<MatchPlayerEntity> players) {
        LobbyMatch match = new LobbyMatch();
        match.setId(entity.getId());
        match.setRoomCode(entity.getRoomCode());
        match.setStatus(parseLobbyStatus(entity.getLobbyStatus()));
        match.setCurrentTurnPlayerId(entity.getCurrentTurnPlayerId());
        match.setTurnNumber(entity.getTurnNumber() == null ? 1 : entity.getTurnNumber());

        List<LobbyPlayer> lobbyPlayers = players.stream()
            .map(player -> new LobbyPlayer(player.getUserId(), player.isReady()))
            .toList();
        match.getPlayers().addAll(lobbyPlayers);
        return match;
    }

    private LobbyMatchStatus parseLobbyStatus(String text) {
        if (!StringUtils.hasText(text)) {
            return LobbyMatchStatus.WAITING;
        }
        try {
            return LobbyMatchStatus.valueOf(text);
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown lobby_status={}, fallback WAITING", text);
            return LobbyMatchStatus.WAITING;
        }
    }

    private Long resolveOpponent(MatchEntity match, Long userId) {
        if (match.getPlayerAId() != null && !match.getPlayerAId().equals(userId)) {
            return match.getPlayerAId();
        }
        if (match.getPlayerBId() != null && !match.getPlayerBId().equals(userId)) {
            return match.getPlayerBId();
        }
        throw new IllegalStateException("找不到對手玩家");
    }

    private void touchUpdatedAt(MatchEntity match) {
        match.setUpdatedAt(LocalDateTime.now());
    }

    private Long ensureHardNpcUser() {
        return userRepository.findByLineUserId(HARD_NPC_LINE_USER_ID)
            .map(user -> user.getId())
            .orElseGet(() -> {
                com.hololive.cardgame.entity.User npc = new com.hololive.cardgame.entity.User();
                npc.setLineUserId(HARD_NPC_LINE_USER_ID);
                npc.setDisplayName(HARD_NPC_DISPLAY_NAME);
                npc.setAvatarUrl(null);
                npc.setCreatedAt(LocalDateTime.now());
                npc.setUpdatedAt(LocalDateTime.now());
                return userRepository.save(npc).getId();
            });
    }
}
