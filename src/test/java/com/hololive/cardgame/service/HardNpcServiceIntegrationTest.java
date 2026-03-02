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
