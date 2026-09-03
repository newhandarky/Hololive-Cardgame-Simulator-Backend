package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SEND_TURN_CHEER vertical slice：擁有交易、共用規則驗證與 pending interaction 建立。
 */
@Service
public class SendTurnCheerApplicationService {

    private static final Set<MatchPhase> ALLOWED_PHASES = Set.of(MatchPhase.MAIN, MatchPhase.CHEER);

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final TurnActionRuleService turnActionRuleService;
    private final TurnCheerAvailabilityService turnCheerAvailabilityService;
    private final MatchTurnLifecycleService matchTurnLifecycleService;

    public SendTurnCheerApplicationService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        TurnActionRuleService turnActionRuleService,
        TurnCheerAvailabilityService turnCheerAvailabilityService,
        MatchTurnLifecycleService matchTurnLifecycleService
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.turnActionRuleService = turnActionRuleService;
        this.turnCheerAvailabilityService = turnCheerAvailabilityService;
        this.matchTurnLifecycleService = matchTurnLifecycleService;
    }

    @Transactional
    public void execute(Long matchId, Long userId) {
        SendTurnCheerContext context = loadContext(matchId, userId);
        if (turnActionRuleService.hasTurnCheerAction(matchId, userId, context.turnNumber())) {
            throw new GameRuleException(GameErrorCode.TURN_CHEER_ALREADY_USED, "這回合你已經發送過吶喊了");
        }

        TurnCheerAvailabilityService.TurnCheerAvailability availability = turnCheerAvailabilityService
            .findAvailability(matchId, userId)
            .orElseThrow(() -> new IllegalStateException("目前無法發送吶喊：請確認你有可用吶喊卡且場上有 Holomem"));
        Long interactionId = matchTurnLifecycleService.createTurnSendCheerPendingInteraction(
            matchId,
            userId,
            availability
        );
        if (interactionId == null) {
            throw new IllegalStateException("目前無法建立回合吶喊互動");
        }
        matchTurnLifecycleService.beginTurnCheer(context.match(), userId, context.turnNumber(), interactionId);
    }

    private SendTurnCheerContext loadContext(Long matchId, Long userId) {
        MatchEntity match = matchRepository.findByIdForUpdate(matchId)
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));
        if (!turnActionRuleService.isMatchActive(match)) {
            throw new IllegalStateException("對戰已結束");
        }
        if (!turnActionRuleService.isMatchStarted(match)) {
            throw new IllegalStateException("對戰尚未開始");
        }
        if (!matchPlayerRepository.existsByMatchIdAndUserId(matchId, userId)) {
            throw new IllegalArgumentException("你不在此房間中");
        }
        if (!turnActionRuleService.isCurrentTurnPlayer(match, userId)) {
            throw new GameRuleException(GameErrorCode.NOT_YOUR_TURN, "現在不是你的回合");
        }

        MatchPhase phase = turnActionRuleService.parsePhase(match.getCurrentPhase());
        if (!ALLOWED_PHASES.contains(phase)) {
            throw new GameRuleException(
                GameErrorCode.PHASE_ACTION_NOT_ALLOWED,
                "目前 phase=" + phase + "，無法執行此操作",
                Map.of("phase", phase.name())
            );
        }
        if (
            turnActionRuleService.hasBlockingPendingDecision(matchId, userId)
                || turnActionRuleService.hasAnyPendingDecision(matchId)
        ) {
            throw new GameRuleException(GameErrorCode.PENDING_INTERACTION_BLOCKED, "對戰中有待處理的互動，請先完成確認");
        }
        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        return new SendTurnCheerContext(match, turnNumber);
    }

    private record SendTurnCheerContext(MatchEntity match, int turnNumber) {
    }
}
