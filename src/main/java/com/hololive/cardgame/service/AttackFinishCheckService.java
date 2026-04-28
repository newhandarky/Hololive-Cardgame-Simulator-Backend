package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.service.AttackFinishCheckResult.FinishType;

public class AttackFinishCheckService {

    private final FinishEvaluator cardEffectFinishEvaluator;
    private final FinishEvaluator lifeDefeatEvaluator;
    private final FinishEvaluator noHolomemDefeatEvaluator;
    private final EffectSummaryPredicate lifeReducedPredicate;
    private final EffectSummaryPredicate holomemDownedPredicate;
    private final MatchSaver matchSaver;

    public AttackFinishCheckService(
        FinishEvaluator cardEffectFinishEvaluator,
        FinishEvaluator lifeDefeatEvaluator,
        FinishEvaluator noHolomemDefeatEvaluator,
        EffectSummaryPredicate lifeReducedPredicate,
        EffectSummaryPredicate holomemDownedPredicate,
        MatchSaver matchSaver
    ) {
        this.cardEffectFinishEvaluator = cardEffectFinishEvaluator;
        this.lifeDefeatEvaluator = lifeDefeatEvaluator;
        this.noHolomemDefeatEvaluator = noHolomemDefeatEvaluator;
        this.lifeReducedPredicate = lifeReducedPredicate;
        this.holomemDownedPredicate = holomemDownedPredicate;
        this.matchSaver = matchSaver;
    }

    public AttackFinishCheckResult resolve(AttackFinishCheckContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack finish check 缺少必要上下文");
        }
        if (cardEffectFinishEvaluator.evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks())) {
            return finish(context, FinishType.CARD_EFFECT);
        }
        if (
            lifeReducedPredicate.matches(context.effectSummaryForChecks()) &&
            lifeDefeatEvaluator.evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks())
        ) {
            return finish(context, FinishType.LIFE_DEFEAT);
        }
        if (
            holomemDownedPredicate.matches(context.effectSummaryForChecks()) &&
            noHolomemDefeatEvaluator.evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks())
        ) {
            return finish(context, FinishType.NO_HOLOMEM_DEFEAT);
        }
        return AttackFinishCheckResult.none();
    }

    private AttackFinishCheckResult finish(AttackFinishCheckContext context, FinishType finishType) {
        matchSaver.save(context.match());
        return AttackFinishCheckResult.finished(finishType, true);
    }

    public interface FinishEvaluator {
        boolean evaluate(MatchEntity match, Long actorUserId, int turnNumber, Object effectSummary);
    }

    public interface EffectSummaryPredicate {
        boolean matches(Object effectSummary);
    }

    public interface MatchSaver {
        void save(MatchEntity match);
    }
}
