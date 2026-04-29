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
import java.util.List;
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
    void createGiftTriggeredEffectConfirmPendingInteractionShouldKeepLegacyGiftPendingContext() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        whenInsertReturns(901L);

        Map<String, Object> card = Map.of("cardInstanceId", 701L, "cardId", "hBP01-001", "zone", "BACK");
        Map<String, Object> trigger = Map.of(
            "triggerType",
            "STAGE_ENTER",
            "giftHolderCardInstanceId",
            801L,
            "giftHolderCardId",
            "hBP06-014",
            "giftHolderZone",
            "BACK",
            "requestedEffects",
            List.of("DRAW"),
            "rawText",
            "gift text"
        );

        FollowupInteractionDecision decision = ReflectionTestUtils.invokeMethod(
            service,
            "createGiftTriggeredEffectConfirmPendingInteraction",
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(card),
            List.of(trigger),
            4
        );

        assertThat(decision).isEqualTo(new FollowupInteractionDecision(901L, "TRIGGER_EFFECT_CONFIRM"));
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
            "GIFT",
            701L,
            "hBP01-001",
            "GIFT_TRIGGER",
            0,
            0,
            "PENDING",
            argsCaptor.getValue()[10]
        );
        assertThat((String) argsCaptor.getValue()[10])
            .contains("\"interactionType\":\"TRIGGER_EFFECT_CONFIRM\"")
            .contains("\"sourceActionType\":\"GIFT\"")
            .contains("\"title\":\"確認 Gift 效果\"")
            .contains("\"message\":\"是否要執行本次 Gift 觸發效果？\\n#1 hBP06-014 [STAGE_ENTER] 效果類型：DRAW\\ngift text\"")
            .contains("\"cards\":[{")
            .contains("\"cardInstanceId\":701")
            .contains("\"cardId\":\"hBP01-001\"")
            .contains("\"zone\":\"BACK\"")
            .contains("\"giftTriggers\"")
            .contains("\"giftCount\":1")
            .contains("\"turnNumber\":4");
    }

    @Test
    void createGiftTriggeredEffectConfirmPendingInteractionShouldKeepBatonTouchSourceCardContext() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        whenInsertReturns(904L);

        Map<String, Object> card = Map.of("cardInstanceId", 701L, "cardId", "hBP01-001", "zone", "BACK");
        Map<String, Object> trigger = Map.of(
            "triggerType",
            "BATON_TOUCH_BACK",
            "giftHolderCardInstanceId",
            701L,
            "giftHolderCardId",
            "hBP01-001",
            "giftHolderZone",
            "BACK",
            "requestedEffects",
            List.of("DRAW"),
            "rawText",
            "baton gift text"
        );

        FollowupInteractionDecision decision = ReflectionTestUtils.invokeMethod(
            service,
            "createGiftTriggeredEffectConfirmPendingInteraction",
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(card),
            List.of(trigger),
            4
        );

        assertThat(decision).isEqualTo(new FollowupInteractionDecision(904L, "TRIGGER_EFFECT_CONFIRM"));
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
            "GIFT",
            701L,
            "hBP01-001",
            "GIFT_TRIGGER",
            0,
            0,
            "PENDING",
            argsCaptor.getValue()[10]
        );
        assertThat((String) argsCaptor.getValue()[10])
            .contains("\"sourceActionType\":\"GIFT\"")
            .contains("\"cardInstanceId\":701")
            .contains("\"cardId\":\"hBP01-001\"")
            .contains("\"zone\":\"BACK\"")
            .contains("\"triggerType\":\"BATON_TOUCH_BACK\"")
            .contains("\"giftHolderCardInstanceId\":701")
            .contains("\"giftCount\":1")
            .contains("\"turnNumber\":4");
    }

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

    @Test
    @SuppressWarnings("unchecked")
    void buildGiftTriggerInteractionCardsShouldIncludeSourceAndGiftHoldersWithFallbackZones() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(), any(), any())).thenReturn(null);

        Map<String, Object> backGiftTrigger = Map.of(
            "giftHolderCardInstanceId",
            801L,
            "giftHolderCardId",
            "hBP06-014",
            "giftHolderZone",
            "BACK"
        );
        Map<String, Object> defaultZoneGiftTrigger = Map.of(
            "giftHolderCardInstanceId",
            802L,
            "giftHolderCardId",
            "hBP06-015",
            "giftHolderZone",
            ""
        );

        List<Map<String, Object>> cards = ReflectionTestUtils.invokeMethod(
            service,
            "buildGiftTriggerInteractionCards",
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(backGiftTrigger, defaultZoneGiftTrigger)
        );

        assertThat(cards).hasSize(3);
        assertThat(cards.get(0))
            .containsEntry("cardInstanceId", 701L)
            .containsEntry("cardId", "hBP01-001")
            .containsEntry("zone", "STAGE");
        assertThat(cards.get(1))
            .containsEntry("cardInstanceId", 801L)
            .containsEntry("cardId", "hBP06-014")
            .containsEntry("zone", "BACK");
        assertThat(cards.get(2))
            .containsEntry("cardInstanceId", 802L)
            .containsEntry("cardId", "hBP06-015")
            .containsEntry("zone", "STAGE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildGiftTriggerInteractionCardsShouldDedupeAndSkipInvalidGiftHolders() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(), any(), any())).thenReturn(null);

        Map<String, Object> sameAsSourceTrigger = Map.of(
            "giftHolderCardInstanceId",
            701L,
            "giftHolderCardId",
            "hBP01-001",
            "giftHolderZone",
            "CENTER"
        );
        Map<String, Object> holderTrigger = Map.of(
            "giftHolderCardInstanceId",
            801L,
            "giftHolderCardId",
            "hBP06-014",
            "giftHolderZone",
            "BACK"
        );
        Map<String, Object> duplicateHolderTrigger = Map.of(
            "giftHolderCardInstanceId",
            801L,
            "giftHolderCardId",
            "hBP06-014",
            "giftHolderZone",
            "BACK"
        );
        Map<String, Object> invalidHolderTrigger = Map.of(
            "giftHolderCardInstanceId",
            0L,
            "giftHolderCardId",
            "hBP06-999",
            "giftHolderZone",
            "BACK"
        );

        List<Map<String, Object>> cards = ReflectionTestUtils.invokeMethod(
            service,
            "buildGiftTriggerInteractionCards",
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(sameAsSourceTrigger, holderTrigger, duplicateHolderTrigger, invalidHolderTrigger)
        );

        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(card -> card.get("cardInstanceId")).containsExactly(701L, 801L);
        assertThat(cards.get(0)).containsEntry("zone", "STAGE");
        assertThat(cards.get(1)).containsEntry("zone", "BACK");
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
