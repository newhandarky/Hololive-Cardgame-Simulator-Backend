package com.hololive.cardgame.service;

import com.hololive.cardgame.service.effect.GiftExecutionSummary;
import com.hololive.cardgame.service.effect.GiftTriggerPreviewService;
import java.util.List;
import java.util.Map;

final class MatchGiftTriggerSummaryService {

    private final GiftTriggerPreviewService giftTriggerPreviewService;

    MatchGiftTriggerSummaryService(GiftTriggerPreviewService giftTriggerPreviewService) {
        this.giftTriggerPreviewService = giftTriggerPreviewService;
    }

    Map<String, Object> buildTriggerSummary(
        String triggerType,
        Long holderHolomemId,
        Long holderCardInstanceId,
        String holderCardId,
        String holderZone,
        Long sourceCardInstanceId,
        Long triggerTargetCardInstanceId,
        String giftText,
        GiftExecutionSummary execution,
        boolean preview,
        Map<String, Object> holder
    ) {
        Map<String, Object> summary = giftTriggerPreviewService.buildTriggerSummary(
            triggerType,
            holderHolomemId,
            holderCardInstanceId,
            holderCardId,
            holderZone,
            sourceCardInstanceId,
            triggerTargetCardInstanceId,
            giftText,
            execution,
            preview
        );
        appendHolderSnapshotContext(summary, holder);
        return summary;
    }

    void appendHolderSnapshotContext(Map<String, Object> summary, Map<String, Object> holder) {
        if (summary == null || summary.isEmpty() || holder == null || holder.isEmpty()) {
            return;
        }
        List<Long> attachedCheerCardInstanceIds = MatchEffectValueHelper.toLongList(
            holder.get("attached_cheer_card_instance_ids")
        );
        if (!attachedCheerCardInstanceIds.isEmpty()) {
            summary.put("giftHolderAttachedCheerCardInstanceIds", attachedCheerCardInstanceIds);
        }
        List<String> attachedCheerCardIds = MatchEffectValueHelper.toTextList(holder.get("attached_cheer_card_ids"));
        if (!attachedCheerCardIds.isEmpty()) {
            summary.put("giftHolderAttachedCheerCardIds", attachedCheerCardIds);
        }
        List<Long> stackCardInstanceIds = MatchEffectValueHelper.toLongList(holder.get("stack_card_instance_ids"));
        if (!stackCardInstanceIds.isEmpty()) {
            summary.put("giftHolderStackCardInstanceIds", stackCardInstanceIds);
        }
        List<String> stackCardIds = MatchEffectValueHelper.toTextList(holder.get("stack_card_ids"));
        if (!stackCardIds.isEmpty()) {
            summary.put("giftHolderStackCardIds", stackCardIds);
        }
    }
}
