package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchRepository;
import com.hololive.cardgame.repository.UserRepository;
import com.hololive.cardgame.support.AbstractPostgresIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class HardNpcServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String HARD_NPC_LINE_USER_ID = "npc-hard-v1";

    @Autowired
    private LobbyMatchService lobbyMatchService;

    @Autowired
    private DeckService deckService;

    @Autowired
    private HardNpcService hardNpcService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void executeHardNpcTurnShouldPerformDrawBeforeTurnCheer() {
        User host = createUser("npc-order-host");
        deckService.setupQuickDeck(host.getId());

        LobbyMatch started = lobbyMatchService.createAndStartHardNpcMatch(host.getId());
        Long matchId = started.getId();
        Long npcUserId = findHardNpcUserId();

        seedNpcCenterFromHand(matchId, npcUserId, 2);
        forceNpcTurn(matchId, npcUserId, 2);
        resolveAllPendingForUser(matchId, npcUserId);

        hardNpcService.executeHardNpcTurn(matchId, host.getId());

        List<Map<String, Object>> orderedActions = jdbcTemplate.queryForList(
            """
            SELECT action_type, action_order
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type IN ('DRAW_TURN', 'TURN_CHEER')
            ORDER BY action_order
            """,
            matchId,
            npcUserId,
            2
        );

        assertThat(orderedActions).isNotEmpty();
        assertThat(orderedActions.stream().anyMatch(row -> "DRAW_TURN".equals(row.get("action_type")))).isTrue();
        assertThat(orderedActions.stream().anyMatch(row -> "TURN_CHEER".equals(row.get("action_type")))).isTrue();

        int drawOrder = orderedActions.stream()
            .filter(row -> "DRAW_TURN".equals(row.get("action_type")))
            .map(row -> ((Number) row.get("action_order")).intValue())
            .findFirst()
            .orElse(Integer.MAX_VALUE);
        int cheerOrder = orderedActions.stream()
            .filter(row -> "TURN_CHEER".equals(row.get("action_type")))
            .map(row -> ((Number) row.get("action_order")).intValue())
            .findFirst()
            .orElse(Integer.MIN_VALUE);
        assertThat(drawOrder).isLessThan(cheerOrder);
    }

    @Test
    void executeHardNpcTurnShouldEndTurnWhenNoExecutableActionsRemain() {
        User host = createUser("npc-end-host");
        deckService.setupQuickDeck(host.getId());

        LobbyMatch started = lobbyMatchService.createAndStartHardNpcMatch(host.getId());
        Long matchId = started.getId();
        Long npcUserId = findHardNpcUserId();
        int turnNumber = 3;

        seedNpcCenterFromHand(matchId, npcUserId, turnNumber);
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB')
            """,
            matchId,
            npcUserId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            matchId,
            npcUserId
        );
        forceNpcTurn(matchId, npcUserId, turnNumber);
        resolveAllPendingForUser(matchId, npcUserId);
        seedActionUsed(matchId, npcUserId, turnNumber, 1, "DRAW_TURN");
        seedActionUsed(matchId, npcUserId, turnNumber, 2, "TURN_CHEER");

        hardNpcService.executeHardNpcTurn(matchId, host.getId());

        Integer endTurnCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'END_TURN'
            """,
            Integer.class,
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(endTurnCount).isNotNull();
        assertThat(endTurnCount).isGreaterThan(0);

        Long currentTurnPlayerId = jdbcTemplate.queryForObject(
            "SELECT current_turn_player_id FROM matches WHERE id = ?",
            Long.class,
            matchId
        );
        assertThat(currentTurnPlayerId).isEqualTo(host.getId());
    }

    @Test
    void executeHardNpcTurnShouldAttackWhenUsableArtExists() {
        User host = createUser("npc-attack-host");
        deckService.setupQuickDeck(host.getId());

        LobbyMatch started = lobbyMatchService.createAndStartHardNpcMatch(host.getId());
        Long matchId = started.getId();
        Long npcUserId = findHardNpcUserId();
        int turnNumber = 2;

        Long npcHolomemId = seedCenterFromHand(matchId, npcUserId, turnNumber - 1);
        Long hostHolomemId = seedCenterFromHand(matchId, host.getId(), turnNumber - 1);
        assertThat(npcHolomemId).isNotNull();
        assertThat(hostHolomemId).isNotNull();
        attachCheer(matchId, npcUserId, npcHolomemId, "HY01-001");
        attachCheer(matchId, npcUserId, npcHolomemId, "HY02-001");
        attachCheer(matchId, npcUserId, npcHolomemId, "HY03-001");
        attachCheer(matchId, npcUserId, npcHolomemId, "HY04-001");
        attachCheer(matchId, npcUserId, npcHolomemId, "HY05-001");
        attachCheer(matchId, npcUserId, npcHolomemId, "HY06-001");

        forceNpcTurn(matchId, npcUserId, turnNumber);
        resolveAllPendingForUser(matchId, npcUserId);
        seedActionUsed(matchId, npcUserId, turnNumber, 1, "DRAW_TURN");
        seedActionUsed(matchId, npcUserId, turnNumber, 2, "TURN_CHEER");

        hardNpcService.executeHardNpcTurn(matchId, host.getId());

        Integer attackCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'ATTACK_ART'
            """,
            Integer.class,
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(attackCount).isNotNull();
        assertThat(attackCount).isGreaterThan(0);
    }

    @Test
    void executeHardNpcTurnShouldResolveOwnTriggerEffectConfirmFromStageEnterGift() {
        User host = createUser("npc-trigger-host");
        deckService.setupQuickDeck(host.getId());

        LobbyMatch started = lobbyMatchService.createAndStartHardNpcMatch(host.getId());
        Long matchId = started.getId();
        Long npcUserId = findHardNpcUserId();
        int turnNumber = 2;

        Long npcHolomemId = seedNpcCenterFromHand(matchId, npcUserId, turnNumber - 1);
        Long npcCenterCardInstanceId = jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE id = ?
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            npcHolomemId
        );
        assertThat(npcCenterCardInstanceId).isNotNull();

        String giftHolderCardId = createMemberCardDefinition(
            "TNPC_GIFT_ENTER_HOLDER",
            "NPC 進場 Gift 持有者",
            "DEBUT",
            150,
            "YELLOW",
            "{\"キーワード\":\"ギフトNPC進場測試 \\n[センターポジション限定][ターンに1回]自分の#Justiceを持つ[DebutホロメンかSpotホロメン]がステージに出た時、自分のデッキを1枚引く。\"}"
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            giftHolderCardId,
            npcCenterCardInstanceId,
            matchId,
            npcUserId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            giftHolderCardId,
            npcHolomemId,
            matchId,
            npcUserId
        );

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
            npcUserId
        );

        String enteredCardId = createMemberCardDefinition("TNPC_GIFT_ENTER_SRC", "NPC Justice Debut", "DEBUT", 120, "BLUE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#Justice\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            enteredCardId
        );
        insertCardIntoHand(matchId, npcUserId, enteredCardId);

        forceNpcTurn(matchId, npcUserId, turnNumber);
        resolveAllPendingForUser(matchId, npcUserId);
        seedActionUsed(matchId, npcUserId, turnNumber, 1, "DRAW_TURN");
        seedActionUsed(matchId, npcUserId, turnNumber, 2, "TURN_CHEER");
        int deckBefore = countZone(matchId, npcUserId, "DECK");

        hardNpcService.executeHardNpcTurn(matchId, host.getId());

        Integer pendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            """,
            Integer.class,
            matchId,
            npcUserId
        );
        assertThat(pendingCount).isNotNull();
        assertThat(pendingCount).isZero();

        Integer giftTriggerCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'GIFT_TRIGGER'
            """,
            Integer.class,
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(giftTriggerCount).isNotNull();
        assertThat(giftTriggerCount).isGreaterThan(0);

        String latestGiftPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'GIFT_TRIGGER'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            npcUserId
        );
        assertThat(latestGiftPayload).containsPattern("\"triggerType\"\\s*:\\s*\"STAGE_ENTER\"");

        int deckAfter = countZone(matchId, npcUserId, "DECK");
        assertThat(deckAfter).isEqualTo(deckBefore - 1);
    }

    @Test
    void executeHardNpcTurnShouldResolveTriggerFollowupLookTopDeckFromStageEnterGift() {
        User host = createUser("npc-trigger-followup-host");
        deckService.setupQuickDeck(host.getId());

        LobbyMatch started = lobbyMatchService.createAndStartHardNpcMatch(host.getId());
        Long matchId = started.getId();
        Long npcUserId = findHardNpcUserId();
        int turnNumber = 2;

        Long npcHolomemId = seedNpcCenterFromHand(matchId, npcUserId, turnNumber - 1);
        Long npcCenterCardInstanceId = jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE id = ?
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            npcHolomemId
        );
        assertThat(npcCenterCardInstanceId).isNotNull();

        String giftHolderCardId = createMemberCardDefinition(
            "TNPC_GIFT_ENTER_LOOK_TOP_HOLDER",
            "NPC 進場查看牌庫頂 Gift 持有者",
            "DEBUT",
            150,
            "YELLOW",
            "{\"キーワード\":\"ギフトNPC進場查看牌庫頂測試 \\n[センターポジション限定][ターンに1回]自分の#Justiceを持つ[DebutホロメンかSpotホロメン]がステージに出た時、自分のデッキの上から1枚を見る。\"}"
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            giftHolderCardId,
            npcCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            giftHolderCardId,
            npcHolomemId,
            matchId,
            npcUserId
        );

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
            npcUserId
        );

        String enteredCardId = createMemberCardDefinition("TNPC_GIFT_ENTER_LOOK_TOP_SRC", "NPC Justice Debut 2", "DEBUT", 120, "BLUE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#Justice\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            enteredCardId
        );
        insertCardIntoHand(matchId, npcUserId, enteredCardId);

        forceNpcTurn(matchId, npcUserId, turnNumber);
        resolveAllPendingForUser(matchId, npcUserId);
        seedActionUsed(matchId, npcUserId, turnNumber, 1, "DRAW_TURN");
        seedActionUsed(matchId, npcUserId, turnNumber, 2, "TURN_CHEER");

        hardNpcService.executeHardNpcTurn(matchId, host.getId());

        Integer triggerPendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            """,
            Integer.class,
            matchId,
            npcUserId
        );
        Integer lookTopPendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'LOOK_TOP_DECK'
            """,
            Integer.class,
            matchId,
            npcUserId
        );
        assertThat(triggerPendingCount).isZero();
        assertThat(lookTopPendingCount).isZero();

        String triggerPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(triggerPayload).containsPattern("\"triggerType\"\\s*:\\s*\"STAGE_ENTER\"");
        assertThat(triggerPayload).containsPattern("\"pendingInteractionDecisionType\"\\s*:\\s*\"LOOK_TOP_DECK\"");

        String resolvedPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'INTERACTION_CONFIRMED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(resolvedPayload).containsPattern("\"decisionType\"\\s*:\\s*\"LOOK_TOP_DECK\"");
        assertThat(resolvedPayload).containsPattern("\"placement\"\\s*:\\s*\"TOP\"");
    }

    @Test
    void executeHardNpcTurnShouldResolveTriggerFollowupReorderDeckBottomFromStageEnterGift() {
        User host = createUser("npc-trigger-reorder-host");
        deckService.setupQuickDeck(host.getId());

        LobbyMatch started = lobbyMatchService.createAndStartHardNpcMatch(host.getId());
        Long matchId = started.getId();
        Long npcUserId = findHardNpcUserId();
        int turnNumber = 2;

        Long npcHolomemId = seedNpcCenterFromHand(matchId, npcUserId, turnNumber - 1);
        Long npcCenterCardInstanceId = jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE id = ?
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            npcHolomemId
        );
        assertThat(npcCenterCardInstanceId).isNotNull();

        String giftHolderCardId = createMemberCardDefinition(
            "TNPC_GIFT_ENTER_REORDER_HOLDER",
            "NPC 進場排序牌庫底 Gift 持有者",
            "DEBUT",
            150,
            "YELLOW",
            "{\"キーワード\":\"ギフトNPC進場排序牌庫底測試 \\n[センターポジション限定][ターンに1回]自分の#Justiceを持つ[DebutホロメンかSpotホロメン]がステージに出た時、自分のデッキの上から4枚を見る。その中から、#ORDER_TESTを持つホロメンを1枚手札に加える。そして残ったカードを好きな順でデッキの下に戻す。\"}"
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            giftHolderCardId,
            npcCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            giftHolderCardId,
            npcHolomemId,
            matchId,
            npcUserId
        );

        String reorderMatchCardId = createMemberCardDefinition("TNPC_REORDER_MATCH", "排序命中", "DEBUT", 90, "RED");
        String reorderMissCardA = createMemberCardDefinition("TNPC_REORDER_MISS_A", "排序未命中 A", "DEBUT", 90, "BLUE");
        String reorderMissCardB = createMemberCardDefinition("TNPC_REORDER_MISS_B", "排序未命中 B", "DEBUT", 90, "GREEN");
        String reorderMissCardC = createMemberCardDefinition("TNPC_REORDER_MISS_C", "排序未命中 C", "DEBUT", 90, "WHITE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#ORDER_TEST\"]'::jsonb WHERE card_id = ?",
            reorderMatchCardId
        );

        insertCardIntoDeckTop(matchId, npcUserId, reorderMissCardA);
        insertCardIntoDeckTop(matchId, npcUserId, reorderMissCardB);
        insertCardIntoDeckTop(matchId, npcUserId, reorderMissCardC);
        insertCardIntoDeckTop(matchId, npcUserId, reorderMatchCardId);

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
            npcUserId
        );

        String enteredCardId = createMemberCardDefinition("TNPC_GIFT_ENTER_REORDER_SRC", "NPC Justice Debut 3", "DEBUT", 120, "BLUE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#Justice\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            enteredCardId
        );
        insertCardIntoHand(matchId, npcUserId, enteredCardId);

        forceNpcTurn(matchId, npcUserId, turnNumber);
        resolveAllPendingForUser(matchId, npcUserId);
        seedActionUsed(matchId, npcUserId, turnNumber, 1, "DRAW_TURN");
        seedActionUsed(matchId, npcUserId, turnNumber, 2, "TURN_CHEER");

        hardNpcService.executeHardNpcTurn(matchId, host.getId());

        Integer triggerPendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            """,
            Integer.class,
            matchId,
            npcUserId
        );
        Integer reorderPendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'REORDER_DECK_BOTTOM'
            """,
            Integer.class,
            matchId,
            npcUserId
        );
        assertThat(triggerPendingCount).isZero();
        assertThat(reorderPendingCount).isZero();

        String triggerPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(triggerPayload).containsPattern("\"triggerType\"\\s*:\\s*\"STAGE_ENTER\"");
        assertThat(triggerPayload).containsPattern("\"pendingInteractionDecisionType\"\\s*:\\s*\"REORDER_DECK_BOTTOM\"");

        String resolvedPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'INTERACTION_CONFIRMED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(resolvedPayload).containsPattern("\"decisionType\"\\s*:\\s*\"REORDER_DECK_BOTTOM\"");
    }

    @Test
    void executeHardNpcTurnShouldResolveTriggerFollowupLookOpponentHandFromStageEnterGift() {
        User host = createUser("npc-trigger-look-opponent-hand-host");
        deckService.setupQuickDeck(host.getId());

        LobbyMatch started = lobbyMatchService.createAndStartHardNpcMatch(host.getId());
        Long matchId = started.getId();
        Long npcUserId = findHardNpcUserId();
        int turnNumber = 2;

        Long npcHolomemId = seedNpcCenterFromHand(matchId, npcUserId, turnNumber - 1);
        Long npcCenterCardInstanceId = jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE id = ?
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            npcHolomemId
        );
        assertThat(npcCenterCardInstanceId).isNotNull();

        String giftHolderCardId = createMemberCardDefinition(
            "TNPC_LOOK_OP_H",
            "NPC 看對手手牌",
            "DEBUT",
            150,
            "YELLOW",
            "{\"キーワード\":\"ギフトNPC進場查看對手手牌測試 \\n[センターポジション限定][ターンに1回]自分の#Justiceを持つ[DebutホロメンかSpotホロメン]がステージに出た時、相手の手札を見る。\"}"
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            giftHolderCardId,
            npcCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            giftHolderCardId,
            npcHolomemId,
            matchId,
            npcUserId
        );

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
            npcUserId
        );

        String enteredCardId = createMemberCardDefinition("TNPC_LOOK_OP_S", "NPC JD4", "DEBUT", 120, "BLUE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#Justice\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            enteredCardId
        );
        insertCardIntoHand(matchId, npcUserId, enteredCardId);

        forceNpcTurn(matchId, npcUserId, turnNumber);
        resolveAllPendingForUser(matchId, npcUserId);
        seedActionUsed(matchId, npcUserId, turnNumber, 1, "DRAW_TURN");
        seedActionUsed(matchId, npcUserId, turnNumber, 2, "TURN_CHEER");

        hardNpcService.executeHardNpcTurn(matchId, host.getId());

        Integer triggerPendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            """,
            Integer.class,
            matchId,
            npcUserId
        );
        Integer lookOpponentHandPendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'LOOK_OPPONENT_HAND'
            """,
            Integer.class,
            matchId,
            npcUserId
        );
        assertThat(triggerPendingCount).isZero();
        assertThat(lookOpponentHandPendingCount).isZero();

        String triggerPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(triggerPayload).containsPattern("\"triggerType\"\\s*:\\s*\"STAGE_ENTER\"");
        assertThat(triggerPayload).containsPattern("\"pendingInteractionDecisionType\"\\s*:\\s*\"LOOK_OPPONENT_HAND\"");

        String resolvedPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'INTERACTION_CONFIRMED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(resolvedPayload).containsPattern("\"decisionType\"\\s*:\\s*\"LOOK_OPPONENT_HAND\"");
    }

    @Test
    void executeHardNpcTurnShouldResolveTriggerFollowupLookHolopowerFromStageEnterGift() {
        User host = createUser("npc-trigger-look-holopower-host");
        deckService.setupQuickDeck(host.getId());

        LobbyMatch started = lobbyMatchService.createAndStartHardNpcMatch(host.getId());
        Long matchId = started.getId();
        Long npcUserId = findHardNpcUserId();
        int turnNumber = 2;

        Long npcHolomemId = seedNpcCenterFromHand(matchId, npcUserId, turnNumber - 1);
        Long npcCenterCardInstanceId = jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE id = ?
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            npcHolomemId
        );
        assertThat(npcCenterCardInstanceId).isNotNull();

        String giftHolderCardId = createMemberCardDefinition(
            "TNPC_LOOK_HP_H",
            "NPC 看 Holopower",
            "DEBUT",
            150,
            "YELLOW",
            "{\"キーワード\":\"ギフトNPC進場查看Holopower測試 \\n[センターポジション限定][ターンに1回]自分の#Justiceを持つ[DebutホロメンかSpotホロメン]がステージに出た時、自分のホロパワーを見る。\"}"
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            giftHolderCardId,
            npcCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            giftHolderCardId,
            npcHolomemId,
            matchId,
            npcUserId
        );

        jdbcTemplate.update(
            """
            WITH target AS (
              SELECT id
              FROM match_cards
              WHERE match_id = ?
                AND owner_user_id = ?
                AND zone = 'DECK'
              ORDER BY order_index NULLS LAST, id
              LIMIT 1
            )
            UPDATE match_cards
            SET zone = 'HOLOPOWER',
                order_index = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN (SELECT id FROM target)
            """,
            matchId,
            npcUserId
        );

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
            npcUserId
        );

        String enteredCardId = createMemberCardDefinition("TNPC_LOOK_HP_S", "NPC JD5", "DEBUT", 120, "BLUE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#Justice\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            enteredCardId
        );
        insertCardIntoHand(matchId, npcUserId, enteredCardId);

        forceNpcTurn(matchId, npcUserId, turnNumber);
        resolveAllPendingForUser(matchId, npcUserId);
        seedActionUsed(matchId, npcUserId, turnNumber, 1, "DRAW_TURN");
        seedActionUsed(matchId, npcUserId, turnNumber, 2, "TURN_CHEER");

        hardNpcService.executeHardNpcTurn(matchId, host.getId());

        Integer triggerPendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            """,
            Integer.class,
            matchId,
            npcUserId
        );
        Integer lookHolopowerPendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'LOOK_HOLOPOWER'
            """,
            Integer.class,
            matchId,
            npcUserId
        );
        assertThat(triggerPendingCount).isZero();
        assertThat(lookHolopowerPendingCount).isZero();

        String triggerPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(triggerPayload).containsPattern("\"triggerType\"\\s*:\\s*\"STAGE_ENTER\"");
        assertThat(triggerPayload).containsPattern("\"pendingInteractionDecisionType\"\\s*:\\s*\"LOOK_HOLOPOWER\"");

        String resolvedPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'INTERACTION_CONFIRMED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            npcUserId,
            turnNumber
        );
        assertThat(resolvedPayload).containsPattern("\"decisionType\"\\s*:\\s*\"LOOK_HOLOPOWER\"");
    }

    private void seedActionUsed(Long matchId, Long userId, int turnNumber, int actionOrder, String actionType) {
        jdbcTemplate.update(
            """
            INSERT INTO match_actions (
                match_id,
                user_id,
                action_type,
                payload,
                turn_number,
                action_order,
                executed_at
            ) VALUES (?, ?, ?, CAST('{}' AS jsonb), ?, ?, CURRENT_TIMESTAMP)
            """,
            matchId,
            userId,
            actionType,
            turnNumber,
            actionOrder
        );
    }

    private Long seedNpcCenterFromHand(Long matchId, Long npcUserId, int enteredTurnNumber) {
        return seedCenterFromHand(matchId, npcUserId, enteredTurnNumber);
    }

    private Long seedCenterFromHand(Long matchId, Long userId, int enteredTurnNumber) {
        Map<String, Object> handMember = jdbcTemplate.query(
            """
            SELECT mc.id AS match_card_id, mc.card_id
            FROM match_cards mc
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone IN ('HAND', 'DECK')
              AND m.level_type IN ('DEBUT', 'SPOT')
            ORDER BY CASE mc.zone WHEN 'HAND' THEN 0 ELSE 1 END, mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "match_card_id",
                    rs.getLong("match_card_id"),
                    "card_id",
                    rs.getString("card_id")
                );
            },
            matchId,
            userId
        );
        assertThat(handMember).isNotNull();

        Long matchCardId = ((Number) handMember.get("match_card_id")).longValue();
        String cardId = String.valueOf(handMember.get("card_id"));

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            matchCardId,
            matchId,
            userId
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
            ) VALUES (?, ?, ?, ?, 'CENTER', FALSE, FALSE, 0, 'DEBUT', ?)
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            matchCardId,
            cardId,
            enteredTurnNumber
        );
        assertThat(holomemId).isNotNull();

        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_stack_cards (match_holomem_id, match_card_id, stack_order)
            VALUES (?, ?, 1)
            ON CONFLICT (match_card_id) DO NOTHING
            """,
            holomemId,
            matchCardId
        );
        return holomemId;
    }

    private void attachCheer(Long matchId, Long userId, Long holomemId, String cheerCardId) {
        Long cheerInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CHEER_DECK'
              AND card_id = ?
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            cheerCardId
        );
        if (cheerInstanceId == null) {
            return;
        }
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            cheerInstanceId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
            VALUES (?, ?, FALSE)
            """,
            holomemId,
            cheerCardId
        );
    }

    private void forceNpcTurn(Long matchId, Long npcUserId, int turnNumber) {
        var match = matchRepository.findByIdForUpdate(matchId).orElseThrow();
        match.setCurrentTurnPlayerId(npcUserId);
        match.setTurnNumber(turnNumber);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setLobbyStatus("STARTED");
        match.setStatus("active");
        match.setUpdatedAt(LocalDateTime.now());
        matchRepository.saveAndFlush(match);
    }

    private void resolveAllPendingForUser(Long matchId, Long userId) {
        jdbcTemplate.update(
            """
            UPDATE match_pending_decisions
            SET status = 'RESOLVED',
                resolved_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
            """,
            matchId,
            userId
        );
    }

    private String createMemberCardDefinition(
        String prefix,
        String displayName,
        String levelType,
        int hp,
        String mainColor
    ) {
        return createMemberCardDefinition(prefix, displayName, levelType, hp, mainColor, "null");
    }

    private String createMemberCardDefinition(
        String prefix,
        String displayName,
        String levelType,
        int hp,
        String mainColor,
        String passiveEffectJson
    ) {
        String cardId = prefix + "_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cardId,
            displayName
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, ?, ?, ?, NULL, ?, CAST(? AS jsonb), NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cardId,
            hp,
            levelType,
            mainColor,
            bloomLevelOf(levelType),
            passiveEffectJson
        );
        return cardId;
    }

    private Long insertCardIntoHand(Long matchId, Long ownerUserId, String cardId) {
        Integer nextHandOrder = jdbcTemplate.queryForObject(
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
            nextHandOrder == null ? 1 : nextHandOrder
        );
        return jdbcTemplate.query(
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
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            cardId
        );
    }

    private Long insertCardIntoDeckTop(Long matchId, Long ownerUserId, String cardId) {
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
        return jdbcTemplate.query(
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
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            cardId
        );
    }

    private int countZone(Long matchId, Long userId, String zone) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            zone
        );
        return count == null ? 0 : count;
    }

    private int bloomLevelOf(String levelType) {
        if (levelType == null) {
            return 0;
        }
        return switch (levelType.trim().toUpperCase()) {
            case "FIRST" -> 1;
            case "SECOND" -> 2;
            default -> 0;
        };
    }

    private Long findHardNpcUserId() {
        return userRepository.findByLineUserId(HARD_NPC_LINE_USER_ID)
            .orElseThrow()
            .getId();
    }

    private User createUser(String prefix) {
        User user = new User();
        String unique = prefix + "_" + System.nanoTime();
        user.setLineUserId(unique);
        user.setDisplayName(unique);
        user.setAvatarUrl("https://example.com/" + unique + ".png");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }
}
