package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Gift effect type 分派器。
 *
 * <p>已抽出的低耦合 execution service 直接注入；仍留在 {@link MatchEffectService} 的高耦合副作用
 * 先透過 callback 保留，避免同批搬動 Gift 規則與 SQL。
 */
final class MatchGiftEffectDispatcher {

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
    private final MatchDownEffectExecutionService downEffectExecutionService;
    private final MatchHealEffectExecutionService healEffectExecutionService;
    private final MatchEffectTypeInferenceService effectTypeInferenceService;
    private final GiftEffectHandlers handlers;

    MatchGiftEffectDispatcher(
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
        MatchDownEffectExecutionService downEffectExecutionService,
        MatchHealEffectExecutionService healEffectExecutionService,
        MatchEffectTypeInferenceService effectTypeInferenceService,
        GiftEffectHandlers handlers
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
        this.downEffectExecutionService = downEffectExecutionService;
        this.healEffectExecutionService = healEffectExecutionService;
        this.effectTypeInferenceService = effectTypeInferenceService;
        this.handlers = handlers;
    }

    Map<String, Object> execute(
        Long matchId,
        Long userId,
        Long holderCardInstanceId,
        Long triggerTargetCardInstanceId,
        String effectType,
        JsonNode giftNode
    ) {
        String targetType = effectTypeInferenceService.inferTargetType(effectType);
        return switch (effectType) {
            case "DRAW" -> drawEffectExecutionService.executeDrawEffect(matchId, userId, effectType, giftNode);
            case "SEARCH" -> cardSelectionExecutionService.executeSearchEffect(matchId, userId, effectType, giftNode, null);
            case "REPLACE_ARCHIVE_WITH_HAND" -> handlers.executeReplaceArchiveWithHandEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                holderCardInstanceId
            );
            case "RETURN_TO_HAND" -> cardSelectionExecutionService.executeReturnToHandEffect(matchId, userId, effectType, giftNode, null);
            case "RETURN_TO_DECK_TOP" -> cardSelectionExecutionService.executeReturnToDeckTopEffect(matchId, userId, effectType, giftNode, null);
            case "ADD_CHEER" -> handlers.executeAddCheerEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId);
            case "DAMAGE" -> handlers.executeDamageEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                triggerTargetCardInstanceId
            );
            case "REATTACH" -> handlers.executeReattachEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId);
            case "SUMMON_TO_STAGE" -> summonToStageEffectExecutionService.executeSummonToStageEffect(
                matchId,
                userId,
                effectType,
                giftNode
            );
            case "REVEAL_TO_ARCHIVE" -> revealToArchiveEffectExecutionService.executeRevealToArchiveEffect(
                matchId,
                userId,
                effectType,
                giftNode
            );
            case "BLOOM_FROM_ARCHIVE" -> archiveBloomEffectExecutionService.executeBloomFromArchiveEffect(
                matchId,
                userId,
                effectType,
                giftNode
            );
            case "RETURN_CHEER_TO_DECK_BOTTOM" -> cheerDeckReturnEffectExecutionService.executeReturnCheerToDeckBottomEffect(
                matchId,
                userId,
                effectType,
                giftNode
            );
            case "DISCARD_HAND" -> discardHandEffectExecutionService.executeDiscardHandEffect(matchId, userId, effectType, giftNode);
            case "REST" -> restEffectExecutionService.executeRestEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                holderCardInstanceId
            );
            case "SWAP_CENTER_BACK" -> swapCenterBackEffectExecutionService.executeSwapCenterBackEffect(matchId, userId, effectType, giftNode);
            case "MOVE_TO_HOLOPOWER" -> holopowerMoveEffectExecutionService.executeMoveToHolopowerEffect(matchId, userId, effectType, giftNode);
            case "DOWN_NO_LIFE" -> downEffectExecutionService.executeDownNoLifeEffect(matchId, userId, effectType, giftNode);
            case "DOWN_EXTRA_LIFE" -> downEffectExecutionService.executeDownExtraLifeEffect(matchId, userId, effectType, giftNode);
            case "BATON_TOUCH_COST_MODIFIER" -> batonTouchCostModifierEffectExecutionService.executeBatonTouchCostModifierEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                holderCardInstanceId
            );
            case "ACTION_LOCK" -> actionLockEffectExecutionService.executeActionLockEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                holderCardInstanceId
            );
            case "ALLOW_EXTRA_BLOOM" -> extraBloomAllowanceEffectExecutionService.executeAllowExtraBloomEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                null,
                holderCardInstanceId
            );
            case "LOOK_TOP_DECK" -> lookEffectExecutionService.executeLookTopDeckEffect(matchId, userId, effectType, giftNode);
            case "LOOK_OPPONENT_HAND" -> lookEffectExecutionService.executeLookOpponentHandEffect(matchId, userId, effectType, giftNode);
            case "LOOK_HOLOPOWER" -> lookEffectExecutionService.executeLookHolopowerEffect(matchId, userId, effectType, giftNode);
            case "ARCHIVE_STACK_CARD" -> handlers.executeArchiveStackCardEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                holderCardInstanceId
            );
            case "SWAP_WITH_COLLAB" -> collabSwapEffectExecutionService.executeSwapWithCollabEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                holderCardInstanceId
            );
            case "HEAL" -> healEffectExecutionService.executeHealEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                holderCardInstanceId
            );
            case "BUFF", "DEBUFF" -> handlers.executeBuffDebuffEffect(matchId, userId, effectType, giftNode, targetType);
            case "MATCH_RESULT", "WIN", "LOSE" -> matchResultEffectExecutionService.executeMatchResultEffect(matchId, userId, effectType, giftNode);
            case "UNIMPLEMENTED" -> handlers.executeNoOpEffect(effectType, giftNode, "尚未支援的 GIFT 效果");
            default -> throw new UnsupportedOperationException("UNSUPPORTED_GIFT_EFFECT");
        };
    }

    interface GiftEffectHandlers {
        Map<String, Object> executeReplaceArchiveWithHandEffect(
            Long matchId,
            Long userId,
            String effectType,
            JsonNode effectNode,
            Long holderCardInstanceId
        );

        Map<String, Object> executeAddCheerEffect(
            Long matchId,
            Long userId,
            String effectType,
            JsonNode effectNode,
            String targetType,
            Long targetHolomemCardInstanceId
        );

        Map<String, Object> executeDamageEffect(
            Long matchId,
            Long userId,
            String effectType,
            JsonNode effectNode,
            String targetType,
            Long requestedTargetCardInstanceId
        );

        Map<String, Object> executeReattachEffect(
            Long matchId,
            Long userId,
            String effectType,
            JsonNode effectNode,
            String targetType,
            Long sourceHolomemCardInstanceId
        );

        Map<String, Object> executeArchiveStackCardEffect(
            Long matchId,
            Long userId,
            String effectType,
            JsonNode effectNode,
            Long holderCardInstanceId
        );

        Map<String, Object> executeBuffDebuffEffect(
            Long matchId,
            Long userId,
            String effectType,
            JsonNode effectNode,
            String targetType
        );

        Map<String, Object> executeNoOpEffect(String effectType, JsonNode effectNode, String reason);
    }
}
