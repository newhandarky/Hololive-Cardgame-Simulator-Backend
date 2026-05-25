package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collab triggered effect type 分派器。
 *
 * <p>目前仍委派回 {@link MatchEffectService} 執行各 effect SQL，先把 Collab orchestration
 * 從主 service 拆出，後續再逐步搬移個別 effect family。
 */
class MatchCollabEffectDispatcher {

    private final MatchCardSelectionExecutionService cardSelectionExecutionService;
    private final MatchLookEffectExecutionService lookEffectExecutionService;
    private final MatchDrawEffectExecutionService drawEffectExecutionService;
    private final MatchHolopowerMoveEffectExecutionService holopowerMoveEffectExecutionService;
    private final MatchRestEffectExecutionService restEffectExecutionService;
    private final MatchSwapCenterBackEffectExecutionService swapCenterBackEffectExecutionService;
    private final MatchCollabSwapEffectExecutionService collabSwapEffectExecutionService;
    private final MatchActionLockEffectExecutionService actionLockEffectExecutionService;
    private final MatchExtraBloomAllowanceEffectExecutionService extraBloomAllowanceEffectExecutionService;
    private final MatchBatonTouchCostModifierEffectExecutionService batonTouchCostModifierEffectExecutionService;
    private final MatchResultEffectExecutionService matchResultEffectExecutionService;
    private final MatchDiscardHandEffectExecutionService discardHandEffectExecutionService;
    private final MatchRevealToArchiveEffectExecutionService revealToArchiveEffectExecutionService;
    private final MatchSummonToStageEffectExecutionService summonToStageEffectExecutionService;
    private final MatchArchiveBloomEffectExecutionService archiveBloomEffectExecutionService;
    private final MatchCheerDeckReturnEffectExecutionService cheerDeckReturnEffectExecutionService;
    private final MatchEffectService effectService;

    MatchCollabEffectDispatcher(
        MatchCardSelectionExecutionService cardSelectionExecutionService,
        MatchLookEffectExecutionService lookEffectExecutionService,
        MatchDrawEffectExecutionService drawEffectExecutionService,
        MatchHolopowerMoveEffectExecutionService holopowerMoveEffectExecutionService,
        MatchRestEffectExecutionService restEffectExecutionService,
        MatchSwapCenterBackEffectExecutionService swapCenterBackEffectExecutionService,
        MatchCollabSwapEffectExecutionService collabSwapEffectExecutionService,
        MatchActionLockEffectExecutionService actionLockEffectExecutionService,
        MatchExtraBloomAllowanceEffectExecutionService extraBloomAllowanceEffectExecutionService,
        MatchBatonTouchCostModifierEffectExecutionService batonTouchCostModifierEffectExecutionService,
        MatchResultEffectExecutionService matchResultEffectExecutionService,
        MatchDiscardHandEffectExecutionService discardHandEffectExecutionService,
        MatchRevealToArchiveEffectExecutionService revealToArchiveEffectExecutionService,
        MatchSummonToStageEffectExecutionService summonToStageEffectExecutionService,
        MatchArchiveBloomEffectExecutionService archiveBloomEffectExecutionService,
        MatchCheerDeckReturnEffectExecutionService cheerDeckReturnEffectExecutionService,
        MatchEffectService effectService
    ) {
        this.cardSelectionExecutionService = cardSelectionExecutionService;
        this.lookEffectExecutionService = lookEffectExecutionService;
        this.drawEffectExecutionService = drawEffectExecutionService;
        this.holopowerMoveEffectExecutionService = holopowerMoveEffectExecutionService;
        this.restEffectExecutionService = restEffectExecutionService;
        this.swapCenterBackEffectExecutionService = swapCenterBackEffectExecutionService;
        this.collabSwapEffectExecutionService = collabSwapEffectExecutionService;
        this.actionLockEffectExecutionService = actionLockEffectExecutionService;
        this.extraBloomAllowanceEffectExecutionService = extraBloomAllowanceEffectExecutionService;
        this.batonTouchCostModifierEffectExecutionService = batonTouchCostModifierEffectExecutionService;
        this.matchResultEffectExecutionService = matchResultEffectExecutionService;
        this.discardHandEffectExecutionService = discardHandEffectExecutionService;
        this.revealToArchiveEffectExecutionService = revealToArchiveEffectExecutionService;
        this.summonToStageEffectExecutionService = summonToStageEffectExecutionService;
        this.archiveBloomEffectExecutionService = archiveBloomEffectExecutionService;
        this.cheerDeckReturnEffectExecutionService = cheerDeckReturnEffectExecutionService;
        this.effectService = effectService;
    }

