package com.hololive.cardgame.service;

import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.model.LobbyPlayer;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class LobbyMatchService {

    private static final String ROOM_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ROOM_CODE_LENGTH = 6;

    private final AtomicLong idGenerator = new AtomicLong(1);
    private final Map<Long, LobbyMatch> matchesById = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIdByRoomCode = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public LobbyMatch createMatch(Long hostUserId) {
        LobbyMatch match = new LobbyMatch();
        match.setId(idGenerator.getAndIncrement());
        match.setRoomCode(generateRoomCode());
        match.getPlayers().add(new LobbyPlayer(hostUserId, false));

        matchesById.put(match.getId(), match);
        matchIdByRoomCode.put(match.getRoomCode(), match.getId());
        return match;
    }

    public LobbyMatch joinMatch(String roomCode, Long userId) {
        LobbyMatch match = getByRoomCode(roomCode);
        synchronized (match) {
            boolean alreadyInRoom = match.getPlayers().stream()
                .anyMatch(player -> player.getUserId().equals(userId));
            if (alreadyInRoom) {
                return match;
            }

            if (match.getPlayers().size() >= 2) {
                throw new IllegalStateException("房間已滿");
            }

            match.getPlayers().add(new LobbyPlayer(userId, false));
            refreshStatus(match);
            return match;
        }
    }

    public LobbyMatch getMatch(Long matchId) {
        LobbyMatch match = matchesById.get(matchId);
        if (match == null) {
            throw new IllegalArgumentException("找不到對戰");
        }
        return match;
    }

    public LobbyMatch setReady(Long matchId, Long userId, boolean ready) {
        LobbyMatch match = getMatch(matchId);
        synchronized (match) {
            LobbyPlayer player = match.getPlayers().stream()
                .filter(item -> item.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("你不在此房間中"));

            player.setReady(ready);
            refreshStatus(match);
            return match;
        }
    }

    public LobbyMatch startMatch(Long matchId, Long userId) {
        LobbyMatch match = getMatch(matchId);
        synchronized (match) {
            if (!match.getPlayers().get(0).getUserId().equals(userId)) {
                throw new IllegalStateException("只有房主可以開始對戰");
            }

            if (match.getPlayers().size() != 2) {
                throw new IllegalStateException("人數不足，無法開始");
            }

            boolean allReady = match.getPlayers().stream().allMatch(LobbyPlayer::isReady);
            if (!allReady) {
                throw new IllegalStateException("尚有玩家未準備完成");
            }

            match.setStatus(LobbyMatchStatus.STARTED);
            return match;
        }
    }

    private void refreshStatus(LobbyMatch match) {
        if (match.getStatus() == LobbyMatchStatus.STARTED) {
            return;
        }

        boolean allReady = match.getPlayers().size() == 2
            && match.getPlayers().stream().allMatch(LobbyPlayer::isReady);

        match.setStatus(allReady ? LobbyMatchStatus.READY : LobbyMatchStatus.WAITING);
    }

    private LobbyMatch getByRoomCode(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            throw new IllegalArgumentException("roomCode 不可為空");
        }

        String normalized = roomCode.trim().toUpperCase(Locale.ROOT);
        Long matchId = matchIdByRoomCode.get(normalized);
        if (matchId == null) {
            throw new IllegalArgumentException("找不到房間");
        }
        return getMatch(matchId);
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
        } while (matchIdByRoomCode.containsKey(roomCode));

        return roomCode;
    }
}

