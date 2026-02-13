package com.hololive.cardgame.service;

import com.hololive.cardgame.dto.GameStateResponse;
import com.hololive.cardgame.dto.PlayerZoneStateResponse;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.entity.MatchPlayerEntity;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchGameStateService {

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final JdbcTemplate jdbcTemplate;

    public MatchGameStateService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        JdbcTemplate jdbcTemplate
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public GameStateResponse getGameStateForUser(Long matchId, Long userId) {
        if (!matchPlayerRepository.existsByMatchIdAndUserId(matchId, userId)) {
            throw new IllegalStateException("你不在此房間中");
        }
        return getGameState(matchId);
    }

    @Transactional(readOnly = true)
    public GameStateResponse getGameState(Long matchId) {
        MatchEntity match = matchRepository.findById(matchId)
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));

        List<MatchPlayerEntity> matchPlayers = matchPlayerRepository.findByMatchIdOrderByIdAsc(matchId);
        Map<Long, PlayerZoneStateResponse> playerStates = new LinkedHashMap<>();
        for (MatchPlayerEntity player : matchPlayers) {
            PlayerZoneStateResponse state = new PlayerZoneStateResponse(player.getUserId());
            if (player.getOshiCardId() != null && !player.getOshiCardId().isBlank()) {
                state.setOshiCount(1);
            }
            playerStates.put(player.getUserId(), state);
        }

        // 以 match_cards 統計牌庫、檔案、生命、手牌等區域數量
        List<Map<String, Object>> zoneRows = jdbcTemplate.queryForList(
            """
            SELECT owner_user_id, zone, COUNT(*) AS cnt
            FROM match_cards
            WHERE match_id = ?
            GROUP BY owner_user_id, zone
            """,
            matchId
        );
        for (Map<String, Object> row : zoneRows) {
            Long ownerUserId = toLong(row.get("owner_user_id"));
            String zone = normalizeZone(row.get("zone"));
            int count = toInt(row.get("cnt"));
            PlayerZoneStateResponse playerState = playerStates.get(ownerUserId);
            if (playerState == null) {
                continue;
            }
            applyMatchCardZone(playerState, zone, count);
        }

        // 以 match_holomems 統計場上 Holomen 區位數量（CENTER/COLLAB/BACK）
        List<Map<String, Object>> stageRows = jdbcTemplate.queryForList(
            """
            SELECT owner_user_id, zone, COUNT(*) AS cnt
            FROM match_holomems
            WHERE match_id = ?
            GROUP BY owner_user_id, zone
            """,
            matchId
        );
        for (Map<String, Object> row : stageRows) {
            Long ownerUserId = toLong(row.get("owner_user_id"));
            String zone = normalizeZone(row.get("zone"));
            int count = toInt(row.get("cnt"));
            PlayerZoneStateResponse playerState = playerStates.get(ownerUserId);
            if (playerState == null) {
                continue;
            }
            applyStageZone(playerState, zone, count);
        }

        GameStateResponse response = new GameStateResponse();
        response.setMatchId(match.getId());
        response.setRoomCode(match.getRoomCode());
        response.setStatus(match.getLobbyStatus());
        response.setCurrentTurnPlayerId(match.getCurrentTurnPlayerId());
        response.setTurnNumber(match.getTurnNumber());
        response.getPlayers().addAll(playerStates.values());
        return response;
    }

    private void applyMatchCardZone(PlayerZoneStateResponse playerState, String zone, int count) {
        switch (zone) {
            case "DECK" -> playerState.setDeckCount(count);
            case "ARCHIVE" -> playerState.setArchiveCount(count);
            case "HOLOPOWER" -> playerState.setHolopowerCount(count);
            case "CHEER_DECK" -> playerState.setCheerDeckCount(count);
            case "LIFE" -> playerState.setLifeCount(count);
            case "HAND" -> playerState.setHandCount(count);
            default -> {
                // 其他區域（如 STAGE）在最小版本先不額外統計
            }
        }
    }

    private void applyStageZone(PlayerZoneStateResponse playerState, String zone, int count) {
        switch (zone) {
            case "CENTER" -> playerState.setCenterCount(count);
            case "COLLAB" -> playerState.setCollabCount(count);
            case "BACK" -> playerState.setBackCount(count);
            default -> {
                // 非場上區位資料先忽略
            }
        }
    }

    private String normalizeZone(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
