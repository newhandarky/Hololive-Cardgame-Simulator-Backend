package com.hololive.cardgame.service;

import java.util.List;
import java.util.Map;

class AttackArtPendingDecisionCreator implements AttackPostTriggerPendingService.PendingDecisionCreator {

    private final GiftTriggerInteractionCardsBuilder giftTriggerInteractionCardsBuilder;
    private final AttackArtPostTriggerConfirmPendingInputBuilder attackArtPostTriggerConfirmPendingInputBuilder;
    private final GiftTriggeredEffectConfirmPendingInputBuilder giftTriggeredEffectConfirmPendingInputBuilder;
    private final FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter;
    private final AttackPendingDecisionConversionService attackPendingDecisionConversionService;

    AttackArtPendingDecisionCreator(
        GiftTriggerInteractionCardsBuilder giftTriggerInteractionCardsBuilder,
        AttackArtPostTriggerConfirmPendingInputBuilder attackArtPostTriggerConfirmPendingInputBuilder,
        GiftTriggeredEffectConfirmPendingInputBuilder giftTriggeredEffectConfirmPendingInputBuilder,
        FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter,
        AttackPendingDecisionConversionService attackPendingDecisionConversionService
    ) {
        this.giftTriggerInteractionCardsBuilder = giftTriggerInteractionCardsBuilder;
        this.attackArtPostTriggerConfirmPendingInputBuilder = attackArtPostTriggerConfirmPendingInputBuilder;
        this.giftTriggeredEffectConfirmPendingInputBuilder = giftTriggeredEffectConfirmPendingInputBuilder;
        this.followupTriggerConfirmPendingDecisionWriter = followupTriggerConfirmPendingDecisionWriter;
        this.attackPendingDecisionConversionService = attackPendingDecisionConversionService;
    }

    @Override
    public AttackPendingDecision createAttackPostTriggerPending(
        AttackPostTriggerPendingContext context,
        Map<String, Object> postTriggerEffectSummary
    ) {
        List<Map<String, Object>> sourceCards = giftTriggerInteractionCardsBuilder.buildGiftTriggerInteractionCards(
            context.matchId(),
            context.attackerUserId(),
            context.attackerCardInstanceId(),
            context.attackerCardId(),
            context.giftTriggeredEffects()
        );
        FollowupTriggerConfirmPendingDecisionInput input = attackArtPostTriggerConfirmPendingInputBuilder
            .buildAttackArtPostTriggerConfirmPendingInput(
                context.matchId(),
                context.attackerUserId(),
                context.attackerCardInstanceId(),
                context.attackerCardId(),
                sourceCards,
                context.giftTriggeredEffects(),
                context.downEventPreview(),
                context.turnNumber()
            );
        return attackPendingDecisionConversionService.toAttackPendingDecision(
            followupTriggerConfirmPendingDecisionWriter.create(input)
        );
    }

    @Override
    public AttackPendingDecision createDefenderGiftPending(
        AttackPostTriggerPendingContext context,
        Map<String, Object> defenderGiftEffectSummary
    ) {
        List<Map<String, Object>> defenderSourceCards = giftTriggerInteractionCardsBuilder.buildGiftTriggerInteractionCards(
            context.matchId(),
            context.defenderUserId(),
            context.downedTargetCardInstanceId(),
            context.downedTargetCardId(),
            context.defenderGiftTriggeredEffects()
        );
        FollowupTriggerConfirmPendingDecisionInput input = giftTriggeredEffectConfirmPendingInputBuilder
            .buildGiftTriggeredEffectConfirmPendingInput(
                context.matchId(),
                context.defenderUserId(),
                context.downedTargetCardInstanceId(),
                context.downedTargetCardId(),
                defenderSourceCards,
                context.defenderGiftTriggeredEffects(),
                context.turnNumber()
            );
        return attackPendingDecisionConversionService.toAttackPendingDecision(
            followupTriggerConfirmPendingDecisionWriter.create(input)
        );
    }
}
