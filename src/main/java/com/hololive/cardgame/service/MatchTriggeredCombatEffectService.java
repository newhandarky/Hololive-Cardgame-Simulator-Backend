package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MatchTriggeredCombatEffectService {

    private final MatchEffectService matchEffectService;
    private final MatchTriggeredGiftDamagePreventionExecutionService triggeredGiftDamagePreventionExecutionService;

    public MatchTriggeredCombatEffectService(
        MatchEffectService matchEffectService,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        DiceService diceService
    ) {
        this.matchEffectService = matchEffectService;
        EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
        this.triggeredGiftDamagePreventionExecutionService =
            new MatchTriggeredGiftDamagePreventionExecutionService(
                jdbcTemplate,
                effectTextParser,
                diceService,
                new GiftTriggerMatcher(),
                new GiftTurnUsageReader(jdbcTemplate),
                new MatchGiftTriggerConditionService(
                    jdbcTemplate,
                    effectTextParser,
                    new GiftTriggerMatcher(),
                    new SearchCriteriaParser(jdbcTemplate, effectTextParser)
                )
            );
    }

    public Map<String, Object> resolveTriggeredGiftDamagePrevention(
        Long matchId,
        Long defendingUserId,
        Long attackingUserId,
        Long sourceCardInstanceId,
        Long targetCardInstanceId,
        int turnNumber,
        int incomingDamage
    ) {
        return triggeredGiftDamagePreventionExecutionService.resolveTriggeredGiftDamagePrevention(
            matchId,
            defendingUserId,
            attackingUserId,
            sourceCardInstanceId,
            targetCardInstanceId,
            turnNumber,
            incomingDamage
        );
    }

    public Map<String, Object> applyArtDownTriggeredEffects(
        Long matchId,
        Long userId,
        Long attackerCardInstanceId,
        String artEffectJsonText
    ) {
        return matchEffectService.applyArtDownTriggeredEffects(
            matchId,
            userId,
            attackerCardInstanceId,
            artEffectJsonText
        );
    }
}
