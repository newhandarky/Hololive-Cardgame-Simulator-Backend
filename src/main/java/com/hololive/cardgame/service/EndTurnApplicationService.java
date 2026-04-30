package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameRuleException;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class EndTurnApplicationService {

    private final EndTurnActionValidator endTurnActionValidator;
    private final EndTurnActionResolver endTurnActionResolver;
    private final EndTurnLegacyResolutionBridge endTurnLegacyResolutionBridge;
    private final EndTurnEventFactory endTurnEventFactory;
    private final EndTurnTriggerDispatcher endTurnTriggerDispatcher;
    private final MatchTurnLifecycleService matchTurnLifecycleService;
    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PendingDecisionReader pendingDecisionReader;

    public EndTurnApplicationService(
        EndTurnActionValidator endTurnActionValidator,
        EndTurnActionResolver endTurnActionResolver,
        EndTurnLegacyResolutionBridge endTurnLegacyResolutionBridge,
        EndTurnEventFactory endTurnEventFactory,
        EndTurnTriggerDispatcher endTurnTriggerDispatcher,
        MatchTurnLifecycleService matchTurnLifecycleService,
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        JdbcTemplate jdbcTemplate
    ) {
        this.endTurnActionValidator = endTurnActionValidator;
        this.endTurnActionResolver = endTurnActionResolver;
        this.endTurnLegacyResolutionBridge = endTurnLegacyResolutionBridge;
        this.endTurnEventFactory = endTurnEventFactory;
        this.endTurnTriggerDispatcher = endTurnTriggerDispatcher;
        this.matchTurnLifecycleService = matchTurnLifecycleService;
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.pendingDecisionReader = new PendingDecisionReader(jdbcTemplate);
    }

    public void handle(EndTurnAction action) {
        EndTurnValidationContext validationContext = loadValidationContext(action);
        EndTurnValidationResult validationResult = endTurnActionValidator.validate(action, validationContext);
        if (!validationResult.allowed()) {
            throw new GameRuleException(
                validationResult.errorCode(),
                validationResult.message(),
                validationResult.details()
            );
        }

        MatchEntity match = validationContext.match();
        EndTurnContext endTurnContext = endTurnLegacyResolutionBridge.prepareContext(action, validationContext);
        EndTurnResolutionResult resolutionResult = endTurnActionResolver.resolve(endTurnContext);
        resolutionResult.endTurnActionPayload().put("traceId", action.traceId());
        resolutionResult.endTurnActionPayload().put("idempotencyKey", action.idempotencyKey());
        List<EndTurnEvent> events = new java.util.ArrayList<>(
            endTurnEventFactory.createCoreEvents(action, endTurnContext, resolutionResult)
        );

        matchTurnLifecycleService.appendEndTurnAction(
            resolutionResult.match(),
            resolutionResult.actingUserId(),
            resolutionResult.currentTurnNumber(),
            resolutionResult.endTurnActionPayload()
        );
        matchRepository.saveAndFlush(resolutionResult.match());

        Long turnStartInteractionId = matchTurnLifecycleService.createTurnStartPendingInteraction(
            resolutionResult.match().getId(),
            resolutionResult.nextTurnPlayerId(),
            resolutionResult.nextTurnNumber()
        );
        if (turnStartInteractionId != null) {
            matchTurnLifecycleService.appendInteractionPendingAction(
                resolutionResult.match(),
                resolutionResult.nextTurnPlayerId(),
                resolutionResult.nextTurnNumber(),
                turnStartInteractionId,
                "TURN_START",
                "TURN_START"
            );
            events.add(
                endTurnEventFactory.createPendingTurnStartInteractionEvent(
                    action,
                    resolutionResult,
                    turnStartInteractionId
                )
            );
        }
        endTurnTriggerDispatcher.dispatch(List.copyOf(events));
    }

    private EndTurnValidationContext loadValidationContext(EndTurnAction action) {
        MatchEntity match = matchRepository.findByIdForUpdate(action.matchId())
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));
        if (!"active".equalsIgnoreCase(String.valueOf(match.getStatus()))) {
            throw new IllegalStateException("對戰已結束");
        }
        if (!LobbyMatchStatus.STARTED.name().equals(match.getLobbyStatus())) {
            throw new IllegalStateException("對戰尚未開始");
        }
        if (!matchPlayerRepository.existsByMatchIdAndUserId(action.matchId(), action.actorUserId())) {
            throw new IllegalArgumentException("你不在此房間中");
        }

        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        Long opponentUserId = resolveOpponent(match, action.actorUserId());
        MatchPhase currentPhase = parsePhase(match.getCurrentPhase());
        EndTurnRequiredActionSummary requiredActionSummary = buildRequiredActionSummary(
            action.matchId(),
            action.actorUserId(),
            turnNumber
        );

        return new EndTurnValidationContext(
            match,
            action.actorUserId(),
            opponentUserId,
            turnNumber,
            match.getCurrentTurnPlayerId(),
            currentPhase,
            String.valueOf(match.getStatus()),
            match.getLobbyStatus(),
            hasDuplicateAction(action),
            hasPendingDecision(action.matchId(), action.actorUserId()),
            hasAnyPendingDecision(action.matchId()),
            requiredActionSummary
        );
    }

    private boolean hasDuplicateAction(EndTurnAction action) {
        if (action == null) {
            return false;
        }
        if (action.idempotencyKey() != null && !action.idempotencyKey().isBlank()) {
            Integer keyedCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_actions
                WHERE match_id = ?
                  AND user_id = ?
                  AND turn_number = ?
                  AND action_type = 'END_TURN'
                  AND payload ->> 'idempotencyKey' = ?
                """,
                Integer.class,
                action.matchId(),
                action.actorUserId(),
                action.requestedTurnNumber(),
                action.idempotencyKey()
            );
            if (keyedCount != null && keyedCount > 0) {
                return true;
            }
        }
        return hasAction(action.matchId(), action.actorUserId(), action.requestedTurnNumber(), "END_TURN");
    }

    private EndTurnRequiredActionSummary buildRequiredActionSummary(Long matchId, Long userId, int turnNumber) {
        boolean drawTurnCompleted = hasAction(matchId, userId, turnNumber, "DRAW_TURN");
        boolean requiresTurnCheer = canPerformTurnCheerAction(matchId, userId);
        boolean turnCheerCompleted = hasAction(matchId, userId, turnNumber, "TURN_CHEER");
        var missingActions = new ArrayList<String>();
        if (!drawTurnCompleted) {
            missingActions.add("抽卡");
        }
        if (requiresTurnCheer && !turnCheerCompleted) {
            missingActions.add("發送吶喊");
        }
        return new EndTurnRequiredActionSummary(
            drawTurnCompleted,
            requiresTurnCheer,
            turnCheerCompleted,
            java.util.List.copyOf(missingActions)
        );
    }

    private boolean hasAction(Long matchId, Long userId, int turnNumber, String actionType) {
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

    private boolean canPerformTurnCheerAction(Long matchId, Long userId) {
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

    private boolean hasPendingDecision(Long matchId, Long userId) {
        return pendingDecisionReader.hasAnyPendingDecision(matchId, userId);
    }

    private boolean hasAnyPendingDecision(Long matchId) {
        return pendingDecisionReader.hasAnyPendingDecision(matchId);
    }

    private Long resolveOpponent(MatchEntity match, Long actorUserId) {
        if (match == null || actorUserId == null) {
            return null;
        }
        if (actorUserId.equals(match.getPlayerAId())) {
            return match.getPlayerBId();
        }
        if (actorUserId.equals(match.getPlayerBId())) {
            return match.getPlayerAId();
        }
        return null;
    }

    private MatchPhase parsePhase(String phaseValue) {
        if (phaseValue == null || phaseValue.isBlank()) {
            return MatchPhase.RESET;
        }
        try {
            return MatchPhase.valueOf(phaseValue.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("未知對戰階段: " + phaseValue, ex);
        }
    }
}
