package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BloomEffectResolutionService {

    private final MatchTriggeredCardEffectService matchTriggeredCardEffectService;
    private final MatchEventHookService matchEventHookService;
    private final FollowupCardCandidateLoader followupCardCandidateLoader;
    private final FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter;

    public BloomEffectResolutionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchTriggeredCardEffectService matchTriggeredCardEffectService,
        MatchEventHookService matchEventHookService
    ) {
        this.matchTriggeredCardEffectService = matchTriggeredCardEffectService;
        this.matchEventHookService = matchEventHookService;
        this.followupCardCandidateLoader = new FollowupCardCandidateLoader(jdbcTemplate);
        this.followupTriggerConfirmPendingDecisionWriter = new FollowupTriggerConfirmPendingDecisionWriter(jdbcTemplate, objectMapper);
    }

    public BloomEffectResolution resolveAfterBloom(
        Long matchId,
        Long userId,
        int turnNumber,
        Long bloomCardInstanceId,
        String bloomCardId,
        BloomTargetSnapshot target
    ) {
        Map<String, Object> passiveGiftSummary = matchTriggeredCardEffectService.applyPassiveGiftExtraBloomAllowanceOnBloom(
            matchId,
            userId,
            target.holomemId(),
            bloomCardInstanceId,
            bloomCardId
        );
        MatchEffectService.TriggeredEffectPreview bloomPreview = matchTriggeredCardEffectService.previewBloomTriggeredEffect(
            matchId,
            userId,
            bloomCardId,
            bloomCardInstanceId,
            target.topLevelType()
        );
        Map<String, Object> bloomEffectSummary = buildTriggeredEffectDeferredSummary(bloomPreview);
        Map<String, Object> triggerSummary = matchEventHookService.onHolomemBloom(
            matchId,
            userId,
            bloomCardId,
            bloomCardInstanceId,
            target.topCardInstanceId(),
            target.zone()
        );
        FollowupInteractionDecision triggerConfirmDecision = null;
        if (bloomPreview.hasEffect()) {
            Map<String, Object> additionalContext = new LinkedHashMap<>();
            additionalContext.put("sourceLevelType", target.topLevelType());
            triggerConfirmDecision = followupTriggerConfirmPendingDecisionWriter.create(
                new FollowupTriggerConfirmPendingDecisionInput(
                    matchId,
                    userId,
                    "BLOOM",
                    bloomCardInstanceId,
                    bloomCardId,
                    "BLOOM_EFFECT",
                    "確認 Bloom 效果",
                    buildTriggeredEffectConfirmMessage(bloomPreview),
                    List.of(buildInteractionSourceCardPayload(matchId, userId, bloomCardInstanceId, bloomCardId)),
                    turnNumber,
                    additionalContext
                )
            );
        }
        return new BloomEffectResolution(
            passiveGiftSummary,
            bloomEffectSummary,
            triggerSummary,
            triggerConfirmDecision == null ? null : triggerConfirmDecision.decisionId(),
            triggerConfirmDecision == null ? null : triggerConfirmDecision.decisionType(),
            buildTriggeredResolutionOrder(bloomEffectSummary, triggerSummary),
            bloomPreview.hasEffect()
        );
    }

    private Map<String, Object> buildInteractionSourceCardPayload(
        Long matchId,
        Long userId,
        Long cardInstanceId,
        String fallbackCardId
    ) {
        return followupCardCandidateLoader.loadOwnedCardCandidateForDecision(
            matchId,
            userId,
            cardInstanceId,
            "STAGE",
            fallbackCardId
        );
    }

    private Map<String, Object> buildTriggeredEffectDeferredSummary(
        MatchEffectService.TriggeredEffectPreview preview
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        boolean hasEffect = preview != null && preview.hasEffect();
        summary.put("hasBloomEffect", hasEffect);
        summary.put("deferred", hasEffect);
        summary.put("requestedEffects", preview == null || preview.effectTypes() == null ? List.of() : preview.effectTypes());
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        summary.put("rawText", preview == null ? null : preview.rawText());
        if (preview != null && preview.diceRoll() != null) {
            summary.put("diceRoll", preview.diceRoll());
        }
        return summary;
    }

    private String buildTriggeredEffectConfirmMessage(MatchEffectService.TriggeredEffectPreview preview) {
        String rawText = preview == null ? null : preview.rawText();
        List<String> effectTypes = preview == null ? List.of() : preview.effectTypes();
        String effectSummary = effectTypes == null || effectTypes.isEmpty()
            ? "無可解析效果類型"
            : String.join("、", effectTypes);
        if (!StringUtils.hasText(rawText)) {
            return "是否要執行此 BLOOM 特殊效果？\n效果類型：" + effectSummary;
        }
        return "是否要執行此 BLOOM 特殊效果？\n能力文本：" + rawText + "\n效果類型：" + effectSummary;
    }

    private List<Map<String, Object>> buildTriggeredResolutionOrder(
        Map<String, Object> bloomEffectSummary,
        Map<String, Object> triggerSummary
    ) {
        List<Map<String, Object>> order = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("step", "BLOOM_EFFECT");
        first.put("priority", 100);
        first.put("applied", bloomEffectSummary != null);
        order.add(first);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("step", "BLOOM_EVENT_HOOK");
        second.put("priority", 200);
        second.put("applied", triggerSummary != null);
        order.add(second);
        return order;
    }

}
