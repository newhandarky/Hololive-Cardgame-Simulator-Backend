package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;

class MatchActionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchActionService service = new MatchActionService(
        mock(MatchRepository.class),
        mock(MatchPlayerRepository.class),
        mock(MatchActionRepository.class),
        jdbcTemplate,
        new ObjectMapper(),
        mock(MatchEffectService.class),
        mock(MatchEffectCombatModifierService.class),
        mock(MatchTriggeredCombatEffectService.class),
        mock(MatchTurnEffectMaintenanceService.class),
        mock(MatchTurnLifecycleService.class),
        mock(EndTurnApplicationService.class),
        mock(BloomApplicationService.class),
        mock(CollabApplicationService.class),
        mock(AttachCheerApplicationService.class),
        mock(PlayCardApplicationService.class),
        mock(CollabEffectResolutionService.class),
        mock(BloomEffectResolutionService.class),
        mock(PlayCardEffectResolutionService.class),
        mock(AttackCostService.class),
        mock(AttackTargetService.class),
        mock(AttackDamageService.class),
        mock(AttackDamageApplicationService.class),
        mock(AttackDownService.class),
        mock(AttackDefenderGiftFollowupService.class),
        mock(MatchPhaseAdvanceGiftTransitionService.class),
        mock(MatchTriggeredCardEffectService.class),
        mock(MatchGiftTriggerService.class),
        mock(MatchTriggeredGiftResolutionService.class),
        mock(MatchTriggeredEffectResolutionService.class),
        mock(MatchEventHookService.class),
        mock(GameActionExecutor.class),
        mock(DiceService.class)
    );

    @Test
    void createTriggeredEffectConfirmPendingInteractionShouldDelegateAdditionalContextBounds() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        whenInsertReturns(902L);

        FollowupInteractionDecision decision = ReflectionTestUtils.invokeMethod(
            service,
            "createTriggeredEffectConfirmPendingInteraction",
            100L,
            10L,
            "BLOOM",
            701L,
            "hBP01-001",
            "BLOOM_EFFECT",
            "確認 Bloom 效果",
            "confirm bloom?",
            null,
            4,
            Map.of("minSelect", 1, "maxSelect", 2, "sourceLevelType", "DEBUT")
        );

        assertThat(decision).isEqualTo(new FollowupInteractionDecision(902L, "TRIGGER_EFFECT_CONFIRM"));
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(
            anyString(),
            any(ResultSetExtractor.class),
            argsCaptor.capture()
        );
        assertThat(argsCaptor.getValue()).containsExactly(
            100L,
            10L,
            "TRIGGER_EFFECT_CONFIRM",
            "BLOOM",
            701L,
            "hBP01-001",
            "BLOOM_EFFECT",
            1,
            2,
            "PENDING",
            argsCaptor.getValue()[10]
        );
        assertThat((String) argsCaptor.getValue()[10])
            .contains("\"sourceActionType\":\"BLOOM\"")
            .contains("\"message\":\"confirm bloom?\"")
            .contains("\"cards\":[]")
            .contains("\"sourceLevelType\":\"DEBUT\"")
            .contains("\"turnNumber\":4");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void whenInsertReturns(Long decisionId) throws Exception {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
            .thenAnswer(invocation -> {
                ResultSetExtractor extractor = invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(decisionId != null);
                when(rs.getLong("id")).thenReturn(decisionId == null ? 0L : decisionId);
                return extractor.extractData(rs);
            });
    }
}
