package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.dto.AttachCheerActionRequest;
import com.hololive.cardgame.dto.AttackArtActionRequest;
import com.hololive.cardgame.dto.GameStateResponse;
import com.hololive.cardgame.dto.PlaySupportActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.repository.UserRepository;
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
class MatchActionServiceIntegrationTest {

    @Autowired
    private LobbyMatchService lobbyMatchService;

    @Autowired
    private MatchActionService matchActionService;

    @Autowired
    private MatchGameStateService matchGameStateService;

    @Autowired
    private DeckService deckService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startMatchShouldPersistOshiAsRealMatchCardInstance() {
        StartedMatchContext context = createStartedMatch("oshi-host", "oshi-guest");

        assertZoneCount(context.matchId(), context.hostId(), "OSHI", 1);
        assertZoneCount(context.matchId(), context.guestId(), "OSHI", 1);

        Long hostOshiInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'OSHI'
            LIMIT 1
            """,
            Long.class,
            context.matchId(),
            context.hostId()
        );
        assertThat(hostOshiInstanceId).isNotNull().isPositive();

        GameStateResponse hostState = matchGameStateService.getGameStateForUser(context.matchId(), context.hostId());
        var hostPlayerState = hostState.getPlayers().stream()
            .filter(player -> context.hostId().equals(player.getUserId()))
            .findFirst()
            .orElseThrow();
        var oshiZone = hostPlayerState.getBoardZones().stream()
            .filter(zone -> "OSHI".equals(zone.getZone()))
            .findFirst()
            .orElseThrow();

        assertThat(oshiZone.getCards()).hasSize(1);
        assertThat(oshiZone.getCards().get(0).getCardInstanceId()).isNotNull().isPositive();
    }

    @Test
    void actionPipelineShouldApplyPlayAttachAttackAndEndTurn() {
        StartedMatchContext context = createStartedMatch("action-host", "action-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long memberHandCardInstanceId = findMemberCardFromHand(matchId, hostId);
        if (memberHandCardInstanceId == null) {
            moveOneMemberFromDeckToHand(matchId, hostId);
            memberHandCardInstanceId = findMemberCardFromHand(matchId, hostId);
        }
        assertThat(memberHandCardInstanceId).isNotNull();

        PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
        playToStage.setCardInstanceId(memberHandCardInstanceId);
        playToStage.setTargetZone("CENTER");
        matchActionService.playToStage(matchId, hostId, playToStage);

        Integer hostCenterCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomems WHERE match_id = ? AND owner_user_id = ? AND zone = 'CENTER'",
            Integer.class,
            matchId,
            hostId
        );
        assertThat(hostCenterCount).isEqualTo(1);

        Long centerCardInstanceId = jdbcTemplate.queryForObject(
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
        Long cheerCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CHEER_DECK'
            ORDER BY order_index, id
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        assertThat(centerCardInstanceId).isNotNull();
        assertThat(cheerCardInstanceId).isNotNull();

        AttachCheerActionRequest attachCheer = new AttachCheerActionRequest();
        attachCheer.setCheerCardInstanceId(cheerCardInstanceId);
        attachCheer.setTargetHolomemCardInstanceId(centerCardInstanceId);
        matchActionService.attachCheer(matchId, hostId, attachCheer);

        Integer attachedCheerCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomem_cheers c
            JOIN match_holomems h ON h.id = c.match_holomem_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
            """,
            Integer.class,
            matchId,
            hostId,
            centerCardInstanceId
        );
        assertThat(attachedCheerCount).isEqualTo(1);

        AttackArtActionRequest attackArt = new AttackArtActionRequest();
        attackArt.setAttackerCardInstanceId(centerCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attackArt);

        assertZoneCount(matchId, guestId, "LIFE", 4);
        String phaseAfterAttack = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(phaseAfterAttack).isEqualTo("END");

        matchActionService.endTurn(matchId, hostId);

        Long currentTurnUserId = jdbcTemplate.queryForObject(
            "SELECT current_turn_player_id FROM matches WHERE id = ?",
            Long.class,
            matchId
        );
        Integer turnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        String phaseAfterEndTurn = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(currentTurnUserId).isEqualTo(guestId);
        assertThat(turnNumber).isEqualTo(2);
        assertThat(phaseAfterEndTurn).isEqualTo("MAIN");

