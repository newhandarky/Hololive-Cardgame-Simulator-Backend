package com.hololive.cardgame.service;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EndTurnActionResolver {

    public EndTurnResolutionResult resolve(EndTurnContext context) {
        if (context == null || context.match() == null) {
            throw new IllegalArgumentException("END_TURN 結算缺少必要上下文");
        }
        MatchEntity match = context.match();
        int nextTurnNumber = context.currentTurnNumber() + 1;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUserId", context.actorUserId());
        payload.put("toUserId", context.opponentUserId());
        payload.put("clearedExpiredTurnEffects", context.clearedEffectCount());
        payload.put("resetRestedCount", context.resetRestedCount());
        payload.put("centerReplenish", context.centerReplenishSummary());
        payload.put("nextTurnNumber", nextTurnNumber);

        match.setCurrentTurnPlayerId(context.opponentUserId());
        match.setTurnNumber(nextTurnNumber);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setUpdatedAt(LocalDateTime.now());

        return new EndTurnResolutionResult(
            match,
            context.actorUserId(),
            context.opponentUserId(),
            context.currentTurnNumber(),
            nextTurnNumber,
            MatchPhase.MAIN,
            payload
        );
    }
}
