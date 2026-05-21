package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bloom triggered effect type 分派器。
 *
 * <p>目前仍委派回 {@link MatchEffectService} 執行各 effect SQL，先把 Bloom orchestration
 * 從主 service 拆出，後續再逐步搬移個別 effect family。
 */
class MatchBloomEffectDispatcher {

    private final MatchCardSelectionExecutionService cardSelectionExecutionService;
    private final MatchEffectService effectService;

    MatchBloomEffectDispatcher(
        MatchCardSelectionExecutionService cardSelectionExecutionService,
        MatchEffectService effectService
    ) {
        this.cardSelectionExecutionService = cardSelectionExecutionService;
        this.effectService = effectService;
    }

    BloomDispatchResult execute(
        Long matchId,
        Long userId,
        Long selfHolomemCardInstanceId,
        String normalizedBloomCardId,
        List<String> effectTypes,
        ObjectNode bloomEffectNode
    ) {
        List<Map<String, Object>> executed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<Map<String, Object>> skippedEffects = new ArrayList<>();
        int archivedStackCostCount = -1;

        for (String effectType : effectTypes) {
            String targetType = effectService.inferBloomTargetType(effectType);
            try {
                switch (effectType) {
                    case "DRAW" -> executed.add(effectService.executeDrawEffect(matchId, userId, effectType, bloomEffectNode));
                    case "SEARCH" -> executed.add(
                        cardSelectionExecutionService.executeSearchEffect(matchId, userId, effectType, bloomEffectNode, null)
                    );
                    case "RETURN_TO_HAND" -> executed.add(
                        cardSelectionExecutionService.executeReturnToHandEffect(matchId, userId, effectType, bloomEffectNode, null)
                    );
                    case "RETURN_TO_DECK_TOP" -> executed.add(
                        cardSelectionExecutionService.executeReturnToDeckTopEffect(matchId, userId, effectType, bloomEffectNode, null)
                    );
                    case "ADD_CHEER" -> executed.add(
                        effectService.executeAddCheerEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "DAMAGE" -> {
                        if (
                            normalizedBloomCardId.startsWith("HSD13-011")
                                && archivedStackCostCount <= 0
                        ) {
                            Map<String, Object> skipped = effectService.executeNoOpEffect(
                                effectType,
                                bloomEffectNode,
                                "條件未成立：未支付重疊 Debut 成本"
                            );
                            executed.add(skipped);
                            skippedEffects.add(skipped);
                            continue;
                        }
                        Long requestedTargetCardInstanceId = null;
                        if (normalizedBloomCardId.startsWith("HSD13-011")) {
                            requestedTargetCardInstanceId = effectService.resolveOpponentCollabCardInstanceId(matchId, userId);
                            if (requestedTargetCardInstanceId == null || requestedTargetCardInstanceId <= 0) {
                                Map<String, Object> skipped = effectService.executeNoOpEffect(
                                    effectType,
                                    bloomEffectNode,
                                    "條件未成立：對手沒有 COLLAB 目標"
                                );
                                executed.add(skipped);
                                skippedEffects.add(skipped);
                                continue;
                            }
                        }
                        executed.add(
                            effectService.executeDamageEffect(
                                matchId,
                                userId,
                                effectType,
                                bloomEffectNode,
                                targetType,
                                requestedTargetCardInstanceId
                            )
                        );
                    }
                    case "REATTACH" -> executed.add(
                        effectService.executeReattachEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "REMOVE_CHEER" -> executed.add(
                        effectService.executeRemoveCheerEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "REMOVE_STAGE_CHEER" -> executed.add(
                        effectService.executeRemoveStageCheerEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "SUMMON_TO_STAGE" -> executed.add(
                        effectService.executeSummonToStageEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "REVEAL_TO_ARCHIVE" -> executed.add(
                        effectService.executeRevealToArchiveEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "BLOOM_FROM_ARCHIVE" -> executed.add(
                        effectService.executeBloomFromArchiveEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "RETURN_CHEER_TO_DECK_BOTTOM" -> executed.add(
                        effectService.executeReturnCheerToDeckBottomEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "DISCARD_HAND" -> executed.add(
                        effectService.executeDiscardHandEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "REST" -> executed.add(
                        effectService.executeRestEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "SWAP_CENTER_BACK" -> executed.add(
                        effectService.executeSwapCenterBackEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "MOVE_TO_HOLOPOWER" -> executed.add(
                        effectService.executeMoveToHolopowerEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "DOWN_NO_LIFE" -> executed.add(
                        effectService.executeDownNoLifeEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "DOWN_EXTRA_LIFE" -> executed.add(
                        effectService.executeDownExtraLifeEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "BATON_TOUCH_COST_MODIFIER" -> executed.add(
                        effectService.executeBatonTouchCostModifierEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "ACTION_LOCK" -> executed.add(
                        effectService.executeActionLockEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "ALLOW_EXTRA_BLOOM" -> executed.add(
                        effectService.executeAllowExtraBloomEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "LOOK_TOP_DECK" -> executed.add(
                        effectService.executeLookTopDeckEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "LOOK_OPPONENT_HAND" -> executed.add(
                        effectService.executeLookOpponentHandEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "LOOK_HOLOPOWER" -> executed.add(
                        effectService.executeLookHolopowerEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "ARCHIVE_STACK_CARD" -> {
                        Map<String, Object> archiveSummary = effectService.executeArchiveStackCardEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            selfHolomemCardInstanceId
                        );
                        executed.add(archiveSummary);
                        archivedStackCostCount = effectService.asInt(archiveSummary.get("archiveApplied"));
                    }
                    case "MOVE_ZONE" -> executed.add(
                        effectService.executeMoveZoneEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            null
                        )
                    );
                    case "SWAP_WITH_COLLAB" -> executed.add(
                        effectService.executeSwapWithCollabEffect(matchId, userId, effectType, bloomEffectNode, selfHolomemCardInstanceId)
                    );
                    case "HEAL" -> executed.add(
                        effectService.executeHealEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "BUFF", "DEBUFF" -> executed.add(
                        effectService.executeBuffDebuffEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType
                        )
                    );
                    case "MATCH_RESULT", "WIN", "LOSE" -> executed.add(
                        effectService.executeMatchResultEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "UNIMPLEMENTED" -> executed.add(
                        effectService.executeNoOpEffect(effectType, bloomEffectNode, "尚未支援的 BLOOM 效果")
                    );
                    default -> {
                        unsupported.add(effectType);
                        Map<String, Object> skipped = effectService.buildSkippedEffect(effectType, "UNSUPPORTED_EFFECT");
                        executed.add(skipped);
                        skippedEffects.add(skipped);
                    }
                }
            } catch (RuntimeException ex) {
                Map<String, Object> skipped = effectService.buildSkippedEffect(effectType, ex.getMessage());
                executed.add(skipped);
                skippedEffects.add(skipped);
            }
        }

        return new BloomDispatchResult(executed, unsupported, skippedEffects);
    }

    record BloomDispatchResult(
        List<Map<String, Object>> executed,
        List<String> unsupported,
        List<Map<String, Object>> skippedEffects
    ) {}
}
