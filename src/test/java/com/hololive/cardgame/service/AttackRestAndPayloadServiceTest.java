package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackRestAndPayloadServiceTest {

    private final AttackRestAndPayloadService service = new AttackRestAndPayloadService();

    @Test
    void resolveShouldBuildAttackArtPayloadWithBaseFields() {
        AttackRestAndPayloadResult result = service.resolve(contextBuilder().build());

        assertThat(result.payload())
            .containsEntry("attackerCardInstanceId", 501L)
            .containsEntry("attackerCardId", "hBP01-001")
            .containsEntry("attackerZone", "CENTER")
            .containsEntry("targetCardInstanceId", 801L)
            .containsEntry("passiveGiftTargetRestrictionToCollab", false)
            .containsEntry("passiveGiftTargetRestrictionApplied", false)
            .containsEntry("damageRedirectApplied", true)
            .containsEntry("targetMainColor", "RED")
            .containsEntry("artName", "Attack")
            .containsEntry("artOrderIndex", 1)
            .containsEntry("artBaseCost", Map.of("RED", 1))
            .containsEntry("artCost", Map.of("RED", 1))
            .containsEntry("artTotalDamage", 50)
            .containsEntry("hasNextPerformanceAction", true)
            .containsEntry("lostLifeCardInstanceId", 901L);
    }

    @Test
    void resolveShouldMergeDamagePayloadFields() {
        AttackRestAndPayloadContext context = contextBuilder()
            .damagePayloadFields(summary("baseDamage", 30, "damageAfterReduction", 20))
            .build();

        AttackRestAndPayloadResult result = service.resolve(context);

        assertThat(result.payload())
            .containsEntry("baseDamage", 30)
            .containsEntry("damageAfterReduction", 20);
    }

    @Test
    void resolveShouldSkipEmptyOptionalSummaries() {
        AttackRestAndPayloadResult result = service.resolve(contextBuilder()
            .holoxReveal(Map.of())
            .hbp02039SupportRecovery(Map.of())
            .hbp02040LifeLoss(Map.of())
            .officialCardArtExtraSummary(Map.of())
            .officialOshiArtReactiveSummary(Map.of())
            .officialOshiSelfDownedSummary(Map.of())
            .build());

        assertThat(result.payload())
            .doesNotContainKeys(
                "holoxReveal",
                "hbp02039SupportRecovery",
                "hbp02040LifeLoss",
                "officialCardArtExtra",
                "officialOshiArtReactive",
                "officialOshiSelfDowned"
            );
    }

    @Test
    void resolveShouldWriteOptionalSummariesWithExistingKeys() {
        Map<String, Object> holoxReveal = summary("revealApplied", true);
        Map<String, Object> recovery = summary("applied", true);
        Map<String, Object> lifeLoss = summary("lifeReduced", true);
        Map<String, Object> officialExtra = summary("cardId", "HBP01-087");
        Map<String, Object> oshiReactive = summary("cardId", "hBP01-004");
        Map<String, Object> oshiSelfDowned = summary("cardId", "hBP01-006");

        AttackRestAndPayloadResult result = service.resolve(contextBuilder()
            .holoxReveal(holoxReveal)
            .hbp02039SupportRecovery(recovery)
            .hbp02040LifeLoss(lifeLoss)
            .officialCardArtExtraSummary(officialExtra)
            .officialOshiArtReactiveSummary(oshiReactive)
            .officialOshiSelfDownedSummary(oshiSelfDowned)
            .build());

        assertThat(result.payload())
            .containsEntry("holoxReveal", holoxReveal)
            .containsEntry("hbp02039SupportRecovery", recovery)
            .containsEntry("hbp02040LifeLoss", lifeLoss)
            .containsEntry("officialCardArtExtra", officialExtra)
            .containsEntry("officialOshiArtReactive", oshiReactive)
            .containsEntry("officialOshiSelfDowned", oshiSelfDowned);
    }

    @Test
    void resolveShouldWritePendingDecisionPayloadKeys() {
        AttackRestAndPayloadResult result = service.resolve(contextBuilder()
            .postTriggerConfirmDecision(new AttackPendingDecision(301L, "ATTACK_ART_POST_TRIGGER"))
            .defenderGiftConfirmDecision(new AttackPendingDecision(401L, "GIFT"))
            .build());

        assertThat(result.payload())
            .containsEntry("pendingInteractionDecisionId", 301L)
            .containsEntry("pendingInteractionDecisionType", "ATTACK_ART_POST_TRIGGER")
            .containsEntry("defenderPendingInteractionDecisionId", 401L)
            .containsEntry("defenderPendingInteractionDecisionType", "GIFT");
    }

    @Test
    void resolveShouldBuildEffectSummaryForChecks() {
        Map<String, Object> artSummary = summary("lifeReduced", true);
        Map<String, Object> extraEffect = summary("holomemDowned", true);

        AttackRestAndPayloadResult result = service.resolve(contextBuilder()
            .artSummary(artSummary)
            .additionalEffectSummaries(List.of(extraEffect))
            .build());

        assertThat(result.effectSummaryForChecks())
            .containsEntry("executedEffects", List.of(artSummary, extraEffect));
    }

    @Test
    void resolveShouldRejectMissingContext() {
        assertThatThrownBy(() -> service.resolve(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attack rest and payload");
    }

    private ContextBuilder contextBuilder() {
        return new ContextBuilder();
    }

    private Map<String, Object> summary(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    private static class ContextBuilder {

        private Map<String, Object> damagePayloadFields = Map.of("damage", 50);
        private Map<String, Object> holoxReveal = Map.of();
        private Map<String, Object> hbp02039SupportRecovery = Map.of();
        private Map<String, Object> hbp02040LifeLoss = Map.of();
        private Map<String, Object> artSummary = Map.of("damage", 50);
        private Map<String, Object> officialCardArtExtraSummary = Map.of();
        private Map<String, Object> officialOshiArtReactiveSummary = Map.of();
        private Map<String, Object> officialOshiSelfDownedSummary = Map.of();
        private AttackPendingDecision postTriggerConfirmDecision;
        private AttackPendingDecision defenderGiftConfirmDecision;
        private List<Map<String, Object>> additionalEffectSummaries = List.of();

        private ContextBuilder damagePayloadFields(Map<String, Object> damagePayloadFields) {
            this.damagePayloadFields = damagePayloadFields;
            return this;
        }

        private ContextBuilder holoxReveal(Map<String, Object> holoxReveal) {
            this.holoxReveal = holoxReveal;
            return this;
        }

        private ContextBuilder hbp02039SupportRecovery(Map<String, Object> hbp02039SupportRecovery) {
            this.hbp02039SupportRecovery = hbp02039SupportRecovery;
            return this;
        }

        private ContextBuilder hbp02040LifeLoss(Map<String, Object> hbp02040LifeLoss) {
            this.hbp02040LifeLoss = hbp02040LifeLoss;
            return this;
        }

        private ContextBuilder artSummary(Map<String, Object> artSummary) {
            this.artSummary = artSummary;
            return this;
        }

        private ContextBuilder officialCardArtExtraSummary(Map<String, Object> officialCardArtExtraSummary) {
            this.officialCardArtExtraSummary = officialCardArtExtraSummary;
            return this;
        }

        private ContextBuilder officialOshiArtReactiveSummary(Map<String, Object> officialOshiArtReactiveSummary) {
            this.officialOshiArtReactiveSummary = officialOshiArtReactiveSummary;
            return this;
        }

        private ContextBuilder officialOshiSelfDownedSummary(Map<String, Object> officialOshiSelfDownedSummary) {
            this.officialOshiSelfDownedSummary = officialOshiSelfDownedSummary;
            return this;
        }

        private ContextBuilder postTriggerConfirmDecision(AttackPendingDecision postTriggerConfirmDecision) {
            this.postTriggerConfirmDecision = postTriggerConfirmDecision;
            return this;
        }

        private ContextBuilder defenderGiftConfirmDecision(AttackPendingDecision defenderGiftConfirmDecision) {
            this.defenderGiftConfirmDecision = defenderGiftConfirmDecision;
            return this;
        }

        private ContextBuilder additionalEffectSummaries(List<Map<String, Object>> additionalEffectSummaries) {
            this.additionalEffectSummaries = additionalEffectSummaries;
            return this;
        }

        private AttackRestAndPayloadContext build() {
            return AttackRestAndPayloadContext.attackArt(
                501L,
                "hBP01-001",
                "CENTER",
                801L,
                false,
                false,
                true,
                "RED",
                "Attack",
                1,
                Map.of("RED", 1),
                Map.of("RED", 1),
                Map.of("applied", false),
                Map.of("paidTotal", 1),
                damagePayloadFields,
                holoxReveal,
                hbp02039SupportRecovery,
                hbp02040LifeLoss,
                Map.of("deferred", false),
                50,
                artSummary,
                officialCardArtExtraSummary,
                officialOshiArtReactiveSummary,
                officialOshiSelfDownedSummary,
                Map.of("deferred", false),
                Map.of("sourceActionType", "ATTACK_ART_POST_TRIGGER"),
                Map.of("sourceActionType", "GIFT"),
                true,
                901L,
                postTriggerConfirmDecision,
                defenderGiftConfirmDecision,
                additionalEffectSummaries
            );
        }
    }
}
