package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.entity.MatchPlayerEntity;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.model.LobbyPlayer;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class LobbyMatchService {

    private static final String ROOM_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ROOM_CODE_LENGTH = 6;

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final MatchActionRepository matchActionRepository;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public LobbyMatchService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        MatchActionRepository matchActionRepository,
        ObjectMapper objectMapper
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.matchActionRepository = matchActionRepository;
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

        appendAction(match, userId, "START_MATCH", "{\"source\":\"host\"}", 1);
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
