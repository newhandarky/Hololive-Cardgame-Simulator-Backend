package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hololive.cardgame.dto.AttackArtActionRequest;
import com.hololive.cardgame.dto.BloomActionRequest;
import com.hololive.cardgame.dto.GameStateResponse;
import com.hololive.cardgame.dto.PlaySupportActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MatchBloomEffectIntegrationTest extends MatchActionFlowIntegrationTestSupport {

    @Test
    void bloomShouldUpgradeHolomemAndKeepStack() {
        StartedMatchContext context = createStartedMatch("bloom-upgrade-host", "bloom-upgrade-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 Bloom 成員";
        String debutCardId = createMemberCardDefinition("TBLOOM_DEBUT", displayName, "DEBUT", 120, "RED");
        String firstCardId = createMemberCardDefinition("TBLOOM_FIRST", displayName, "FIRST", 180, "RED");

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        Map<String, Object> top = jdbcTemplate.queryForMap(
            """
            SELECT match_card_id, card_id, current_level, last_bloom_turn
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
              AND match_card_id = ?
            LIMIT 1
            """,
            matchId,
            hostId,
            bloomCardInstanceId
        );
        assertThat(((Number) top.get("match_card_id")).longValue()).isEqualTo(bloomCardInstanceId);
        assertThat(top.get("card_id")).isEqualTo(firstCardId);
        assertThat(top.get("current_level")).isEqualTo("FIRST");
        assertThat(((Number) top.get("last_bloom_turn")).intValue()).isEqualTo(1);

        Integer stackCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomem_stack_cards s
            JOIN match_holomems h ON h.id = s.match_holomem_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'CENTER'
              AND h.match_card_id = ?
            """,
            Integer.class,
            matchId,
            hostId,
            bloomCardInstanceId
        );
        assertThat(stackCount).isEqualTo(2);

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var hostState = state.getPlayers().stream()
            .filter(p -> hostId.equals(p.getUserId()))
            .findFirst()
            .orElseThrow();
        var centerZone = hostState.getBoardZones().stream()
            .filter(z -> "CENTER".equals(z.getZone()))
            .findFirst()
            .orElseThrow();
        var bloomedCard = centerZone.getCards().stream()
            .filter(card -> bloomCardInstanceId.equals(card.getCardInstanceId()))
            .findFirst()
            .orElseThrow();
        assertThat(bloomedCard.getStackDepth()).isEqualTo(2);
        assertThat(bloomedCard.getStackCardInstanceIds()).contains(targetHolomemCardInstanceId, bloomCardInstanceId);
    }

    @Test
    void bloomShouldRejectSkippingLevelTransition() {
        StartedMatchContext context = createStartedMatch("bloom-skip-level-host", "bloom-skip-level-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 Bloom 跳階";
        String debutCardId = createMemberCardDefinition("TBLOOM_SKIP_DEBUT", displayName, "DEBUT", 120, "RED");
        String secondCardId = createMemberCardDefinition("TBLOOM_SKIP_SECOND", displayName, "SECOND", 220, "RED");

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, secondCardId);

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);

        assertThatThrownBy(() -> matchActionService.bloom(matchId, hostId, request))
            .isInstanceOf(GameRuleException.class)
            .hasMessageContaining("依序遞進");
    }

    @Test
    void playToStageShouldRejectFirstSecondBuzzFromHand() {
        StartedMatchContext context = createStartedMatch("play-to-stage-first-host", "play-to-stage-first-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String firstCardId = createMemberCardDefinition("TPTS_FIRST_ONLY", "測試 First 直上", "FIRST", 170, "BLUE");
        Long firstCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);

        PlayToStageActionRequest request = new PlayToStageActionRequest();
        request.setCardInstanceId(firstCardInstanceId);
        request.setTargetZone("BACK");

        assertThatThrownBy(() -> matchActionService.playToStage(matchId, hostId, request))
            .isInstanceOf(GameRuleException.class)
            .hasMessageContaining("請改用 BLOOM");

        Integer stillInHand = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            Integer.class,
            firstCardInstanceId,
            matchId,
            hostId
        );
        assertThat(stillInHand).isEqualTo(1);
    }

    @Test
    void bloomShouldRejectTargetEnteredThisTurn() {
        StartedMatchContext context = createStartedMatch("bloom-newly-host", "bloom-newly-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 本回合新上場";
        String debutCardId = createMemberCardDefinition("TBLOOM_NEW_DEBUT", displayName, "DEBUT", 120, "BLUE");
        String firstCardId = createMemberCardDefinition("TBLOOM_NEW_FIRST", displayName, "FIRST", 170, "BLUE");

        Long debutInHand = insertCardIntoHand(matchId, hostId, debutCardId);
        PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
        playToStage.setCardInstanceId(debutInHand);
        playToStage.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, playToStage);

        Long targetHolomemCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);

        assertThatThrownBy(() -> matchActionService.bloom(matchId, hostId, request))
            .isInstanceOf(GameRuleException.class)
            .hasMessageContaining("剛上場");
    }

    @Test
    void bloomShouldRejectSameHolomemSecondBloomInSameTurn() {
        StartedMatchContext context = createStartedMatch("bloom-once-host", "bloom-once-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 Bloom 次數限制";
        String debutCardId = createMemberCardDefinition("TBLOOM_ONCE_DEBUT", displayName, "DEBUT", 110, "GREEN");
        String firstCardId = createMemberCardDefinition("TBLOOM_ONCE_FIRST", displayName, "FIRST", 160, "GREEN");
        String secondCardId = createMemberCardDefinition("TBLOOM_ONCE_SECOND", displayName, "SECOND", 220, "GREEN");

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long firstInHand = insertCardIntoHand(matchId, hostId, firstCardId);
        Long secondInHand = insertCardIntoHand(matchId, hostId, secondCardId);

        BloomActionRequest firstBloom = new BloomActionRequest();
        firstBloom.setBloomCardInstanceId(firstInHand);
        firstBloom.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, firstBloom);

        Long newTopCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        BloomActionRequest secondBloom = new BloomActionRequest();
        secondBloom.setBloomCardInstanceId(secondInHand);
        secondBloom.setTargetHolomemCardInstanceId(newTopCardInstanceId);

        assertThatThrownBy(() -> matchActionService.bloom(matchId, hostId, secondBloom))
            .isInstanceOf(GameRuleException.class)
            .hasMessageContaining("本回合已執行過 BLOOM");
    }

    @Test
    void bloomShouldAllowOfficialGiftHbp01045IgnoreBloomLevelWhenLifeAtMostThree() {
        StartedMatchContext context = createStartedMatch("bloom-hbp01045-host", "bloom-hbp01045-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HBP01-045',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            hostCenterCardInstanceId,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HBP01-045',
                current_level = 'DEBUT',
                entered_turn_number = 0,
                last_bloom_turn = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = 3,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            hostId
        );

        Long secondBloomInHand = insertCardIntoHand(matchId, hostId, "HBP01-047");
        BloomActionRequest bloom = new BloomActionRequest();
        bloom.setBloomCardInstanceId(secondBloomInHand);
        bloom.setTargetHolomemCardInstanceId(hostCenterCardInstanceId);
        matchActionService.bloom(matchId, hostId, bloom);

        Map<String, Object> centerAfter = jdbcTemplate.queryForMap(
            """
            SELECT card_id, current_level, match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            LIMIT 1
            """,
            matchId,
            hostId
        );
        assertThat(centerAfter.get("card_id")).isEqualTo("HBP01-047");
        assertThat(centerAfter.get("current_level")).isEqualTo("SECOND");
        assertThat(((Number) centerAfter.get("match_card_id")).longValue()).isEqualTo(secondBloomInHand);

        String payload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'BLOOM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(payload).containsPattern("\"bloomLevelOverrideApplied\"\\s*:\\s*true");
        assertThat(payload).contains("HBP01-045");
        assertThat(payload).contains("HBP01-047");
    }

    @Test
    void bloomShouldNotAllowOfficialGiftHbp01045IgnoreBloomLevelWhenLifeAboveThree() {
        StartedMatchContext context = createStartedMatch("bloom-hbp01045-fail-host", "bloom-hbp01045-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HBP01-045',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            hostCenterCardInstanceId,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HBP01-045',
                current_level = 'DEBUT',
                entered_turn_number = 0,
                last_bloom_turn = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = 4,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            hostId
        );

        Long secondBloomInHand = insertCardIntoHand(matchId, hostId, "HBP01-047");
        BloomActionRequest bloom = new BloomActionRequest();
        bloom.setBloomCardInstanceId(secondBloomInHand);
        bloom.setTargetHolomemCardInstanceId(hostCenterCardInstanceId);

        assertThatThrownBy(() -> matchActionService.bloom(matchId, hostId, bloom))
            .isInstanceOf(GameRuleException.class)
            .hasMessageContaining("BLOOM 只能依序遞進");
    }

    @Test
    void bloomShouldAllowOfficialGiftHsd10004ToGrantSecondBloomWhenConditionsSatisfied() {
        StartedMatchContext context = createStartedMatch("bloom-hsd10004-host", "bloom-hsd10004-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        // HSD10-004 的 Gift 會看 match_players.oshi_card_id，因此測試直接把 host 的推し改成官方指定的輪堂千速。
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET oshi_card_id = 'HSD10-001',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            hostId
        );

        // 把我方既有中心改成官方的 Debut 輪堂千速，讓後續 Bloom 鏈可以直接驗證：
        // HSD10-002 (Debut) -> HSD10-004 (First) -> HSD10-006 (Second)
        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD10-002',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            hostCenterCardInstanceId,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD10-002',
                current_level = 'DEBUT',
                entered_turn_number = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );

        // 只要對手場上存在任一 1st Holomem，HSD10-004 的條件就成立。這裡直接把對手中心預置成 1st。
        String durableTargetCardId = createMemberCardDefinition("THBP06014_TARGET", "HBP06-014 測試目標", "DEBUT", 600, "BLUE");
        Long guestCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            durableTargetCardId,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                damage_taken = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            durableTargetCardId,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD10-003',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            guestCenterCardInstanceId,
            matchId,
            guestId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD10-003',
                current_level = 'FIRST',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );

        Long firstBloomInHand = insertCardIntoHand(matchId, hostId, "HSD10-004");
        Long secondBloomInHand = insertCardIntoHand(matchId, hostId, "HSD10-006");

        BloomActionRequest firstBloom = new BloomActionRequest();
        firstBloom.setBloomCardInstanceId(firstBloomInHand);
        firstBloom.setTargetHolomemCardInstanceId(hostCenterCardInstanceId);
        matchActionService.bloom(matchId, hostId, firstBloom);

        Long firstTopCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        Integer allowanceCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ALLOW_EXTRA_BLOOM'
            """,
            Integer.class,
            matchId,
            hostId
        );

        BloomActionRequest secondBloom = new BloomActionRequest();
        secondBloom.setBloomCardInstanceId(secondBloomInHand);
        secondBloom.setTargetHolomemCardInstanceId(firstTopCardInstanceId);
        matchActionService.bloom(matchId, hostId, secondBloom);

        Map<String, Object> centerAfter = jdbcTemplate.queryForMap(
            """
            SELECT match_card_id, card_id, current_level, last_bloom_turn
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            LIMIT 1
            """,
            matchId,
            hostId
        );
        Integer allowanceAfter = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ALLOW_EXTRA_BLOOM'
            """,
            Integer.class,
            matchId,
            hostId
        );
        Integer stackDepth = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomem_stack_cards
            WHERE match_holomem_id = (
                SELECT id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'CENTER'
                LIMIT 1
            )
            """,
            Integer.class,
            matchId,
            hostId
        );

        assertThat(allowanceCount).isEqualTo(1);
        assertThat(centerAfter.get("card_id")).isEqualTo("HSD10-006");
        assertThat(centerAfter.get("current_level")).isEqualTo("SECOND");
        assertThat(((Number) centerAfter.get("match_card_id")).longValue()).isEqualTo(secondBloomInHand);
        assertThat(((Number) centerAfter.get("last_bloom_turn")).intValue()).isEqualTo(1);
        assertThat(allowanceAfter).isZero();
        assertThat(stackDepth).isEqualTo(3);
    }

    @Test
    void bloomShouldNotGrantOfficialGiftHsd10004SecondBloomWhenOshiConditionFails() {
        StartedMatchContext context = createStartedMatch("bloom-hsd10004-fail-host", "bloom-hsd10004-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD10-002',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            hostCenterCardInstanceId,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD10-002',
                current_level = 'DEBUT',
                entered_turn_number = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );

        Long guestCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD10-003',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            guestCenterCardInstanceId,
            matchId,
            guestId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD10-003',
                current_level = 'FIRST',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );

        Long firstBloomInHand = insertCardIntoHand(matchId, hostId, "HSD10-004");
        Long secondBloomInHand = insertCardIntoHand(matchId, hostId, "HSD10-006");

        BloomActionRequest firstBloom = new BloomActionRequest();
        firstBloom.setBloomCardInstanceId(firstBloomInHand);
        firstBloom.setTargetHolomemCardInstanceId(hostCenterCardInstanceId);
        matchActionService.bloom(matchId, hostId, firstBloom);

        Long firstTopCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        Integer allowanceCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ALLOW_EXTRA_BLOOM'
            """,
            Integer.class,
            matchId,
            hostId
        );

        BloomActionRequest secondBloom = new BloomActionRequest();
        secondBloom.setBloomCardInstanceId(secondBloomInHand);
        secondBloom.setTargetHolomemCardInstanceId(firstTopCardInstanceId);

        assertThat(allowanceCount).isZero();
        assertThatThrownBy(() -> matchActionService.bloom(matchId, hostId, secondBloom))
            .isInstanceOf(GameRuleException.class)
            .hasMessageContaining("本回合已執行過 BLOOM");
    }

    @Test
    void bloomShouldTriggerDrawEffectFromPassiveText() {
        StartedMatchContext context = createStartedMatch("bloom-effect-host", "bloom-effect-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 Bloom 抽牌";
        String debutCardId = createMemberCardDefinition("TBLOOM_EFF_DEBUT", displayName, "DEBUT", 120, "WHITE");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_EFF_FIRST",
            displayName,
            "FIRST",
            170,
            "WHITE",
            "{\"キーワード\":\"ブルームエフェクトテスト \\n自分のデッキを1枚引く。\"}"
        );

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);

        int handBefore = countZone(matchId, hostId, "HAND");
        int deckBefore = countZone(matchId, hostId, "DECK");

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        int handAfterBloom = countZone(matchId, hostId, "HAND");
        int deckAfterBloom = countZone(matchId, hostId, "DECK");
        assertThat(handAfterBloom).isEqualTo(handBefore - 1);
        assertThat(deckAfterBloom).isEqualTo(deckBefore);

        String pendingContextText = jdbcTemplate.query(
            """
            SELECT context_json::text
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("context_json") : "",
            matchId,
            hostId
        );
        assertThat(pendingContextText).containsPattern("\"sourceActionType\"\\s*:\\s*\"BLOOM\"");
        assertThat(pendingContextText).contains("DRAW");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int handAfterConfirm = countZone(matchId, hostId, "HAND");
        int deckAfterConfirm = countZone(matchId, hostId, "DECK");
        assertThat(handAfterConfirm).isEqualTo(handBefore);
        assertThat(deckAfterConfirm).isEqualTo(deckBefore - 1);

        String payload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payload).containsPattern("\"sourceActionType\"\\s*:\\s*\"BLOOM\"");
        assertThat(payload).containsPattern("\"effectType\"\\s*:\\s*\"DRAW\"");
        assertThat(payload).containsPattern("\"drawApplied\"\\s*:\\s*1");
    }

    @Test
    void bloomDamageShouldCreateSendCheerInteractionWhenLifeReduced() {
        StartedMatchContext context = createStartedMatch("bloom-life-loss-host", "bloom-life-loss-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String bloomDisplayName = "測試 Bloom 扣命";
        String hostDebutCardId = createMemberCardDefinition("TBL_LIFE_DBT", bloomDisplayName, "DEBUT", 120, "RED");
        String hostFirstCardId = createMemberCardDefinition(
            "TBL_LIFE_FST",
            bloomDisplayName,
            "FIRST",
            170,
            "RED",
            "{\"bloomEffect\":{\"effects\":[\"DAMAGE\"],\"type\":\"DAMAGE\",\"value\":999}}"
        );
        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            hostDebutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, hostFirstCardId);

        String guestCenterCardId = createMemberCardDefinition("TBLOOM_LIFE_GUEST_CENTER", "測試被打中心", "DEBUT", 80, "BLUE");
        Long guestCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            guestCenterCardId,
            guestCenterCardInstanceId,
            matchId,
            guestId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                damage_taken = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            guestCenterCardId,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        String guestBackCardId = createMemberCardDefinition("TBLOOM_LIFE_GUEST_BACK", "測試存活後排", "DEBUT", 110, "BLUE");
        createStageHolomemWithSingleCard(matchId, guestId, guestBackCardId, "BACK", "DEBUT", 0);

        forceTopLifeCardToCheer(matchId, guestId);
        int lifeBefore = countZone(matchId, guestId, "LIFE");

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int lifeAfter = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfter).isEqualTo(lifeBefore - 1);

        List<Map<String, Object>> sendCheerPending = jdbcTemplate.queryForList(
            """
            SELECT decision_type, source_action_type, source_card_instance_id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'SEND_CHEER'
              AND source_action_type = 'LIFE_LOSS'
            ORDER BY id DESC
            LIMIT 1
            """,
            matchId,
            guestId
        );
        if (!sendCheerPending.isEmpty()) {
            Map<String, Object> latest = sendCheerPending.get(0);
            assertThat(latest.get("decision_type")).isEqualTo("SEND_CHEER");
            assertThat(latest.get("source_action_type")).isEqualTo("LIFE_LOSS");
            assertThat(((Number) latest.get("source_card_instance_id")).longValue()).isPositive();
        }
    }

    @Test
    void actionLockBloomShouldBlockBloomOnTargetHolomem() {
        StartedMatchContext context = createStartedMatch("bloom-lock-host", "bloom-lock-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 Bloom 封鎖";
        String debutCardId = createMemberCardDefinition("TBLOOM_LOCK_DEBUT", displayName, "DEBUT", 120, "GREEN");
        String firstCardId = createMemberCardDefinition("TBLOOM_LOCK_FIRST", displayName, "FIRST", 170, "GREEN");
        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);
        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TBLOOM_LOCK_SUPPORT_" + System.nanoTime(),
            false,
            "ACTION_LOCK",
            "{\"type\":\"ACTION_LOCK\",\"rawText\":\"このターンの間、このホロメンはBloomできない。\"}",
            "SELF"
        );

        PlaySupportActionRequest playSupport = new PlaySupportActionRequest();
        playSupport.setCardInstanceId(supportCardInstanceId);
        playSupport.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.playSupport(matchId, hostId, playSupport);

        BloomActionRequest bloom = new BloomActionRequest();
        bloom.setBloomCardInstanceId(bloomCardInstanceId);
        bloom.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        assertThatThrownBy(() -> matchActionService.bloom(matchId, hostId, bloom))
            .isInstanceOf(GameRuleException.class)
            .satisfies(ex -> assertThat(((GameRuleException) ex).getCode()).isEqualTo(GameErrorCode.STAGE_ACTION_LOCKED));
    }

    @Test
    void playSupportDownExtraLifeShouldReduceAdditionalLifeAndCreateSendCheerInteraction() {
        StartedMatchContext context = createStartedMatch("down-extra-life-host", "down-extra-life-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String guestBackCardId = createMemberCardDefinition("TDOWN_EXTRA_GUEST_BACK", "測試 Down 目標", "DEBUT", 100, "BLUE");
        Long guestBackCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            guestBackCardId,
            "BACK",
            "DEBUT",
            0
        );
        createStageHolomemWithSingleCard(
            matchId,
            guestId,
            findMemberCardIdByLevel("DEBUT"),
            "CENTER",
            "DEBUT",
            0
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = 60
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestBackCardInstanceId
        );

        forceTopLifeCardToCheer(matchId, guestId);
        int lifeBefore = countZone(matchId, guestId, "LIFE");

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TDOWN_EXTRA_SUPPORT_" + System.nanoTime(),
            false,
            "DOWN_EXTRA_LIFE",
            "{\"type\":\"DOWN_EXTRA_LIFE\",\"rawText\":\"相手のバックホロメン1人をダウンさせる。さらに相手のライフを1つ減らす。\",\"extraLifeLoss\":1}",
            "ENEMY"
        );
        PlaySupportActionRequest playSupport = new PlaySupportActionRequest();
        playSupport.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, playSupport);

        int lifeAfter = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfter).isEqualTo(lifeBefore - 1);

        List<Map<String, Object>> sendCheerPending = jdbcTemplate.queryForList(
            """
            SELECT decision_type, source_action_type, source_card_instance_id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'SEND_CHEER'
              AND source_action_type = 'LIFE_LOSS'
            ORDER BY id DESC
            LIMIT 1
            """,
            matchId,
            guestId
        );
        if (!sendCheerPending.isEmpty()) {
            Map<String, Object> latest = sendCheerPending.get(0);
            assertThat(latest.get("decision_type")).isEqualTo("SEND_CHEER");
            assertThat(latest.get("source_action_type")).isEqualTo("LIFE_LOSS");
            assertThat(((Number) latest.get("source_card_instance_id")).longValue()).isPositive();
        }
    }

    @Test
    void attackArtShouldTriggerDownedHolomemExtraLifeLoss() {
        StartedMatchContext context = createStartedMatch("down-event-extra-host", "down-event-extra-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        jdbcTemplate.update(
            """
            DELETE FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id IN (?, ?)
            """,
            matchId,
            hostId,
            guestId
        );

        Long hostCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            200,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220,\"rawHeader\":\"測試藝能 220\"}",
            0,
            "RED",
            "down-event-host-center"
        );
        String guestCenterCardId = createMemberCardDefinition(
            "TDOWN_EVENT_EXTRA_CENTER",
            "Down Event Extra 測試目標",
            "DEBUT",
            120,
            "BLUE",
            "{\"エクストラ\":\"このホロメンがダウンした時、自分のライフ-2\"}"
        );
        Long guestCenterCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            guestCenterCardId,
            "CENTER",
            "DEBUT",
            0
        );
        String guestBackCardId = createMemberCardDefinition("TDOWN_EVENT_EXTRA_BACK", "Down Event Extra 後排", "DEBUT", 100, "WHITE");
        createStageHolomemWithSingleCard(matchId, guestId, guestBackCardId, "BACK", "DEBUT", 0);

        int lifeBefore = countZone(matchId, guestId, "LIFE");
        assertThat(lifeBefore).isGreaterThanOrEqualTo(3);

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 2,
                current_phase = 'PERFORMANCE',
                current_turn_player_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_actions (match_id, user_id, action_type, payload, executed_at, turn_number, action_order)
            VALUES (?, ?, 'DRAW_TURN', '{}'::jsonb, CURRENT_TIMESTAMP, 2, 1),
                   (?, ?, 'TURN_CHEER', '{}'::jsonb, CURRENT_TIMESTAMP, 2, 2)
            """,
            matchId,
            hostId,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            UPDATE match_pending_decisions
            SET status = 'RESOLVED',
                resolved_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND status = 'PENDING'
            """,
            matchId
        );
        entityManager.clear();

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(hostCenterCardInstanceId);
        attack.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        int lifeAfterAttack = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfterAttack).isEqualTo(lifeBefore - 1);

        String pendingContextText = jdbcTemplate.query(
            """
            SELECT context_json::text
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("context_json") : "",
            matchId,
            hostId
        );
        assertThat(pendingContextText).contains("\"triggerSections\"");
        assertThat(pendingContextText).containsPattern("\"sectionType\"\\s*:\\s*\"DOWN_EVENT\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int lifeAfterConfirm = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfterConfirm).isEqualTo(lifeBefore - 3);

        String payloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'ATTACK_ART'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(payloadText).containsPattern("\"downEvent\"\\s*:\\s*\\{");
        assertThat(payloadText).containsPattern("\"deferred\"\\s*:\\s*true");
        assertThat(payloadText).containsPattern("\"appliedLifeLoss\"\\s*:\\s*0");
    }

    @Test
    void attackArtShouldTriggerOfficialExtraLifeLossForHbp02041WhenSelfDowned() {
        StartedMatchContext context = createStartedMatch("hbp02041-down-host", "hbp02041-down-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        jdbcTemplate.update(
            """
            DELETE FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id IN (?, ?)
            """,
            matchId,
            hostId,
            guestId
        );

        Long hostCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            200,
            "RED",
            240,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":240,\"rawHeader\":\"測試藝能 240\"}",
            0,
            "RED",
            "hbp02041-down-host-center"
        );
        Long guestCenterCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            "HBP02-041",
            "CENTER",
            "FIRST",
            0
        );
        createStageHolomemWithSingleCard(
            matchId,
            guestId,
            findMemberCardIdByLevel("DEBUT"),
            "BACK",
            "DEBUT",
            0
        );

        int lifeBefore = countZone(matchId, guestId, "LIFE");
        assertThat(lifeBefore).isGreaterThanOrEqualTo(3);

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 2,
                current_phase = 'PERFORMANCE',
                current_turn_player_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_actions (match_id, user_id, action_type, payload, executed_at, turn_number, action_order)
            VALUES (?, ?, 'DRAW_TURN', '{}'::jsonb, CURRENT_TIMESTAMP, 2, 1),
                   (?, ?, 'TURN_CHEER', '{}'::jsonb, CURRENT_TIMESTAMP, 2, 2)
            """,
            matchId,
            hostId,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            UPDATE match_pending_decisions
            SET status = 'RESOLVED',
                resolved_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND status = 'PENDING'
            """,
            matchId
        );
        entityManager.clear();

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(hostCenterCardInstanceId);
        attack.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        int lifeAfterAttack = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfterAttack).isEqualTo(lifeBefore - 1);

        String pendingContextText = jdbcTemplate.query(
            """
            SELECT context_json::text
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("context_json") : "",
            matchId,
            hostId
        );
        assertThat(pendingContextText).contains("HBP02-041");
        assertThat(pendingContextText).containsPattern("\"sectionType\"\\s*:\\s*\"DOWN_EVENT\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int lifeAfterConfirm = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfterConfirm).isEqualTo(lifeBefore - 3);
    }

    @Test
    void attackArtShouldTriggerOfficialExtraLifeLossForHbp03022WhenSelfDowned() {
        StartedMatchContext context = createStartedMatch("hbp03022-down-host", "hbp03022-down-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        jdbcTemplate.update(
            """
            DELETE FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id IN (?, ?)
            """,
            matchId,
            hostId,
            guestId
        );

        Long hostCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            200,
            "RED",
            260,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":260,\"rawHeader\":\"測試藝能 260\"}",
            0,
            "RED",
            "hbp03022-down-host-center"
        );
        Long guestCenterCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            "HBP03-022",
            "CENTER",
            "FIRST",
            0
        );
        createStageHolomemWithSingleCard(
            matchId,
            guestId,
            findMemberCardIdByLevel("DEBUT"),
            "BACK",
            "DEBUT",
            0
        );

        int lifeBefore = countZone(matchId, guestId, "LIFE");
        assertThat(lifeBefore).isGreaterThanOrEqualTo(3);

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 2,
                current_phase = 'PERFORMANCE',
                current_turn_player_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_actions (match_id, user_id, action_type, payload, executed_at, turn_number, action_order)
            VALUES (?, ?, 'DRAW_TURN', '{}'::jsonb, CURRENT_TIMESTAMP, 2, 1),
                   (?, ?, 'TURN_CHEER', '{}'::jsonb, CURRENT_TIMESTAMP, 2, 2)
            """,
            matchId,
            hostId,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            UPDATE match_pending_decisions
            SET status = 'RESOLVED',
                resolved_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND status = 'PENDING'
            """,
            matchId
        );
        entityManager.clear();

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(hostCenterCardInstanceId);
        attack.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        int lifeAfterAttack = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfterAttack).isEqualTo(lifeBefore - 1);

        String pendingContextText = jdbcTemplate.query(
            """
            SELECT context_json::text
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("context_json") : "",
            matchId,
            hostId
        );
        assertThat(pendingContextText).contains("HBP03-022");
        assertThat(pendingContextText).containsPattern("\"sectionType\"\\s*:\\s*\"DOWN_EVENT\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int lifeAfterConfirm = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfterConfirm).isEqualTo(lifeBefore - 3);
    }

    @Test
    void bloomShouldTriggerReturnToHandEffectFromPassiveText() {
        StartedMatchContext context = createStartedMatch("bloom-return-host", "bloom-return-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 Bloom 回手";
        String debutCardId = createMemberCardDefinition("TBLOOM_RET_DEBUT", displayName, "DEBUT", 120, "GREEN");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_RET_FIRST",
            displayName,
            "FIRST",
            170,
            "GREEN",
            "{\"キーワード\":\"ブルームエフェクトテスト \\n自分のアーカイブのホロメン1枚を手札に戻せる。\"}"
        );
        String archivedMemberCardId = createMemberCardDefinition(
            "TBLOOM_RET_ARCHIVE",
            "測試回手目標",
            "DEBUT",
            100,
            "GREEN"
        );

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);
        int nextArchiveOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            Integer.class,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'ARCHIVE', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            archivedMemberCardId,
            nextArchiveOrder
        );
        Long archivedCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
              AND zone = 'ARCHIVE'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId,
            archivedMemberCardId
        );
        assertThat(archivedCardInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        int handAfterBloom = countZone(matchId, hostId, "HAND");
        int archiveAfterBloom = countZone(matchId, hostId, "ARCHIVE");
        String pendingContextText = jdbcTemplate.query(
            """
            SELECT context_json::text
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("context_json") : "",
            matchId,
            hostId
        );
        String zoneBeforeConfirm = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            archivedCardInstanceId
        );

        assertThat(pendingContextText).containsPattern("\"sourceActionType\"\\s*:\\s*\"BLOOM\"");
        assertThat(pendingContextText).contains("RETURN_TO_HAND");
        assertThat(handAfterBloom).isEqualTo(handBefore - 1);
        assertThat(archiveAfterBloom).isEqualTo(archiveBefore);
        assertThat(zoneBeforeConfirm).isEqualTo("ARCHIVE");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int handAfterConfirm = countZone(matchId, hostId, "HAND");
        int archiveAfterConfirm = countZone(matchId, hostId, "ARCHIVE");
        String returnedCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            archivedCardInstanceId
        );

        assertThat(handAfterConfirm).isEqualTo(handBefore);
        assertThat(archiveAfterConfirm).isEqualTo(archiveBefore - 1);
        assertThat(returnedCardZone).isEqualTo("HAND");

        String payload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'BLOOM'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payload).contains("RETURN_TO_HAND");
    }

    @Test
    void bloomShouldTriggerReturnToHandEffectFromStructuredDefinition() {
        StartedMatchContext context = createStartedMatch("bloom-structured-host", "bloom-structured-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String uniqueTag = "#TBLOOM_STRUCT_" + System.nanoTime();
        String displayName = "測試 Bloom 結構化回手";
        String debutCardId = createMemberCardDefinition("TBLOOM_STRUCT_DEBUT", displayName, "DEBUT", 120, "GREEN");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_STRUCT_FIRST",
            displayName,
            "FIRST",
            170,
            "GREEN",
            "{\"bloomEffect\":{\"effects\":[\"RETURN_TO_HAND\"],\"value\":1,"
                + "\"searchCriteria\":{\"cardType\":\"MEMBER\",\"tag\":\""
                + uniqueTag
                + "\"}}}"
        );
        String archivedMemberCardId = createMemberCardDefinition(
            "TBLOOM_STRUCT_ARCHIVE",
            "測試結構化回手目標",
            "DEBUT",
            100,
            "GREEN"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = ?::jsonb WHERE card_id = ?",
            "[\"" + uniqueTag + "\"]",
            archivedMemberCardId
        );

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);
        int nextArchiveOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            Integer.class,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'ARCHIVE', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            archivedMemberCardId,
            nextArchiveOrder
        );
        Long archivedCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
              AND zone = 'ARCHIVE'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId,
            archivedMemberCardId
        );
        assertThat(archivedCardInstanceId).isNotNull();

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        String pendingContextText = jdbcTemplate.query(
            """
            SELECT context_json::text
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("context_json") : "",
            matchId,
            hostId
        );
        assertThat(pendingContextText).containsPattern("\"sourceActionType\"\\s*:\\s*\"BLOOM\"");
        assertThat(pendingContextText).contains("RETURN_TO_HAND");

        String zoneBeforeConfirm = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            archivedCardInstanceId
        );
        assertThat(zoneBeforeConfirm).isEqualTo("ARCHIVE");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        String returnedCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            archivedCardInstanceId
        );
        assertThat(returnedCardZone).isEqualTo("HAND");

        String payload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'BLOOM'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payload).contains("RETURN_TO_HAND");
    }

    @Test
    void bloomShouldTriggerReturnToDeckTopEffectFromStructuredDefinition() {
        StartedMatchContext context = createStartedMatch("bloom-structured-deck-top-host", "bloom-structured-deck-top-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String uniqueTag = "#TBLOOM_STRUCT_DECKTOP_" + System.nanoTime();
        String displayName = "測試 Bloom 結構化回牌庫頂";
        String debutCardId = createMemberCardDefinition("TBLOOM_STRUCT_DECKTOP_DEBUT", displayName, "DEBUT", 120, "RED");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_STRUCT_DECKTOP_FIRST",
            displayName,
            "FIRST",
            170,
            "RED",
            "{\"bloomEffect\":{\"effects\":[\"RETURN_TO_DECK_TOP\"],\"value\":1,"
                + "\"searchCriteria\":{\"cardType\":\"MEMBER\",\"tag\":\""
                + uniqueTag
                + "\"}}}"
        );
        String archiveMemberCardId = createMemberCardDefinition(
            "TBLOOM_STRUCT_DECKTOP_TARGET",
            "測試結構化回牌庫頂目標",
            "DEBUT",
            110,
            "RED"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = ?::jsonb WHERE card_id = ?",
            "[\"" + uniqueTag + "\"]",
            archiveMemberCardId
        );

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);
        int nextArchiveOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            Integer.class,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'ARCHIVE', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            archiveMemberCardId,
            nextArchiveOrder
        );
        Long archiveCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
              AND zone = 'ARCHIVE'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId,
            archiveMemberCardId
        );
        assertThat(archiveCardInstanceId).isNotNull();

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        String pendingContextText = jdbcTemplate.query(
            """
            SELECT context_json::text
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("context_json") : "",
            matchId,
            hostId
        );
        assertThat(pendingContextText).containsPattern("\"sourceActionType\"\\s*:\\s*\"BLOOM\"");
        assertThat(pendingContextText).contains("RETURN_TO_DECK_TOP");

        String zoneBeforeConfirm = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            archiveCardInstanceId
        );
        assertThat(zoneBeforeConfirm).isEqualTo("ARCHIVE");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        Map<String, Object> moved = jdbcTemplate.queryForMap(
            "SELECT zone, is_face_down FROM match_cards WHERE id = ?",
            archiveCardInstanceId
        );
        assertThat(moved.get("zone")).isEqualTo("DECK");
        assertThat(moved.get("is_face_down")).isEqualTo(true);

        String payload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'BLOOM'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payload).contains("RETURN_TO_DECK_TOP");
    }

    @Test
    void bloomShouldTriggerBloomFromArchiveEffectFromStructuredDefinition() {
        StartedMatchContext context = createStartedMatch(
            "bloom-structured-archive-bloom-host",
            "bloom-structured-archive-bloom-guest"
        );
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String uniqueTag = "#TBLOOM_STRUCT_ARCHIVE_" + System.nanoTime();
        String triggerName = "測試 Structured Archive Bloom 觸發者";
        String triggerDebutCardId = createMemberCardDefinition(
            "TBLOOM_STRUCT_ARCHIVE_SRC_DEBUT",
            triggerName,
            "DEBUT",
            120,
            "RED"
        );
        String triggerFirstCardId = createMemberCardDefinition(
            "TBLOOM_STRUCT_ARCHIVE_SRC_FIRST",
            triggerName,
            "FIRST",
            170,
            "RED",
            "{\"bloomEffect\":{\"effects\":[\"BLOOM_FROM_ARCHIVE\"],"
                + "\"searchCriteria\":{\"cardType\":\"MEMBER\",\"level\":\"DEBUT\",\"tag\":\""
                + uniqueTag
                + "\"}}}"
        );
        String targetName = "測試 Structured Archive Bloom 目標";
        String targetDebutCardId = createMemberCardDefinition(
            "TBLOOM_STRUCT_ARCHIVE_TARGET_DEBUT",
            targetName,
            "DEBUT",
            110,
            "RED"
        );
        String targetFirstCardId = createMemberCardDefinition(
            "TBLOOM_STRUCT_ARCHIVE_TARGET_FIRST",
            targetName,
            "FIRST",
            160,
            "RED"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = ?::jsonb WHERE card_id = ?",
            "[\"" + uniqueTag + "\"]",
            targetDebutCardId
        );

        Long triggerHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            triggerDebutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            targetDebutCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long triggerBloomCardInstanceId = insertCardIntoHand(matchId, hostId, triggerFirstCardId);

        int nextArchiveOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            Integer.class,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'ARCHIVE', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            targetFirstCardId,
            nextArchiveOrder
        );
        Long archiveBloomCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
              AND zone = 'ARCHIVE'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId,
            targetFirstCardId
        );
        assertThat(archiveBloomCardInstanceId).isNotNull();

        Long targetHolomemId = jdbcTemplate.queryForObject(
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
            hostId,
            targetHolomemCardInstanceId
        );
        assertThat(targetHolomemId).isNotNull();

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(triggerBloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(triggerHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        String pendingContextText = jdbcTemplate.query(
            """
            SELECT context_json::text
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("context_json") : "",
            matchId,
            hostId
        );
        assertThat(pendingContextText).containsPattern("\"sourceActionType\"\\s*:\\s*\"BLOOM\"");
        assertThat(pendingContextText).contains("BLOOM_FROM_ARCHIVE");

        Map<String, Object> targetBeforeConfirm = jdbcTemplate.queryForMap(
            """
            SELECT match_card_id, card_id, current_level
            FROM match_holomems
            WHERE id = ?
            """,
            targetHolomemId
        );
        String archiveBloomCardZoneBeforeConfirm = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            archiveBloomCardInstanceId
        );
        assertThat(((Number) targetBeforeConfirm.get("match_card_id")).longValue()).isEqualTo(targetHolomemCardInstanceId);
        assertThat(targetBeforeConfirm.get("card_id")).isEqualTo(targetDebutCardId);
        assertThat(targetBeforeConfirm.get("current_level")).isEqualTo("DEBUT");
        assertThat(archiveBloomCardZoneBeforeConfirm).isEqualTo("ARCHIVE");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        Map<String, Object> targetAfter = jdbcTemplate.queryForMap(
            """
            SELECT match_card_id, card_id, current_level
            FROM match_holomems
            WHERE id = ?
            """,
            targetHolomemId
        );
        String archiveBloomCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            archiveBloomCardInstanceId
        );
        assertThat(((Number) targetAfter.get("match_card_id")).longValue()).isEqualTo(archiveBloomCardInstanceId);
        assertThat(targetAfter.get("card_id")).isEqualTo(targetFirstCardId);
        assertThat(targetAfter.get("current_level")).isEqualTo("FIRST");
        assertThat(archiveBloomCardZone).isEqualTo("STAGE");

        String payload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'BLOOM'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payload).contains("BLOOM_FROM_ARCHIVE");
        assertThat(payload).contains("\"triggerResolutionOrder\"");
        assertThat(payload).contains("\"step\": \"BLOOM_EFFECT\"");
        assertThat(payload).contains("\"step\": \"BLOOM_EVENT_HOOK\"");
        assertThat(payload).contains("\"priority\": 100");
        assertThat(payload).contains("\"priority\": 200");
    }
}
