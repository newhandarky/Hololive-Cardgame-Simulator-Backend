package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AttackPendingDecisionConversionServiceTest {

    private final AttackPendingDecisionConversionService service = new AttackPendingDecisionConversionService();

    @Test
    void toFollowupInteractionDecisionShouldCopyDecisionFields() {
        FollowupInteractionDecision result = service.toFollowupInteractionDecision(
            new AttackPendingDecision(301L, "TRIGGER_EFFECT_CONFIRM")
        );

        assertThat(result).isEqualTo(new FollowupInteractionDecision(301L, "TRIGGER_EFFECT_CONFIRM"));
    }

    @Test
    void toAttackPendingDecisionShouldCopyDecisionFields() {
        AttackPendingDecision result = service.toAttackPendingDecision(
            new FollowupInteractionDecision(401L, "LOOK_TOP_DECK")
        );

        assertThat(result).isEqualTo(new AttackPendingDecision(401L, "LOOK_TOP_DECK"));
    }

    @Test
    void conversionsShouldAllowMissingDecision() {
        assertThat(service.toFollowupInteractionDecision(null)).isNull();
        assertThat(service.toAttackPendingDecision(null)).isNull();
    }
}
