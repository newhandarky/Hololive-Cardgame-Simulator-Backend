package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameRuleException;
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
    private final TurnActionRuleService turnActionRuleService;

    public EndTurnApplicationService(
        EndTurnActionValidator endTurnActionValidator,
        EndTurnActionResolver endTurnActionResolver,
        EndTurnLegacyResolutionBridge endTurnLegacyResolutionBridge,
        EndTurnEventFactory endTurnEventFactory,
        EndTurnTriggerDispatcher endTurnTriggerDispatcher,
        MatchTurnLifecycleService matchTurnLifecycleService,
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        JdbcTemplate jdbcTemplate,
        TurnActionRuleService turnActionRuleService
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
        this.turnActionRuleService = turnActionRuleService;
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
        if (!turnActionRuleService.isMatchActive(match)) {
            throw new IllegalStateException("對戰已結束");
        }
        if (!turnActionRuleService.isMatchStarted(match)) {
            throw new IllegalStateException("對戰尚未開始");
        }
        if (!matchPlayerRepository.existsByMatchIdAndUserId(action.matchId(), action.actorUserId())) {
            throw new IllegalArgumentException("你不在此房間中");
        }

        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        Long opponentUserId = resolveOpponent(match, action.actorUserId());
        MatchPhase currentPhase = turnActionRuleService.parsePhase(match.getCurrentPhase());
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
        return turnActionRuleService.hasAction(
            action.matchId(), action.actorUserId(), action.requestedTurnNumber(), "END_TURN"
        );
    }

    private EndTurnRequiredActionSummary buildRequiredActionSummary(Long matchId, Long userId, int turnNumber) {
        boolean drawTurnCompleted = turnActionRuleService.hasDrawTurnAction(matchId, userId, turnNumber);
        boolean requiresTurnCheer = turnActionRuleService.canPerformTurnCheerAction(matchId, userId);
        boolean turnCheerCompleted = turnActionRuleService.hasTurnCheerAction(matchId, userId, turnNumber);
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

    private boolean hasPendingDecision(Long matchId, Long userId) {
        return turnActionRuleService.hasAnyPendingDecision(matchId, userId);
    }

    private boolean hasAnyPendingDecision(Long matchId) {
        return turnActionRuleService.hasAnyPendingDecision(matchId);
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

}
