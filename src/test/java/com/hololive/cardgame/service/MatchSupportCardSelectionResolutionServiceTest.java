package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MatchSupportCardSelectionResolutionServiceTest {

    private final PendingDecisionStore pendingDecisionStore = mock(PendingDecisionStore.class);
    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final MatchActionRepository matchActionRepository = mock(MatchActionRepository.class);
    private final EffectFollowupDecisionResolver effectFollowupDecisionResolver = mock(EffectFollowupDecisionResolver.class);
    private final MatchSupportCardSelectionResolutionService.SupportEffectApplier supportEffectApplier = mock(
        MatchSupportCardSelectionResolutionService.SupportEffectApplier.class
    );
    private final MatchSupportCardSelectionResolutionService.ResolvedEffectFinalizer resolvedEffectFinalizer = mock(
        MatchSupportCardSelectionResolutionService.ResolvedEffectFinalizer.class
    );
    private final MatchSupportCardSelectionResolutionService service = new MatchSupportCardSelectionResolutionService(
        pendingDecisionStore,
        matchRepository,
        matchActionRepository,
        new MatchPayloadJsonService(new ObjectMapper()),
        new MatchTimestampService(),
        new SelectedCardValidationService(),
        new SupportOshiEffectPayloadBuilder(),
        effectFollowupDecisionResolver,
        new FollowupDecisionPayloadAppender(),
        supportEffectApplier,
        resolvedEffectFinalizer
    );

    @Test
    void resolveShouldApplySupportSelectionAndWritePlaySupportAction() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentPhase(MatchPhase.PERFORMANCE.name());
        PendingDecision pending = pending("PLAY_SUPPORT", true);
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setSelectedCardInstanceIds(List.of(500L));
        Map<String, Object> effectSummary = Map.of("effectType", "SEARCH_DECK", "applied", true);
        when(supportEffectApplier.apply(100L, 10L, pending, List.of(500L))).thenReturn(effectSummary);
        when(effectFollowupDecisionResolver.resolvePostTriggerOrInteraction(
            eq(100L),
            eq(10L),
            eq("PLAY_SUPPORT"),
            eq(400L),
            eq("hBP01-001"),
            eq("SEARCH_DECK"),
            eq(effectSummary),
            eq(2)
        )).thenReturn(new FollowupInteractionDecision(700L, "LOOK_TOP_DECK"));
        ArgumentCaptor<MatchActionEntity> actionCaptor = ArgumentCaptor.forClass(MatchActionEntity.class);
        when(matchActionRepository.findMaxActionOrderByTurn(100L, 2)).thenReturn(4);
        when(matchActionRepository.save(actionCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        service.resolve(100L, 10L, 2, match, pending, request);

        verify(pendingDecisionStore).markResolved(300L);
        verify(matchRepository).saveAndFlush(match);
        assertThat(match.getCurrentPhase()).isEqualTo(MatchPhase.MAIN.name());
        verify(resolvedEffectFinalizer).finalizeResolvedEffect(match, 100L, 10L, 2, effectSummary);
        MatchActionEntity action = actionCaptor.getValue();
        assertThat(action.getActionType()).isEqualTo("PLAY_SUPPORT");
        assertThat(action.getActionOrder()).isEqualTo(5);
        assertThat(action.getPayload()).contains("\"decisionId\":300");
        assertThat(action.getPayload()).contains("\"cardInstanceId\":400");
        assertThat(action.getPayload()).contains("\"selectedCardInstanceIds\":[500]");
        assertThat(action.getPayload()).contains("\"pendingInteractionDecisionId\":700");
    }

    @Test
    void resolveShouldUseOshiSkillActionForOshiSource() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        PendingDecision pending = pending("USE_OSHI_SKILL", false);
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setSelectedCardInstanceIds(List.of(500L));
        Map<String, Object> effectSummary = Map.of("effectType", "DRAW", "applied", true);
        when(supportEffectApplier.apply(100L, 10L, pending, List.of(500L))).thenReturn(effectSummary);
        ArgumentCaptor<MatchActionEntity> actionCaptor = ArgumentCaptor.forClass(MatchActionEntity.class);
        when(matchActionRepository.findMaxActionOrderByTurn(100L, 2)).thenReturn(0);
        when(matchActionRepository.save(actionCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        service.resolve(100L, 10L, 2, match, pending, request);

        MatchActionEntity action = actionCaptor.getValue();
        assertThat(action.getActionType()).isEqualTo("USE_OSHI_SKILL");
        assertThat(action.getPayload()).contains("\"oshiCardInstanceId\":400");
        assertThat(action.getPayload()).contains("\"oshiCardId\":\"hBP01-001\"");
        verify(effectFollowupDecisionResolver).resolvePostTriggerOrInteraction(
            eq(100L),
            eq(10L),
            eq("USE_OSHI_SKILL"),
            eq(400L),
            eq("hBP01-001"),
            eq("SEARCH_DECK"),
            eq(effectSummary),
            eq(2)
        );
        verify(resolvedEffectFinalizer).finalizeResolvedEffect(match, 100L, 10L, 2, effectSummary);
    }

    private PendingDecision pending(String sourceActionType, boolean limited) {
        return new PendingDecision(
            300L,
            "CARD_SELECTION",
            sourceActionType,
            400L,
            "hBP01-001",
            "SEARCH_DECK",
            1,
            2,
            900L,
            "STAGE",
            null,
            List.of(500L, 501L),
            limited,
            new ObjectMapper().createObjectNode().put("type", "SEARCH_DECK")
        );
    }
}
