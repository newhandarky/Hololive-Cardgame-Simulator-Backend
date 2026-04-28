package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.repository.MatchActionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GiftTriggerActionWriterTest {

    private final MatchActionRepository matchActionRepository = mock(MatchActionRepository.class);
    private final GiftTriggerActionWriter writer = new GiftTriggerActionWriter(
        matchActionRepository,
        new MatchPayloadJsonService(new ObjectMapper())
    );

    @Test
    void appendGiftTriggerActionsShouldReturnEmptyAndNotPersistForEmptyPayloads() {
        assertThat(writer.appendGiftTriggerActions(100L, 10L, 2, List.of())).isEmpty();

        verify(matchActionRepository, never()).save(any(MatchActionEntity.class));
    }

    @Test
    void appendGiftTriggerActionsShouldPersistGiftTriggerActionsWithNextActionOrder() {
        when(matchActionRepository.findMaxActionOrderByTurn(100L, 2)).thenReturn(6);
        when(matchActionRepository.save(any(MatchActionEntity.class))).thenAnswer(invocation -> {
            MatchActionEntity action = invocation.getArgument(0);
            action.setId(9001L);
            return action;
        });

        List<MatchActionEntity> savedActions = writer.appendGiftTriggerActions(
            100L,
            10L,
            2,
            List.of(Map.of("triggerType", "COLLAB", "requestedEffects", List.of("DRAW")))
        );

        ArgumentCaptor<MatchActionEntity> actionCaptor = ArgumentCaptor.forClass(MatchActionEntity.class);
        verify(matchActionRepository).save(actionCaptor.capture());
        MatchActionEntity savedAction = actionCaptor.getValue();
        assertThat(savedAction.getMatchId()).isEqualTo(100L);
        assertThat(savedAction.getUserId()).isEqualTo(10L);
        assertThat(savedAction.getActionType()).isEqualTo(GiftTriggerActionWriter.ACTION_TYPE_GIFT_TRIGGER);
        assertThat(savedAction.getPayload()).contains("\"triggerType\":\"COLLAB\"");
        assertThat(savedAction.getPayload()).contains("\"requestedEffects\":[\"DRAW\"]");
        assertThat(savedAction.getTurnNumber()).isEqualTo(2);
        assertThat(savedAction.getActionOrder()).isEqualTo(7);
        assertThat(savedAction.getExecutedAt()).isNotNull();

        assertThat(savedActions).hasSize(1);
        assertThat(savedActions.get(0).getId()).isEqualTo(9001L);
        assertThat(savedActions.get(0).getActionType()).isEqualTo(GiftTriggerActionWriter.ACTION_TYPE_GIFT_TRIGGER);
    }
}
