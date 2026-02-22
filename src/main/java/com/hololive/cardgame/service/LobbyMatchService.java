package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.entity.MatchPlayerEntity;
import com.hololive.cardgame.entity.UserCard;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.model.LobbyPlayer;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import com.hololive.cardgame.repository.UserCardRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
    private static final int INITIAL_HAND_SIZE = 5;
    private static final int MIN_MAIN_DECK_SIZE = 5;
    private static final int MIN_CHEER_DECK_SIZE = 5;
    private static final int DEFAULT_OSHI_LIFE = 5;

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final MatchActionRepository matchActionRepository;
    private final UserCardRepository userCardRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public LobbyMatchService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        MatchActionRepository matchActionRepository,
        UserCardRepository userCardRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.matchActionRepository = matchActionRepository;
        this.userCardRepository = userCardRepository;
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
        match.setCreatedAt(LocalDateTime.now());
        match.setUpdatedAt(LocalDateTime.now());
        match = matchRepository.save(match);

        createMatchPlayer(match.getId(), hostUserId, false);
        return toModel(match, matchPlayerRepository.findByMatchIdOrderByIdAsc(match.getId()));
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

        match.setLobbyStatus(LobbyMatchStatus.STARTED.name());
        match.setCurrentTurnPlayerId(match.getPlayerAId());
        match.setTurnNumber(1);
        match.setStartedAt(LocalDateTime.now());
        touchUpdatedAt(match);
        matchRepository.save(match);

        initializeMatchRuntime(match, players);

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
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        matchPlayerRepository.save(entity);
    }

    /**
     * 對戰開始時初始化每位玩家的牌區快照資料（DECK/HAND/LIFE/CHEER_DECK）。
     */
    private void initializeMatchRuntime(MatchEntity match, List<MatchPlayerEntity> players) {
        // 若重複觸發初始化（例如本地測試重跑），先清空舊的卡片實例資料。
        jdbcTemplate.update(
            "DELETE FROM match_holomem_cheers WHERE match_holomem_id IN (SELECT id FROM match_holomems WHERE match_id = ?)",
            match.getId()
        );
        jdbcTemplate.update("DELETE FROM match_holomems WHERE match_id = ?", match.getId());
        jdbcTemplate.update("DELETE FROM match_holopower WHERE match_id = ?", match.getId());
        jdbcTemplate.update("DELETE FROM match_cards WHERE match_id = ?", match.getId());

        for (MatchPlayerEntity player : players) {
            initializePlayerRuntime(match.getId(), player);
        }
    }

    private void initializePlayerRuntime(Long matchId, MatchPlayerEntity matchPlayer) {
        List<UserCard> userCards = userCardRepository.findByUserIdOrderByCardIdAsc(matchPlayer.getUserId())
            .stream()
            .filter(card -> card.getCount() != null && card.getCount() > 0)
            .toList();

        List<String> oshiCandidates = new ArrayList<>();
        List<String> mainDeck = new ArrayList<>();
        List<String> cheerDeck = new ArrayList<>();

        for (UserCard userCard : userCards) {
            String cardType = resolveCardType(userCard.getCardId());
            if (!StringUtils.hasText(cardType)) {
                continue;
            }
            int count = userCard.getCount();
            if ("OSHI".equals(cardType)) {
                oshiCandidates.add(userCard.getCardId());
            } else if ("CHEER".equals(cardType)) {
                for (int i = 0; i < count; i++) {
                    cheerDeck.add(userCard.getCardId());
                }
            } else {
                for (int i = 0; i < count; i++) {
                    mainDeck.add(userCard.getCardId());
                }
            }
        }

        if (oshiCandidates.isEmpty()) {
            throw new IllegalStateException("玩家 #" + matchPlayer.getUserId() + " 沒有推し卡，無法開始對戰");
        }
        if (mainDeck.size() < MIN_MAIN_DECK_SIZE) {
            throw new IllegalStateException(
                "玩家 #" + matchPlayer.getUserId() + " 主牌庫不足，至少需要 " + MIN_MAIN_DECK_SIZE + " 張"
            );
        }
        if (cheerDeck.size() < MIN_CHEER_DECK_SIZE) {
            throw new IllegalStateException(
                "玩家 #" + matchPlayer.getUserId() + " エール牌庫不足，至少需要 " + MIN_CHEER_DECK_SIZE + " 張"
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

        batchInsertMatchCards(matchId, matchPlayer.getUserId(), deckCards, "DECK", true);
        batchInsertMatchCards(matchId, matchPlayer.getUserId(), handCards, "HAND", false);
        batchInsertMatchCards(matchId, matchPlayer.getUserId(), lifeCards, "LIFE", true);
        batchInsertMatchCards(matchId, matchPlayer.getUserId(), cheerDeckCards, "CHEER_DECK", true);

        matchPlayer.setOshiCardId(oshiCardId);
        matchPlayer.setCurrentLife(oshiLife);
        matchPlayer.setUpdatedAt(LocalDateTime.now());
        matchPlayerRepository.save(matchPlayer);
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

    private String resolveCardType(String cardId) {
        List<String> types = jdbcTemplate.query(
            "SELECT card_type FROM cards WHERE card_id = ?",
            (rs, rowNum) -> rs.getString("card_type"),
            cardId
        );
        return types.isEmpty() ? null : types.get(0);
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
}
