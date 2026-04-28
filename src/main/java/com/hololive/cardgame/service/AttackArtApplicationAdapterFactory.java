package com.hololive.cardgame.service;

import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class AttackArtApplicationAdapterFactory {

    private final AttackCostService attackCostService;
    private final AttackTargetService attackTargetService;
    private final AttackDamageService attackDamageService;
    private final AttackDamageApplicationService attackDamageApplicationService;
    private final AttackDownService attackDownService;
    private final AttackDefenderGiftFollowupService attackDefenderGiftFollowupService;
    private final AttackPostTriggerPendingService attackPostTriggerPendingService;
    private final AttackRestAndPayloadService attackRestAndPayloadService;
    private final AttackActionLogService attackActionLogService;
    private final AttackPayloadJsonService attackPayloadJsonService;
    private final AttackPendingDecisionConversionService attackPendingDecisionConversionService;
    private final AttackEffectSummaryExtractor attackEffectSummaryExtractor;
    private final AttackFinishCheckService attackFinishCheckService;
    private final AttackEffectFollowupService attackEffectFollowupService;
    private final MatchEffectCombatModifierService matchEffectCombatModifierService;
    private final MatchGiftTriggerService matchGiftTriggerService;
    private final JdbcTemplate jdbcTemplate;
    private final MatchRepository matchRepository;
    private final AttackArtApplicationAdapterDependencies dependencies;

    AttackArtApplicationAdapterFactory(
        AttackCostService attackCostService,
        AttackTargetService attackTargetService,
        AttackDamageService attackDamageService,
        AttackDamageApplicationService attackDamageApplicationService,
        AttackDownService attackDownService,
        AttackDefenderGiftFollowupService attackDefenderGiftFollowupService,
        AttackPostTriggerPendingService attackPostTriggerPendingService,
        AttackRestAndPayloadService attackRestAndPayloadService,
        AttackActionLogService attackActionLogService,
        AttackPayloadJsonService attackPayloadJsonService,
        AttackPendingDecisionConversionService attackPendingDecisionConversionService,
        AttackEffectSummaryExtractor attackEffectSummaryExtractor,
        AttackFinishCheckService attackFinishCheckService,
        AttackEffectFollowupService attackEffectFollowupService,
        MatchEffectCombatModifierService matchEffectCombatModifierService,
        MatchGiftTriggerService matchGiftTriggerService,
        JdbcTemplate jdbcTemplate,
        MatchRepository matchRepository,
        AttackArtApplicationAdapterDependencies dependencies
    ) {
        this.attackCostService = attackCostService;
        this.attackTargetService = attackTargetService;
        this.attackDamageService = attackDamageService;
        this.attackDamageApplicationService = attackDamageApplicationService;
        this.attackDownService = attackDownService;
        this.attackDefenderGiftFollowupService = attackDefenderGiftFollowupService;
        this.attackPostTriggerPendingService = attackPostTriggerPendingService;
        this.attackRestAndPayloadService = attackRestAndPayloadService;
        this.attackActionLogService = attackActionLogService;
        this.attackPayloadJsonService = attackPayloadJsonService;
        this.attackPendingDecisionConversionService = attackPendingDecisionConversionService;
        this.attackEffectSummaryExtractor = attackEffectSummaryExtractor;
        this.attackFinishCheckService = attackFinishCheckService;
        this.attackEffectFollowupService = attackEffectFollowupService;
        this.matchEffectCombatModifierService = matchEffectCombatModifierService;
        this.matchGiftTriggerService = matchGiftTriggerService;
        this.jdbcTemplate = jdbcTemplate;
        this.matchRepository = matchRepository;
        this.dependencies = dependencies;
    }

    AttackArtApplicationService create() {
        return new AttackArtApplicationService(
            new AttackApplicationPreDamageFollowupResolver(),
            new AttackApplicationCostResolver(),
            new AttackApplicationTargetResolver(),
            new AttackApplicationDamageResolver(),
            new AttackApplicationDamagePreventionResolver(),
            new AttackApplicationDamageApplicationResolver(),
            new AttackApplicationPostDamageFollowupResolver(),
            new AttackApplicationDownResolver(),
            new AttackApplicationDefenderGiftFollowupResolver(),
            new AttackApplicationPostTriggerPendingResolver(),
            new AttackApplicationRestAndPayloadResolver(),
            new AttackApplicationActionLogResolver(),
            new AttackApplicationFinishCheckResolver()
        );
    }

    private <T> T requireAttackStage(Object stageResult, Class<T> type, String stageName) {
        if (type.isInstance(stageResult)) {
            return type.cast(stageResult);
        }
        throw new IllegalStateException("attack application stage result 型別錯誤：" + stageName);
    }

    private Object requireAttackStageResult(Map<String, Object> previousStageResults, String stageName) {
        if (!previousStageResults.containsKey(stageName)) {
            throw new IllegalStateException("attack application 缺少前序 stage：" + stageName);
        }
        return previousStageResults.get(stageName);
    }

    private <T> T requireAttackStageResult(Map<String, Object> previousStageResults, String stageName, Class<T> type) {
        return requireAttackStage(requireAttackStageResult(previousStageResults, stageName), type, stageName);
    }

    private record AttackApplicationPreDamageStage(
        AttackEffectFollowupResult result,
        HoloxSlotRevealSummary holoxSlotRevealSummary
    ) {
    }

    private record AttackApplicationCostStage(
        Map<String, Integer> baseRequiredCheerCost,
        Map<String, Integer> passiveGiftArtCostReduction,
        Map<String, Integer> requiredCheerCost,
        Map<String, Object> costSummary
    ) {
    }

    private record AttackApplicationTargetStage(
        AttackTargetResult result,
        Map<String, Object> defenderSelfDownedHolderSnapshot,
        List<Map<String, Object>> defenderSelfDownedFanSupportSnapshots
    ) {
    }

    private record AttackApplicationDamageStage(
        AttackDamageResult result,
        int totalDamage
    ) {
    }

    private record AttackApplicationDamagePreventionStage(
        AttackEffectDamagePreventionResult result,
        Map<String, Object> defenderDamageReceivedGiftSummary,
        int adjustedDamage
    ) {
    }

    private record AttackApplicationDamageApplicationStage(
        AttackDamageApplicationResult result,
        Map<String, Object> artSummary,
        Long lostLifeCardInstanceId
    ) {
    }

    private record AttackApplicationPostDamageStage(
        AttackEffectPostDamageResult result
    ) {
    }

    private record AttackApplicationDownStage(
        AttackDownResult result,
        String downedTargetCardId,
        String downedTargetZone
    ) {
    }

    private record AttackApplicationDefenderGiftStage(
        AttackDefenderGiftFollowupResult result
    ) {
    }

    private record AttackApplicationPendingStage(
        AttackPostTriggerPendingResult result,
        FollowupInteractionDecision postTriggerConfirmDecision,
        FollowupInteractionDecision defenderGiftConfirmDecision
    ) {
    }

    record AttackApplicationRestPayloadStage(
        AttackRestAndPayloadResult result
    ) implements AttackArtApplicationService.AttackPayloadCarrier {

        @Override
        public Map<String, Object> payload() {
            return result.payload();
        }
    }

    private class AttackApplicationPreDamageFollowupResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackEffectFollowupResult result = attackEffectFollowupService.resolvePreDamage(
                AttackEffectFollowupContext.preDamage(
                    context.matchId(),
                    context.attackerUserId(),
                    context.defenderUserId(),
                    context.turnNumber(),
                    context.attackerHolomemId(),
                    context.attackerCardId(),
                    context.artName(),
                    context.artEffectJsonText()
                )
            );
            return new AttackApplicationPreDamageStage(
                result,
                result.holoxSlotRevealSummary() == null ? HoloxSlotRevealSummary.empty() : result.holoxSlotRevealSummary()
            );
        }
    }

    private class AttackApplicationCostResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            Map<String, Integer> baseRequiredCheerCost = attackCostService.parseCost(context.artCostCheerJsonText());
            Map<String, Integer> passiveGiftArtCostReduction =
                matchEffectCombatModifierService.resolvePassiveGiftArtCheerCostReduction(
                    context.matchId(),
                    context.attackerUserId(),
                    context.attackerHolomemId(),
                    context.artName()
                );
            Map<String, Integer> requiredCheerCost = attackCostService.applyReduction(
                baseRequiredCheerCost,
                passiveGiftArtCostReduction
            );
            AttackCostPaymentResult costPaymentResult = attackCostService.resolvePayment(
                AttackCostPaymentContext.preview(
                    context.matchId(),
                    context.attackerUserId(),
                    context.attackerHolomemId(),
                    baseRequiredCheerCost,
                    passiveGiftArtCostReduction
                )
            );
            return new AttackApplicationCostStage(
                baseRequiredCheerCost,
                passiveGiftArtCostReduction,
                requiredCheerCost,
                costPaymentResult.toPaymentSummary()
            );
        }
    }

    private class AttackApplicationTargetResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackTargetResult targetResult = attackTargetService.resolveTarget(
                AttackTargetContext.resolve(
                    context.matchId(),
                    context.attackerUserId(),
                    context.defenderUserId(),
                    context.turnNumber(),
                    context.targetCardInstanceId()
                )
            );
            Map<String, Object> defenderSelfDownedHolderSnapshot = null;
            List<Map<String, Object>> defenderSelfDownedFanSupportSnapshots = List.of();
            if (targetResult.hasOpponentHolomem() && targetResult.targetBeforeRedirect() != null) {
                defenderSelfDownedHolderSnapshot = matchGiftTriggerService.loadGiftHolderSnapshot(
                    context.matchId(),
                    context.defenderUserId(),
                    targetResult.targetBeforeRedirect().holomemId()
                );
                defenderSelfDownedFanSupportSnapshots = dependencies.loadSelfDownedFanSupportSnapshots(
                    context.matchId(),
                    context.defenderUserId(),
                    targetResult.targetBeforeRedirect().holomemId()
                );
            }
            return new AttackApplicationTargetStage(
                targetResult,
                defenderSelfDownedHolderSnapshot,
                defenderSelfDownedFanSupportSnapshots
            );
        }
    }

    private class AttackApplicationDamageResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackApplicationPreDamageStage preDamageStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_PRE_DAMAGE_FOLLOWUP,
                AttackApplicationPreDamageStage.class
            );
            AttackApplicationTargetStage targetStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_TARGET,
                AttackApplicationTargetStage.class
            );
            AttackTargetResult targetResult = targetStage.result();
            AttackDamageResult damageResult = attackDamageService.resolveDamage(
                AttackDamageContext.resolve(
                    context.matchId(),
                    context.attackerUserId(),
                    context.defenderUserId(),
                    context.turnNumber(),
                    context.attackerHolomemId(),
                    context.attackerCurrentLevel(),
                    targetResult.target(),
                    targetResult.hasOpponentHolomem(),
                    context.artEffectJsonText(),
                    preDamageStage.result().artBonus()
                )
            );
            int totalDamage = damageResult.totalDamage();
            if (totalDamage <= 0) {
                throw new IllegalStateException("此藝能目前未解析出可造成的傷害");
            }
            return new AttackApplicationDamageStage(damageResult, totalDamage);
        }
    }

    private class AttackApplicationDamagePreventionResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackApplicationTargetStage targetStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_TARGET,
                AttackApplicationTargetStage.class
            );
            AttackApplicationDamageStage damageStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DAMAGE,
                AttackApplicationDamageStage.class
            );
            AttackTargetResult targetResult = targetStage.result();
            AttackEffectDamagePreventionResult result = attackEffectFollowupService.resolveDamagePrevention(
                AttackEffectDamagePreventionContext.attackArt(
                    context.matchId(),
                    context.attackerUserId(),
                    context.defenderUserId(),
                    context.attackerCardInstanceId(),
                    targetResult.effectiveTargetCardInstanceId(),
                    context.turnNumber(),
                    damageStage.totalDamage(),
                    targetResult.hasOpponentHolomem(),
                    targetResult.target() != null
                )
            );
            Map<String, Object> defenderDamageReceivedGiftSummary = result.actionLogRequired()
                ? result.defenderDamageReceivedGiftSummary()
                : null;
            if (result.actionLogRequired()) {
                dependencies.appendGiftTriggerAction(
                    context.match(),
                    context.defenderUserId(),
                    defenderDamageReceivedGiftSummary,
                    context.turnNumber()
                );
            }
            return new AttackApplicationDamagePreventionStage(
                result,
                defenderDamageReceivedGiftSummary,
                result.adjustedDamage()
            );
        }
    }

    private class AttackApplicationDamageApplicationResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackApplicationTargetStage targetStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_TARGET,
                AttackApplicationTargetStage.class
            );
            AttackApplicationDamagePreventionStage damagePreventionStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DAMAGE_PREVENTION,
                AttackApplicationDamagePreventionStage.class
            );
            AttackTargetResult targetResult = targetStage.result();
            AttackDamageApplicationResult result = attackDamageApplicationService.applyDamage(
                AttackDamageApplicationContext.attackArt(
                    context.matchId(),
                    context.attackerUserId(),
                    context.defenderUserId(),
                    damagePreventionStage.adjustedDamage(),
                    targetResult.effectiveTargetCardInstanceId(),
                    targetResult.hasOpponentHolomem()
                )
            );
            return new AttackApplicationDamageApplicationStage(
                result,
                new LinkedHashMap<>(result.artSummary()),
                result.lostLifeCardInstanceId()
            );
        }
    }

    private class AttackApplicationPostDamageFollowupResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackApplicationTargetStage targetStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_TARGET,
                AttackApplicationTargetStage.class
            );
            AttackApplicationDamageApplicationStage damageApplicationStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DAMAGE_APPLICATION,
                AttackApplicationDamageApplicationStage.class
            );
            AttackTargetResult targetResult = targetStage.result();
            return new AttackApplicationPostDamageStage(
                attackEffectFollowupService.resolvePostDamage(
                    AttackEffectPostDamageContext.attackArt(
                        context.matchId(),
                        context.attackerUserId(),
                        context.defenderUserId(),
                        context.turnNumber(),
                        context.attackerHolomemId(),
                        targetResult.effectiveTargetCardInstanceId(),
                        context.attackerCardId(),
                        context.artName(),
                        context.attackerMainColor(),
                        targetResult.target(),
                        damageApplicationStage.artSummary()
                    )
                )
            );
        }
    }

    private class AttackApplicationDownResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackApplicationTargetStage targetStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_TARGET,
                AttackApplicationTargetStage.class
            );
            AttackApplicationDamageApplicationStage damageApplicationStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DAMAGE_APPLICATION,
                AttackApplicationDamageApplicationStage.class
            );
            AttackApplicationPostDamageStage postDamageStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_POST_DAMAGE_FOLLOWUP,
                AttackApplicationPostDamageStage.class
            );
            AttackTargetResult targetResult = targetStage.result();
            AttackEffectPostDamageResult postDamageResult = postDamageStage.result();
            AttackDownResult result = attackDownService.resolveDown(AttackDownContext.attackArt(
                context.matchId(),
                context.attackerUserId(),
                context.defenderUserId(),
                context.turnNumber(),
                context.attackerCardInstanceId(),
                context.attackerCardId(),
                context.artName(),
                context.artEffectJsonText(),
                targetResult.effectiveTargetCardInstanceId(),
                targetResult.hasOpponentHolomem(),
                damageApplicationStage.artSummary(),
                postDamageResult.officialCardArtExtraEffects(),
                postDamageResult.officialOshiArtReactiveEffects()
            ));
            AttackTargetHolomem targetHolomem = targetResult.target();
            return new AttackApplicationDownStage(
                result,
                targetHolomem == null ? null : targetHolomem.cardId(),
                targetHolomem == null ? null : targetHolomem.zone()
            );
        }
    }

    private class AttackApplicationDefenderGiftFollowupResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackApplicationTargetStage targetStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_TARGET,
                AttackApplicationTargetStage.class
            );
            AttackApplicationDamageApplicationStage damageApplicationStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DAMAGE_APPLICATION,
                AttackApplicationDamageApplicationStage.class
            );
            AttackApplicationDownStage downStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DOWN,
                AttackApplicationDownStage.class
            );
            AttackTargetResult targetResult = targetStage.result();
            return new AttackApplicationDefenderGiftStage(
                attackDefenderGiftFollowupService.resolveFollowup(AttackDefenderGiftFollowupContext.attackArt(
                    context.matchId(),
                    context.defenderUserId(),
                    context.attackerUserId(),
                    context.turnNumber(),
                    downStage.result().hasDownedHolomem(),
                    targetResult.effectiveTargetCardInstanceId(),
                    downStage.downedTargetCardId(),
                    downStage.downedTargetZone(),
                    targetResult.target(),
                    targetStage.defenderSelfDownedHolderSnapshot(),
                    targetStage.defenderSelfDownedFanSupportSnapshots(),
                    damageApplicationStage.artSummary()
                ))
            );
        }
    }

    private class AttackApplicationPostTriggerPendingResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackApplicationTargetStage targetStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_TARGET,
                AttackApplicationTargetStage.class
            );
            AttackApplicationDownStage downStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DOWN,
                AttackApplicationDownStage.class
            );
            AttackApplicationDefenderGiftStage defenderGiftStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DEFENDER_GIFT_FOLLOWUP,
                AttackApplicationDefenderGiftStage.class
            );
            AttackDefenderGiftFollowupResult defenderGiftResult = defenderGiftStage.result();
            List<Map<String, Object>> giftTriggeredEffects = new ArrayList<>(downStage.result().giftTriggeredEffects());
            List<Map<String, Object>> defenderGiftTriggeredEffects =
                new ArrayList<>(defenderGiftResult.defenderGiftTriggeredEffects());
            AttackPostTriggerPendingResult result = attackPostTriggerPendingService.resolvePending(
                AttackPostTriggerPendingContext.attackArt(
                    context.matchId(),
                    context.attackerUserId(),
                    context.defenderUserId(),
                    context.turnNumber(),
                    context.attackerCardInstanceId(),
                    context.attackerCardId(),
                    targetStage.result().effectiveTargetCardInstanceId(),
                    defenderGiftResult.downedTargetCardId(),
                    giftTriggeredEffects,
                    downStage.result().downEventPreview(),
                    defenderGiftTriggeredEffects
                )
            );
            return new AttackApplicationPendingStage(
                result,
                attackPendingDecisionConversionService.toFollowupInteractionDecision(result.postTriggerConfirmDecision()),
                attackPendingDecisionConversionService.toFollowupInteractionDecision(result.defenderGiftConfirmDecision())
            );
        }
    }

    private class AttackApplicationRestAndPayloadResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackApplicationPreDamageStage preDamageStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_PRE_DAMAGE_FOLLOWUP,
                AttackApplicationPreDamageStage.class
            );
            AttackApplicationCostStage costStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_COST,
                AttackApplicationCostStage.class
            );
            AttackApplicationTargetStage targetStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_TARGET,
                AttackApplicationTargetStage.class
            );
            AttackApplicationDamageStage damageStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DAMAGE,
                AttackApplicationDamageStage.class
            );
            AttackApplicationDamagePreventionStage damagePreventionStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DAMAGE_PREVENTION,
                AttackApplicationDamagePreventionStage.class
            );
            AttackApplicationDamageApplicationStage damageApplicationStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DAMAGE_APPLICATION,
                AttackApplicationDamageApplicationStage.class
            );
            AttackApplicationPostDamageStage postDamageStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_POST_DAMAGE_FOLLOWUP,
                AttackApplicationPostDamageStage.class
            );
            AttackApplicationDownStage downStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DOWN,
                AttackApplicationDownStage.class
            );
            AttackApplicationDefenderGiftStage defenderGiftStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_DEFENDER_GIFT_FOLLOWUP,
                AttackApplicationDefenderGiftStage.class
            );
            AttackApplicationPendingStage pendingStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_POST_TRIGGER_PENDING,
                AttackApplicationPendingStage.class
            );

            int attackerRested = jdbcTemplate.update(
                """
                UPDATE match_holomems
                SET is_rested = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND is_rested = FALSE
                """,
                context.attackerHolomemId(),
                context.matchId(),
                context.attackerUserId()
            );
            if (attackerRested != 1) {
                throw new IllegalStateException("藝能結算失敗，請重新整理後再試");
            }

            boolean hasNextPerformanceAction = dependencies.hasAvailableArtAttacker(
                context.matchId(),
                context.attackerUserId(),
                context.turnNumber()
            );
            context.match().setCurrentPhase(MatchPhase.PERFORMANCE.name());
            dependencies.touchUpdatedAt(context.match());
            matchRepository.saveAndFlush(context.match());

            AttackEffectPostDamageResult postDamageResult = postDamageStage.result();
            AttackEffectFollowupResult preDamageResult = preDamageStage.result();
            AttackDefenderGiftFollowupResult defenderGiftResult = defenderGiftStage.result();
            AttackPostTriggerPendingResult pendingResult = pendingStage.result();
            List<Map<String, Object>> additionalEffectSummaries = new ArrayList<>();
            additionalEffectSummaries.addAll(postDamageResult.officialCardArtExtraEffects());
            additionalEffectSummaries.addAll(postDamageResult.officialOshiArtReactiveEffects());
            additionalEffectSummaries.addAll(
                attackEffectSummaryExtractor.extractExecutedEffectSummaries(defenderGiftResult.officialOshiSelfDownedSummary())
            );
            additionalEffectSummaries.add(downStage.result().artDownTriggeredEffectSummary());
            if (!preDamageResult.hbp02040LifeLoss().isEmpty()) {
                additionalEffectSummaries.add(preDamageResult.hbp02040LifeLoss());
            }
            AttackTargetResult targetResult = targetStage.result();
            AttackRestAndPayloadResult result = attackRestAndPayloadService.resolve(
                AttackRestAndPayloadContext.attackArt(
                    context.attackerCardInstanceId(),
                    context.attackerCardId(),
                    context.attackerZone(),
                    targetResult.effectiveTargetCardInstanceId(),
                    targetResult.passiveGiftTargetRestrictionToCollab(),
                    targetResult.passiveGiftTargetRestrictionApplied(),
                    targetResult.damageRedirectApplied(),
                    targetResult.target() == null ? null : targetResult.target().mainColor(),
                    context.artName(),
                    context.artOrderIndex(),
                    costStage.baseRequiredCheerCost(),
                    costStage.requiredCheerCost(),
                    costStage.passiveGiftArtCostReduction(),
                    costStage.costSummary(),
                    damageStage.result().toPayloadFields(),
                    preDamageStage.holoxSlotRevealSummary().revealApplied()
                        ? preDamageStage.holoxSlotRevealSummary().toPayload()
                        : Map.of(),
                    preDamageResult.hbp02039SupportRecovery(),
                    preDamageResult.hbp02040LifeLoss(),
                    damagePreventionStage.defenderDamageReceivedGiftSummary(),
                    damagePreventionStage.adjustedDamage(),
                    damageApplicationStage.artSummary(),
                    postDamageResult.officialCardArtExtraSummary(),
                    postDamageResult.officialOshiArtReactiveSummary(),
                    defenderGiftResult.officialOshiSelfDownedSummary(),
                    downStage.result().artDownTriggeredEffectSummary(),
                    pendingResult.postTriggerEffectSummary(),
                    pendingResult.defenderGiftEffectSummary(),
                    hasNextPerformanceAction,
                    damageApplicationStage.lostLifeCardInstanceId(),
                    attackPendingDecisionConversionService.toAttackPendingDecision(pendingStage.postTriggerConfirmDecision()),
                    attackPendingDecisionConversionService.toAttackPendingDecision(pendingStage.defenderGiftConfirmDecision()),
                    additionalEffectSummaries
                )
            );
            return new AttackApplicationRestPayloadStage(result);
        }
    }

    private class AttackApplicationActionLogResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackApplicationRestPayloadStage restPayloadStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_REST_AND_PAYLOAD,
                AttackApplicationRestPayloadStage.class
            );
            attackActionLogService.appendAttackArt(AttackActionLogContext.attackArt(
                context.matchId(),
                context.attackerUserId(),
                context.turnNumber(),
                attackPayloadJsonService.toJson(restPayloadStage.result().payload())
            ));
            return restPayloadStage.result().payload();
        }
    }

    private class AttackApplicationFinishCheckResolver implements AttackArtApplicationService.AttackStageResolver {

        @Override
        public Object resolve(AttackArtApplicationContext context, Map<String, Object> previousStageResults) {
            AttackApplicationRestPayloadStage restPayloadStage = requireAttackStageResult(
                previousStageResults,
                AttackArtApplicationService.STAGE_REST_AND_PAYLOAD,
                AttackApplicationRestPayloadStage.class
            );
            Map<String, Object> effectSummaryForChecks = restPayloadStage.result().effectSummaryForChecks();
            attackFinishCheckService.resolve(AttackFinishCheckContext.attackArt(
                context.match(),
                context.attackerUserId(),
                context.turnNumber(),
                effectSummaryForChecks
            ));
            return effectSummaryForChecks;
        }
    }
}