        List<String> actionTypes = jdbcTemplate.queryForList(
            "SELECT action_type FROM match_actions WHERE match_id = ? ORDER BY id",
            String.class,
            matchId
        );
        assertThat(actionTypes).contains("PLAY_TO_STAGE", "ATTACH_CHEER", "ATTACK_ART", "END_TURN");
    }

    @Test
    void playSupportShouldMoveCardToArchiveAndApplyDrawEffect() {
        StartedMatchContext context = createStartedMatch("support-host", "support-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        int handBefore = countZone(matchId, hostId, "HAND");
        int deckBefore = countZone(matchId, hostId, "DECK");

        String supportCardId = "TSUP" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "測試抽牌支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'DRAW', CAST(? AS jsonb), 'SELF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "{\"type\":\"DRAW\",\"value\":2}"
        );

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
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            supportCardId,
            nextHandOrder
        );
        Long supportCardInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            supportCardId
        );
        assertThat(supportCardInstanceId).isNotNull();

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        Integer supportInArchive = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            Integer.class,
            supportCardInstanceId,
            matchId,
            hostId
        );
        assertThat(supportInArchive).isEqualTo(1);
        assertThat(countZone(matchId, hostId, "HAND")).isEqualTo(handBefore + 2);
        assertThat(countZone(matchId, hostId, "DECK")).isEqualTo(deckBefore - 2);

        String latestPlaySupportPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'PLAY_SUPPORT'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : null,
            matchId
        );
        assertThat(latestPlaySupportPayload).contains("DRAW");
        assertThat(latestPlaySupportPayload).contains("drawApplied");
    }

    @Test
    void playSupportShouldAttachCheerToTargetHolomem() {
        StartedMatchContext context = createStartedMatch("support-cheer-host", "support-cheer-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long memberHandCardInstanceId = findMemberCardFromHand(matchId, hostId);
        if (memberHandCardInstanceId == null) {
            moveOneMemberFromDeckToHand(matchId, hostId);
            memberHandCardInstanceId = findMemberCardFromHand(matchId, hostId);
        }
        assertThat(memberHandCardInstanceId).isNotNull();

        PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
        playToStage.setCardInstanceId(memberHandCardInstanceId);
        playToStage.setTargetZone("CENTER");
        matchActionService.playToStage(matchId, hostId, playToStage);

        Long centerCardInstanceId = jdbcTemplate.queryForObject(
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
        assertThat(centerCardInstanceId).isNotNull();

        String supportCardId = "TSUP_ADD_CHEER_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "測試附加 Cheer 支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'ADD_CHEER', CAST(? AS jsonb), 'SELF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "{\"type\":\"ADD_CHEER\",\"value\":1}"
        );

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
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            supportCardId,
            nextHandOrder
        );
        Long supportCardInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            supportCardId
        );
        assertThat(supportCardInstanceId).isNotNull();

        int beforeCheerAttached = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomem_cheers c
            JOIN match_holomems h ON h.id = c.match_holomem_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
            """,
            Integer.class,
            matchId,
            hostId,
            centerCardInstanceId
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        request.setTargetHolomemCardInstanceId(centerCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        int afterCheerAttached = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomem_cheers c
            JOIN match_holomems h ON h.id = c.match_holomem_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
            """,
            Integer.class,
            matchId,
            hostId,
            centerCardInstanceId
        );
        assertThat(afterCheerAttached).isEqualTo(beforeCheerAttached + 1);
    }

    @Test
    void playSupportDamageShouldDownOpponentCenterAndReduceLife() {
        StartedMatchContext context = createStartedMatch("support-damage-host", "support-damage-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long guestMemberFromHand = findMemberCardFromHand(matchId, guestId);
        if (guestMemberFromHand == null) {
            moveOneMemberFromDeckToHand(matchId, guestId);
            guestMemberFromHand = findMemberCardFromHand(matchId, guestId);
        }
        assertThat(guestMemberFromHand).isNotNull();

        String guestCardId = jdbcTemplate.queryForObject(
            "SELECT card_id FROM match_cards WHERE id = ?",
            String.class,
            guestMemberFromHand
        );
        String guestLevel = jdbcTemplate.query(
            "SELECT level_type FROM member_cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getString("level_type") : "DEBUT",
            guestCardId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            guestMemberFromHand
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomems (
                match_id, owner_user_id, match_card_id, card_id, zone, is_rested, is_face_down, damage_taken, current_level
            ) VALUES (?, ?, ?, ?, 'CENTER', FALSE, FALSE, 0, ?)
            """,
            matchId,
            guestId,
            guestMemberFromHand,
            guestCardId,
            guestLevel == null ? "DEBUT" : guestLevel
        );

        String supportCardId = "TSUP_DAMAGE_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "測試傷害支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'DAMAGE', CAST(? AS jsonb), 'ENEMY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "{\"type\":\"DAMAGE\",\"value\":999}"
        );
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
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            supportCardId,
            nextHandOrder
        );
        Long supportCardInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            supportCardId
        );
        assertThat(supportCardInstanceId).isNotNull();

        int lifeBefore = jdbcTemplate.queryForObject(
            "SELECT COALESCE(current_life, 0) FROM match_players WHERE match_id = ? AND user_id = ?",
            Integer.class,
            matchId,
            guestId
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        int opponentCenterCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            """,
            Integer.class,
            matchId,
            guestId
        );
        int lifeAfter = jdbcTemplate.queryForObject(
            "SELECT COALESCE(current_life, 0) FROM match_players WHERE match_id = ? AND user_id = ?",
            Integer.class,
            matchId,
            guestId
        );
        assertThat(opponentCenterCount).isEqualTo(0);
        assertThat(lifeAfter).isEqualTo(lifeBefore - 1);
    }

    @Test
    void playSupportHealShouldRecoverTargetHolomemDamage() {
        StartedMatchContext context = createStartedMatch("support-heal-host", "support-heal-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long memberHandCardInstanceId = findMemberCardFromHand(matchId, hostId);
        if (memberHandCardInstanceId == null) {
            moveOneMemberFromDeckToHand(matchId, hostId);
            memberHandCardInstanceId = findMemberCardFromHand(matchId, hostId);
        }
        assertThat(memberHandCardInstanceId).isNotNull();

        PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
        playToStage.setCardInstanceId(memberHandCardInstanceId);
        playToStage.setTargetZone("CENTER");
        matchActionService.playToStage(matchId, hostId, playToStage);

        Long centerCardInstanceId = jdbcTemplate.queryForObject(
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
        assertThat(centerCardInstanceId).isNotNull();

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = 80
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            centerCardInstanceId
        );

        String supportCardId = "TSUP_HEAL_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "測試回復支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'HEAL', CAST(? AS jsonb), 'SELF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "{\"type\":\"HEAL\",\"value\":30}"
        );

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
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            supportCardId,
            nextHandOrder
        );
        Long supportCardInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            supportCardId
        );
        assertThat(supportCardInstanceId).isNotNull();

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        request.setTargetHolomemCardInstanceId(centerCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        Integer damageAfter = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(damage_taken, 0)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            Integer.class,
            matchId,
            hostId,
            centerCardInstanceId
        );
        assertThat(damageAfter).isEqualTo(50);
    }

    @Test
    void playSupportRemoveCheerShouldDetachAndArchiveCheerCard() {
        StartedMatchContext context = createStartedMatch("support-rm-cheer-host", "support-rm-cheer-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long memberHandCardInstanceId = findMemberCardFromHand(matchId, hostId);
        if (memberHandCardInstanceId == null) {
            moveOneMemberFromDeckToHand(matchId, hostId);
            memberHandCardInstanceId = findMemberCardFromHand(matchId, hostId);
        }
        assertThat(memberHandCardInstanceId).isNotNull();

        PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
        playToStage.setCardInstanceId(memberHandCardInstanceId);
        playToStage.setTargetZone("CENTER");
        matchActionService.playToStage(matchId, hostId, playToStage);

        Long centerCardInstanceId = jdbcTemplate.queryForObject(
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
        Long cheerCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CHEER_DECK'
            ORDER BY order_index, id
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        assertThat(centerCardInstanceId).isNotNull();
        assertThat(cheerCardInstanceId).isNotNull();

        AttachCheerActionRequest attach = new AttachCheerActionRequest();
        attach.setCheerCardInstanceId(cheerCardInstanceId);
        attach.setTargetHolomemCardInstanceId(centerCardInstanceId);
        matchActionService.attachCheer(matchId, hostId, attach);

        int attachedBefore = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomem_cheers c
            JOIN match_holomems h ON h.id = c.match_holomem_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
            """,
            Integer.class,
            matchId,
            hostId,
            centerCardInstanceId
        );
        int stageCheerBefore = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards mc
            JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'STAGE'
            """,
            Integer.class,
            matchId,
            hostId
        );
        assertThat(attachedBefore).isGreaterThanOrEqualTo(1);
        assertThat(stageCheerBefore).isGreaterThanOrEqualTo(1);

        String supportCardId = "TSUP_REMOVE_CHEER_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "測試移除 Cheer 支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'REMOVE_CHEER', CAST(? AS jsonb), 'SELF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "{\"type\":\"REMOVE_CHEER\",\"value\":1}"
        );
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
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            supportCardId,
            nextHandOrder
        );
        Long supportCardInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            supportCardId
        );
        assertThat(supportCardInstanceId).isNotNull();

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        request.setTargetHolomemCardInstanceId(centerCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        int attachedAfter = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomem_cheers c
            JOIN match_holomems h ON h.id = c.match_holomem_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
            """,
            Integer.class,
            matchId,
            hostId,
            centerCardInstanceId
        );
        int stageCheerAfter = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards mc
            JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'STAGE'
            """,
            Integer.class,
            matchId,
            hostId
        );
        int archiveCheerAfter = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards mc
            JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'ARCHIVE'
            """,
            Integer.class,
            matchId,
            hostId
        );
        assertThat(attachedAfter).isEqualTo(attachedBefore - 1);
        assertThat(stageCheerAfter).isEqualTo(stageCheerBefore - 1);
        assertThat(archiveCheerAfter).isGreaterThanOrEqualTo(1);
    }

    @Test
    void playSupportMoveZoneShouldMoveOpponentCenterToBackAndRest() {
        StartedMatchContext context = createStartedMatch("support-move-host", "support-move-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long guestMemberFromHand = findMemberCardFromHand(matchId, guestId);
        if (guestMemberFromHand == null) {
            moveOneMemberFromDeckToHand(matchId, guestId);
            guestMemberFromHand = findMemberCardFromHand(matchId, guestId);
        }
        assertThat(guestMemberFromHand).isNotNull();

        String guestCardId = jdbcTemplate.queryForObject(
            "SELECT card_id FROM match_cards WHERE id = ?",
            String.class,
            guestMemberFromHand
        );
        String guestLevel = jdbcTemplate.query(
            "SELECT level_type FROM member_cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getString("level_type") : "DEBUT",
            guestCardId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            guestMemberFromHand
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomems (
                match_id, owner_user_id, match_card_id, card_id, zone, is_rested, is_face_down, damage_taken, current_level
            ) VALUES (?, ?, ?, ?, 'CENTER', FALSE, FALSE, 0, ?)
            """,
            matchId,
            guestId,
            guestMemberFromHand,
            guestCardId,
            guestLevel == null ? "DEBUT" : guestLevel
        );

        String supportCardId = "TSUP_MOVE_ZONE_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "測試移位支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'MOVE_ZONE', CAST(? AS jsonb), 'ENEMY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "{\"type\":\"MOVE_ZONE\",\"toZone\":\"BACK\",\"rawText\":\"相手をお休みさせてバックポジションに移動\"}"
        );
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
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            supportCardId,
            nextHandOrder
        );
        Long supportCardInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            supportCardId
        );
        assertThat(supportCardInstanceId).isNotNull();

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        request.setTargetHolomemCardInstanceId(guestMemberFromHand);
        matchActionService.playSupport(matchId, hostId, request);

        Map<String, Object> movedHolomem = jdbcTemplate.queryForMap(
            """
            SELECT zone, is_rested
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestMemberFromHand
        );
        assertThat(movedHolomem.get("zone")).isEqualTo("BACK");
        assertThat(movedHolomem.get("is_rested")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void playSupportSearchShouldMoveMatchingCardFromDeckToHand() {
        StartedMatchContext context = createStartedMatch("support-search-host", "support-search-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String searchableCardId = "TSEARCH_MEMBER_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, rarity, expansion_code, card_no, tags_json, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', 'C', 'TEST', ?, '["#TEST"]'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            searchableCardId,
            "測試搜尋成員",
            searchableCardId
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, 80, 'DEBUT', 'RED', NULL, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            searchableCardId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'DECK', 0, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            searchableCardId
        );
        Long searchableCardInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            searchableCardId
        );
        assertThat(searchableCardInstanceId).isNotNull();

        String supportCardId = "TSUP_SEARCH_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "測試搜尋支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'SEARCH', CAST(? AS jsonb), 'SELF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "{\"type\":\"SEARCH\",\"value\":1,\"searchCriteria\":{\"cardType\":\"MEMBER\",\"level\":\"DEBUT\",\"tag\":\"#TEST\"}}"
        );
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
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            supportCardId,
            nextHandOrder
        );
        Long supportCardInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            supportCardId
        );
        assertThat(supportCardInstanceId).isNotNull();

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        Integer movedToHand = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            Integer.class,
            searchableCardInstanceId,
            matchId,
            hostId
        );
        assertThat(movedToHand).isEqualTo(1);
    }

    @Test
    void playSupportSearchShouldRespectSelectedCardInstanceIds() {
        StartedMatchContext context = createStartedMatch("support-search-select-host", "support-search-select-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String searchableCardA = "TSEARCH_A_" + System.nanoTime();
        String searchableCardB = "TSEARCH_B_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, rarity, expansion_code, card_no, tags_json, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', 'C', 'TEST', ?, '[]'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            searchableCardA,
            "測試搜尋 A",
            searchableCardA
        );
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, rarity, expansion_code, card_no, tags_json, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', 'C', 'TEST', ?, '[]'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            searchableCardB,
            "測試搜尋 B",
            searchableCardB
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, 70, 'DEBUT', 'RED', NULL, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            searchableCardA
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, 70, 'DEBUT', 'RED', NULL, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            searchableCardB
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'DECK', 0, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            searchableCardA
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'DECK', 1, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            searchableCardB
        );
        Long cardInstanceA = jdbcTemplate.queryForObject(
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
            hostId,
            searchableCardA
        );
        Long cardInstanceB = jdbcTemplate.queryForObject(
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
            hostId,
            searchableCardB
        );
        assertThat(cardInstanceA).isNotNull();
        assertThat(cardInstanceB).isNotNull();

        String supportCardId = "TSUP_SEARCH_SELECT_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "測試搜尋選牌支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'SEARCH', CAST(? AS jsonb), 'SELF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "{\"type\":\"SEARCH\",\"value\":1,\"searchCriteria\":{\"cardType\":\"MEMBER\"}}"
        );
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
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            supportCardId,
            nextHandOrder
        );
        Long supportCardInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            supportCardId
        );
        assertThat(supportCardInstanceId).isNotNull();

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        request.setSelectedCardInstanceIds(List.of(cardInstanceB));
        matchActionService.playSupport(matchId, hostId, request);

        Integer movedBToHand = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            Integer.class,
            cardInstanceB,
            matchId,
            hostId
        );
        Integer remainedAInDeck = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            cardInstanceA,
            matchId,
            hostId
        );
        assertThat(movedBToHand).isEqualTo(1);
        assertThat(remainedAInDeck).isEqualTo(1);
    }

    @Test
    void playSupportBuffShouldIncreaseDamageThisTurnAndExpireAfterEndTurn() {
        StartedMatchContext context = createStartedMatch("support-buff-host", "support-buff-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String targetCardId = "TBUFF_TARGET_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            targetCardId,
            "測試 BUFF 目標"
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, 300, 'DEBUT', 'BLUE', NULL, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            targetCardId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'STAGE', NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            guestId,
            targetCardId
        );
        Long targetCardInstanceId = jdbcTemplate.queryForObject(
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
            guestId,
            targetCardId
        );
        assertThat(targetCardInstanceId).isNotNull();
        jdbcTemplate.update(
            """
            INSERT INTO match_holomems (
                match_id, owner_user_id, match_card_id, card_id, zone, is_rested, is_face_down, damage_taken, current_level
            ) VALUES (?, ?, ?, ?, 'CENTER', FALSE, FALSE, 0, 'DEBUT')
            """,
            matchId,
            guestId,
            targetCardInstanceId,
            targetCardId
        );

        String buffSupportCardId = "TSUP_BUFF_" + System.nanoTime();
        String damageSupportCardId = "TSUP_DAMAGE_WITH_BUFF_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            buffSupportCardId,
            "測試 BUFF 支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            damageSupportCardId,
            "測試 BUFF 後傷害支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'BUFF', CAST(? AS jsonb), 'SELF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            buffSupportCardId,
            "{\"type\":\"BUFF\",\"value\":30}"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'DAMAGE', CAST(? AS jsonb), 'ENEMY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            damageSupportCardId,
            "{\"type\":\"DAMAGE\",\"value\":40}"
        );

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
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            buffSupportCardId,
            nextHandOrder
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            damageSupportCardId,
            nextHandOrder + 1
        );

        Long buffSupportInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            buffSupportCardId
        );
        Long damageSupportInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            damageSupportCardId
        );
        assertThat(buffSupportInstanceId).isNotNull();
        assertThat(damageSupportInstanceId).isNotNull();

        PlaySupportActionRequest buffRequest = new PlaySupportActionRequest();
        buffRequest.setCardInstanceId(buffSupportInstanceId);
        matchActionService.playSupport(matchId, hostId, buffRequest);

        Integer modifierInTurn = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(modifier_value), 0)
            FROM match_turn_effects
            WHERE match_id = ?
              AND source_user_id = ?
              AND affected_user_id = ?
              AND stat_type = 'DAMAGE_MODIFIER'
            """,
            Integer.class,
            matchId,
            hostId,
            hostId
        );
        assertThat(modifierInTurn).isEqualTo(30);

        PlaySupportActionRequest damageRequest = new PlaySupportActionRequest();
        damageRequest.setCardInstanceId(damageSupportInstanceId);
        damageRequest.setTargetHolomemCardInstanceId(targetCardInstanceId);
        matchActionService.playSupport(matchId, hostId, damageRequest);

        Integer damageTakenAfter = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(damage_taken, 0)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            Integer.class,
            matchId,
            guestId,
            targetCardInstanceId
        );
        assertThat(damageTakenAfter).isEqualTo(70);

        matchActionService.endTurn(matchId, hostId);

        Integer remainingTurnEffects = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_turn_effects WHERE match_id = ?",
            Integer.class,
            matchId
        );
        assertThat(remainingTurnEffects).isEqualTo(0);
    }

    private StartedMatchContext createStartedMatch(String hostPrefix, String guestPrefix) {
        User host = createUser(hostPrefix);
        User guest = createUser(guestPrefix);
        deckService.setupQuickDeck(host.getId());
        deckService.setupQuickDeck(guest.getId());

        LobbyMatch created = lobbyMatchService.createMatch(host.getId());
        lobbyMatchService.joinMatch(created.getRoomCode(), guest.getId());
        lobbyMatchService.setReady(created.getId(), host.getId(), true);
        lobbyMatchService.setReady(created.getId(), guest.getId(), true);
        lobbyMatchService.startMatch(created.getId(), host.getId());

        return new StartedMatchContext(created.getId(), host.getId(), guest.getId());
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

    private Long findMemberCardFromHand(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'HAND'
              AND c.card_type = 'MEMBER'
            ORDER BY mc.order_index, mc.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
    }

    private void moveOneMemberFromDeckToHand(Long matchId, Long userId) {
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

    private void assertZoneCount(Long matchId, Long userId, String zone, int expected) {
        Integer actual = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = ?",
            Integer.class,
            matchId,
            userId,
            zone
        );
        assertThat(actual).isEqualTo(expected);
    }

    private int countZone(Long matchId, Long userId, String zone) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = ?",
            Integer.class,
            matchId,
            userId,
            zone
        );
        return value == null ? 0 : value;
    }

    private record StartedMatchContext(Long matchId, Long hostId, Long guestId) {
    }
}
