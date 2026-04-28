package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class AttackActionLogServiceTest {

    private final AttackActionLogService.AttackActionWriter actionWriter =
        mock(AttackActionLogService.AttackActionWriter.class);
    private final AttackActionLogService service = new AttackActionLogService(actionWriter);

    @Test
    void appendAttackArtShouldUseAttackArtActionType() {
        AttackActionLogContext context = context("{\"damage\":50}");
        AttackActionLogResult expected = new AttackActionLogResult(
            7001L,
            4,
            AttackActionLogService.ACTION_TYPE_ATTACK_ART,
            context.payloadJson()
        );
        when(actionWriter.appendAction(
            context.matchId(),
            context.userId(),
            AttackActionLogService.ACTION_TYPE_ATTACK_ART,
            context.payloadJson(),
            context.turnNumber()
        )).thenReturn(expected);

        AttackActionLogResult result = service.appendAttackArt(context);

        assertThat(result).isEqualTo(expected);
        verify(actionWriter).appendAction(
            context.matchId(),
            context.userId(),
            AttackActionLogService.ACTION_TYPE_ATTACK_ART,
            context.payloadJson(),
            context.turnNumber()
        );
    }

    @Test
    void appendAttackArtShouldPassPayloadThroughUnchanged() {
        String payload = "{\"postTriggerEffects\":{\"deferred\":true}}";
        AttackActionLogContext context = context(payload);
        when(actionWriter.appendAction(
            context.matchId(),
            context.userId(),
            AttackActionLogService.ACTION_TYPE_ATTACK_ART,
            payload,
            context.turnNumber()
        )).thenReturn(new AttackActionLogResult(7002L, 5, AttackActionLogService.ACTION_TYPE_ATTACK_ART, payload));

        AttackActionLogResult result = service.appendAttackArt(context);

        assertThat(result.payloadJson()).isEqualTo(payload);
        verify(actionWriter).appendAction(
            context.matchId(),
            context.userId(),
            AttackActionLogService.ACTION_TYPE_ATTACK_ART,
            payload,
            context.turnNumber()
        );
    }

    @Test
    void appendAttackArtShouldRejectMissingContext() {
        assertThatThrownBy(() -> service.appendAttackArt(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attack action log");
    }

    private AttackActionLogContext context(String payloadJson) {
        return AttackActionLogContext.attackArt(
            100L,
            10L,
            3,
            payloadJson
        );
    }
}
