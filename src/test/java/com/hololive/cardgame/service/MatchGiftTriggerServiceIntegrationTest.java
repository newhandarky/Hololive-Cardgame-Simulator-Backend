package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MatchGiftTriggerServiceIntegrationTest extends MatchIntegrationTestSupport {

    @Test
    void previewGiftTriggeredEffectsShouldRouteStageEnterAndBatonTouchBackToCanonicalTriggerTypes() {
        StartedMatchContext context = createStartedMatch("gift-routing-host", "gift-routing-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        int turnNumber = loadTurnNumber(matchId);

        String stageEnterHolderCardId = createGeneratedMemberCardDefinition(
            "TGIFT_ROUTE_ENTER_HOLDER",
            "進場 routing holder",
            "DEBUT",
            150,
            "YELLOW",
            "{\"キーワード\":\"ギフト正義的進場測試 \\n[センターポジション・コラボポジション限定][ターンに1回]自分の#Justiceを持つ[DebutホロメンかSpotホロメン]がステージに出た時、自分のデッキを1枚引く。\"}"
        );
        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        replaceStageHolomemCard(matchId, hostId, hostCenterCardInstanceId, stageEnterHolderCardId, "DEBUT");

        String enteredCardId = createGeneratedMemberCardDefinition(
            "TGIFT_ROUTE_ENTER_SRC",
            "Justice Debut",
            "DEBUT",
            120,
            "BLUE"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#Justice\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            enteredCardId
        );
        Long enteredCardInstanceId = createStageHolomemCard(matchId, hostId, enteredCardId, "BACK", "DEBUT", turnNumber);

        List<Map<String, Object>> stageEnterPreview = matchGiftTriggerService.previewGiftTriggeredEffectsOnStageEnter(
            matchId,
            hostId,
            enteredCardInstanceId,
            "BACK",
            turnNumber
        );

        assertThat(stageEnterPreview).singleElement().satisfies(summary -> {
            assertThat(summary.get("triggerType")).isEqualTo("STAGE_ENTER");
            assertThat(summary.get("giftHolderCardId")).isEqualTo(stageEnterHolderCardId);
            assertThat(summary.get("giftHolderZone")).isEqualTo("CENTER");
            assertThat(summary.get("sourceCardInstanceId")).isEqualTo(enteredCardInstanceId);
            assertThat((List<String>) summary.get("requestedEffects")).contains("DRAW");
        });

        String batonTouchHolderCardId = createGeneratedMemberCardDefinition(
            "TGIFT_ROUTE_BATON_HOLDER",
            "後排 routing holder",
            "DEBUT",
            140,
            "GREEN",
            "{\"キーワード\":\"ギフト退到後排抽牌 \\nこのホロメンがバトンタッチしてバックポジションに移動した時、自分のデッキを1枚引く。\"}"
        );
        Long batonTouchHolderCardInstanceId = createStageHolomemCard(
            matchId,
            hostId,
            batonTouchHolderCardId,
            "BACK",
            "DEBUT",
            turnNumber
        );

        List<Map<String, Object>> batonTouchPreview = matchGiftTriggerService.previewGiftTriggeredEffectsOnBatonTouchBack(
            matchId,
            hostId,
            batonTouchHolderCardInstanceId,
            turnNumber
        );

        assertThat(batonTouchPreview).singleElement().satisfies(summary -> {
            assertThat(summary.get("triggerType")).isEqualTo("BATON_TOUCH_BACK");
            assertThat(summary.get("giftHolderCardId")).isEqualTo(batonTouchHolderCardId);
            assertThat(summary.get("giftHolderZone")).isEqualTo("BACK");
            assertThat(summary.get("sourceCardInstanceId")).isEqualTo(batonTouchHolderCardInstanceId);
            assertThat((List<String>) summary.get("requestedEffects")).contains("DRAW");
        });
    }

    @Test
    void applyGiftTriggeredEffectsShouldNormalizePerformanceStartAliasesForDirectAndStoredRoutes() {
        StartedMatchContext context = createStartedMatch("gift-alias-host", "gift-alias-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        int turnNumber = loadTurnNumber(matchId);

        String performanceGiftText = "{\"キーワード\":\"ギフト自己的表演開始 \\n自分のパフォーマンスステップが開始する時、自分のデッキを1枚引く。\"}";
        String performanceHolderCardId = createGeneratedMemberCardDefinition(
            "TGIFT_ALIAS_PERF",
            "表演開始 alias holder",
            "DEBUT",
            140,
            "BLUE",
            performanceGiftText
        );
        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        replaceStageHolomemCard(matchId, hostId, hostCenterCardInstanceId, performanceHolderCardId, "DEBUT");
        Long performanceHolderHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, hostCenterCardInstanceId);

        int deckBeforeDirect = countZone(matchId, hostId, "DECK");
        Map<String, Object> directSummary = matchGiftTriggerService.applySingleGiftTriggeredEffect(
            matchId,
            hostId,
            "OWN_PERFORMANCE_START",
            hostCenterCardInstanceId,
            hostCenterCardInstanceId,
            turnNumber,
            performanceHolderHolomemId
        );

        assertThat(directSummary).isNotNull().isNotEmpty();
        assertThat(directSummary.get("triggerType")).isEqualTo("PERFORMANCE_START_SELF");
        assertThat(directSummary.get("giftHolderCardId")).isEqualTo(performanceHolderCardId);
        assertThat(countZone(matchId, hostId, "DECK")).isEqualTo(deckBeforeDirect - 1);

        Map<String, Object> holderSnapshot = matchGiftTriggerService.loadGiftHolderSnapshot(
            matchId,
            hostId,
            performanceHolderHolomemId
        );
        Map<String, Object> storedTrigger = new LinkedHashMap<>();
        storedTrigger.put("giftHolderHolomemId", holderSnapshot.get("holomem_id"));
        storedTrigger.put("giftHolderCardInstanceId", holderSnapshot.get("match_card_id"));
        storedTrigger.put("giftHolderCardId", holderSnapshot.get("card_id"));
        storedTrigger.put("giftHolderZone", holderSnapshot.get("zone"));
        storedTrigger.put("rawText", performanceGiftText);

        int deckBeforeStored = countZone(matchId, hostId, "DECK");
        Map<String, Object> storedSummary = matchGiftTriggerService.applyStoredGiftTriggeredEffect(
            matchId,
            hostId,
            "PERFORMANCE_START",
            hostCenterCardInstanceId,
            hostCenterCardInstanceId,
            storedTrigger
        );

        assertThat(storedSummary).isNotNull().isNotEmpty();
        assertThat(storedSummary.get("triggerType")).isEqualTo("PERFORMANCE_START_SELF");
        assertThat(storedSummary.get("giftHolderCardId")).isEqualTo(performanceHolderCardId);
        assertThat(countZone(matchId, hostId, "DECK")).isEqualTo(deckBeforeStored - 1);
    }

    @Override
    protected void executeRequiredTurnActions(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        resolvePendingInteractionIfExists(matchId, userId, "TURN_START");
        try {
            executeDrawTurn(matchId, userId);
        } catch (IllegalStateException | GameRuleException ex) {
            if (ex instanceof GameRuleException gameRuleException
                && gameRuleException.getCode() == GameErrorCode.TURN_DRAW_ALREADY_USED) {
                // Keep going; turn cheer may still be required.
            } else {
                String message = ex.getMessage();
                if (message != null && message.contains("phase=END")) {
                    return;
                }
                if (message != null && message.contains("已經抽過卡")) {
                    // Keep going; turn cheer may still be required.
                } else {
                    throw ex;
                }
            }
        }
        resolvePendingInteractionIfExists(matchId, userId, "DRAW_REVEAL");
        try {
            matchActionService.sendTurnCheer(matchId, userId);
        } catch (IllegalStateException | GameRuleException ex) {
            if (ex instanceof GameRuleException gameRuleException
                && gameRuleException.getCode() == GameErrorCode.TURN_CHEER_ALREADY_USED) {
                return;
            }
            String message = ex.getMessage();
            if (message != null && message.contains("目前無法發送吶喊")) {
                return;
            }
            if (message != null && message.contains("已經發送過吶喊")) {
                return;
            }
            throw ex;
        }
        Long sendCheerDecisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'SEND_CHEER'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (sendCheerDecisionId == null) {
            return;
        }
        Long effectiveTargetCardInstanceId = sendCheerTargetCardInstanceId == null
            ? loadFirstStageCardInstanceId(matchId, userId)
            : sendCheerTargetCardInstanceId;
        if (effectiveTargetCardInstanceId == null) {
            return;
        }
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setDecisionId(sendCheerDecisionId);
        request.setSelectedCardInstanceIds(List.of(effectiveTargetCardInstanceId));
        matchActionService.resolveDecision(matchId, userId, request);
    }

    private int loadTurnNumber(Long matchId) {
        Integer turnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        return turnNumber == null ? 1 : turnNumber;
    }

    private void replaceStageHolomemCard(
        Long matchId,
        Long ownerUserId,
        Long cardInstanceId,
        String cardId,
        String levelType
    ) {
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            cardId,
            matchId,
            ownerUserId,
            cardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            cardId,
            levelType,
            matchId,
            ownerUserId,
            cardInstanceId
        );
    }

    private Long createStageHolomemCard(
        Long matchId,
        Long ownerUserId,
        String cardId,
        String zone,
        String currentLevel,
        int enteredTurnNumber
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'STAGE', NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            ownerUserId,
            cardId
        );
        Long cardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
              AND zone = 'STAGE'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            cardId
        );
        Long holomemId = jdbcTemplate.query(
            """
            INSERT INTO match_holomems (
                match_id,
                owner_user_id,
                match_card_id,
                card_id,
                zone,
                is_rested,
                is_face_down,
                damage_taken,
                current_level,
                entered_turn_number
            ) VALUES (?, ?, ?, ?, ?, FALSE, FALSE, 0, ?, ?)
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            cardInstanceId,
            cardId,
            zone,
            currentLevel,
            enteredTurnNumber
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_stack_cards (match_holomem_id, match_card_id, stack_order)
            VALUES (?, ?, 1)
            """,
            holomemId,
            cardInstanceId
        );
        return cardInstanceId;
    }
}
