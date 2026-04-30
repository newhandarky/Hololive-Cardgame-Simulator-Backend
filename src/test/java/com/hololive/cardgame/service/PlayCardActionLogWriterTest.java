package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.repository.MatchActionRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlayCardActionLogWriterTest {

    private final MatchActionRepository matchActionRepository = mock(MatchActionRepository.class);
    private final PlayCardActionLogWriter writer = new PlayCardActionLogWriter(
        matchActionRepository,
        new MatchPayloadJsonService(new ObjectMapper()),
        new PlayCardActionLogPayloadBuilder()
    );

    @Test
    void appendPlayCardActionShouldPersistLegacyActionLog() {
        when(matchActionRepository.findMaxActionOrderByTurn(100L, 3)).thenReturn(4);
        when(matchActionRepository.save(any(MatchActionEntity.class))).thenAnswer(invocation -> {
            MatchActionEntity action = invocation.getArgument(0);
            action.setId(7001L);
            return action;
        });

        MatchActionEntity result = writer.appendPlayCardAction(
            action(),
            resolutionResult(),
            effectResolution()
        );

        ArgumentCaptor<MatchActionEntity> actionCaptor = ArgumentCaptor.forClass(MatchActionEntity.class);
        verify(matchActionRepository).save(actionCaptor.capture());
        MatchActionEntity savedAction = actionCaptor.getValue();
        assertThat(savedAction.getMatchId()).isEqualTo(100L);
        assertThat(savedAction.getUserId()).isEqualTo(201L);
        assertThat(savedAction.getActionType()).isEqualTo("PLAY_TO_STAGE");
        assertThat(savedAction.getTurnNumber()).isEqualTo(3);
        assertThat(savedAction.getActionOrder()).isEqualTo(5);
        assertThat(savedAction.getExecutedAt()).isNotNull();
        assertThat(savedAction.getPayload())
            .contains("\"cardInstanceId\":501")
            .contains("\"cardId\":\"hBP01-001\"")
            .contains("\"targetZone\":\"BACK\"")
            .contains("\"pendingInteractionDecisionId\":901");

        assertThat(result.getId()).isEqualTo(7001L);
    }

    private PlayCardAction action() {
        return PlayCardAction.fromApi(
            100L,
            201L,
            501L,
            "BACK",
            3,
            false,
            "idem-501"
        );
    }

    private PlayCardResolutionResult resolutionResult() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        return new PlayCardResolutionResult(
            match,
            201L,
            3,
            501L,
            "hBP01-001",
            "HAND",
            "BACK",
            601L,
            3,
            false,
            "DEBUT",
            false
        );
    }

    private PlayCardEffectResolution effectResolution() {
        return new PlayCardEffectResolution(
            Map.of("triggered", true),
            List.of(Map.of("cardInstanceId", 701L)),
            Map.of("effectType", "GIFT_TRIGGER"),
            901L,
            "TRIGGER_EFFECT_CONFIRM",
            false,
            List.of(Map.of("source", "GIFT"))
        );
    }
}
