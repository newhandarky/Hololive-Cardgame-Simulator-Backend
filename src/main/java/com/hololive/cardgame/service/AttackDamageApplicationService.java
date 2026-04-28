package com.hololive.cardgame.service;

import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.game.action.ReduceLifeAction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AttackDamageApplicationService {

    private static final String ACTION_TYPE_RULE_EVENT = "RULE_EVENT";

    private final MatchEffectDamageService matchEffectDamageService;
    private final GameActionExecutor gameActionExecutor;

    public AttackDamageApplicationService(
        MatchEffectDamageService matchEffectDamageService,
        GameActionExecutor gameActionExecutor
    ) {
        this.matchEffectDamageService = matchEffectDamageService;
        this.gameActionExecutor = gameActionExecutor;
    }

    public AttackDamageApplicationResult applyDamage(AttackDamageApplicationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack damage application 缺少必要上下文");
        }
        if (context.hasOpponentHolomem()) {
            if (context.finalDamage() > 0) {
                Map<String, Object> artSummary = matchEffectDamageService.applyArtDamage(
                    context.matchId(),
                    context.attackerUserId(),
                    context.finalDamage(),
                    context.effectiveTargetCardInstanceId(),
                    context.deferDownEvent()
                );
                return new AttackDamageApplicationResult(
                    artSummary,
                    asLong(artSummary == null ? null : artSummary.get("lostLifeCardInstanceId"))
                );
            }
            return new AttackDamageApplicationResult(preventedSummary(), null);
        }

        Long lostLifeCardInstanceId = loseLifeOnce(context.matchId(), context.opponentUserId());
        if (lostLifeCardInstanceId == null) {
            throw new IllegalStateException("對手沒有可失去的 LIFE");
        }
        return new AttackDamageApplicationResult(
            fallbackLifeLossSummary(context.finalDamage(), lostLifeCardInstanceId),
            lostLifeCardInstanceId
        );
    }

    private Map<String, Object> preventedSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "ART_DAMAGE_PREVENTED");
        summary.put("damageRequested", 0);
        summary.put("damageApplied", 0);
        summary.put("reason", "傷害已由受傷 Gift 抵銷");
        summary.put("lifeReduced", false);
        return summary;
    }

    private Map<String, Object> fallbackLifeLossSummary(int finalDamage, Long lostLifeCardInstanceId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "ART_DAMAGE_FALLBACK");
        summary.put("damageRequested", finalDamage);
        summary.put("damageApplied", 0);
        summary.put("reason", "對手場上無 Holomen，改為扣除 1 點 LIFE");
        summary.put("lifeReduced", true);
        summary.put("lostLifeCardInstanceId", lostLifeCardInstanceId);
        return summary;
    }

    private Long loseLifeOnce(Long matchId, Long ownerUserId) {
        EffectContext effectContext = EffectContext.system(matchId, ownerUserId, ACTION_TYPE_RULE_EVENT);
        ReduceLifeAction reduceLifeAction = new ReduceLifeAction(ownerUserId, 1, "LOSE_LIFE_ONCE");
        List<ActionResult> actionResults = gameActionExecutor.execute(effectContext, List.of(reduceLifeAction));
        if (actionResults.isEmpty() || !actionResults.get(0).success()) {
            return null;
        }
        Object movedCards = actionResults.get(0).details().get("lifeCardInstanceIds");
        if (!(movedCards instanceof List<?> movedList) || movedList.isEmpty()) {
            return null;
        }
        Object first = movedList.get(0);
        if (first instanceof Number number) {
            return number.longValue();
        }
        if (first instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Long asLong(Object value) {
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
