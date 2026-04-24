package com.hololive.cardgame.service;

import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import java.util.Objects;
import java.util.Set;

final class MatchGiftTriggerEligibilityService {

    private final MatchGiftTriggerConditionService giftTriggerConditionService;
    private final GiftTriggerMatcher giftTriggerMatcher;

    MatchGiftTriggerEligibilityService(
        MatchGiftTriggerConditionService giftTriggerConditionService,
        GiftTriggerMatcher giftTriggerMatcher
    ) {
        this.giftTriggerConditionService = giftTriggerConditionService;
        this.giftTriggerMatcher = giftTriggerMatcher;
    }

    boolean isEligible(
        Long matchId,
        Long userId,
        int turnNumber,
        Long holderHolomemId,
        Long holderCardInstanceId,
        String holderZone,
        String holderLevel,
        Long sourceCardInstanceId,
        MatchGiftTriggerSourceContext sourceContext,
        String giftText,
        String triggerType,
        AttachedSupportConditionChecker attachedSupportConditionChecker,
        GiftAlreadyUsedThisTurnChecker giftAlreadyUsedThisTurnChecker
    ) {
        if (giftText.contains("このホロメンが") && !Objects.equals(sourceCardInstanceId, holderCardInstanceId)) {
            return false;
        }
        if ("ART_USED".equals(triggerType)
            && !giftTriggerConditionService.matchesReferencedArtNameCondition(
                giftText,
                sourceContext == null ? null : sourceContext.artName()
            )) {
            return false;
        }
        if ("ART_USED".equals(triggerType)
            && !giftTriggerConditionService.matchesSpecialDamageThresholdCondition(
                giftText,
                sourceContext == null ? null : sourceContext.cardId(),
                sourceContext == null ? null : sourceContext.cardName(),
                sourceContext == null ? null : sourceContext.artName()
            )) {
            return false;
        }
        if (!giftTriggerConditionService.matchesTurnOwnershipCondition(matchId, userId, giftText)) {
            return false;
        }
        if (!giftTriggerConditionService.matchesLifeComparisonCondition(matchId, userId, giftText)) {
            return false;
        }
        if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(giftText, holderZone)) {
            return false;
        }
        if (giftText.contains("このホロメンに")
            && giftText.contains("が付いている")
            && !attachedSupportConditionChecker.matches(giftText, holderHolomemId)) {
            return false;
        }
        if (giftText.contains("1stホロメンからBloomしているこのホロメン")
            && !Set.of("SECOND", "BUZZ").contains(holderLevel)) {
            return false;
        }
        if ("SELF_DOWNED".equals(triggerType)
            && !giftText.contains("このホロメンがダウンした時")
            && !Objects.equals(sourceCardInstanceId, holderCardInstanceId)) {
            return false;
        }
        if ("ALLY_DOWNED".equals(triggerType)
            && !giftText.contains("このホロメンがダウンした時")
            && Objects.equals(sourceCardInstanceId, holderCardInstanceId)) {
            return false;
        }
        if (giftText.contains("ターンに1回")
            && giftAlreadyUsedThisTurnChecker.isUsed(matchId, userId, turnNumber, holderHolomemId)) {
            return false;
        }
        if ("OPPONENT_DOWNED".equals(triggerType)) {
            if (giftText.contains("このホロメンがダウンした時")) {
                return false;
            }
            if (
                (giftText.contains("相手のホロメンがダウンした時") || giftText.contains("ダウンさせた時"))
                && sourceCardInstanceId == null
            ) {
                return false;
            }
        }
        if (Set.of("SELF_DOWNED", "ALLY_DOWNED").contains(triggerType)
            && !giftTriggerConditionService.matchesDownedSourceCondition(
                giftText,
                sourceContext == null ? null : sourceContext.cardName(),
                sourceContext == null ? null : sourceContext.levelType(),
                sourceContext == null ? null : sourceContext.stageZone(),
                sourceContext == null ? null : sourceContext.tagsJson(),
                triggerType
            )) {
            return false;
        }
        if ("COLLAB".equals(triggerType)
            && !giftTriggerConditionService.matchesCollabSourceCondition(
                giftText,
                sourceContext == null ? null : sourceContext.cardName(),
                sourceContext == null ? null : sourceContext.levelType(),
                sourceContext == null ? null : sourceContext.stageZone(),
                sourceContext == null ? null : sourceContext.tagsJson()
            )) {
            return false;
        }
        if ("BATON_TOUCH_BACK".equals(triggerType)
            && !giftTriggerConditionService.matchesBatonTouchBackSourceCondition(
                giftText,
                sourceContext == null ? null : sourceContext.cardName(),
                sourceContext == null ? null : sourceContext.levelType(),
                sourceContext == null ? null : sourceContext.stageZone(),
                sourceContext == null ? null : sourceContext.tagsJson()
            )) {
            return false;
        }
        if (Set.of("PERFORMANCE_END_SELF", "PERFORMANCE_END_OPPONENT").contains(triggerType)
            && !giftTriggerConditionService.matchesPerformanceEndCondition(
                matchId,
                userId,
                turnNumber,
                holderHolomemId,
                giftText
            )) {
            return false;
        }
        if ("STAGE_ENTER".equals(triggerType)
            && !giftTriggerConditionService.matchesStageEnterSourceCondition(
                giftText,
                sourceContext == null ? null : sourceContext.levelType(),
                sourceContext == null ? null : sourceContext.stageZone(),
                sourceContext == null ? null : sourceContext.tagsJson()
            )) {
            return false;
        }
        return giftTriggerConditionService.matchesHandCountCondition(matchId, userId, giftText);
    }

    @FunctionalInterface
    interface AttachedSupportConditionChecker {

        boolean matches(String giftText, Long holderHolomemId);
    }

    @FunctionalInterface
    interface GiftAlreadyUsedThisTurnChecker {

        boolean isUsed(Long matchId, Long userId, int turnNumber, Long holderHolomemId);
    }
}
