package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.service.AttackFinishCheckResult.FinishType;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AttackFinishCheckServiceTest {

    private final AttackFinishCheckService.FinishEvaluator cardEffectFinishEvaluator =
        mock(AttackFinishCheckService.FinishEvaluator.class);
    private final AttackFinishCheckService.FinishEvaluator lifeDefeatEvaluator =
        mock(AttackFinishCheckService.FinishEvaluator.class);
    private final AttackFinishCheckService.FinishEvaluator noHolomemDefeatEvaluator =
        mock(AttackFinishCheckService.FinishEvaluator.class);
    private final AttackFinishCheckService.EffectSummaryPredicate lifeReducedPredicate =
        mock(AttackFinishCheckService.EffectSummaryPredicate.class);
    private final AttackFinishCheckService.EffectSummaryPredicate holomemDownedPredicate =
        mock(AttackFinishCheckService.EffectSummaryPredicate.class);
    private final AttackFinishCheckService.MatchSaver matchSaver =
        mock(AttackFinishCheckService.MatchSaver.class);
    private final AttackFinishCheckService service = new AttackFinishCheckService(
        cardEffectFinishEvaluator,
        lifeDefeatEvaluator,
        noHolomemDefeatEvaluator,
        lifeReducedPredicate,
        holomemDownedPredicate,
        matchSaver
    );

    @Test
    void resolveShouldStopAfterCardEffectFinish() {
        AttackFinishCheckContext context = context();
        when(cardEffectFinishEvaluator.evaluate(
            context.match(),
            context.actorUserId(),
            context.turnNumber(),
            context.effectSummaryForChecks()
        )).thenReturn(true);

        AttackFinishCheckResult result = service.resolve(context);

        assertThat(result.finished()).isTrue();
        assertThat(result.finishType()).isEqualTo(FinishType.CARD_EFFECT);
        assertThat(result.saved()).isTrue();
        verify(lifeReducedPredicate, never()).matches(context.effectSummaryForChecks());
        verify(lifeDefeatEvaluator, never()).evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks());
        verify(noHolomemDefeatEvaluator, never()).evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks());
        verify(matchSaver).save(context.match());
    }

    @Test
    void resolveShouldSkipLifeEvaluatorWhenLifeWasNotReduced() {
        AttackFinishCheckContext context = context();
        when(lifeReducedPredicate.matches(context.effectSummaryForChecks())).thenReturn(false);
        when(holomemDownedPredicate.matches(context.effectSummaryForChecks())).thenReturn(false);

        AttackFinishCheckResult result = service.resolve(context);

        assertThat(result.finished()).isFalse();
        assertThat(result.finishType()).isEqualTo(FinishType.NONE);
        verify(lifeDefeatEvaluator, never()).evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks());
        verify(matchSaver, never()).save(context.match());
    }

    @Test
    void resolveShouldStopAfterLifeDefeatFinish() {
        AttackFinishCheckContext context = context();
        when(lifeReducedPredicate.matches(context.effectSummaryForChecks())).thenReturn(true);
        when(lifeDefeatEvaluator.evaluate(
            context.match(),
            context.actorUserId(),
            context.turnNumber(),
            context.effectSummaryForChecks()
        )).thenReturn(true);

        AttackFinishCheckResult result = service.resolve(context);

        assertThat(result.finished()).isTrue();
        assertThat(result.finishType()).isEqualTo(FinishType.LIFE_DEFEAT);
        verify(holomemDownedPredicate, never()).matches(context.effectSummaryForChecks());
        verify(noHolomemDefeatEvaluator, never()).evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks());
        verify(matchSaver).save(context.match());
    }

    @Test
    void resolveShouldSkipNoHolomemEvaluatorWhenHolomemWasNotDowned() {
        AttackFinishCheckContext context = context();
        when(lifeReducedPredicate.matches(context.effectSummaryForChecks())).thenReturn(false);
        when(holomemDownedPredicate.matches(context.effectSummaryForChecks())).thenReturn(false);

        AttackFinishCheckResult result = service.resolve(context);

        assertThat(result.finished()).isFalse();
        verify(noHolomemDefeatEvaluator, never()).evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks());
        verify(matchSaver, never()).save(context.match());
    }

    @Test
    void resolveShouldReturnNoHolomemDefeatWhenNoHolomemEvaluatorFinishes() {
        AttackFinishCheckContext context = context();
        when(lifeReducedPredicate.matches(context.effectSummaryForChecks())).thenReturn(false);
        when(holomemDownedPredicate.matches(context.effectSummaryForChecks())).thenReturn(true);
        when(noHolomemDefeatEvaluator.evaluate(
            context.match(),
            context.actorUserId(),
            context.turnNumber(),
            context.effectSummaryForChecks()
        )).thenReturn(true);

        AttackFinishCheckResult result = service.resolve(context);

        assertThat(result.finished()).isTrue();
        assertThat(result.finishType()).isEqualTo(FinishType.NO_HOLOMEM_DEFEAT);
        verify(matchSaver).save(context.match());
    }

    @Test
    void resolveShouldEvaluateInExpectedOrder() {
        AttackFinishCheckContext context = context();
        when(lifeReducedPredicate.matches(context.effectSummaryForChecks())).thenReturn(true);
        when(lifeDefeatEvaluator.evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks()))
            .thenReturn(false);
        when(holomemDownedPredicate.matches(context.effectSummaryForChecks())).thenReturn(true);
        when(noHolomemDefeatEvaluator.evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks()))
            .thenReturn(false);

        service.resolve(context);

        InOrder inOrder = inOrder(
            cardEffectFinishEvaluator,
            lifeReducedPredicate,
            lifeDefeatEvaluator,
            holomemDownedPredicate,
            noHolomemDefeatEvaluator
        );
        inOrder.verify(cardEffectFinishEvaluator).evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks());
        inOrder.verify(lifeReducedPredicate).matches(context.effectSummaryForChecks());
        inOrder.verify(lifeDefeatEvaluator).evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks());
        inOrder.verify(holomemDownedPredicate).matches(context.effectSummaryForChecks());
        inOrder.verify(noHolomemDefeatEvaluator).evaluate(context.match(), context.actorUserId(), context.turnNumber(), context.effectSummaryForChecks());
    }

    @Test
    void resolveShouldRejectMissingContext() {
        assertThatThrownBy(() -> service.resolve(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attack finish check");
    }

    private AttackFinishCheckContext context() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        return AttackFinishCheckContext.attackArt(
            match,
            10L,
            3,
            "effect-summary"
        );
    }
}
