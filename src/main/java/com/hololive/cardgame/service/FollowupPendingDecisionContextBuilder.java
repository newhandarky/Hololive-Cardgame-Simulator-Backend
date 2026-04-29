package com.hololive.cardgame.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

class FollowupPendingDecisionContextBuilder {

    Map<String, Object> buildPendingDecisionContext(
        FollowupInteractionContext interaction,
        String effectType
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", interaction.decisionType());
        context.put("title", interaction.title());
        context.put("message", interaction.message());
        context.put("cards", interaction.cards());
        if (interaction.placementOptions() != null && !interaction.placementOptions().isEmpty()) {
            context.put("placementOptions", interaction.placementOptions());
        }
        context.put("effectType", effectType);
        context.put("candidateCardInstanceIds", interaction.candidateCardInstanceIds());
        context.put("candidateCards", interaction.cards());
        if (interaction.lookedCardInstanceId() != null) {
            context.put("lookedCardInstanceId", interaction.lookedCardInstanceId());
        }
        if (StringUtils.hasText(interaction.lookedCardId())) {
            context.put("lookedCardId", interaction.lookedCardId());
        }
        return context;
    }
}
