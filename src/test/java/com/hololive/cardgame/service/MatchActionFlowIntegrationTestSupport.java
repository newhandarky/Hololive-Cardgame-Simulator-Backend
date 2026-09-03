package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.dto.AttachCheerActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class MatchActionFlowIntegrationTestSupport extends MatchIntegrationTestSupport {

    protected Long playOpeningBack(Long matchId, Long userId) {
        Long memberCardInstanceId = findOpeningBackMemberFromHand(matchId, userId);
        assertThat(memberCardInstanceId).isNotNull();

        PlayToStageActionRequest play = new PlayToStageActionRequest();
        play.setCardInstanceId(memberCardInstanceId);
        play.setTargetZone("BACK");
        matchActionService.playToStage(matchId, userId, play);
        return memberCardInstanceId;
    }

    protected Long findOpeningBackMemberFromHand(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            JOIN member_cards m ON m.card_id = c.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'HAND'
              AND c.card_type = 'MEMBER'
              AND UPPER(COALESCE(m.level_type, '')) IN ('DEBUT', 'SPOT')
            ORDER BY CASE
                WHEN UPPER(COALESCE(m.level_type, '')) = 'DEBUT' THEN 0
                ELSE 1
            END, mc.order_index, mc.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
    }

    protected void clearAttachedStageCheers(Long matchId, Long userId) {
        jdbcTemplate.update(
            """
            DELETE FROM match_holomem_cheers
            WHERE match_holomem_id IN (
                SELECT id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
            )
            """,
            matchId,
            userId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards mc
            SET zone = 'ARCHIVE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'STAGE'
              AND EXISTS (
                  SELECT 1
                  FROM cheer_cards cc
                  WHERE cc.card_id = mc.card_id
              )
            """,
            matchId,
            userId
        );
    }

    protected void clearHandToArchive(Long matchId, Long userId) {
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            matchId,
            userId
        );
    }

    /**
     * 把手牌數量調整成測試需要的固定值。
     *
     * <p>Gift 條件常會直接讀目前 `HAND` 張數；若沿用開局預設手牌，測試會被抽牌、mulligan 或前置
     * setup 影響而變得不穩。這裡先清空手牌，再用同一張 filler 卡補到指定數量，讓條件測試只驗證
     * 我們在乎的門檻本身。
     */
    protected void setExactHandCount(Long matchId, Long userId, int targetCount, String cardPrefix) {
        clearHandToArchive(matchId, userId);
        if (targetCount <= 0) {
            return;
        }
        String fillerCardId = createMemberCardDefinition(
            cardPrefix,
            "手牌條件測試填充卡 " + cardPrefix,
            "DEBUT",
            90,
            "WHITE"
        );
        for (int i = 0; i < targetCount; i++) {
            insertCardIntoHand(matchId, userId, fillerCardId);
        }
    }

    @Override
    protected void executeRequiredTurnActions(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        resolvePendingInteractionIfExists(matchId, userId, "TURN_START");
        try {
            executeDrawTurn(matchId, userId);
        } catch (IllegalStateException | GameRuleException ex) {
            if (ex instanceof GameRuleException gameRuleException
                && gameRuleException.getCode() == GameErrorCode.TURN_DRAW_ALREADY_USED) {
                // keep going; turn cheer may still be required.
            } else {
            String message = ex.getMessage();
            if (message != null && message.contains("phase=END")) {
                return;
            }
            if (message != null && message.contains("已經抽過卡")) {
                // keep going; turn cheer may still be required.
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

    protected void advanceToPerformancePhase(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        executeRequiredTurnActions(matchId, userId, sendCheerTargetCardInstanceId);
        String phase = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        if ("MAIN".equals(phase)) {
            matchActionService.advancePhase(matchId, userId);
        }
    }

    protected void advanceToEndPhase(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        executeRequiredTurnActions(matchId, userId, sendCheerTargetCardInstanceId);
        while (true) {
            String phase = jdbcTemplate.queryForObject(
                "SELECT current_phase FROM matches WHERE id = ?",
                String.class,
                matchId
            );
            if ("END".equals(phase)) {
                return;
            }
            if (!"MAIN".equals(phase) && !"PERFORMANCE".equals(phase)) {
                throw new IllegalStateException("無法推進至 END，當前 phase=" + phase);
            }
            matchActionService.advancePhase(matchId, userId);
        }
    }

    protected Long findMemberCardFromHand(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            JOIN member_cards m ON m.card_id = c.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'HAND'
              AND c.card_type = 'MEMBER'
              AND UPPER(COALESCE(m.level_type, '')) = 'DEBUT'
            ORDER BY mc.order_index, mc.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
    }

    protected void seedHolopower(Long matchId, Long userId, int count) {
        if (count <= 0) {
            return;
        }
        List<Long> deckCardIds = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT ?
            """,
            (rs, rowNum) -> rs.getLong("id"),
            matchId,
            userId,
            count
        );
        for (int i = 0; i < deckCardIds.size(); i++) {
            jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'HOLOPOWER',
                    order_index = ?,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'DECK'
                """,
                i + 1,
                deckCardIds.get(i),
                matchId,
                userId
            );
        }
    }

    protected Long createStageHolomemWithArtAndCheer(
        Long matchId,
        Long ownerUserId,
        String zone,
        int hp,
        String mainColor,
        int artDamage,
        String artCostJson,
        String artEffectJson,
        int cheerCount,
        String cheerColor,
        String prefix
    ) {
        String unique = prefix + "_" + System.nanoTime();
        String memberCardId = unique + "_MEMBER";
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            memberCardId,
            "測試 Holomen " + unique
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, ?, 'DEBUT', ?, NULL, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            memberCardId,
            hp,
            mainColor
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_arts (member_card_id, name, description, cost_cheer_json, effect_json, order_index)
            VALUES (?, ?, '', CAST(? AS jsonb), CAST(? AS jsonb), 0)
            """,
            memberCardId,
            "測試藝能 " + artDamage,
            artCostJson,
            artEffectJson
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'STAGE', NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            ownerUserId,
            memberCardId
        );
        Long memberCardInstanceId = jdbcTemplate.queryForObject(
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
            Long.class,
            matchId,
            ownerUserId,
            memberCardId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomems (
                match_id, owner_user_id, match_card_id, card_id, zone, is_rested, is_face_down, damage_taken, current_level
            ) VALUES (?, ?, ?, ?, ?, FALSE, FALSE, 0, 'DEBUT')
            """,
            matchId,
            ownerUserId,
            memberCardInstanceId,
            memberCardId,
            zone
        );
        Long matchHolomemId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            Long.class,
            matchId,
            ownerUserId,
            memberCardInstanceId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_stack_cards (match_holomem_id, match_card_id, stack_order)
            VALUES (?, ?, 1)
            ON CONFLICT (match_card_id) DO NOTHING
            """,
            matchHolomemId,
            memberCardInstanceId
        );
        for (int i = 0; i < cheerCount; i++) {
            String cheerCardId = unique + "_CHEER_" + i;
            jdbcTemplate.update(
                """
                INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
                VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                cheerCardId,
                "測試 Cheer " + unique + "_" + i
            );
            jdbcTemplate.update(
                """
                INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                cheerCardId,
                cheerColor
            );
            jdbcTemplate.update(
                """
                INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
                VALUES (?, ?, ?, 'STAGE', NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                matchId,
                ownerUserId,
                cheerCardId
            );
            jdbcTemplate.update(
                """
                INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                VALUES (?, ?, FALSE)
                """,
                matchHolomemId,
                cheerCardId
            );
        }
        return memberCardInstanceId;
    }

    protected String createMemberCardDefinition(
        String prefix,
        String displayName,
        String levelType,
        int hp,
        String mainColor
    ) {
        return createGeneratedMemberCardDefinition(prefix, displayName, levelType, hp, mainColor);
    }

    protected String createMemberCardDefinition(
        String prefix,
        String displayName,
        String levelType,
        int hp,
        String mainColor,
        String passiveEffectJson
    ) {
        return createGeneratedMemberCardDefinition(prefix, displayName, levelType, hp, mainColor, passiveEffectJson);
    }

    protected Long insertCardIntoHand(Long matchId, Long ownerUserId, String cardId) {
        int nextHandOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            Integer.class,
            matchId,
            ownerUserId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            ownerUserId,
            cardId,
            nextHandOrder
        );
        return jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
              AND zone = 'HAND'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            ownerUserId,
            cardId
        );
    }

    protected Long insertCardIntoDeckTop(Long matchId, Long ownerUserId, String cardId) {
        int nextDeckTopOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MIN(order_index), 1) - 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            matchId,
            ownerUserId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'DECK', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            ownerUserId,
            cardId,
            nextDeckTopOrder
        );
        return jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
              AND zone = 'DECK'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            ownerUserId,
            cardId
        );
    }

    /**
     * 幫既有 member card definition 補一個主藝能。
     *
     * <p>`HBP06-084` 這類測試需要「名稱固定為官方條件要匹配的角色」，但又要自訂一個可穩定驗證的
     * 藝能傷害值。直接提供這個 helper，可以把「卡名條件」與「藝能數值 setup」拆開，不必為了測一張
     * Gift 再額外建立一套專用建卡流程。
     */
    protected void insertPrimaryArtForMember(
        String memberCardId,
        String artName,
        String artCostJson,
        String artEffectJson
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO member_arts (member_card_id, name, description, cost_cheer_json, effect_json, order_index)
            VALUES (?, ?, '', CAST(? AS jsonb), CAST(? AS jsonb), 0)
            """,
            memberCardId,
            artName,
            artCostJson,
            artEffectJson
        );
    }

    protected void moveOneMemberFromDeckToHand(Long matchId, Long userId) {
        Long candidate = jdbcTemplate.query(
            """
            SELECT mc.id
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'DECK'
              AND c.card_type = 'MEMBER'
            ORDER BY mc.order_index, mc.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (candidate == null) {
            return;
        }
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'HAND',
                is_face_down = FALSE,
                order_index = COALESCE(
                    (SELECT MAX(order_index) + 1 FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HAND'),
                    1
                ),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            matchId,
            userId,
            candidate
        );
    }

    protected Map<String, Integer> loadPrimaryArtRequiredCheerCost(Long matchId, Long cardInstanceId) {
        Map<String, Integer> requiredCost = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
            WITH art AS (
              SELECT ma.cost_cheer_json
              FROM match_cards mc
              JOIN member_arts ma ON ma.member_card_id = mc.card_id
              WHERE mc.match_id = ?
                AND mc.id = ?
              ORDER BY ma.order_index ASC, ma.id ASC
              LIMIT 1
            )
            SELECT e.key AS color, COALESCE((e.value)::int, 0) AS required
            FROM art, jsonb_each_text(COALESCE(art.cost_cheer_json, '{}'::jsonb)) e
            """,
            rs -> {
                String color = rs.getString("color");
                int required = rs.getInt("required");
                if (required <= 0) {
                    return;
                }
                requiredCost.put(color == null ? "" : color.trim().toUpperCase(), required);
            },
            matchId,
            cardInstanceId
        );
        return requiredCost;
    }

    /**
     * 依主藝能費用自動從 Cheer Deck 補足並貼到指定 Holomem。
     *
     * 這個 helper 的目的不是測藝能費用本身，而是讓 Gift/觸發測試可以快速進到
     * 「確實能出招」的狀態，避免每個案例都重複手寫 attach 流程。
     */
    protected void attachPrimaryArtCostFromCheerDeck(Long matchId, Long userId, Long holomemCardInstanceId) {
        Map<String, Integer> requiredCheerCost = loadPrimaryArtRequiredCheerCost(matchId, holomemCardInstanceId);
        seedCheerDeckForPrimaryArtCost(matchId, userId, requiredCheerCost);

        int attachTargetCount = Math.max(requiredCheerCost.values().stream().mapToInt(Integer::intValue).sum(), 1);
        for (int i = 0; i < attachTargetCount; i++) {
            Long cheerCardInstanceId = findTopCheerDeckCard(matchId, userId);
            assertThat(cheerCardInstanceId).isNotNull();
            AttachCheerActionRequest attach = new AttachCheerActionRequest();
            attach.setCheerCardInstanceId(cheerCardInstanceId);
            attach.setTargetHolomemCardInstanceId(holomemCardInstanceId);
            matchActionService.attachCheer(matchId, userId, attach);
        }
    }

    protected void seedCheerDeckForPrimaryArtCost(Long matchId, Long userId, Map<String, Integer> requiredCheerCost) {
        if (requiredCheerCost == null || requiredCheerCost.isEmpty()) {
            return;
        }
        Integer nextTopOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MIN(order_index), 1) - 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CHEER_DECK'
            """,
            Integer.class,
            matchId,
            userId
        );
        int order = nextTopOrder == null ? 0 : nextTopOrder;
        for (Map.Entry<String, Integer> entry : requiredCheerCost.entrySet()) {
            String color = normalizeCheerColorForTest(entry.getKey());
            int required = entry.getValue() == null ? 0 : entry.getValue();
            for (int i = 0; i < required; i++) {
                String cheerCardId = "TCHEER_COST_" + color + "_" + System.nanoTime() + "_" + i;
                jdbcTemplate.update(
                    """
                    INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
                    VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    cheerCardId,
                    "測試費用 Cheer " + color
                );
                jdbcTemplate.update(
                    """
                    INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    cheerCardId,
                    color
                );
                jdbcTemplate.update(
                    """
                    INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
                    VALUES (?, ?, ?, 'CHEER_DECK', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    matchId,
                    userId,
                    cheerCardId,
                    order--
                );
            }
        }
    }

    /**
     * 直接建立測試 Cheer 並附著到指定 Holomem。
     *
     * <p>`HSD13-007` 這類常駐 Gift 只在乎「目前這張 Holomem 身上實際有幾張 Cheer」，
     * 並不在乎 Cheer 是透過哪個動作附著上去。測試若硬走完整 attach phase，反而會讓焦點
     * 從「常駐 HP 加成是否正確」漂移到回合流程本身。
     *
     * <p>因此這個 helper 刻意直接寫入：
     *
     * <p>- `cards`
     * <p>- `cheer_cards`
     * <p>- `match_cards`
     * <p>- `match_holomem_cheers`
     *
     * <p>讓測試能用最短路徑建立「已附著 N 張 Cheer」的盤面。
     */
    protected void attachDirectTestCheers(
        Long matchId,
        Long ownerUserId,
        Long matchHolomemId,
        int cheerCount,
        String cheerColor,
        String prefix
    ) {
        if (cheerCount <= 0) {
            return;
        }
        String normalizedColor = normalizeCheerColorForTest(cheerColor);
        for (int i = 0; i < cheerCount; i++) {
            String cheerCardId = prefix + "_CHEER_" + i + "_" + System.nanoTime();
            jdbcTemplate.update(
                """
                INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
                VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                cheerCardId,
                "直接附著測試 Cheer " + prefix + "_" + i
            );
            jdbcTemplate.update(
                """
                INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                cheerCardId,
                normalizedColor
            );
            jdbcTemplate.update(
                """
                INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
                VALUES (?, ?, ?, 'STAGE', NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                matchId,
                ownerUserId,
                cheerCardId
            );
            Long cheerCardInstanceId = jdbcTemplate.query(
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
                cheerCardId
            );
            jdbcTemplate.update(
                """
                INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                VALUES (?, ?, ?, FALSE)
                """,
                matchHolomemId,
                cheerCardInstanceId,
                cheerCardId
            );
        }
    }

    protected Long attachTrackedTestCheer(
        Long matchId,
        Long ownerUserId,
        Long matchHolomemId,
        String cheerColor,
        String prefix
    ) {
        Long cheerCardInstanceId = insertCheerCardIntoZone(matchId, ownerUserId, cheerColor, "STAGE");
        String cheerCardId = jdbcTemplate.queryForObject(
            "SELECT card_id FROM match_cards WHERE id = ?",
            String.class,
            cheerCardInstanceId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
            VALUES (?, ?, ?, FALSE)
            """,
            matchHolomemId,
            cheerCardInstanceId,
            cheerCardId
        );
        return cheerCardInstanceId;
    }

    protected void insertOshiSkillHistoryAction(
        Long matchId,
        Long userId,
        int turnNumber,
        int actionOrder,
        String skillType,
        String skillName
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO match_actions (match_id, user_id, action_type, payload, executed_at, turn_number, action_order)
            VALUES (?, ?, 'USE_OSHI_SKILL', CAST(? AS jsonb), CURRENT_TIMESTAMP, ?, ?)
            """,
            matchId,
            userId,
            "{\"skillType\":\"" + skillType + "\",\"skillName\":\"" + skillName + "\"}",
            turnNumber,
            actionOrder
        );
    }

    protected void insertAttackArtHistoryAction(
        Long matchId,
        Long userId,
        int turnNumber,
        int actionOrder,
        String attackerCardId,
        String artName
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO match_actions (match_id, user_id, action_type, payload, executed_at, turn_number, action_order)
            VALUES (?, ?, 'ATTACK_ART', CAST(? AS jsonb), CURRENT_TIMESTAMP, ?, ?)
            """,
            matchId,
            userId,
            "{\"attackerCardId\":\"" + attackerCardId + "\",\"artName\":\"" + artName + "\"}",
            turnNumber,
            actionOrder
        );
    }

    protected String normalizeCheerColorForTest(String color) {
        if (color == null || color.isBlank()) {
            return "WHITE";
        }
        String normalized = color.trim().toUpperCase();
        if ("COLORLESS".equals(normalized)) {
            return "WHITE";
        }
        return normalized;
    }

    protected Long findTopCheerDeckCard(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CHEER_DECK'
            ORDER BY order_index, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
    }

    protected void assertZoneCount(Long matchId, Long userId, String zone, int expected) {
        Integer actual = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = ?",
            Integer.class,
            matchId,
            userId,
            zone
        );
        assertThat(actual).isEqualTo(expected);
    }

    protected void replaceZoneCardsCardId(Long matchId, Long userId, String zone, String cardId) {
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            cardId,
            matchId,
            userId,
            zone
        );
    }

    /**
     * 讀取單張 match card 當前所在區域。
     *
     * <p>很多卡效測試最後只在意「有沒有真的被移到正確 zone」，直接抽成 helper 可以讓 assertion
     * 聚焦在規則結果，而不是每個測試都重覆拼同一段 SQL。
     */
    protected String loadCardZone(Long cardInstanceId) {
        return jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            cardInstanceId
        );
    }

    protected Long forceTopLifeCardToCheer(Long matchId, Long userId) {
        String cheerCardId = jdbcTemplate.query(
            "SELECT card_id FROM cheer_cards ORDER BY card_id LIMIT 1",
            rs -> rs.next() ? rs.getString("card_id") : null
        );
        if (cheerCardId == null || cheerCardId.isBlank()) {
            throw new IllegalStateException("測試資料缺少 Cheer 卡");
        }
        Integer minLifeOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MIN(order_index), 1)
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'LIFE'
            """,
            Integer.class,
            matchId,
            userId
        );
        int topOrder = (minLifeOrder == null ? 1 : minLifeOrder) - 1;
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'LIFE', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            userId,
            cheerCardId,
            topOrder
        );
        return jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
              AND zone = 'LIFE'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            userId,
            cheerCardId
        );
    }

    protected void keepTopLifeCards(Long matchId, Long userId, int keepCount) {
        jdbcTemplate.update(
            """
            WITH ordered AS (
              SELECT id,
                     ROW_NUMBER() OVER (ORDER BY order_index ASC, id ASC) AS rn
              FROM match_cards
              WHERE match_id = ?
                AND owner_user_id = ?
                AND zone = 'LIFE'
            )
            DELETE FROM match_cards
            WHERE id IN (
              SELECT id
              FROM ordered
              WHERE rn > ?
            )
            """,
            matchId,
            userId,
            Math.max(keepCount, 0)
        );
    }

    protected String normalizeHolomemLevel(String rawLevel) {
        if ("FIRST".equals(rawLevel) || "SECOND".equals(rawLevel) || "SPOT".equals(rawLevel) || "BUZZ".equals(rawLevel)) {
            return rawLevel;
        }
        return "DEBUT";
    }

    protected Long insertSupportCardIntoHand(
        Long matchId,
        Long userId,
        String supportCardId,
        boolean isLimited,
        String effectType,
        String effectJson,
        String targetType
    ) {
        return insertSupportCardIntoHand(
            matchId,
            userId,
            supportCardId,
            "測試支援卡 " + supportCardId,
            isLimited,
            effectType,
            effectJson,
            targetType
        );
    }

    protected Long insertSupportCardIntoHand(
        Long matchId,
        Long userId,
        String supportCardId,
        String supportName,
        boolean isLimited,
        String effectType,
        String effectJson,
        String targetType
    ) {
        return insertSupportCardIntoZone(
            matchId,
            userId,
            supportCardId,
            supportName,
            "HAND",
            isLimited,
            effectType,
            effectJson,
            targetType
        );
    }

    /**
     * 建立測試用 support definition，讓測試可以自由決定之後要放進手牌、棄牌區或牌庫。
     */
    protected void createSupportCardDefinition(
        String supportCardId,
        String supportName,
        boolean isLimited,
        String effectType,
        String effectJson,
        String targetType
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            supportName
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, ?, NULL, NULL, ?, CAST(? AS jsonb), ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            isLimited,
            effectType,
            effectJson,
            targetType
        );
    }

    /**
     * 把指定支援卡直接放到牌庫頂。
     *
     * <p>搜尋效果測試常需要控制「第一張會被找到的是哪一張」，因此這裡直接組合建卡與牌庫頂插入。
     */
    protected Long insertSupportCardIntoDeckTop(
        Long matchId,
        Long userId,
        String supportCardId,
        String supportName,
        boolean isLimited,
        String effectType,
        String effectJson,
        String targetType
    ) {
        createSupportCardDefinition(supportCardId, supportName, isLimited, effectType, effectJson, targetType);
        return insertCardIntoDeckTop(matchId, userId, supportCardId);
    }

    /**
     * 建立測試用 support card 並直接放到指定區域。
     *
     * <p>`HSD08-005` 這類案例需要驗證「從 ARCHIVE 把特定名稱的 support 回手」，因此不能只把卡先塞
     * 進手牌再人工改 zone。直接提供 zone 參數，能讓測試更明確表達：
     *
     * <p>- 這張卡原本在哪裡
     * <p>- 效果執行後應該移到哪裡
     *
     * <p>同時也避免未來有更多 `ARCHIVE / HOLOPOWER / HAND` support 測試時重複寫 SQL。
     */
    protected Long insertSupportCardIntoZone(
        Long matchId,
        Long userId,
        String supportCardId,
        String supportName,
        String zone,
        boolean isLimited,
        String effectType,
        String effectJson,
        String targetType
    ) {
        createSupportCardDefinition(supportCardId, supportName, isLimited, effectType, effectJson, targetType);
        Integer orderIndex = null;
        if ("HAND".equals(zone)) {
            orderIndex = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(MAX(order_index), 0) + 1
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'HAND'
                """,
                Integer.class,
                matchId,
                userId
            );
        }
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            userId,
            supportCardId,
            zone,
            orderIndex
        );
        return jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
              AND zone = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            userId,
            supportCardId,
            zone
        );
    }

    protected void attachDirectTestSupport(
        Long matchId,
        Long userId,
        Long targetHolomemId,
        String supportCardId,
        String supportName,
        String supportType
    ) {
        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            userId,
            supportCardId,
            supportName,
            false,
            "BUFF",
            "{\"type\":\"BUFF\",\"rawText\":\"カードタイプ\\nサポート・ツール\\nテスト用。\"}",
            "SELF"
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            supportCardInstanceId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_supports (match_holomem_id, match_card_id, support_card_id, support_type)
            VALUES (?, ?, ?, ?)
            """,
            targetHolomemId,
            supportCardInstanceId,
            supportCardId,
            supportType
        );
    }
}
