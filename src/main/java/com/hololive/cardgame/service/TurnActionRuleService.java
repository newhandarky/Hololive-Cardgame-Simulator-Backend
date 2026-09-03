package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.model.MatchPhase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 回合 action 共用的唯讀規則事實查詢。
 */
@Service
public class TurnActionRuleService {

    private final JdbcTemplate jdbcTemplate;
    private final PendingDecisionReader pendingDecisionReader;

    public TurnActionRuleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.pendingDecisionReader = new PendingDecisionReader(jdbcTemplate);
    }

    public boolean isMatchActive(MatchEntity match) {
        return match != null && "active".equalsIgnoreCase(String.valueOf(match.getStatus()));
    }

    public boolean isMatchStarted(MatchEntity match) {
        return match != null && LobbyMatchStatus.STARTED.name().equals(match.getLobbyStatus());
    }

    public boolean isCurrentTurnPlayer(MatchEntity match, Long userId) {
        return match != null && userId != null && userId.equals(match.getCurrentTurnPlayerId());
    }

    public MatchPhase parsePhase(String phaseValue) {
        if (phaseValue == null || phaseValue.isBlank()) {
            return MatchPhase.RESET;
        }
        try {
            return MatchPhase.valueOf(phaseValue.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("未知對戰階段: " + phaseValue, ex);
        }
    }

    public boolean hasAction(Long matchId, Long userId, int turnNumber, String actionType) {
        if (matchId == null || userId == null || turnNumber <= 0 || actionType == null || actionType.isBlank()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = ?
            """,
            Integer.class,
            matchId,
            userId,
            turnNumber,
            actionType
        );
        return count != null && count > 0;
    }

    public boolean hasDrawTurnAction(Long matchId, Long userId, int turnNumber) {
        return hasAction(matchId, userId, turnNumber, "DRAW_TURN");
    }

    public boolean hasTurnCheerAction(Long matchId, Long userId, int turnNumber) {
        return hasAction(matchId, userId, turnNumber, "TURN_CHEER");
    }

    public boolean canPerformTurnCheerAction(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return false;
        }
        Integer cheerDeckCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CHEER_DECK'
            """,
            Integer.class,
            matchId,
            userId
        );
        if (cheerDeckCount == null || cheerDeckCount <= 0) {
            return false;
        }
        Integer stageHolomemCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB', 'BACK')
            """,
            Integer.class,
            matchId,
            userId
        );
        return stageHolomemCount != null && stageHolomemCount > 0;
    }

    public boolean isFirstPlayerFirstTurn(MatchEntity match, Long userId, int turnNumber) {
        return match != null && userId != null && turnNumber == 1 && userId.equals(match.getPlayerAId());
    }

    public boolean hasBlockingPendingDecision(Long matchId, Long userId) {
        return pendingDecisionReader.hasBlockingPendingDecision(matchId, userId);
    }

    public boolean hasAnyPendingDecision(Long matchId) {
        return pendingDecisionReader.hasAnyPendingDecision(matchId);
    }

    public boolean hasAnyPendingDecision(Long matchId, Long userId) {
        return pendingDecisionReader.hasAnyPendingDecision(matchId, userId);
    }
}
