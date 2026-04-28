package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.repository.MatchActionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AttackActionWriterAdapterTest {

    private final MatchActionRepository matchActionRepository = mock(MatchActionRepository.class);
    private final AttackActionWriterAdapter adapter = new AttackActionWriterAdapter(matchActionRepository);

    @Test
    void appendActionShouldPersistNextActionOrderAndReturnSavedSnapshot() {
        when(matchActionRepository.findMaxActionOrderByTurn(100L, 3)).thenReturn(4);
        when(matchActionRepository.save(any(MatchActionEntity.class))).thenAnswer(invocation -> {
            MatchActionEntity action = invocation.getArgument(0);
            action.setId(7001L);
            return action;
        });

        AttackActionLogResult result = adapter.appendAction(
            100L,
            10L,
            AttackActionLogService.ACTION_TYPE_ATTACK_ART,
            "{\"artTotalDamage\":50}",
            3
        );

        ArgumentCaptor<MatchActionEntity> actionCaptor = ArgumentCaptor.forClass(MatchActionEntity.class);
        verify(matchActionRepository).save(actionCaptor.capture());
        MatchActionEntity savedAction = actionCaptor.getValue();
        assertThat(savedAction.getMatchId()).isEqualTo(100L);
        assertThat(savedAction.getUserId()).isEqualTo(10L);
        assertThat(savedAction.getActionType()).isEqualTo(AttackActionLogService.ACTION_TYPE_ATTACK_ART);
        assertThat(savedAction.getPayload()).isEqualTo("{\"artTotalDamage\":50}");
        assertThat(savedAction.getTurnNumber()).isEqualTo(3);
        assertThat(savedAction.getActionOrder()).isEqualTo(5);
        assertThat(savedAction.getExecutedAt()).isNotNull();

        assertThat(result.actionId()).isEqualTo(7001L);
        assertThat(result.actionOrder()).isEqualTo(5);
        assertThat(result.actionType()).isEqualTo(AttackActionLogService.ACTION_TYPE_ATTACK_ART);
        assertThat(result.payloadJson()).isEqualTo("{\"artTotalDamage\":50}");
    }
}
