package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MatchTriggeredCombatEffectService {

    private final MatchTriggeredGiftDamagePreventionExecutionService triggeredGiftDamagePreventionExecutionService;
    private final MatchArtDownTriggeredEffectExecutionService artDownTriggeredEffectExecutionService;

    @Autowired
    public MatchTriggeredCombatEffectService(
        MatchEffectService matchEffectService,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        DiceService diceService
    ) {
        EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
        MatchEffectTypeInferenceService effectTypeInferenceService = new MatchEffectTypeInferenceService(effectTextParser);
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
        this.artDownTriggeredEffectExecutionService =
            new MatchArtDownTriggeredEffectExecutionService(
                objectMapper,
                effectTextParser,
                matchEffectService::extractAttachedSupportRawText,
                effectTypeInferenceService::inferEffectTypes,
                effectTypeInferenceService::inferTargetType,
                matchEffectService::applySupportEffect
            );
    }

    MatchTriggeredCombatEffectService(
        MatchTriggeredGiftDamagePreventionExecutionService triggeredGiftDamagePreventionExecutionService,
        MatchArtDownTriggeredEffectExecutionService artDownTriggeredEffectExecutionService
    ) {
        this.triggeredGiftDamagePreventionExecutionService = triggeredGiftDamagePreventionExecutionService;
        this.artDownTriggeredEffectExecutionService = artDownTriggeredEffectExecutionService;
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
        return artDownTriggeredEffectExecutionService.applyArtDownTriggeredEffects(
            matchId,
            userId,
            attackerCardInstanceId,
            artEffectJsonText
        );
    }
}
