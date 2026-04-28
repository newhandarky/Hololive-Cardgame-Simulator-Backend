package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class AttackArtApplicationAdapterFactoryTest {

    private final AttackCostService attackCostService = mock(AttackCostService.class);
    private final AttackTargetService attackTargetService = mock(AttackTargetService.class);
    private final AttackDamageService attackDamageService = mock(AttackDamageService.class);
    private final AttackDamageApplicationService attackDamageApplicationService =
        mock(AttackDamageApplicationService.class);
    private final AttackDownService attackDownService = mock(AttackDownService.class);
    private final AttackDefenderGiftFollowupService attackDefenderGiftFollowupService =
        mock(AttackDefenderGiftFollowupService.class);
    private final AttackPostTriggerPendingService attackPostTriggerPendingService =
        mock(AttackPostTriggerPendingService.class);
    private final AttackActionLogService attackActionLogService = mock(AttackActionLogService.class);
    private final AttackPayloadJsonService attackPayloadJsonService = mock(AttackPayloadJsonService.class);
    private final AttackPendingDecisionConversionService attackPendingDecisionConversionService =
        new AttackPendingDecisionConversionService();
    private final AttackEffectSummaryExtractor attackEffectSummaryExtractor = new AttackEffectSummaryExtractor();
    private final AttackFinishCheckService attackFinishCheckService = mock(AttackFinishCheckService.class);
    private final AttackEffectFollowupService attackEffectFollowupService = mock(AttackEffectFollowupService.class);
    private final AttackPerformanceAvailabilityService attackPerformanceAvailabilityService =
        mock(AttackPerformanceAvailabilityService.class);
    private final MatchTimestampService matchTimestampService = mock(MatchTimestampService.class);
    private final MatchEffectCombatModifierService matchEffectCombatModifierService =
        mock(MatchEffectCombatModifierService.class);
    private final MatchGiftTriggerService matchGiftTriggerService = mock(MatchGiftTriggerService.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final AttackRestAndPayloadService attackRestAndPayloadService = new AttackRestAndPayloadService();

    @Test
    void adapterShouldRestAndSavePhaseBeforeActionLogThenFinishCheck() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        stubDefaultAttackFlow();
        AttackArtApplicationService service = factory().create();

        service.execute(context(match));

        assertThat(match.getCurrentPhase()).isEqualTo(MatchPhase.PERFORMANCE.name());

        InOrder order = inOrder(
            jdbcTemplate,
            attackPerformanceAvailabilityService,
            matchTimestampService,
            matchRepository,
            attackPayloadJsonService,
            attackActionLogService,
            attackFinishCheckService
        );
        order.verify(jdbcTemplate).update(
            contains("UPDATE match_holomems"),
            eq(501L),
            eq(100L),
            eq(10L)
        );
        order.verify(attackPerformanceAvailabilityService).hasAvailableArtAttacker(100L, 10L, 3);
        order.verify(matchTimestampService).touchUpdatedAt(match);
        order.verify(matchRepository).saveAndFlush(match);
        order.verify(attackPayloadJsonService).toJson(any());
        order.verify(attackActionLogService).appendAttackArt(any(AttackActionLogContext.class));
        order.verify(attackFinishCheckService).resolve(any(AttackFinishCheckContext.class));
    }

    private void stubDefaultAttackFlow() {
        Map<String, Integer> emptyCost = Map.of();
        when(attackEffectFollowupService.resolvePreDamage(any())).thenReturn(
            new AttackEffectFollowupResult(null, Map.of(), Map.of(), 0)
        );
        when(attackCostService.parseCost("{}")).thenReturn(emptyCost);
        when(matchEffectCombatModifierService.resolvePassiveGiftArtCheerCostReduction(100L, 10L, 501L, "雨のマントラ"))
            .thenReturn(emptyCost);
        when(attackCostService.applyReduction(emptyCost, emptyCost)).thenReturn(emptyCost);
        when(attackCostService.resolvePayment(any())).thenReturn(
            new AttackCostPaymentResult(
                emptyCost,
                emptyCost,
                emptyCost,
                0,
                emptyCost,
                0,
                List.of(),
                List.of(),
                List.of(),
                false
            )
        );
        when(attackTargetService.resolveTarget(any())).thenReturn(AttackTargetResult.noOpponent(4001L));
        when(attackDamageService.resolveDamage(any())).thenReturn(
            new AttackDamageResult(50, 0, 0, 0, 0, 0, null, 0, false, 0, 0, 0, 0, 50)
        );
        when(attackEffectFollowupService.resolveDamagePrevention(any())).thenReturn(
            AttackEffectDamagePreventionResult.unchanged(50)
        );
        when(attackDamageApplicationService.applyDamage(any())).thenReturn(
            new AttackDamageApplicationResult(Map.of("effectType", "ART_DAMAGE", "damage", 50), null)
        );
        when(attackEffectFollowupService.resolvePostDamage(any())).thenReturn(
            new AttackEffectPostDamageResult(Map.of(), List.of(), Map.of(), List.of())
        );
        when(attackDownService.resolveDown(any())).thenReturn(
            new AttackDownResult(Map.of(), false, List.of(), Map.of(), null)
        );
        when(attackDefenderGiftFollowupService.resolveFollowup(any())).thenReturn(
            new AttackDefenderGiftFollowupResult(Map.of(), List.of(), null, null)
        );
        when(attackPostTriggerPendingService.resolvePending(any())).thenReturn(
            new AttackPostTriggerPendingResult(Map.of(), null, Map.of(), null)
        );
        when(attackPerformanceAvailabilityService.hasAvailableArtAttacker(100L, 10L, 3)).thenReturn(false);
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);
        when(attackPayloadJsonService.toJson(any())).thenReturn("{\"artTotalDamage\":50}");
        when(attackActionLogService.appendAttackArt(any())).thenReturn(
            new AttackActionLogResult(7001L, 1, AttackActionLogService.ACTION_TYPE_ATTACK_ART, "{}")
        );
        when(attackFinishCheckService.resolve(any())).thenReturn(AttackFinishCheckResult.none());
    }

    private AttackArtApplicationAdapterFactory factory() {
        return new AttackArtApplicationAdapterFactory(
            attackCostService,
            attackTargetService,
            attackDamageService,
            attackDamageApplicationService,
            attackDownService,
            attackDefenderGiftFollowupService,
            attackPostTriggerPendingService,
            attackRestAndPayloadService,
            attackActionLogService,
            attackPayloadJsonService,
            attackPendingDecisionConversionService,
            attackEffectSummaryExtractor,
            attackFinishCheckService,
            attackEffectFollowupService,
            attackPerformanceAvailabilityService,
            matchTimestampService,
            matchEffectCombatModifierService,
            matchGiftTriggerService,
            jdbcTemplate,
            matchRepository
        );
    }

    private AttackArtApplicationContext context(MatchEntity match) {
        return AttackArtApplicationContext.attackArt(
            match,
            100L,
            10L,
            20L,
            3,
            3001L,
            4001L,
            501L,
            "CENTER",
            "HBP01-087",
            "SECOND",
            "BLUE",
            "雨のマントラ",
            1,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":50}",
            null,
            Map.of(),
            List.of()
        );
    }
}
