package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.game.action.MoveZoneAction;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DRAW_TURN vertical slice：擁有交易、規則驗證、抽牌與 lifecycle 結算。
 */
@Service
public class DrawTurnApplicationService {

    private static final String ACTION_TYPE_DRAW_TURN = "DRAW_TURN";
    private static final Set<MatchPhase> ALLOWED_PHASES = Set.of(MatchPhase.MAIN, MatchPhase.DRAW);

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final TurnActionRuleService turnActionRuleService;
    private final JdbcTemplate jdbcTemplate;
    private final GameActionExecutor gameActionExecutor;
    private final MatchTurnLifecycleService matchTurnLifecycleService;

    public DrawTurnApplicationService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        TurnActionRuleService turnActionRuleService,
        JdbcTemplate jdbcTemplate,
        GameActionExecutor gameActionExecutor,
        MatchTurnLifecycleService matchTurnLifecycleService
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.turnActionRuleService = turnActionRuleService;
        this.jdbcTemplate = jdbcTemplate;
        this.gameActionExecutor = gameActionExecutor;
        this.matchTurnLifecycleService = matchTurnLifecycleService;
    }

    @Transactional
    public void execute(Long matchId, Long userId) {
        DrawTurnContext context = loadContext(matchId, userId);
        if (turnActionRuleService.hasDrawTurnAction(matchId, userId, context.turnNumber())) {
            throw new GameRuleException(GameErrorCode.TURN_DRAW_ALREADY_USED, "這回合你已經抽過卡了");
        }

        Long drawnCardInstanceId = drawTopDeckCardToHand(matchId, userId);
        if (drawnCardInstanceId == null) {
            matchTurnLifecycleService.finishDrawDeckOut(context.match(), userId, context.turnNumber());
            return;
        }

        Long drawInteractionId = matchTurnLifecycleService.createDrawRevealPendingInteraction(
            matchId,
            userId,
            drawnCardInstanceId
        );
        matchTurnLifecycleService.beginDrawTurn(
            context.match(),
            userId,
            context.turnNumber(),
            drawnCardInstanceId,
            drawInteractionId
        );
    }

    private DrawTurnContext loadContext(Long matchId, Long userId) {
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
        return new DrawTurnContext(match, turnNumber);
    }

    private Long drawTopDeckCardToHand(Long matchId, Long userId) {
        Long deckCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (deckCardInstanceId == null) {
            return null;
        }
        Integer nextHandOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            Integer.class,
            matchId,
            userId
        );
        MoveZoneAction moveZoneAction = new MoveZoneAction(
            deckCardInstanceId,
            userId,
            "DECK",
            "HAND",
            nextHandOrder == null ? 1 : nextHandOrder,
            false
        );
        List<ActionResult> results = gameActionExecutor.execute(
            EffectContext.system(matchId, userId, ACTION_TYPE_DRAW_TURN),
            List.of(moveZoneAction)
        );
        if (results.isEmpty() || !results.get(0).success()) {
            return null;
        }
        return deckCardInstanceId;
    }

    private record DrawTurnContext(MatchEntity match, int turnNumber) {
    }
}
