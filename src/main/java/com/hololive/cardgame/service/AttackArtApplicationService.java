package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.Map;

public class AttackArtApplicationService {

    public static final String STAGE_PRE_DAMAGE_FOLLOWUP = "preDamageFollowup";
    public static final String STAGE_COST = "cost";
    public static final String STAGE_TARGET = "target";
    public static final String STAGE_DAMAGE = "damage";
    public static final String STAGE_DAMAGE_PREVENTION = "damagePrevention";
    public static final String STAGE_DAMAGE_APPLICATION = "damageApplication";
    public static final String STAGE_POST_DAMAGE_FOLLOWUP = "postDamageFollowup";
    public static final String STAGE_DOWN = "down";
    public static final String STAGE_DEFENDER_GIFT_FOLLOWUP = "defenderGiftFollowup";
    public static final String STAGE_POST_TRIGGER_PENDING = "postTriggerPending";
    public static final String STAGE_REST_AND_PAYLOAD = "restAndPayload";
    public static final String STAGE_ACTION_LOG = "actionLog";
    public static final String STAGE_FINISH_CHECK = "finishCheck";

    private final AttackStageResolver preDamageFollowupResolver;
    private final AttackStageResolver costResolver;
    private final AttackStageResolver targetResolver;
    private final AttackStageResolver damageResolver;
    private final AttackStageResolver damagePreventionResolver;
    private final AttackStageResolver damageApplicationResolver;
    private final AttackStageResolver postDamageFollowupResolver;
    private final AttackStageResolver downResolver;
    private final AttackStageResolver defenderGiftFollowupResolver;
    private final AttackStageResolver postTriggerPendingResolver;
    private final AttackStageResolver restAndPayloadResolver;
    private final AttackStageResolver actionLogResolver;
    private final AttackStageResolver finishCheckResolver;

    public AttackArtApplicationService(
        AttackStageResolver preDamageFollowupResolver,
        AttackStageResolver costResolver,
        AttackStageResolver targetResolver,
        AttackStageResolver damageResolver,
        AttackStageResolver damagePreventionResolver,
        AttackStageResolver damageApplicationResolver,
        AttackStageResolver postDamageFollowupResolver,
        AttackStageResolver downResolver,
        AttackStageResolver defenderGiftFollowupResolver,
        AttackStageResolver postTriggerPendingResolver,
        AttackStageResolver restAndPayloadResolver,
        AttackStageResolver actionLogResolver,
        AttackStageResolver finishCheckResolver
    ) {
        this.preDamageFollowupResolver = preDamageFollowupResolver;
        this.costResolver = costResolver;
        this.targetResolver = targetResolver;
        this.damageResolver = damageResolver;
        this.damagePreventionResolver = damagePreventionResolver;
        this.damageApplicationResolver = damageApplicationResolver;
        this.postDamageFollowupResolver = postDamageFollowupResolver;
        this.downResolver = downResolver;
        this.defenderGiftFollowupResolver = defenderGiftFollowupResolver;
        this.postTriggerPendingResolver = postTriggerPendingResolver;
        this.restAndPayloadResolver = restAndPayloadResolver;
        this.actionLogResolver = actionLogResolver;
        this.finishCheckResolver = finishCheckResolver;
    }

    public AttackArtApplicationResult execute(AttackArtApplicationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack art application 缺少必要上下文");
        }

        Map<String, Object> stageResults = new LinkedHashMap<>();
        resolveStage(stageResults, STAGE_PRE_DAMAGE_FOLLOWUP, preDamageFollowupResolver, context);
        resolveStage(stageResults, STAGE_COST, costResolver, context);
        resolveStage(stageResults, STAGE_TARGET, targetResolver, context);
        resolveStage(stageResults, STAGE_DAMAGE, damageResolver, context);
        resolveStage(stageResults, STAGE_DAMAGE_PREVENTION, damagePreventionResolver, context);
        resolveStage(stageResults, STAGE_DAMAGE_APPLICATION, damageApplicationResolver, context);
        resolveStage(stageResults, STAGE_POST_DAMAGE_FOLLOWUP, postDamageFollowupResolver, context);
        resolveStage(stageResults, STAGE_DOWN, downResolver, context);
        resolveStage(stageResults, STAGE_DEFENDER_GIFT_FOLLOWUP, defenderGiftFollowupResolver, context);
        resolveStage(stageResults, STAGE_POST_TRIGGER_PENDING, postTriggerPendingResolver, context);
        Object restAndPayload = resolveStage(stageResults, STAGE_REST_AND_PAYLOAD, restAndPayloadResolver, context);
        Object actionLog = resolveStage(stageResults, STAGE_ACTION_LOG, actionLogResolver, context);
        Object finishCheck = resolveStage(stageResults, STAGE_FINISH_CHECK, finishCheckResolver, context);

        return new AttackArtApplicationResult(
            stageResults,
            restAndPayload instanceof AttackPayloadCarrier payloadCarrier ? payloadCarrier.payload() : Map.of(),
            actionLog,
            finishCheck
        );
    }

    private Object resolveStage(
        Map<String, Object> stageResults,
        String stageName,
        AttackStageResolver resolver,
        AttackArtApplicationContext context
    ) {
        Object result = resolver.resolve(context, stageResults);
        stageResults.put(stageName, result);
        return result;
    }

    public interface AttackStageResolver {
        Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults);
    }

    public interface AttackPayloadCarrier {
        Map<String, Object> payload();
    }
}
