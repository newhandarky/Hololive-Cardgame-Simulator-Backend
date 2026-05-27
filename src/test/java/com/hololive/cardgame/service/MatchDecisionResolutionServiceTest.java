package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;

class MatchDecisionResolutionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PendingDecisionStore pendingDecisionStore = mock(PendingDecisionStore.class);
    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final MatchActionRepository matchActionRepository = mock(MatchActionRepository.class);
    private final MatchTurnLifecycleService matchTurnLifecycleService = mock(MatchTurnLifecycleService.class);
    private final MatchTurnStartCollabReturnService matchTurnStartCollabReturnService = mock(
        MatchTurnStartCollabReturnService.class
    );
    private final MainStepGiftFollowupPayloadAppender mainStepGiftFollowupPayloadAppender = mock(
        MainStepGiftFollowupPayloadAppender.class
    );
    private final GameActionExecutor gameActionExecutor = mock(GameActionExecutor.class);
    private final MatchDecisionResolutionService service = new MatchDecisionResolutionService(
        jdbcTemplate,
        pendingDecisionStore,
        matchRepository,
        matchActionRepository,
        new MatchPayloadJsonService(new ObjectMapper()),
        new InteractionConfirmedPayloadBuilder(),
        new MatchTimestampService(),
        matchTurnLifecycleService,
        matchTurnStartCollabReturnService,
        mainStepGiftFollowupPayloadAppender,
        gameActionExecutor,
        new SendCheerInteractionPayloadBuilder(),
        new SelectedCardValidationService()
    );

    @Test
    void resolveLowCouplingDecisionShouldResolveLookTopDeckToBottom() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentPhase(MatchPhase.PERFORMANCE.name());
        PendingDecision pending = pending("LOOK_TOP_DECK", List.of(500L), 1);
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setPlacement("BOTTOM");
        when(jdbcTemplate.queryForObject(
            contains("COALESCE(MAX(order_index)"),
            eq(Integer.class),
            eq(100L),
            eq(10L)
        )).thenReturn(7);
        when(matchActionRepository.findMaxActionOrderByTurn(100L, 2)).thenReturn(3);
        ArgumentCaptor<MatchActionEntity> actionCaptor = ArgumentCaptor.forClass(MatchActionEntity.class);
        when(matchActionRepository.save(actionCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        boolean handled = service.resolveLowCouplingDecision(100L, 10L, 2, match, pending, request);

        assertThat(handled).isTrue();
        verify(jdbcTemplate).update(
            contains("SET order_index = ?"),
            eq(7),
            eq(500L),
            eq(100L),
            eq(10L)
        );
        verify(pendingDecisionStore).markResolved(300L);
        verify(matchRepository).saveAndFlush(match);
        assertThat(match.getCurrentPhase()).isEqualTo(MatchPhase.MAIN.name());
        MatchActionEntity action = actionCaptor.getValue();
        assertThat(action.getActionType()).isEqualTo("INTERACTION_CONFIRMED");
        assertThat(action.getPayload()).contains("\"decisionType\":\"LOOK_TOP_DECK\"");
        assertThat(action.getPayload()).contains("\"placement\":\"BOTTOM\"");
    }

    @Test
    void resolveLowCouplingDecisionShouldReturnFalseForUnsupportedDecisionType() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        PendingDecision pending = pending("TRIGGER_EFFECT_CONFIRM", List.of(500L), 1);

        boolean handled = service.resolveLowCouplingDecision(100L, 10L, 2, match, pending, new ResolveDecisionRequest());

        assertThat(handled).isFalse();
        verify(pendingDecisionStore, never()).markResolved(any());
        verify(matchActionRepository, never()).save(any());
    }

    @Test
    void resolveLowCouplingDecisionShouldResolveDrawRevealToCheerWhenTurnCheerAvailable() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        PendingDecision pending = pending("DRAW_REVEAL", List.of(), 0);
        when(jdbcTemplate.queryForObject(
            contains("zone = 'CHEER_DECK'"),
            eq(Integer.class),
            eq(100L),
            eq(10L)
        )).thenReturn(1);
        when(jdbcTemplate.queryForObject(
            contains("FROM match_holomems"),
            eq(Integer.class),
            eq(100L),
            eq(10L)
        )).thenReturn(2);

        boolean handled = service.resolveLowCouplingDecision(100L, 10L, 2, match, pending, new ResolveDecisionRequest());

        assertThat(handled).isTrue();
        verify(pendingDecisionStore).markResolved(300L);
        verify(mainStepGiftFollowupPayloadAppender, never()).append(any(), any(), any(), any(Integer.class));
        verify(matchTurnLifecycleService).confirmDrawRevealDecision(
            eq(match),
            eq(10L),
            eq(2),
            eq(300L),
            eq(MatchPhase.CHEER),
            eq(400L),
            eq("hBP01-001"),
            any()
        );
    }

    @Test
    void resolveLowCouplingDecisionShouldResolveDrawRevealToMainWithMainStepGiftFollowupWhenTurnCheerUnavailable() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        PendingDecision pending = pending("DRAW_REVEAL", List.of(), 0);
        when(jdbcTemplate.queryForObject(
            contains("zone = 'CHEER_DECK'"),
            eq(Integer.class),
            eq(100L),
            eq(10L)
        )).thenReturn(0);

        boolean handled = service.resolveLowCouplingDecision(100L, 10L, 2, match, pending, new ResolveDecisionRequest());

        assertThat(handled).isTrue();
        verify(pendingDecisionStore).markResolved(300L);
        verify(mainStepGiftFollowupPayloadAppender).append(any(), eq(100L), eq(10L), eq(2));
        verify(matchTurnLifecycleService).confirmDrawRevealDecision(
            eq(match),
            eq(10L),
            eq(2),
            eq(300L),
            eq(MatchPhase.MAIN),
            eq(400L),
            eq("hBP01-001"),
            any()
        );
    }

    @Test
    void resolveLowCouplingDecisionShouldResolveTurnSendCheerAndWriteTurnCheerAction() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentPhase(MatchPhase.CHEER.name());
        PendingDecision pending = pending("SEND_CHEER", "TURN_CHEER", List.of(800L), 1);
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setSelectedCardInstanceIds(List.of(800L));
        when(jdbcTemplate.query(
            contains("FROM match_holomems"),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(10L),
            eq(800L)
        )).thenReturn(900L);
        when(jdbcTemplate.queryForList(
            contains("FROM match_cards"),
            eq(400L),
            eq(100L),
            eq(10L)
        )).thenReturn(List.of(Map.of("id", 400L, "card_id", "hY01-001", "zone", "CHEER_DECK")));
        when(jdbcTemplate.queryForObject(
            contains("FROM cheer_cards"),
            eq(Integer.class),
            eq("hY01-001")
        )).thenReturn(1);
        when(gameActionExecutor.execute(any(EffectContext.class), anyList()))
            .thenReturn(List.of(ActionResult.success("SEND_CHEER", Map.of())));
        when(matchActionRepository.findMaxActionOrderByTurn(100L, 2)).thenReturn(3);
        ArgumentCaptor<MatchActionEntity> actionCaptor = ArgumentCaptor.forClass(MatchActionEntity.class);
        when(matchActionRepository.save(actionCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        boolean handled = service.resolveLowCouplingDecision(100L, 10L, 2, match, pending, request);

        assertThat(handled).isTrue();
        verify(gameActionExecutor).execute(any(EffectContext.class), anyList());
        verify(pendingDecisionStore).markResolved(300L);
        verify(matchRepository).saveAndFlush(match);
        assertThat(match.getCurrentPhase()).isEqualTo(MatchPhase.MAIN.name());
        verify(mainStepGiftFollowupPayloadAppender).append(any(), eq(100L), eq(10L), eq(2));
        assertThat(actionCaptor.getAllValues())
            .extracting(MatchActionEntity::getActionType)
            .containsExactly("INTERACTION_CONFIRMED", "TURN_CHEER");
        assertThat(actionCaptor.getAllValues().get(0).getPayload()).contains("\"interactionType\":\"SEND_CHEER\"");
        assertThat(actionCaptor.getAllValues().get(1).getPayload()).contains("\"targetHolomemCardInstanceId\":800");
    }

    @Test
    void resolveLowCouplingDecisionShouldResolveLiveStartThroughLifecycleService() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setPlayerAId(10L);
        match.setPlayerBId(20L);
        PendingDecision pending = pending("LIVE_START", "LIVE_START", List.of(), 0);

        boolean handled = service.resolveLowCouplingDecision(100L, 10L, 1, match, pending, new ResolveDecisionRequest());

        assertThat(handled).isTrue();
        verify(pendingDecisionStore).markResolved(300L);
        verify(matchTurnLifecycleService).confirmLiveStartDecision(match, 10L, 1, 300L);
        verify(matchActionRepository, never()).save(any());
    }

    @Test
    void resolveLowCouplingDecisionShouldResolveTurnStartThroughLifecycleService() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        PendingDecision pending = pending("TURN_START", "TURN_START", List.of(), 0);

        boolean handled = service.resolveLowCouplingDecision(100L, 10L, 2, match, pending, new ResolveDecisionRequest());

        assertThat(handled).isTrue();
        verify(pendingDecisionStore).markResolved(300L);
        verify(matchTurnStartCollabReturnService).returnCollabToBackAsRested(100L, 10L);
        verify(matchTurnLifecycleService).confirmTurnStartDecision(match, 10L, 2, 300L);
        verify(matchActionRepository, never()).save(any());
    }

    private PendingDecision pending(String decisionType, List<Long> candidates, int maxSelect) {
        return pending(decisionType, "PLAY_SUPPORT", candidates, maxSelect);
    }

    private PendingDecision pending(String decisionType, String sourceActionType, List<Long> candidates, int maxSelect) {
        return new PendingDecision(
            300L,
            decisionType,
            sourceActionType,
            400L,
            "hBP01-001",
            decisionType,
            0,
            maxSelect,
            null,
            null,
            null,
            candidates,
            false,
            new ObjectMapper().nullNode()
        );
    }
}