    CollabDispatchResult execute(
        Long matchId,
        Long userId,
        Long selfHolomemCardInstanceId,
        String normalizedCollabCardId,
        List<String> effectTypes,
        ObjectNode collabEffectNode
    ) {
        List<Map<String, Object>> executed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<Map<String, Object>> skippedEffects = new ArrayList<>();
        int returnedCheerCount = -1;
        int removedCheerCount = -1;

        for (String effectType : effectTypes) {
            String targetType = effectService.inferBloomTargetType(effectType);
            String effectiveTargetType = targetType;
            if ("MOVE_ZONE".equals(effectType)) {
                effectiveTargetType = effectService.resolveCollabMoveTargetType(collabEffectNode, targetType);
            }
            if (
                "HSD13-015".equals(normalizedCollabCardId)
                    && "ADD_CHEER".equals(effectType)
                    && returnedCheerCount == 0
            ) {
                Map<String, Object> skipped = effectService.executeNoOpEffect(effectType, collabEffectNode, "條件未成立：未退回場上エール");
                executed.add(skipped);
                skippedEffects.add(skipped);
                continue;
            }
            if (
                normalizedCollabCardId.startsWith("HBP06-078")
                    && "SEARCH".equals(effectType)
                    && removedCheerCount == 0
            ) {
                Map<String, Object> skipped = effectService.executeNoOpEffect(effectType, collabEffectNode, "條件未成立：未支付此卡附屬エール成本");
                executed.add(skipped);
                skippedEffects.add(skipped);
                continue;
            }
            try {
                switch (effectType) {
                    case "DRAW" -> executed.add(
                        drawEffectExecutionService.executeDrawEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "SEARCH" -> executed.add(
                        cardSelectionExecutionService.executeSearchEffect(matchId, userId, effectType, collabEffectNode, null)
                    );
                    case "RETURN_TO_HAND" -> executed.add(
                        cardSelectionExecutionService.executeReturnToHandEffect(matchId, userId, effectType, collabEffectNode, null)
                    );
                    case "RETURN_TO_DECK_TOP" -> executed.add(
                        cardSelectionExecutionService.executeReturnToDeckTopEffect(matchId, userId, effectType, collabEffectNode, null)
                    );
                    case "ADD_CHEER" -> executed.add(
                        effectService.executeAddCheerEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            effectService.resolveCollabAddCheerTargetCardInstanceId(collabEffectNode, selfHolomemCardInstanceId)
                        )
                    );
                    case "DAMAGE" -> executed.add(
                        effectService.executeDamageEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            null
                        )
                    );
                    case "REATTACH" -> executed.add(
                        effectService.executeReattachEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "REMOVE_CHEER" -> executed.add(
                        effectService.executeRemoveCheerEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "SUMMON_TO_STAGE" -> executed.add(
                        summonToStageEffectExecutionService.executeSummonToStageEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode
                        )
                    );
                    case "REVEAL_TO_ARCHIVE" -> executed.add(
                        revealToArchiveEffectExecutionService.executeRevealToArchiveEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode
                        )
                    );
                    case "BLOOM_FROM_ARCHIVE" -> executed.add(
                        archiveBloomEffectExecutionService.executeBloomFromArchiveEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "RETURN_CHEER_TO_DECK_BOTTOM" -> executed.add(
                        cheerDeckReturnEffectExecutionService.executeReturnCheerToDeckBottomEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "DISCARD_HAND" -> executed.add(
                        discardHandEffectExecutionService.executeDiscardHandEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "REST" -> executed.add(
                        restEffectExecutionService.executeRestEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "SWAP_CENTER_BACK" -> executed.add(
                        swapCenterBackEffectExecutionService.executeSwapCenterBackEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "MOVE_TO_HOLOPOWER" -> executed.add(
                        holopowerMoveEffectExecutionService.executeMoveToHolopowerEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "DOWN_NO_LIFE" -> executed.add(
                        effectService.executeDownNoLifeEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "DOWN_EXTRA_LIFE" -> executed.add(
                        effectService.executeDownExtraLifeEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "BATON_TOUCH_COST_MODIFIER" -> executed.add(
                        batonTouchCostModifierEffectExecutionService.executeBatonTouchCostModifierEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "ACTION_LOCK" -> executed.add(
                        actionLockEffectExecutionService.executeActionLockEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "ALLOW_EXTRA_BLOOM" -> executed.add(
                        extraBloomAllowanceEffectExecutionService.executeAllowExtraBloomEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode
                        )
                    );
                    case "LOOK_TOP_DECK" -> executed.add(
                        lookEffectExecutionService.executeLookTopDeckEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "LOOK_OPPONENT_HAND" -> executed.add(
                        lookEffectExecutionService.executeLookOpponentHandEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "LOOK_HOLOPOWER" -> executed.add(
                        lookEffectExecutionService.executeLookHolopowerEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "MOVE_ZONE" -> executed.add(
                        effectService.executeMoveZoneEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            effectiveTargetType,
                            effectService.resolveCollabMoveTargetCardInstanceId(collabEffectNode, selfHolomemCardInstanceId)
                        )
                    );
                    case "SWAP_WITH_COLLAB" -> executed.add(
                        collabSwapEffectExecutionService.executeSwapWithCollabEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "HEAL" -> executed.add(
                        effectService.executeHealEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "BUFF", "DEBUFF" -> executed.add(
                        effectService.executeBuffDebuffEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType
                        )
                    );
                    case "MATCH_RESULT", "WIN", "LOSE" -> executed.add(
                        matchResultEffectExecutionService.executeMatchResultEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "UNIMPLEMENTED" -> executed.add(
                        effectService.executeNoOpEffect(effectType, collabEffectNode, "尚未支援的 COLLAB 效果")
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
            if ("RETURN_CHEER_TO_DECK_BOTTOM".equals(effectType)) {
                Map<String, Object> latest = executed.isEmpty() ? null : executed.get(executed.size() - 1);
                returnedCheerCount = latest == null ? 0 : effectService.asInt(latest.get("returnApplied"));
            }
            if ("REMOVE_CHEER".equals(effectType)) {
                Map<String, Object> latest = executed.isEmpty() ? null : executed.get(executed.size() - 1);
                removedCheerCount = latest == null ? 0 : effectService.asInt(latest.get("removeApplied"));
            }
        }

        return new CollabDispatchResult(executed, unsupported, skippedEffects);
    }

    record CollabDispatchResult(
        List<Map<String, Object>> executed,
        List<String> unsupported,
        List<Map<String, Object>> skippedEffects
    ) {}
}
