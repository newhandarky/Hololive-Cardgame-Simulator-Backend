package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportOshiEffectPayloadBuilderTest {

    private final SupportOshiEffectPayloadBuilder builder = new SupportOshiEffectPayloadBuilder();

    @Test
    void buildSupportSelectionPendingPayloadShouldKeepSupportPendingFields() {
        MatchEffectService.SupportDecisionPlan decisionPlan = decisionPlan();

        Map<String, Object> payload = builder.buildSupportSelectionPendingPayload(
            901L,
            501L,
            "hBP01-001",
            decisionPlan
        );

        assertThat(payload)
            .containsEntry("decisionId", 901L)
            .containsEntry("decisionType", "CARD_SELECTION")
            .containsEntry("cardInstanceId", 501L)
            .containsEntry("cardId", "hBP01-001")
            .containsEntry("effectType", "LOOK_TOP_DECK")
            .containsEntry("candidateCount", 2)
            .containsEntry("minSelect", 1)
            .containsEntry("maxSelect", 2);
    }

    @Test
    void buildOshiSkillSelectionPendingPayloadShouldKeepSkillPendingFields() {
        MatchEffectService.SupportDecisionPlan decisionPlan = decisionPlan();
        Map<String, Object> holopowerPayment = Map.of("paid", List.of(301L));

        Map<String, Object> payload = builder.buildOshiSkillSelectionPendingPayload(
            902L,
            "SP",
            "skill",
            502L,
            "hBP01-002",
            2,
            holopowerPayment,
            decisionPlan
        );

        assertThat(payload)
            .containsEntry("decisionId", 902L)
            .containsEntry("decisionType", "CARD_SELECTION")
            .containsEntry("skillType", "SP")
            .containsEntry("skillName", "skill")
            .containsEntry("oshiCardInstanceId", 502L)
            .containsEntry("oshiCardId", "hBP01-002")
            .containsEntry("holopowerCost", 2)
            .containsEntry("holopowerPayment", holopowerPayment)
            .containsEntry("effectType", "LOOK_TOP_DECK")
            .containsEntry("candidateCount", 2)
            .containsEntry("minSelect", 1)
            .containsEntry("maxSelect", 2);
    }

    @Test
    void buildPlaySupportEffectPayloadShouldKeepSupportFields() {
        Map<String, Object> effectSummary = Map.of("effectType", "DRAW");

        Map<String, Object> payload = builder.buildPlaySupportEffectPayload(
            501L,
            "hBP01-001",
            true,
            701L,
            List.of(801L),
            effectSummary
        );

        assertThat(payload)
            .containsEntry("cardInstanceId", 501L)
            .containsEntry("cardId", "hBP01-001")
            .containsEntry("limited", true)
            .containsEntry("targetHolomemCardInstanceId", 701L)
            .containsEntry("selectedCardInstanceIds", List.of(801L))
            .containsEntry("effect", effectSummary);
    }

    @Test
    void buildResolvedSelectionEffectPayloadShouldUseOshiFieldsForOshiSource() {
        Map<String, Object> effectSummary = Map.of("effectType", "LOOK_TOP_DECK");

        Map<String, Object> payload = builder.buildResolvedSelectionEffectPayload(
            901L,
            "USE_OSHI_SKILL",
            501L,
            "hBP01-001",
            false,
            701L,
            List.of(801L),
            effectSummary
        );

        assertThat(payload)
            .containsEntry("decisionId", 901L)
            .containsEntry("sourceActionType", "USE_OSHI_SKILL")
            .containsEntry("oshiCardInstanceId", 501L)
            .containsEntry("oshiCardId", "hBP01-001")
            .containsEntry("targetHolomemCardInstanceId", 701L)
            .containsEntry("selectedCardInstanceIds", List.of(801L))
            .containsEntry("effect", effectSummary)
            .doesNotContainKeys("cardInstanceId", "cardId", "limited");
    }

    @Test
    void buildResolvedSelectionEffectPayloadShouldUseSupportFieldsForSupportSource() {
        Map<String, Object> effectSummary = Map.of("effectType", "LOOK_TOP_DECK");

        Map<String, Object> payload = builder.buildResolvedSelectionEffectPayload(
            902L,
            "PLAY_SUPPORT",
            502L,
            "hBP01-002",
            true,
            702L,
            List.of(802L),
            effectSummary
        );

        assertThat(payload)
            .containsEntry("decisionId", 902L)
            .containsEntry("sourceActionType", "PLAY_SUPPORT")
            .containsEntry("cardInstanceId", 502L)
            .containsEntry("cardId", "hBP01-002")
            .containsEntry("limited", true)
            .containsEntry("targetHolomemCardInstanceId", 702L)
            .containsEntry("selectedCardInstanceIds", List.of(802L))
            .containsEntry("effect", effectSummary)
            .doesNotContainKeys("oshiCardInstanceId", "oshiCardId");
    }

    @Test
    void buildOshiSkillEffectPayloadShouldKeepSkillAndPaymentFields() {
        Map<String, Object> effectSummary = Map.of("effectType", "DRAW");
        Map<String, Object> holopowerPayment = Map.of("paid", List.of(301L));

        Map<String, Object> payload = builder.buildOshiSkillEffectPayload(
            "SP",
            "skill",
            503L,
            "hBP01-003",
            2,
            holopowerPayment,
            703L,
            List.of(803L),
            effectSummary
        );

        assertThat(payload)
            .containsEntry("skillType", "SP")
            .containsEntry("skillName", "skill")
            .containsEntry("oshiCardInstanceId", 503L)
            .containsEntry("oshiCardId", "hBP01-003")
            .containsEntry("holopowerCost", 2)
            .containsEntry("holopowerPayment", holopowerPayment)
            .containsEntry("targetHolomemCardInstanceId", 703L)
            .containsEntry("selectedCardInstanceIds", List.of(803L))
            .containsEntry("effect", effectSummary);
    }

    private MatchEffectService.SupportDecisionPlan decisionPlan() {
        return new MatchEffectService.SupportDecisionPlan(
            "LOOK_TOP_DECK",
            1,
            2,
            List.of(
                new MatchEffectService.DecisionCandidate(801L, "hBP02-001", "card 1", "holomem", "Debut", "DECK"),
                new MatchEffectService.DecisionCandidate(802L, "hBP02-002", "card 2", "holomem", "Debut", "DECK")
            )
        );
    }
}
