package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import com.hololive.cardgame.dto.AttachCheerActionRequest;
import com.hololive.cardgame.dto.AttackArtActionRequest;
import com.hololive.cardgame.dto.BatonTouchActionRequest;
import com.hololive.cardgame.dto.BloomActionRequest;
import com.hololive.cardgame.dto.GameStateResponse;
import com.hololive.cardgame.dto.MulliganActionRequest;
import com.hololive.cardgame.dto.MoveStageHolomemActionRequest;
import com.hololive.cardgame.dto.PlaySupportActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.dto.UseOshiSkillActionRequest;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    private MatchEffectService matchEffectService;

    @Autowired
    private DeckService deckService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private DiceService diceService;

    @BeforeEach
    void setupDiceRoll() {
        Mockito.when(diceService.rollD6()).thenReturn(6);
    }

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
    void startMatchShouldDrawSevenCardsAndEnterResetPhaseBeforeMulligan() {
        StartedMatchContext context = createReadyMatch("mulligan-open-host", "mulligan-open-guest");
        lobbyMatchService.startMatch(context.matchId(), context.hostId());

        Integer hostHandCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HAND'",
            Integer.class,
            context.matchId(),
            context.hostId()
        );
        Integer guestHandCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HAND'",
            Integer.class,
            context.matchId(),
            context.guestId()
        );
        assertThat(hostHandCount).isEqualTo(7);
        assertThat(guestHandCount).isEqualTo(7);

        String phase = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        assertThat(phase).isEqualTo("RESET");
    }

    @Test
    void playToStageShouldAutoResolveResetAndCreateOpeningTurnStartInteraction() {
        StartedMatchContext context = createReadyMatch("reset-auto-host", "reset-auto-guest");
        lobbyMatchService.startMatch(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.guestId());

        Long hostMemberCardInstanceId = findMemberCardFromHand(context.matchId(), context.hostId());
        assertThat(hostMemberCardInstanceId).isNotNull();

        PlayToStageActionRequest play = new PlayToStageActionRequest();
        play.setCardInstanceId(hostMemberCardInstanceId);
        play.setTargetZone("CENTER");

        matchActionService.playToStage(context.matchId(), context.hostId(), play);

        String phase = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        Long currentTurnPlayerId = jdbcTemplate.queryForObject(
            "SELECT current_turn_player_id FROM matches WHERE id = ?",
            Long.class,
            context.matchId()
        );
        assertThat(phase).isEqualTo("MAIN");
        assertThat(currentTurnPlayerId).isEqualTo(context.hostId());

        Integer drawTurnCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = 1
              AND action_type = 'DRAW_TURN'
            """,
            Integer.class,
            context.matchId(),
            context.hostId()
        );
        assertThat(drawTurnCount).isZero();

        Integer turnStartPendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TURN_START'
            """,
            Integer.class,
            context.matchId(),
            context.hostId()
        );
        assertThat(turnStartPendingCount).isEqualTo(1);
    }

    @Test
    void mulliganShouldSwitchResetToMainAfterBothPlayersResolve() {
        StartedMatchContext context = createReadyMatch("mulligan-flow-host", "mulligan-flow-guest");
        lobbyMatchService.startMatch(context.matchId(), context.hostId());

        MulliganActionRequest hostRequest = new MulliganActionRequest();
        hostRequest.setUseMulligan(true);
        matchActionService.mulligan(context.matchId(), context.hostId(), hostRequest);

        String phaseAfterHost = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        Long turnAfterHost = jdbcTemplate.queryForObject(
            "SELECT current_turn_player_id FROM matches WHERE id = ?",
            Long.class,
            context.matchId()
        );
        assertThat(phaseAfterHost).isEqualTo("RESET");
        assertThat(turnAfterHost).isEqualTo(context.guestId());

        MulliganActionRequest guestRequest = new MulliganActionRequest();
        guestRequest.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.guestId(), guestRequest);

        String phaseAfterGuest = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        Long turnAfterGuest = jdbcTemplate.queryForObject(
            "SELECT current_turn_player_id FROM matches WHERE id = ?",
            Long.class,
            context.matchId()
        );
        assertThat(phaseAfterGuest).isEqualTo("MAIN");
        assertThat(turnAfterGuest).isEqualTo(context.hostId());
    }

    @Test
    void mulliganShouldForceReduceHandWhenNoDebutInHand() {
        StartedMatchContext context = createReadyMatch("mulligan-force-host", "mulligan-force-guest");
        lobbyMatchService.startMatch(context.matchId(), context.hostId());

        String firstCardId = findMemberCardIdByLevel("FIRST");
        String debutCardId = findMemberCardIdByLevel("DEBUT");
        replaceZoneCardsCardId(context.matchId(), context.hostId(), "HAND", firstCardId);
        replaceZoneCardsCardId(context.matchId(), context.hostId(), "DECK", debutCardId);

        MulliganActionRequest request = new MulliganActionRequest();
        request.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.hostId(), request);

        Integer hostHandCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HAND'",
            Integer.class,
            context.matchId(),
            context.hostId()
        );
        assertThat(hostHandCount).isEqualTo(6);

        Integer hostDebutCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards mc
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'HAND'
              AND m.level_type = 'DEBUT'
            """,
            Integer.class,
            context.matchId(),
            context.hostId()
        );
        assertThat(hostDebutCount).isNotNull();
        assertThat(hostDebutCount).isGreaterThan(0);

        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'MULLIGAN'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            context.matchId(),
            context.hostId()
        );
        assertThat(payloadText).containsPattern("\"forcedRedrawCount\"\\s*:\\s*1");
        assertThat(payloadText).containsPattern("\"handCountAfter\"\\s*:\\s*6");
    }

    @Test
    void mulliganShouldFinishMatchWhenStillNoDebutAtOneCard() {
        StartedMatchContext context = createReadyMatch("mulligan-defeat-host", "mulligan-defeat-guest");
        lobbyMatchService.startMatch(context.matchId(), context.hostId());

        String firstCardId = findMemberCardIdByLevel("FIRST");
        replaceZoneCardsCardId(context.matchId(), context.hostId(), "HAND", firstCardId);
        replaceZoneCardsCardId(context.matchId(), context.hostId(), "DECK", firstCardId);

        MulliganActionRequest request = new MulliganActionRequest();
        request.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.hostId(), request);

        String matchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        Long winnerUserId = jdbcTemplate.queryForObject(
            "SELECT winner_user_id FROM matches WHERE id = ?",
            Long.class,
            context.matchId()
        );
        String phase = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'MULLIGAN'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            context.matchId(),
            context.hostId()
        );
        assertThat(matchStatus).isEqualTo("finished");
        assertThat(winnerUserId).isEqualTo(context.guestId());
        assertThat(phase).isEqualTo("END");
        assertThat(payloadText).containsPattern("\"forcedRedrawCount\"\\s*:\\s*6");
        assertThat(payloadText).containsPattern("\"forcedDrawSequence\"\\s*:\\s*\\[\\s*6\\s*,\\s*5\\s*,\\s*4\\s*,\\s*3\\s*,\\s*2\\s*,\\s*1\\s*\\]");
        assertThat(payloadText).containsPattern("\"hasDebutInHand\"\\s*:\\s*false");
        assertThat(payloadText).containsPattern("\"defeatedByNoDebut\"\\s*:\\s*true");
    }

    @Test
    void concedeShouldFinishMatchImmediatelyEvenWhenNotCurrentTurnPlayer() {
        StartedMatchContext context = createStartedMatch("concede-host", "concede-guest");
        matchActionService.concede(context.matchId(), context.guestId());

        String matchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        Long winnerUserId = jdbcTemplate.queryForObject(
            "SELECT winner_user_id FROM matches WHERE id = ?",
            Long.class,
            context.matchId()
        );
        Long currentTurnPlayerId = jdbcTemplate.queryForObject(
            "SELECT current_turn_player_id FROM matches WHERE id = ?",
            Long.class,
            context.matchId()
        );
        String phase = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'MATCH_FINISHED'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            context.matchId(),
            context.guestId()
        );
        String ruleEventPayload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'RULE_EVENT'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            context.matchId(),
            context.guestId()
        );

        assertThat(matchStatus).isEqualTo("finished");
        assertThat(winnerUserId).isEqualTo(context.hostId());
        assertThat(currentTurnPlayerId).isNull();
        assertThat(phase).isEqualTo("END");
        assertThat(payloadText).containsPattern("\"reason\"\\s*:\\s*\"CONCEDE\"");
        assertThat(payloadText).containsPattern("\"reasonCode\"\\s*:\\s*\"CONCEDE\"");
        assertThat(payloadText).containsPattern("\"loserUserId\"\\s*:\\s*" + context.guestId());
        assertThat(payloadText).containsPattern("\"winnerUserId\"\\s*:\\s*" + context.hostId());
        assertThat(ruleEventPayload).containsPattern("\"eventType\"\\s*:\\s*\"MATCH_FINISHED\"");
        assertThat(ruleEventPayload).containsPattern("\"reasonCode\"\\s*:\\s*\"CONCEDE\"");
    }

    @Test
    void actionPipelineShouldApplyPlayAttachAttackAndEndTurn() {
        StartedMatchContext context = createStartedMatch("action-host", "action-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        Long guestCenterCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            findMemberCardIdByLevel("DEBUT"),
            "CENTER",
            "DEBUT",
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
        String guestCenterCardId = jdbcTemplate.queryForObject(
            """
            SELECT card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            String.class,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            "UPDATE member_cards SET hp = 1, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            guestCenterCardId
        );

        Long memberHandCardInstanceId = findDebutMemberCardFromHand(matchId, hostId);
        if (memberHandCardInstanceId == null) {
            moveOneMemberFromDeckToHand(matchId, hostId);
            memberHandCardInstanceId = findDebutMemberCardFromHand(matchId, hostId);
        }
        assertThat(memberHandCardInstanceId).isNotNull();

        PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
        playToStage.setCardInstanceId(memberHandCardInstanceId);
        playToStage.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, playToStage);
        MoveStageHolomemActionRequest moveToCenter = new MoveStageHolomemActionRequest();
        moveToCenter.setCardInstanceId(memberHandCardInstanceId);
        moveToCenter.setTargetZone("CENTER");
        matchActionService.moveStageHolomem(matchId, hostId, moveToCenter);

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
        assertThat(centerCardInstanceId).isNotNull();
        Map<String, Integer> requiredCheerCost = loadPrimaryArtRequiredCheerCost(matchId, centerCardInstanceId);
        seedCheerDeckForPrimaryArtCost(matchId, hostId, requiredCheerCost);
        int requiredCheerCount = requiredCheerCost.values().stream().mapToInt(Integer::intValue).sum();
        int attachTargetCount = Math.max(requiredCheerCount, 1);

        int attachedCheerCount = 0;
        while (attachedCheerCount < attachTargetCount) {
            Long nextCheerCardInstanceId = findTopCheerDeckCard(matchId, hostId);
            assertThat(nextCheerCardInstanceId).isNotNull();
            AttachCheerActionRequest attach = new AttachCheerActionRequest();
            attach.setCheerCardInstanceId(nextCheerCardInstanceId);
            attach.setTargetHolomemCardInstanceId(centerCardInstanceId);
            matchActionService.attachCheer(matchId, hostId, attach);
            attachedCheerCount++;
        }

        Integer attachedCheerCountInDb = jdbcTemplate.queryForObject(
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
        assertThat(attachedCheerCountInDb).isEqualTo(attachTargetCount);

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 3,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();
        executeRequiredTurnActions(matchId, hostId, centerCardInstanceId);

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
        assertThat(turnNumber).isEqualTo(4);
        assertThat(phaseAfterEndTurn).isEqualTo("MAIN");

        List<String> actionTypes = jdbcTemplate.queryForList(
            "SELECT action_type FROM match_actions WHERE match_id = ? ORDER BY id",
            String.class,
            matchId
        );
        assertThat(actionTypes).contains("PLAY_TO_STAGE", "ATTACH_CHEER", "ATTACK_ART", "END_TURN");
    }

    @Test
    void turnCycleShouldCompleteMandatoryInteractionsForBothPlayers() {
        StartedMatchContext context = createStartedMatch("cycle-host", "cycle-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        createStageHolomemWithSingleCard(matchId, hostId, findMemberCardIdByLevel("DEBUT"), "CENTER", "DEBUT", 0);
        createStageHolomemWithSingleCard(matchId, guestId, findMemberCardIdByLevel("DEBUT"), "CENTER", "DEBUT", 0);

        executeRequiredTurnActions(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
        matchActionService.endTurn(matchId, hostId);

        Integer guestTurnStartPending = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TURN_START'
            """,
            Integer.class,
            matchId,
            guestId
        );
        assertThat(guestTurnStartPending).isEqualTo(1);

        executeRequiredTurnActions(matchId, guestId, loadFirstCenterCardInstanceId(matchId, guestId));
        matchActionService.endTurn(matchId, guestId);

        Long currentTurnPlayerId = jdbcTemplate.queryForObject(
            "SELECT current_turn_player_id FROM matches WHERE id = ?",
            Long.class,
            matchId
        );
        Integer turnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        String phase = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(currentTurnPlayerId).isEqualTo(hostId);
        assertThat(turnNumber).isEqualTo(3);
        assertThat(phase).isEqualTo("MAIN");

        Integer drawActions = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'DRAW_TURN'
              AND turn_number IN (1, 2)
            """,
            Integer.class,
            matchId
        );
        Integer turnCheerActions = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'TURN_CHEER'
              AND turn_number IN (1, 2)
            """,
            Integer.class,
            matchId
        );
        assertThat(drawActions).isEqualTo(2);
        assertThat(turnCheerActions).isEqualTo(2);
    }

    @Test
    void attackShouldEmitStandardizedReasonCodeWhenLifeBecomesZero() {
        StartedMatchContext context = createStartedMatch("reason-life-host", "reason-life-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            120,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":120}",
            1,
            "RED",
            "reason-life-host-center"
        );
        Long guestCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "CENTER",
            50,
            "BLUE",
            10,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":10}",
            0,
            "BLUE",
            "reason-life-guest-center"
        );

        keepTopLifeCards(matchId, guestId, 1);

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 3,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);

        AttackArtActionRequest request = new AttackArtActionRequest();
        request.setAttackerCardInstanceId(hostCenterCardInstanceId);
        request.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, request);

        String matchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        Long winnerUserId = jdbcTemplate.queryForObject(
            "SELECT winner_user_id FROM matches WHERE id = ?",
            Long.class,
            matchId
        );
        String matchFinishedPayload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'MATCH_FINISHED'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId
        );
        String ruleEventPayload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'RULE_EVENT'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId
        );

        assertThat(matchStatus).isEqualTo("finished");
        assertThat(winnerUserId).isEqualTo(hostId);
        assertThat(matchFinishedPayload).containsPattern("\"reason\"\\s*:\\s*\"LIFE_ZERO\"");
        assertThat(matchFinishedPayload).containsPattern("\"reasonCode\"\\s*:\\s*\"LIFE_ZERO\"");
        assertThat(ruleEventPayload).containsPattern("\"eventType\"\\s*:\\s*\"MATCH_FINISHED\"");
        assertThat(ruleEventPayload).containsPattern("\"reasonCode\"\\s*:\\s*\"LIFE_ZERO\"");
    }

    @Test
    void endTurnShouldDrawOneCardForNextTurnPlayer() {
        StartedMatchContext context = createStartedMatch("turn-draw-host", "turn-draw-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String debutCardId = findMemberCardIdByLevel("DEBUT");
        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, debutCardId, "CENTER", "DEBUT", 0);
        createStageHolomemWithSingleCard(matchId, guestId, debutCardId, "CENTER", "DEBUT", 0);
        int guestDeckBefore = countZone(matchId, guestId, "DECK");
        int guestHandBefore = countZone(matchId, guestId, "HAND");

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);

        Long turnStartDecisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TURN_START'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            guestId
        );
        if (turnStartDecisionId != null) {
            ResolveDecisionRequest resolveTurnStart = new ResolveDecisionRequest();
            resolveTurnStart.setDecisionId(turnStartDecisionId);
            matchActionService.resolveDecision(matchId, guestId, resolveTurnStart);
        }
        matchActionService.drawTurn(matchId, guestId);

        int guestDeckAfter = countZone(matchId, guestId, "DECK");
        int guestHandAfter = countZone(matchId, guestId, "HAND");
        assertThat(guestDeckAfter).isEqualTo(guestDeckBefore - 1);
        assertThat(guestHandAfter).isEqualTo(guestHandBefore + 1);

        String drawPayload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'DRAW_TURN'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            guestId
        );
        assertThat(drawPayload).containsPattern("\"drawCount\"\\s*:\\s*1");
        assertThat(drawPayload).contains("drawnCardInstanceIds");
    }

    @Test
    void endTurnShouldCreatePendingTurnStartInteractionForNextTurnPlayer() {
        StartedMatchContext context = createStartedMatch("turn-draw-modal-host", "turn-draw-modal-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String debutCardId = findMemberCardIdByLevel("DEBUT");
        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, debutCardId, "CENTER", "DEBUT", 0);
        createStageHolomemWithSingleCard(matchId, guestId, debutCardId, "CENTER", "DEBUT", 0);
        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);

        Map<String, Object> pendingRow = jdbcTemplate.queryForMap(
            """
            SELECT decision_type,
                   source_action_type,
                   effect_type,
                   status,
                   source_card_instance_id,
                   source_card_id,
                   context_json::text AS context_text
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
            ORDER BY id DESC
            LIMIT 1
            """,
            matchId,
            guestId
        );
        String contextText = (String) pendingRow.get("context_text");

        assertThat(pendingRow.get("decision_type")).isEqualTo("TURN_START");
        assertThat(pendingRow.get("source_action_type")).isEqualTo("TURN_START");
        assertThat(pendingRow.get("effect_type")).isEqualTo("TURN_START");
        assertThat(pendingRow.get("status")).isEqualTo("PENDING");
        assertThat(contextText).contains("interactionType");
        assertThat(contextText).contains("TURN_START");

        GameStateResponse guestState = matchGameStateService.getGameStateForUser(matchId, guestId);
        assertThat(guestState.getPendingDecisions()).isEmpty();
        assertThat(guestState.getPendingInteractions()).hasSize(1);
        assertThat(guestState.getPendingInteractions().get(0).getInteractionType()).isEqualTo("TURN_START");
    }

    @Test
    void endTurnShouldAutoReplenishCenterFromBackPreferNonRested() {
        StartedMatchContext context = createStartedMatch("end-center-host", "end-center-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String restedBackCardId = createMemberCardDefinition("TEND_CENTER_RESTED", "休息後排", "DEBUT", 120, "GREEN");
        String activeBackCardId = createMemberCardDefinition("TEND_CENTER_ACTIVE", "站立後排", "DEBUT", 120, "BLUE");
        Long restedBackCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, restedBackCardId, "BACK", "DEBUT", 0);
        Long activeBackCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, activeBackCardId, "BACK", "DEBUT", 0);
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = TRUE
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            restedBackCardInstanceId
        );
        jdbcTemplate.update(
            """
            DELETE FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            """,
            matchId,
            hostId
        );
        createStageHolomemWithSingleCard(matchId, guestId, findMemberCardIdByLevel("DEBUT"), "CENTER", "DEBUT", 0);

        executeRequiredTurnActions(matchId, hostId, activeBackCardInstanceId);
        matchActionService.endTurn(matchId, hostId);

        Long currentCenterCardInstanceId = jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            hostId
        );
        assertThat(currentCenterCardInstanceId).isEqualTo(activeBackCardInstanceId);
    }

    @Test
    void endTurnShouldAutoReplenishCenterFromRestedBackWhenNoActiveBack() {
        StartedMatchContext context = createStartedMatch("end-center-rested-host", "end-center-rested-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String restedBackCardId = createMemberCardDefinition("TEND_CENTER_ONLY_RESTED", "唯一後排", "DEBUT", 120, "GREEN");
        Long restedBackCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, restedBackCardId, "BACK", "DEBUT", 0);
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = TRUE
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            restedBackCardInstanceId
        );
        jdbcTemplate.update(
            """
            DELETE FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            """,
            matchId,
            hostId
        );

        executeRequiredTurnActions(matchId, hostId, restedBackCardInstanceId);
        matchActionService.endTurn(matchId, hostId);

        Long currentCenterCardInstanceId = jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            hostId
        );
        assertThat(currentCenterCardInstanceId).isEqualTo(restedBackCardInstanceId);
    }

    @Test
    void endTurnShouldNotFinishMatchWhenOpponentHasNoHolomem() {
        StartedMatchContext context = createStartedMatch("end-no-holomem-host", "end-no-holomem-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        jdbcTemplate.update(
            """
            DELETE FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
            """,
            matchId,
            guestId
        );

        executeRequiredTurnActions(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
        matchActionService.endTurn(matchId, hostId);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        Long currentTurnPlayerId = jdbcTemplate.queryForObject(
            "SELECT current_turn_player_id FROM matches WHERE id = ?",
            Long.class,
            matchId
        );
        String phase = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(status).isEqualTo("active");
        assertThat(currentTurnPlayerId).isEqualTo(guestId);
        assertThat(phase).isEqualTo("MAIN");
    }

    @Test
    void resolveDecisionShouldConfirmDrawRevealInteraction() {
        StartedMatchContext context = createStartedMatch("turn-draw-confirm-host", "turn-draw-confirm-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String debutCardId = findMemberCardIdByLevel("DEBUT");
        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, debutCardId, "CENTER", "DEBUT", 0);
        createStageHolomemWithSingleCard(matchId, guestId, debutCardId, "CENTER", "DEBUT", 0);
        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);

        Long turnStartDecisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TURN_START'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            guestId
        );
        if (turnStartDecisionId != null) {
            ResolveDecisionRequest resolveTurnStart = new ResolveDecisionRequest();
            resolveTurnStart.setDecisionId(turnStartDecisionId);
            matchActionService.resolveDecision(matchId, guestId, resolveTurnStart);
        }
        matchActionService.drawTurn(matchId, guestId);

        Long decisionId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'DRAW_REVEAL'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            guestId
        );
        assertThat(decisionId).isNotNull();

        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setDecisionId(decisionId);
        matchActionService.resolveDecision(matchId, guestId, request);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM match_pending_decisions WHERE id = ?",
            String.class,
            decisionId
        );
        assertThat(status).isEqualTo("RESOLVED");

        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'INTERACTION_CONFIRMED'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            guestId
        );
        assertThat(payloadText).containsPattern("\"interactionType\"\\s*:\\s*\"DRAW_REVEAL\"");
    }

    @Test
    void playSupportLookTopDeckShouldExposePendingInteractionWithCardContext() {
        StartedMatchContext context = createStartedMatch("look-top-host", "look-top-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TLOOK_TOP_" + System.nanoTime(),
            false,
            "LOOK_TOP_DECK",
            "{\"type\":\"LOOK_TOP_DECK\",\"rawText\":\"自分のデッキの上から1枚を見る。\"}",
            "SELF"
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var lookTop = state.getPendingInteractions().stream()
            .filter(item -> "LOOK_TOP_DECK".equals(item.getInteractionType()))
            .findFirst()
            .orElseThrow();

        assertThat(lookTop.getTitle()).isEqualTo("查看牌庫頂");
        assertThat(lookTop.getMessage()).contains("保留在牌庫頂");
        assertThat(lookTop.getCards()).hasSize(1);
        assertThat(lookTop.getPlacementOptions()).containsExactly("TOP", "BOTTOM");
        assertThat(lookTop.getLookedCardInstanceId()).isEqualTo(lookTop.getCards().get(0).getCardInstanceId());
        assertThat(lookTop.getLookedCardId()).isEqualTo(lookTop.getCards().get(0).getCardId());
        assertThat(lookTop.getCards().get(0).getCardInstanceId()).isNotNull();
        assertThat(lookTop.getCards().get(0).getCardId()).isNotBlank();
    }

    @Test
    void resolveLookTopDeckDecisionShouldAcceptPlacementOption() {
        StartedMatchContext context = createStartedMatch("look-top-placement-host", "look-top-placement-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TLOOK_TOP_PLACE_" + System.nanoTime(),
            false,
            "LOOK_TOP_DECK",
            "{\"type\":\"LOOK_TOP_DECK\",\"rawText\":\"自分のデッキの上から1枚を見る。\"}",
            "SELF"
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        Long decisionId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'LOOK_TOP_DECK'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        assertThat(decisionId).isNotNull();

        ResolveDecisionRequest resolve = new ResolveDecisionRequest();
        resolve.setDecisionId(decisionId);
        resolve.setPlacement("BOTTOM");
        matchActionService.resolveDecision(matchId, hostId, resolve);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM match_pending_decisions WHERE id = ?",
            String.class,
            decisionId
        );
        assertThat(status).isEqualTo("RESOLVED");

        String payload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'INTERACTION_CONFIRMED'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payload).containsPattern("\"decisionType\"\\s*:\\s*\"LOOK_TOP_DECK\"");
        assertThat(payload).containsPattern("\"placement\"\\s*:\\s*\"BOTTOM\"");
    }

    @Test
    void playSupportLookOpponentHandShouldExposePendingInteractionWithCardContext() {
        StartedMatchContext context = createStartedMatch("look-opponent-hand-host", "look-opponent-hand-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TLOOK_OPPONENT_HAND_" + System.nanoTime(),
            false,
            "LOOK_OPPONENT_HAND",
            "{\"type\":\"LOOK_OPPONENT_HAND\",\"rawText\":\"相手の手札を見る。\"}",
            "ENEMY"
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var interaction = state.getPendingInteractions().stream()
            .filter(item -> "LOOK_OPPONENT_HAND".equals(item.getInteractionType()))
            .findFirst()
            .orElseThrow();

        assertThat(interaction.getTitle()).isEqualTo("查看對手手牌");
        assertThat(interaction.getCards()).isNotEmpty();
        assertThat(interaction.getCards().get(0).getCardInstanceId()).isNotNull();
        assertThat(interaction.getCards().get(0).getCardId()).isNotBlank();
    }

    @Test
    void resolveLookOpponentHandDecisionShouldMarkResolved() {
        StartedMatchContext context = createStartedMatch("resolve-look-opponent-hand-host", "resolve-look-opponent-hand-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TRESOLVE_LOOK_OPPONENT_HAND_" + System.nanoTime(),
            false,
            "LOOK_OPPONENT_HAND",
            "{\"type\":\"LOOK_OPPONENT_HAND\",\"rawText\":\"相手の手札を見る。\"}",
            "ENEMY"
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        Long decisionId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'LOOK_OPPONENT_HAND'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        assertThat(decisionId).isNotNull();

        ResolveDecisionRequest resolve = new ResolveDecisionRequest();
        resolve.setDecisionId(decisionId);
        matchActionService.resolveDecision(matchId, hostId, resolve);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM match_pending_decisions WHERE id = ?",
            String.class,
            decisionId
        );
        assertThat(status).isEqualTo("RESOLVED");

        String payload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'INTERACTION_CONFIRMED'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payload).containsPattern("\"decisionType\"\\s*:\\s*\"LOOK_OPPONENT_HAND\"");
    }

    @Test
    void playSupportLookHolopowerShouldExposePendingInteractionAndResolve() {
        StartedMatchContext context = createStartedMatch("look-holopower-host", "look-holopower-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

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
            hostId
        );

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TLOOK_HOLOPOWER_" + System.nanoTime(),
            false,
            "LOOK_HOLOPOWER",
            "{\"type\":\"LOOK_HOLOPOWER\",\"rawText\":\"自分のホロパワーを見る。\"}",
            "SELF"
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        Long decisionId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'LOOK_HOLOPOWER'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        assertThat(decisionId).isNotNull();

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var interaction = state.getPendingInteractions().stream()
            .filter(item -> "LOOK_HOLOPOWER".equals(item.getInteractionType()))
            .findFirst()
            .orElseThrow();
        assertThat(interaction.getCards()).hasSize(1);

        ResolveDecisionRequest resolve = new ResolveDecisionRequest();
        resolve.setDecisionId(decisionId);
        matchActionService.resolveDecision(matchId, hostId, resolve);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM match_pending_decisions WHERE id = ?",
            String.class,
            decisionId
        );
        assertThat(status).isEqualTo("RESOLVED");
    }

    @Test
    void playSupportDiceMultiRollMaxShouldUseHighestRollForPerEffectConditions() {
        StartedMatchContext context = createStartedMatch("dice-max-host", "dice-max-guest");
        Mockito.when(diceService.rollD6()).thenReturn(2, 5);
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TDICE_MAX_" + System.nanoTime(),
            false,
            "DRAW",
            "{\"type\":\"DRAW\",\"effects\":[\"DRAW\",\"MOVE_TO_HOLOPOWER\"],\"value\":1,\"rawText\":\"サイコロを2回振る。\",\"diceRollCount\":2,\"dicePickStrategy\":\"MAX\",\"effectDiceConditions\":{\"DRAW\":\"AT_LEAST_5\",\"MOVE_TO_HOLOPOWER\":\"AT_MOST_4\"}}",
            "SELF"
        );

        Integer handBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HAND'",
            Integer.class,
            matchId,
            hostId
        );
        Integer holopowerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HOLOPOWER'",
            Integer.class,
            matchId,
            hostId
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        Integer handAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HAND'",
            Integer.class,
            matchId,
            hostId
        );
        Integer holopowerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HOLOPOWER'",
            Integer.class,
            matchId,
            hostId
        );

        assertThat(handAfter).isEqualTo(handBefore);
        assertThat(holopowerAfter).isEqualTo(holopowerBefore);
    }

    @Test
    void playSupportDiceMultiRollMinShouldUseLowestRollForPerEffectConditions() {
        StartedMatchContext context = createStartedMatch("dice-min-host", "dice-min-guest");
        Mockito.when(diceService.rollD6()).thenReturn(2, 5);
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TDICE_MIN_" + System.nanoTime(),
            false,
            "DRAW",
            "{\"type\":\"DRAW\",\"effects\":[\"DRAW\",\"MOVE_TO_HOLOPOWER\"],\"value\":1,\"rawText\":\"サイコロを2回振る。\",\"diceRollCount\":2,\"dicePickStrategy\":\"MIN\",\"effectDiceConditions\":{\"DRAW\":\"AT_LEAST_5\",\"MOVE_TO_HOLOPOWER\":\"AT_MOST_4\"}}",
            "SELF"
        );

        Integer handBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HAND'",
            Integer.class,
            matchId,
            hostId
        );
        Integer holopowerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HOLOPOWER'",
            Integer.class,
            matchId,
            hostId
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        Integer handAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HAND'",
            Integer.class,
            matchId,
            hostId
        );
        Integer holopowerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'HOLOPOWER'",
            Integer.class,
            matchId,
            hostId
        );

        assertThat(handAfter).isEqualTo(handBefore - 1);
        assertThat(holopowerAfter).isEqualTo(holopowerBefore + 1);
    }

    @Test
    void drawRevealPendingShouldBlockOtherActionsUntilConfirmed() {
        StartedMatchContext context = createStartedMatch("turn-draw-block-host", "turn-draw-block-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String debutCardId = findMemberCardIdByLevel("DEBUT");
        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, debutCardId, "CENTER", "DEBUT", 0);
        createStageHolomemWithSingleCard(matchId, guestId, debutCardId, "CENTER", "DEBUT", 0);
        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);
        Long turnStartDecisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TURN_START'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            guestId
        );
        if (turnStartDecisionId != null) {
            ResolveDecisionRequest resolveTurnStart = new ResolveDecisionRequest();
            resolveTurnStart.setDecisionId(turnStartDecisionId);
            matchActionService.resolveDecision(matchId, guestId, resolveTurnStart);
        }
        matchActionService.drawTurn(matchId, guestId);

        Long handCardInstanceId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            Long.class,
            matchId,
            guestId
        );
        assertThat(handCardInstanceId).isNotNull();

        PlayToStageActionRequest play = new PlayToStageActionRequest();
        play.setCardInstanceId(handCardInstanceId);
        play.setTargetZone("BACK");

        assertThatThrownBy(() -> matchActionService.playToStage(matchId, guestId, play))
            .isInstanceOfAny(IllegalStateException.class, GameRuleException.class)
            .hasMessageContaining("待處理的互動");
    }

    @Test
    void sendTurnCheerShouldCreatePendingInteractionWhenHolomemExists() {
        StartedMatchContext context = createStartedMatch("turn-cheer-create-host", "turn-cheer-create-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String guestCenterCardId = findMemberCardIdByLevel("DEBUT");
        createStageHolomemWithSingleCard(matchId, guestId, guestCenterCardId, "CENTER", "DEBUT", 1);

        String hostDebutCardId = findMemberCardIdByLevel("DEBUT");
        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, hostDebutCardId, "CENTER", "DEBUT", 0);
        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);
        Long turnStartDecisionId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TURN_START'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            guestId
        );
        ResolveDecisionRequest resolveTurnStart = new ResolveDecisionRequest();
        resolveTurnStart.setDecisionId(turnStartDecisionId);
        matchActionService.resolveDecision(matchId, guestId, resolveTurnStart);
        matchActionService.sendTurnCheer(matchId, guestId);

        Map<String, Object> sendCheerPending = jdbcTemplate.queryForMap(
            """
            SELECT decision_type,
                   source_action_type,
                   source_card_instance_id,
                   source_card_id,
                   context_json::text AS context_text
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'SEND_CHEER'
            ORDER BY id DESC
            LIMIT 1
            """,
            matchId,
            guestId
        );
        assertThat(sendCheerPending.get("decision_type")).isEqualTo("SEND_CHEER");
        assertThat(sendCheerPending.get("source_action_type")).isEqualTo("TURN_CHEER");
        assertThat(((Number) sendCheerPending.get("source_card_instance_id")).longValue()).isPositive();
        assertThat((String) sendCheerPending.get("source_card_id")).isNotBlank();
        assertThat((String) sendCheerPending.get("context_text")).contains("SEND_CHEER");
    }

    @Test
    void resolveDecisionShouldAttachCheerForSendCheerInteraction() {
        StartedMatchContext context = createStartedMatch("turn-cheer-resolve-host", "turn-cheer-resolve-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String guestCenterCardId = findMemberCardIdByLevel("DEBUT");
        Long guestCenterCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            guestCenterCardId,
            "CENTER",
            "DEBUT",
            1
        );

        String hostDebutCardId = findMemberCardIdByLevel("DEBUT");
        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, hostDebutCardId, "CENTER", "DEBUT", 0);
        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);
        Long turnStartDecisionId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TURN_START'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            guestId
        );
        ResolveDecisionRequest resolveTurnStart = new ResolveDecisionRequest();
        resolveTurnStart.setDecisionId(turnStartDecisionId);
        matchActionService.resolveDecision(matchId, guestId, resolveTurnStart);
        matchActionService.sendTurnCheer(matchId, guestId);

        Map<String, Object> sendCheerPending = jdbcTemplate.queryForMap(
            """
            SELECT id,
                   source_card_instance_id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'SEND_CHEER'
            ORDER BY id DESC
            LIMIT 1
            """,
            matchId,
            guestId
        );
        Long sendCheerDecisionId = ((Number) sendCheerPending.get("id")).longValue();
        Long sourceCheerCardInstanceId = ((Number) sendCheerPending.get("source_card_instance_id")).longValue();

        ResolveDecisionRequest resolveSendCheer = new ResolveDecisionRequest();
        resolveSendCheer.setDecisionId(sendCheerDecisionId);
        resolveSendCheer.setSelectedCardInstanceIds(List.of(guestCenterCardInstanceId));
        matchActionService.resolveDecision(matchId, guestId, resolveSendCheer);

        String decisionStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM match_pending_decisions WHERE id = ?",
            String.class,
            sendCheerDecisionId
        );
        assertThat(decisionStatus).isEqualTo("RESOLVED");

        Integer attachedCount = jdbcTemplate.queryForObject(
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
            guestId,
            guestCenterCardInstanceId
        );
        assertThat(attachedCount).isEqualTo(1);

        String sourceCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            sourceCheerCardInstanceId
        );
        assertThat(sourceCardZone).isEqualTo("STAGE");
    }

    @Test
    void endTurnShouldFinishMatchWhenNextTurnPlayerCannotDraw() {
        StartedMatchContext context = createStartedMatch("turn-deckout-host", "turn-deckout-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        String debutCardId = findMemberCardIdByLevel("DEBUT");
        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, debutCardId, "CENTER", "DEBUT", 0);
        createStageHolomemWithSingleCard(matchId, guestId, debutCardId, "CENTER", "DEBUT", 0);

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            matchId,
            guestId
        );
        assertThat(countZone(matchId, guestId, "DECK")).isZero();

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);
        resolvePendingInteractionIfExists(matchId, guestId, "TURN_START");
        matchActionService.drawTurn(matchId, guestId);

        String matchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        Long winnerUserId = jdbcTemplate.queryForObject(
            "SELECT winner_user_id FROM matches WHERE id = ?",
            Long.class,
            matchId
        );
        String phase = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'MATCH_FINISHED'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId
        );

        assertThat(matchStatus).isEqualTo("finished");
        assertThat(winnerUserId).isEqualTo(hostId);
        assertThat(phase).isEqualTo("END");
        assertThat(payloadText).containsPattern("\"reason\"\\s*:\\s*\"DRAW_DECK_OUT\"");
        assertThat(payloadText).containsPattern("\"loserUserId\"\\s*:\\s*" + guestId);
        assertThat(payloadText).containsPattern("\"winnerUserId\"\\s*:\\s*" + hostId);
    }

    @Test
    void attackShouldFinishMatchWhenOpponentHasNoHolomemOnStage() {
        StartedMatchContext context = createStartedMatch("no-holomem-host", "no-holomem-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostMemberHandCardInstanceId = findDebutMemberCardFromHand(matchId, hostId);
        if (hostMemberHandCardInstanceId == null) {
            moveOneMemberFromDeckToHand(matchId, hostId);
            hostMemberHandCardInstanceId = findDebutMemberCardFromHand(matchId, hostId);
        }
        assertThat(hostMemberHandCardInstanceId).isNotNull();

        PlayToStageActionRequest hostPlay = new PlayToStageActionRequest();
        hostPlay.setCardInstanceId(hostMemberHandCardInstanceId);
        hostPlay.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, hostPlay);

        MoveStageHolomemActionRequest moveToCenter = new MoveStageHolomemActionRequest();
        moveToCenter.setCardInstanceId(hostMemberHandCardInstanceId);
        moveToCenter.setTargetZone("CENTER");
        matchActionService.moveStageHolomem(matchId, hostId, moveToCenter);

        Long hostCenterCardInstanceId = jdbcTemplate.queryForObject(
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
        assertThat(hostCenterCardInstanceId).isNotNull();
        Map<String, Integer> requiredCheerCost = loadPrimaryArtRequiredCheerCost(matchId, hostCenterCardInstanceId);
        seedCheerDeckForPrimaryArtCost(matchId, hostId, requiredCheerCost);
        int requiredCheerCount = Math.max(requiredCheerCost.values().stream().mapToInt(Integer::intValue).sum(), 1);
        for (int i = 0; i < requiredCheerCount; i++) {
            Long cheerCardInstanceId = findTopCheerDeckCard(matchId, hostId);
            assertThat(cheerCardInstanceId).isNotNull();
            AttachCheerActionRequest attach = new AttachCheerActionRequest();
            attach.setCheerCardInstanceId(cheerCardInstanceId);
            attach.setTargetHolomemCardInstanceId(hostCenterCardInstanceId);
            matchActionService.attachCheer(matchId, hostId, attach);
        }

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        createStageHolomemWithSingleCard(matchId, guestId, findMemberCardIdByLevel("DEBUT"), "CENTER", "DEBUT", 0);
        matchActionService.endTurn(matchId, hostId);

        Long guestCenterCardInstanceId = jdbcTemplate.queryForObject(
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
            guestId
        );
        String guestCenterCardId = jdbcTemplate.queryForObject(
            """
            SELECT card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            LIMIT 1
            """,
            String.class,
            matchId,
            guestId
        );
        assertThat(guestCenterCardInstanceId).isNotNull();
        assertThat(guestCenterCardId).isNotBlank();

        jdbcTemplate.update(
            "UPDATE member_cards SET hp = 1, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            guestCenterCardId
        );

        resolvePendingInteractionIfExists(matchId, guestId, "TURN_START");
        executeRequiredTurnActions(matchId, guestId, guestCenterCardInstanceId);
        matchActionService.endTurn(matchId, guestId);
        resolvePendingInteractionIfExists(matchId, hostId, "TURN_START");

        AttackArtActionRequest attackArt = new AttackArtActionRequest();
        attackArt.setAttackerCardInstanceId(hostCenterCardInstanceId);
        attackArt.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attackArt);

        String matchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        Long winnerUserId = jdbcTemplate.queryForObject(
            "SELECT winner_user_id FROM matches WHERE id = ?",
            Long.class,
            matchId
        );
        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'MATCH_FINISHED'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId
        );

        assertThat(matchStatus).isEqualTo("finished");
        assertThat(winnerUserId).isEqualTo(hostId);
        assertThat(payloadText).containsPattern("\"reason\"\\s*:\\s*\"STAGE_NO_HOLOMEM\"");
        assertThat(payloadText).containsPattern("\"loserUserId\"\\s*:\\s*" + guestId);
    }

    @Test
    void attackShouldFinishMatchWhenOpponentLifeBecomesZero() {
        StartedMatchContext context = createStartedMatch("life-zero-host", "life-zero-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostMemberHandCardInstanceId = findDebutMemberCardFromHand(matchId, hostId);
        if (hostMemberHandCardInstanceId == null) {
            moveOneMemberFromDeckToHand(matchId, hostId);
            hostMemberHandCardInstanceId = findDebutMemberCardFromHand(matchId, hostId);
        }
        assertThat(hostMemberHandCardInstanceId).isNotNull();

        PlayToStageActionRequest hostPlay = new PlayToStageActionRequest();
        hostPlay.setCardInstanceId(hostMemberHandCardInstanceId);
        hostPlay.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, hostPlay);

        MoveStageHolomemActionRequest moveToCenter = new MoveStageHolomemActionRequest();
        moveToCenter.setCardInstanceId(hostMemberHandCardInstanceId);
        moveToCenter.setTargetZone("CENTER");
        matchActionService.moveStageHolomem(matchId, hostId, moveToCenter);

        Long hostCenterCardInstanceId = jdbcTemplate.queryForObject(
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
        assertThat(hostCenterCardInstanceId).isNotNull();
        String guestLowHpCenterCardId = createMemberCardDefinition("TLIFE_ZERO_GUEST_CENTER", "測試低血中心", "DEBUT", 10, "BLUE");
        Long guestCenterCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            guestLowHpCenterCardId,
            "CENTER",
            "DEBUT",
            0
        );

        List<Long> guestLifeCardInstanceIds = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'LIFE'
            ORDER BY order_index NULLS LAST, id
            """,
            (rs, rowNum) -> rs.getLong("id"),
            matchId,
            guestId
        );
        assertThat(guestLifeCardInstanceIds).hasSizeGreaterThan(1);
        for (int i = 1; i < guestLifeCardInstanceIds.size(); i++) {
            Long lifeCardInstanceId = guestLifeCardInstanceIds.get(i);
            int archiveOrder = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(MAX(order_index), 0) + 1
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'ARCHIVE'
                """,
                Integer.class,
                matchId,
                guestId
            );
            jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'ARCHIVE',
                    order_index = ?,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'LIFE'
                """,
                archiveOrder,
                lifeCardInstanceId,
                matchId,
                guestId
            );
        }
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            guestId
        );

        Map<String, Integer> requiredCheerCost = loadPrimaryArtRequiredCheerCost(matchId, hostCenterCardInstanceId);
        seedCheerDeckForPrimaryArtCost(matchId, hostId, requiredCheerCost);
        int requiredCheerCount = Math.max(requiredCheerCost.values().stream().mapToInt(Integer::intValue).sum(), 1);
        for (int i = 0; i < requiredCheerCount; i++) {
            Long cheerCardInstanceId = findTopCheerDeckCard(matchId, hostId);
            assertThat(cheerCardInstanceId).isNotNull();
            AttachCheerActionRequest attach = new AttachCheerActionRequest();
            attach.setCheerCardInstanceId(cheerCardInstanceId);
            attach.setTargetHolomemCardInstanceId(hostCenterCardInstanceId);
            matchActionService.attachCheer(matchId, hostId, attach);
        }

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);
        resolvePendingInteractionIfExists(matchId, guestId, "TURN_START");
        executeRequiredTurnActions(matchId, guestId, guestCenterCardInstanceId);
        matchActionService.endTurn(matchId, guestId);
        resolvePendingInteractionIfExists(matchId, hostId, "TURN_START");

        AttackArtActionRequest attackArt = new AttackArtActionRequest();
        attackArt.setAttackerCardInstanceId(hostCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attackArt);

        String matchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        Long winnerUserId = jdbcTemplate.queryForObject(
            "SELECT winner_user_id FROM matches WHERE id = ?",
            Long.class,
            matchId
        );
        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'MATCH_FINISHED'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId
        );

        assertThat(matchStatus).isEqualTo("finished");
        assertThat(winnerUserId).isEqualTo(hostId);
        assertThat(payloadText).containsPattern("\"reason\"\\s*:\\s*\"LIFE_ZERO\"");
        assertThat(payloadText).containsPattern("\"loserUserId\"\\s*:\\s*" + guestId);
    }

    @Test
    void playSupportShouldFinishMatchImmediatelyWhenCardEffectDeclaresWin() {
        StartedMatchContext context = createStartedMatch("effect-win-host", "effect-win-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String supportCardId = "TSUP_MATCH_WIN_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'SUPPORT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "測試直接勝利支援"
        );
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, FALSE, NULL, NULL, 'MATCH_RESULT', CAST(? AS jsonb), 'SELF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            supportCardId,
            "{\"type\":\"MATCH_RESULT\",\"result\":\"WIN\",\"reason\":\"CARD_EFFECT_WIN\"}"
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

        String matchStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        Long winnerUserId = jdbcTemplate.queryForObject(
            "SELECT winner_user_id FROM matches WHERE id = ?",
            Long.class,
            matchId
        );
        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'MATCH_FINISHED'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId
        );

        assertThat(matchStatus).isEqualTo("finished");
        assertThat(winnerUserId).isEqualTo(hostId);
        assertThat(payloadText).containsPattern("\"reason\"\\s*:\\s*\"CARD_EFFECT_WIN\"");
        assertThat(payloadText).containsPattern("\"loserUserId\"\\s*:\\s*" + guestId);
    }

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

        Map<String, Object> top = jdbcTemplate.queryForMap(
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
            """,
            Integer.class,
            matchId,
            hostId
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
        assertThat(centerZone.getCards()).hasSize(1);
        assertThat(centerZone.getCards().get(0).getStackDepth()).isEqualTo(2);
        assertThat(centerZone.getCards().get(0).getStackCardInstanceIds()).contains(targetHolomemCardInstanceId, bloomCardInstanceId);
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

        int handAfter = countZone(matchId, hostId, "HAND");
        int deckAfter = countZone(matchId, hostId, "DECK");
        assertThat(handAfter).isEqualTo(handBefore);
        assertThat(deckAfter).isEqualTo(deckBefore - 1);

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
        assertThat(payload).contains("bloomEffect");
        assertThat(payload).contains("DRAW");
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
        createStageHolomemWithSingleCard(matchId, guestId, guestCenterCardId, "CENTER", "DEBUT", 0);
        String guestBackCardId = createMemberCardDefinition("TBLOOM_LIFE_GUEST_BACK", "測試存活後排", "DEBUT", 110, "BLUE");
        createStageHolomemWithSingleCard(matchId, guestId, guestBackCardId, "BACK", "DEBUT", 0);

        forceTopLifeCardToCheer(matchId, guestId);
        int lifeBefore = countZone(matchId, guestId, "LIFE");

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

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

        int handAfter = countZone(matchId, hostId, "HAND");
        int archiveAfter = countZone(matchId, hostId, "ARCHIVE");
        String returnedCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            archivedCardInstanceId
        );

        assertThat(handAfter).isEqualTo(handBefore);
        assertThat(archiveAfter).isEqualTo(archiveBefore - 1);
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

    @Test
    void collabShouldIncludeResolutionOrderWithPriority() {
        StartedMatchContext context = createStartedMatch("collab-order-host", "collab-order-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String collabCardId = createMemberCardDefinition(
            "TCOLLAB_ORDER",
            "測試連動順序",
            "DEBUT",
            120,
            "WHITE",
            "{\"collabEffect\":{\"effects\":[\"DRAW\"],\"value\":1}}"
        );
        Long backCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );
        assertThat(backCardInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");
        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(backCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);

        int handAfter = countZone(matchId, hostId, "HAND");
        assertThat(handAfter).isEqualTo(handBefore + 1);

        String payload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'COLLAB'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payload).contains("\"triggerResolutionOrder\"");
        assertThat(payload).contains("\"step\": \"COLLAB_EFFECT\"");
        assertThat(payload).contains("\"step\": \"COLLAB_EVENT_HOOK\"");
        assertThat(payload).contains("\"priority\": 100");
        assertThat(payload).contains("\"priority\": 200");
    }

    @Test
    void bloomShouldTriggerReattachEffectFromPassiveText() {
        StartedMatchContext context = createStartedMatch("bloom-reattach-host", "bloom-reattach-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String centerCardId = createMemberCardDefinition("TBLOOM_REATTACH_CENTER", "測試 Cheer 來源", "DEBUT", 130, "BLUE");
        String targetName = "測試 Cheer 付替";
        String targetDebutCardId = createMemberCardDefinition("TBLOOM_REATTACH_DEBUT", targetName, "DEBUT", 120, "BLUE");
        String bloomCardId = createMemberCardDefinition(
            "TBLOOM_REATTACH_FIRST",
            targetName,
            "FIRST",
            170,
            "BLUE",
            "{\"キーワード\":\"ブルームエフェクトテスト \\n自分のステージのエール1枚を、このホロメンに付け替えられる。\"}"
        );

        Long centerCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            centerCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long targetBackCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            targetDebutCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, bloomCardId);

        String cheerCardId = "TBLOOM_REATTACH_CHEER_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId,
            "測試付替 Cheer"
        );
        jdbcTemplate.update(
            """
            INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
            VALUES (?, 'BLUE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'STAGE', NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            cheerCardId
        );

        Long centerHolomemId = jdbcTemplate.queryForObject(
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
            centerCardInstanceId
        );
        Long backHolomemId = jdbcTemplate.queryForObject(
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
            targetBackCardInstanceId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
            VALUES (?, ?, FALSE)
            """,
            centerHolomemId,
            cheerCardId
        );

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetBackCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        Integer centerCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            centerHolomemId
        );
        Integer backCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            backHolomemId
        );
        assertThat(centerCheerAfter).isZero();
        assertThat(backCheerAfter).isGreaterThanOrEqualTo(1);

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
        assertThat(payload).contains("REATTACH");
    }

    @Test
    void bloomShouldTriggerSummonToStageEffectFromPassiveText() {
        StartedMatchContext context = createStartedMatch("bloom-summon-host", "bloom-summon-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 Bloom 展開";
        String debutCardId = createMemberCardDefinition("TBLOOM_SUMMON_DEBUT", displayName, "DEBUT", 120, "YELLOW");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_SUMMON_FIRST",
            displayName,
            "FIRST",
            170,
            "YELLOW",
            "{\"キーワード\":\"ブルームエフェクトテスト \\n自分のデッキから、Debutホロメンの〈測試展開目標〉1枚を公開し、ステージに出せる。そしてデッキをシャッフルする。\"}"
        );
        String summonTargetCardId = createMemberCardDefinition(
            "TBLOOM_SUMMON_TARGET",
            "測試展開目標",
            "DEBUT",
            110,
            "YELLOW"
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
        int nextDeckOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MIN(order_index), 1) - 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'DECK', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            summonTargetCardId,
            nextDeckOrder
        );
        Long summonTargetCardInstanceId = jdbcTemplate.queryForObject(
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
            summonTargetCardId
        );
        assertThat(summonTargetCardInstanceId).isNotNull();

        int holomemBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomems WHERE match_id = ? AND owner_user_id = ?",
            Integer.class,
            matchId,
            hostId
        );
        int deckBefore = countZone(matchId, hostId, "DECK");

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        String summonedZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            summonTargetCardInstanceId
        );
        Integer summonedHolomemCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            Integer.class,
            matchId,
            hostId,
            summonTargetCardInstanceId
        );
        int holomemAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomems WHERE match_id = ? AND owner_user_id = ?",
            Integer.class,
            matchId,
            hostId
        );
        int deckAfter = countZone(matchId, hostId, "DECK");

        assertThat(summonedZone).isEqualTo("STAGE");
        assertThat(summonedHolomemCount).isEqualTo(1);
        assertThat(holomemAfter).isEqualTo(holomemBefore + 1);
        assertThat(deckAfter).isEqualTo(deckBefore - 1);

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
        assertThat(payload).contains("SUMMON_TO_STAGE");
    }

    @Test
    void bloomShouldTriggerRevealToArchiveEffectFromPassiveText() {
        StartedMatchContext context = createStartedMatch("bloom-reveal-host", "bloom-reveal-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 Bloom 公開送墓";
        String debutCardId = createMemberCardDefinition("TBLOOM_REVEAL_DEBUT", displayName, "DEBUT", 120, "PURPLE");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_REVEAL_FIRST",
            displayName,
            "FIRST",
            170,
            "PURPLE",
            "{\"キーワード\":\"ブルームエフェクトテスト \\n自分のデッキから、カード1枚を公開し、アーカイブする。そしてデッキをシャッフルする。\"}"
        );
        String revealTargetCardId = createMemberCardDefinition(
            "TBLOOM_REVEAL_TARGET",
            "測試送墓目標",
            "DEBUT",
            100,
            "PURPLE"
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
        int nextDeckOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MIN(order_index), 1) - 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'DECK', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            revealTargetCardId,
            nextDeckOrder
        );
        Long revealTargetCardInstanceId = jdbcTemplate.queryForObject(
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
            revealTargetCardId
        );
        assertThat(revealTargetCardInstanceId).isNotNull();

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        String zoneAfter = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            revealTargetCardInstanceId
        );
        assertThat(zoneAfter).isEqualTo("ARCHIVE");

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
        assertThat(payload).contains("REVEAL_TO_ARCHIVE");
    }

    @Test
    void bloomShouldTriggerBloomFromArchiveEffectFromPassiveText() {
        StartedMatchContext context = createStartedMatch("bloom-from-archive-host", "bloom-from-archive-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String archiveBloomTag = "#TBLOOM_ARCHIVE_TAG";
        String triggerName = "測試 Archive Bloom 觸發者";
        String triggerDebutCardId = createMemberCardDefinition("TBLOOM_ARCHIVE_SRC_DEBUT", triggerName, "DEBUT", 120, "RED");
        String triggerFirstCardId = createMemberCardDefinition(
            "TBLOOM_ARCHIVE_SRC_FIRST",
            triggerName,
            "FIRST",
            170,
            "RED",
            "{\"キーワード\":\"ブルームエフェクトテスト \\n自分の" + archiveBloomTag + "を持つDebutホロメン1人を、自分のアーカイブのホロメンを使ってBloomできる。\"}"
        );
        String targetName = "測試 Archive Bloom 目標";
        String targetDebutCardId = createMemberCardDefinition("TBLOOM_ARCHIVE_TARGET_DEBUT", targetName, "DEBUT", 110, "RED");
        String targetFirstCardId = createMemberCardDefinition("TBLOOM_ARCHIVE_TARGET_FIRST", targetName, "FIRST", 160, "RED");

        jdbcTemplate.update(
            "UPDATE cards SET tags_json = ?::jsonb WHERE card_id = ?",
            "[\"" + archiveBloomTag + "\"]",
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
    }

    @Test
    void bloomShouldTriggerReturnCheerToDeckBottomEffectFromPassiveText() {
        StartedMatchContext context = createStartedMatch("bloom-cheer-bottom-host", "bloom-cheer-bottom-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 Bloom 回牌庫底";
        String debutCardId = createMemberCardDefinition("TBLOOM_CHEER_BOTTOM_DEBUT", displayName, "DEBUT", 120, "RED");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_CHEER_BOTTOM_FIRST",
            displayName,
            "FIRST",
            170,
            "RED",
            "{\"キーワード\":\"ブルームエフェクトテスト \\n自分のアーカイブの赤エール2枚を好きな順でエールデッキの下に戻せる。\"}"
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

        String cheerCardId = "TBLOOM_CHEER_BOTTOM_RED_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId,
            "測試紅 Cheer"
        );
        jdbcTemplate.update(
            """
            INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
            VALUES (?, 'RED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId
        );
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
            cheerCardId,
            nextArchiveOrder
        );
        Long archivedCheerCardInstanceId = jdbcTemplate.queryForObject(
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
            cheerCardId
        );
        assertThat(archivedCheerCardInstanceId).isNotNull();

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        Map<String, Object> cheerAfter = jdbcTemplate.queryForMap(
            "SELECT zone, is_face_down FROM match_cards WHERE id = ?",
            archivedCheerCardInstanceId
        );
        assertThat(cheerAfter.get("zone")).isEqualTo("CHEER_DECK");
        assertThat(cheerAfter.get("is_face_down")).isEqualTo(true);

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
        assertThat(payload).contains("RETURN_CHEER_TO_DECK_BOTTOM");
    }

    @Test
    void bloomShouldTriggerMoveZoneEffectFromPassiveText() {
        StartedMatchContext context = createStartedMatch("bloom-move-zone-host", "bloom-move-zone-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String displayName = "測試 Bloom 移動";
        String debutCardId = createMemberCardDefinition("TBLOOM_MOVE_DEBUT", displayName, "DEBUT", 120, "YELLOW");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_MOVE_FIRST",
            displayName,
            "FIRST",
            170,
            "YELLOW",
            "{\"キーワード\":\"ブルームエフェクトテスト \\n相手のコラボホロメンがいないなら、相手は、自身のバックホロメン1人をコラボポジションに移動させる。\"}"
        );
        String enemyBackCardId = createMemberCardDefinition("TBLOOM_MOVE_ENEMY_BACK", "測試移動目標", "DEBUT", 110, "YELLOW");

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);
        Long enemyBackCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            enemyBackCardId,
            "BACK",
            "DEBUT",
            0
        );

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        String enemyZoneAfter = jdbcTemplate.queryForObject(
            """
            SELECT zone
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            String.class,
            matchId,
            guestId,
            enemyBackCardInstanceId
        );
        assertThat(enemyZoneAfter).isEqualTo("COLLAB");

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
        assertThat(payload).contains("MOVE_ZONE");
    }

    @Test
    void bloomShouldTriggerSwapWithCollabEffectFromPassiveText() {
        StartedMatchContext context = createStartedMatch("bloom-swap-host", "bloom-swap-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "測試 Bloom 交換";
        String debutCardId = createMemberCardDefinition("TBLOOM_SWAP_DEBUT", displayName, "DEBUT", 120, "BLUE");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_SWAP_FIRST",
            displayName,
            "FIRST",
            170,
            "BLUE",
            "{\"キーワード\":\"ブルームエフェクトテスト \\n[バックポジション限定]自分の残りHP70以下のコラボホロメンとこのホロメンを交代できる。\"}"
        );
        String collabCardId = createMemberCardDefinition("TBLOOM_SWAP_COLLAB", "測試低血 COLLAB", "DEBUT", 100, "BLUE");

        Long sourceBackCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, firstCardId);
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "COLLAB",
            "DEBUT",
            0
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = 40
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            collabCardInstanceId
        );

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(sourceBackCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        String sourceZoneAfter = jdbcTemplate.queryForObject(
            """
            SELECT zone
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
            ORDER BY id
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId,
            firstCardId
        );
        String collabZoneAfter = jdbcTemplate.queryForObject(
            """
            SELECT zone
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId,
            collabCardInstanceId
        );
        assertThat(sourceZoneAfter).isEqualTo("COLLAB");
        assertThat(collabZoneAfter).isEqualTo("BACK");

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
        assertThat(payload).contains("SWAP_WITH_COLLAB");
    }

    @Test
    void bloomDiceBranchShouldReturnArchiveCardToHandOnOddRoll() {
        Mockito.when(diceService.rollD6()).thenReturn(1);

        StartedMatchContext context = createStartedMatch("bloom-dice-odd-host", "bloom-dice-odd-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String diceOddTag = "#TBLOOM_DICE_ODD_TAG";
        String displayName = "測試 Bloom 骰子分歧";
        String debutCardId = createMemberCardDefinition("TBLOOM_DICE_ODD_DEBUT", displayName, "DEBUT", 120, "RED");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_DICE_ODD_FIRST",
            displayName,
            "FIRST",
            170,
            "RED",
            "{\"キーワード\":\"ブルームエフェクトテスト \\nサイコロを1回振れる：奇数の時、自分のアーカイブの"
                + diceOddTag
                + "を持つホロメン1枚を手札に戻す。偶数の時、自分のアーカイブの"
                + diceOddTag
                + "を持つホロメン1枚をデッキの上に戻す。\"}"
        );
        String archiveMemberCardId = createMemberCardDefinition("TBLOOM_DICE_ODD_TARGET", "測試骰子目標", "DEBUT", 110, "RED");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = ?::jsonb WHERE card_id = ?",
            "[\"" + diceOddTag + "\"]",
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

        String zoneAfter = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            archiveCardInstanceId
        );
        assertThat(zoneAfter).isEqualTo("HAND");

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
        assertThat(payload).containsPattern("\"diceRoll\"\\s*:\\s*1");
    }

    @Test
    void bloomDiceBranchShouldReturnArchiveCardToDeckTopOnEvenRoll() {
        Mockito.when(diceService.rollD6()).thenReturn(6);

        StartedMatchContext context = createStartedMatch("bloom-dice-even-host", "bloom-dice-even-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String diceEvenTag = "#TBLOOM_DICE_EVEN_TAG";
        String displayName = "測試 Bloom 骰子分歧";
        String debutCardId = createMemberCardDefinition("TBLOOM_DICE_EVEN_DEBUT", displayName, "DEBUT", 120, "RED");
        String firstCardId = createMemberCardDefinition(
            "TBLOOM_DICE_EVEN_FIRST",
            displayName,
            "FIRST",
            170,
            "RED",
            "{\"キーワード\":\"ブルームエフェクトテスト \\nサイコロを1回振れる：奇数の時、自分のアーカイブの"
                + diceEvenTag
                + "を持つホロメン1枚を手札に戻す。偶数の時、自分のアーカイブの"
                + diceEvenTag
                + "を持つホロメン1枚をデッキの上に戻す。\"}"
        );
        String archiveMemberCardId = createMemberCardDefinition("TBLOOM_DICE_EVEN_TARGET", "測試骰子目標", "DEBUT", 110, "RED");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = ?::jsonb WHERE card_id = ?",
            "[\"" + diceEvenTag + "\"]",
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

        Map<String, Object> after = jdbcTemplate.queryForMap(
            "SELECT zone, is_face_down FROM match_cards WHERE id = ?",
            archiveCardInstanceId
        );
        assertThat(after.get("zone")).isEqualTo("DECK");
        assertThat(after.get("is_face_down")).isEqualTo(true);

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
        assertThat(payload).containsPattern("\"diceRoll\"\\s*:\\s*6");
        assertThat(payload).contains("RETURN_TO_DECK_TOP");
    }

    @Test
    void playSupportLimitedShouldRejectOnlyForFirstPlayerOnTurnOne() {
        StartedMatchContext context = createStartedMatch("support-limited-first-host", "support-limited-first-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long limitedSupportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TLIMIT_FIRST_" + System.nanoTime(),
            true,
            "DRAW",
            "{\"type\":\"DRAW\",\"value\":1}",
            "SELF"
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(limitedSupportCardInstanceId);

        assertThatThrownBy(() -> matchActionService.playSupport(matchId, hostId, request))
            .isInstanceOf(GameRuleException.class)
            .satisfies(ex -> assertThat(((GameRuleException) ex).getCode()).isEqualTo(GameErrorCode.LIMITED_FIRST_TURN));

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
            limitedSupportCardInstanceId,
            matchId,
            hostId
        );
        assertThat(stillInHand).isEqualTo(1);

        Long guestLimitedSupportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            guestId,
            "TLIMIT_FIRST_GUEST_" + System.nanoTime(),
            true,
            "DRAW",
            "{\"type\":\"DRAW\",\"value\":1}",
            "SELF"
        );
        jdbcTemplate.update(
            """
            UPDATE matches
            SET current_turn_player_id = ?,
                turn_number = 2,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            guestId,
            matchId
        );
        entityManager.clear();

        PlaySupportActionRequest guestRequest = new PlaySupportActionRequest();
        guestRequest.setCardInstanceId(guestLimitedSupportCardInstanceId);
        matchActionService.playSupport(matchId, guestId, guestRequest);

        Integer guestStillInHand = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            Integer.class,
            guestLimitedSupportCardInstanceId,
            matchId,
            guestId
        );
        assertThat(guestStillInHand).isZero();
    }

    @Test
    void playSupportLimitedShouldOnlyAllowOnePerTurn() {
        StartedMatchContext context = createStartedMatch("support-limited-turn-host", "support-limited-turn-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 3,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();
        entityManager.clear();

        Long firstLimited = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TLIMIT_ONCE_A_" + System.nanoTime(),
            true,
            "DRAW",
            "{\"type\":\"DRAW\",\"value\":1}",
            "SELF"
        );
        Long secondLimited = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TLIMIT_ONCE_B_" + System.nanoTime(),
            true,
            "DRAW",
            "{\"type\":\"DRAW\",\"value\":1}",
            "SELF"
        );

        PlaySupportActionRequest firstRequest = new PlaySupportActionRequest();
        firstRequest.setCardInstanceId(firstLimited);
        matchActionService.playSupport(matchId, hostId, firstRequest);

        PlaySupportActionRequest secondRequest = new PlaySupportActionRequest();
        secondRequest.setCardInstanceId(secondLimited);
        assertThatThrownBy(() -> matchActionService.playSupport(matchId, hostId, secondRequest))
            .isInstanceOf(GameRuleException.class)
            .satisfies(ex -> assertThat(((GameRuleException) ex).getCode()).isEqualTo(GameErrorCode.LIMITED_ALREADY_USED_THIS_TURN));
    }

    @Test
    void attackArtShouldApplyDamageToOpponentHolomemAndRestAttacker() {
        StartedMatchContext context = createStartedMatch("attack-damage-host", "attack-damage-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String hostCardId = "TART_HOST_" + System.nanoTime();
        String guestCardId = "TART_GUEST_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            hostCardId,
            "測試攻擊方 Holomen"
        );
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            guestCardId,
            "測試受擊方 Holomen"
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, 180, 'DEBUT', 'BLUE', NULL, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            hostCardId
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, 220, 'DEBUT', 'RED', NULL, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            guestCardId
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_arts (member_card_id, name, description, cost_cheer_json, effect_json, order_index)
            VALUES (?, '測試藝能 60', '', '{"COLORLESS":1}'::jsonb, '{"type":"DAMAGE","value":60,"rawHeader":"赤+50"}'::jsonb, 0)
            """,
            hostCardId
        );

        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'STAGE', NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            hostCardId
        );
        Long hostCardInstanceId = jdbcTemplate.queryForObject(
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
            hostId,
            hostCardId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomems (
                match_id, owner_user_id, match_card_id, card_id, zone, is_rested, is_face_down, damage_taken, current_level
            ) VALUES (?, ?, ?, ?, 'CENTER', FALSE, FALSE, 0, 'DEBUT')
            """,
            matchId,
            hostId,
            hostCardInstanceId,
            hostCardId
        );
        String cheerCardId = "TCHEER_ART_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId,
            "測試藝能費用 Cheer"
        );
        jdbcTemplate.update(
            """
            INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
            VALUES (?, 'BLUE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'STAGE', NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            hostId,
            cheerCardId
        );
        Long attackerHolomemId = jdbcTemplate.queryForObject(
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
            hostCardInstanceId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
            VALUES (?, ?, FALSE)
            """,
            attackerHolomemId,
            cheerCardId
        );

        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'STAGE', NULL, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            guestId,
            guestCardId
        );
        Long guestCardInstanceId = jdbcTemplate.queryForObject(
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
            guestCardId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomems (
                match_id, owner_user_id, match_card_id, card_id, zone, is_rested, is_face_down, damage_taken, current_level
            ) VALUES (?, ?, ?, ?, 'CENTER', FALSE, FALSE, 0, 'DEBUT')
            """,
            matchId,
            guestId,
            guestCardInstanceId,
            guestCardId
        );

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 3,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        AttackArtActionRequest request = new AttackArtActionRequest();
        request.setAttackerCardInstanceId(hostCardInstanceId);
        request.setTargetCardInstanceId(guestCardInstanceId);
        matchActionService.attackArt(matchId, hostId, request);

        Integer guestDamageTaken = jdbcTemplate.queryForObject(
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
            guestCardInstanceId
        );
        Boolean attackerRested = jdbcTemplate.query(
            """
            SELECT is_rested
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            rs -> rs.next() ? rs.getBoolean("is_rested") : null,
            matchId,
            hostId,
            hostCardInstanceId
        );

        assertThat(guestDamageTaken).isEqualTo(110);
        assertThat(attackerRested).isTrue();
    }

    @Test
    void attackArtShouldApplyIncomingDamageReductionFromTurnEffects() {
        StartedMatchContext context = createStartedMatch("attack-reduction-host", "attack-reduction-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            60,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":60}",
            1,
            "RED",
            "TREDUCE_HOST_CENTER"
        );
        Long guestCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "CENTER",
            200,
            "BLUE",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            0,
            "BLUE",
            "TREDUCE_GUEST_CENTER"
        );

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 3,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();
        jdbcTemplate.update(
            """
            INSERT INTO match_turn_effects (
                match_id,
                source_user_id,
                affected_user_id,
                effect_type,
                stat_type,
                modifier_value,
                expires_turn,
                payload
            ) VALUES (?, ?, ?, 'BUFF', 'DAMAGE_MODIFIER', 30, 3, CAST(? AS jsonb))
            """,
            matchId,
            guestId,
            guestId,
            "{\"rawText\":\"このターンの間、自分のホロメンが受けるダメージ-30。\"}"
        );

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);

        AttackArtActionRequest request = new AttackArtActionRequest();
        request.setAttackerCardInstanceId(hostCenterCardInstanceId);
        request.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, request);

        Integer guestDamageTaken = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
        );
        assertThat(guestDamageTaken).isEqualTo(30);

        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'ATTACK_ART'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payloadText).containsPattern("\"incomingDamageReduction\"\\s*:\\s*30");
        assertThat(payloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*30");
    }

    @Test
    void attackArtShouldRedirectDamageToPreparedReplacementTarget() {
        StartedMatchContext context = createStartedMatch("attack-redirect-host", "attack-redirect-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            70,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":70}",
            1,
            "RED",
            "TREDIRECT_HOST_CENTER"
        );
        Long guestCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "CENTER",
            200,
            "BLUE",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            0,
            "BLUE",
            "TREDIRECT_GUEST_CENTER"
        );
        Long guestBackCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "BACK",
            200,
            "BLUE",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            0,
            "BLUE",
            "TREDIRECT_GUEST_BACK"
        );
        Long guestBackHolomemId = jdbcTemplate.queryForObject(
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
            guestId,
            guestBackCardInstanceId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_turn_effects (
                match_id,
                source_user_id,
                affected_user_id,
                effect_type,
                stat_type,
                modifier_value,
                expires_turn,
                payload
            ) VALUES (?, ?, ?, 'DEBUFF', 'ACTION_LOCK', 1, 3, CAST(? AS jsonb))
            """,
            matchId,
            guestId,
            guestId,
            "{\"actions\":[\"DAMAGE_REDIRECT\"],\"targetHolomemId\":" + guestBackHolomemId + ",\"rawText\":\"そのダメージを、選んだホロメンがかわりに受ける。\"}"
        );

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 3,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);

        AttackArtActionRequest request = new AttackArtActionRequest();
        request.setAttackerCardInstanceId(hostCenterCardInstanceId);
        request.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, request);

        Integer centerDamageTaken = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
        );
        Integer backDamageTaken = jdbcTemplate.queryForObject(
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
            guestBackCardInstanceId
        );
        assertThat(centerDamageTaken).isZero();
        assertThat(backDamageTaken).isEqualTo(70);

        Integer redirectEffectRemaining = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ACTION_LOCK'
              AND payload ->> 'targetHolomemId' = ?
            """,
            Integer.class,
            matchId,
            guestId,
            String.valueOf(guestBackHolomemId)
        );
        assertThat(redirectEffectRemaining).isZero();

        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'ATTACK_ART'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payloadText).containsPattern("\"damageRedirectApplied\"\\s*:\\s*true");
        assertThat(payloadText).containsPattern("\"targetCardInstanceId\"\\s*:\\s*" + guestBackCardInstanceId);
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
    void playSupportMascotShouldAttachToHolomemAndRemainOnStage() {
        StartedMatchContext context = createStartedMatch("support-mascot-host", "support-mascot-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long memberHandCardInstanceId = findDebutMemberCardFromHand(matchId, hostId);
        if (memberHandCardInstanceId == null) {
            moveOneMemberFromDeckToHand(matchId, hostId);
            memberHandCardInstanceId = findDebutMemberCardFromHand(matchId, hostId);
        }
        assertThat(memberHandCardInstanceId).isNotNull();

        PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
        playToStage.setCardInstanceId(memberHandCardInstanceId);
        playToStage.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, playToStage);

        Long backCardInstanceId = jdbcTemplate.queryForObject(
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
        assertThat(backCardInstanceId).isNotNull();

        String mascotSupportCardId = "TSUP_MASCOT_" + System.nanoTime();
        Long mascotSupportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            mascotSupportCardId,
            false,
            "BUFF",
            "{\"type\":\"BUFF\",\"rawText\":\"カードタイプ\\nサポート・マスコット\\nこのマスコットが付いているホロメンのHP+20。\"}",
            "SELF"
        );
        assertThat(mascotSupportCardInstanceId).isNotNull();

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(mascotSupportCardInstanceId);
        request.setTargetHolomemCardInstanceId(backCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        Integer supportOnStage = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'STAGE'
            """,
            Integer.class,
            mascotSupportCardInstanceId,
            matchId,
            hostId
        );
        assertThat(supportOnStage).isEqualTo(1);

        Integer attachedRows = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomem_supports hs
            JOIN match_holomems h ON h.id = hs.match_holomem_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
              AND hs.match_card_id = ?
              AND hs.support_type = 'MASCOT'
            """,
            Integer.class,
            matchId,
            hostId,
            backCardInstanceId,
            mascotSupportCardInstanceId
        );
        assertThat(attachedRows).isEqualTo(1);

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var playerState = state.getPlayers().stream()
            .filter(player -> hostId.equals(player.getUserId()))
            .findFirst()
            .orElseThrow();
        var backZone = playerState.getBoardZones().stream()
            .filter(zone -> "BACK".equals(zone.getZone()))
            .findFirst()
            .orElseThrow();
        assertThat(backZone.getCards()).hasSize(1);
        assertThat(backZone.getCards().get(0).getAttachedSupportCount()).isEqualTo(1);
        Integer baseHp = jdbcTemplate.queryForObject(
            """
            SELECT m.hp
            FROM match_cards mc
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.id = ?
            """,
            Integer.class,
            backCardInstanceId
        );
        assertThat(backZone.getCards().get(0).getMaxHp()).isEqualTo((baseHp == null ? 0 : baseHp) + 20);
    }

    @Test
    void playSupportShouldAttachCheerToTargetHolomem() {
        StartedMatchContext context = createStartedMatch("support-cheer-host", "support-cheer-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        ensureOpeningHandContainsDebut(matchId, hostId);
        Long memberHandCardInstanceId = findMemberCardFromHand(matchId, hostId);
        if (memberHandCardInstanceId == null) {
            moveOneMemberFromDeckToHand(matchId, hostId);
            memberHandCardInstanceId = findMemberCardFromHand(matchId, hostId);
        }
        assertThat(memberHandCardInstanceId).isNotNull();

        PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
        playToStage.setCardInstanceId(memberHandCardInstanceId);
        playToStage.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, playToStage);

        Long backCardInstanceId = jdbcTemplate.queryForObject(
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
        assertThat(backCardInstanceId).isNotNull();

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
            backCardInstanceId
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        request.setTargetHolomemCardInstanceId(backCardInstanceId);
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
            backCardInstanceId
        );
        assertThat(afterCheerAttached).isEqualTo(beforeCheerAttached + 1);
    }

    @Test
    void attackArtShouldIncludeAttachedToolArtBonus() {
        StartedMatchContext context = createStartedMatch("support-art-bonus-host", "support-art-bonus-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            140,
            "RED",
            60,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":60,\"rawHeader\":\"測試藝能 60\"}",
            0,
            "RED",
            "tool-bonus-host-center"
        );
        Long guestCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "CENTER",
            160,
            "BLUE",
            30,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":30,\"rawHeader\":\"測試藝能 30\"}",
            0,
            "BLUE",
            "tool-bonus-guest-center"
        );

        Long toolSupportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TSUP_TOOL_ART_BONUS_" + System.nanoTime(),
            false,
            "BUFF",
            "{\"type\":\"BUFF\",\"rawText\":\"カードタイプ\\nサポート・ツール\\nこのツールが付いているホロメンのアーツ+10。\"}",
            "SELF"
        );
        PlaySupportActionRequest attachTool = new PlaySupportActionRequest();
        attachTool.setCardInstanceId(toolSupportCardInstanceId);
        attachTool.setTargetHolomemCardInstanceId(hostCenterCardInstanceId);
        matchActionService.playSupport(matchId, hostId, attachTool);

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 2,
                current_phase = 'MAIN',
                current_turn_player_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(hostCenterCardInstanceId);
        attack.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        String payloadText = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'ATTACK_ART'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payloadText).containsPattern("\"attachedSupportArtBonus\"\\s*:\\s*10");
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
            normalizeHolomemLevel(guestLevel)
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

        int lifeZoneBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'LIFE'",
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
        int lifeZoneAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = 'LIFE'",
            Integer.class,
            matchId,
            guestId
        );
        assertThat(opponentCenterCount).isEqualTo(0);
        assertThat(lifeZoneAfter).isEqualTo(lifeZoneBefore - 1);
    }

    @Test
    void playSupportDamageShouldCreateSendCheerInteractionWhenLifeReduced() {
        StartedMatchContext context = createStartedMatch("support-damage-send-cheer-host", "support-damage-send-cheer-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String hostCenterCardId = createMemberCardDefinition("TSUP_DMG_SC_HOST", "測試我方中心", "DEBUT", 120, "YELLOW");
        createStageHolomemWithSingleCard(matchId, hostId, hostCenterCardId, "CENTER", "DEBUT", 0);

        String guestCenterCardId = createMemberCardDefinition("TSUP_DMG_SC_GUEST_CENTER", "測試對手中心", "DEBUT", 80, "YELLOW");
        createStageHolomemWithSingleCard(matchId, guestId, guestCenterCardId, "CENTER", "DEBUT", 0);
        String guestBackCardId = createMemberCardDefinition("TSUP_DMG_SC_GUEST_BACK", "測試對手後排", "DEBUT", 110, "YELLOW");
        createStageHolomemWithSingleCard(matchId, guestId, guestBackCardId, "BACK", "DEBUT", 0);

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TSUP_DAMAGE_SEND_CHEER_" + System.nanoTime(),
            false,
            "DAMAGE",
            "{\"type\":\"DAMAGE\",\"value\":999}",
            "ENEMY"
        );
        forceTopLifeCardToCheer(matchId, guestId);
        int lifeBefore = countZone(matchId, guestId, "LIFE");

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        int lifeAfter = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfter).isEqualTo(lifeBefore - 1);

        Map<String, Object> sendCheerPending = jdbcTemplate.queryForMap(
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
        assertThat(sendCheerPending.get("decision_type")).isEqualTo("SEND_CHEER");
        assertThat(sendCheerPending.get("source_action_type")).isEqualTo("LIFE_LOSS");
        assertThat(((Number) sendCheerPending.get("source_card_instance_id")).longValue()).isPositive();
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
        playToStage.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, playToStage);
        MoveStageHolomemActionRequest moveToCenter = new MoveStageHolomemActionRequest();
        moveToCenter.setCardInstanceId(memberHandCardInstanceId);
        moveToCenter.setTargetZone("CENTER");
        matchActionService.moveStageHolomem(matchId, hostId, moveToCenter);

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

        Long memberHandCardInstanceId = findDebutMemberCardFromHand(matchId, hostId);
        if (memberHandCardInstanceId == null) {
            moveOneMemberFromDeckToHand(matchId, hostId);
            memberHandCardInstanceId = findDebutMemberCardFromHand(matchId, hostId);
        }
        assertThat(memberHandCardInstanceId).isNotNull();

        PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
        playToStage.setCardInstanceId(memberHandCardInstanceId);
        playToStage.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, playToStage);
        MoveStageHolomemActionRequest moveToCenter = new MoveStageHolomemActionRequest();
        moveToCenter.setCardInstanceId(memberHandCardInstanceId);
        moveToCenter.setTargetZone("CENTER");
        matchActionService.moveStageHolomem(matchId, hostId, moveToCenter);

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
            normalizeHolomemLevel(guestLevel)
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
    void playSupportSearchShouldApplyCompositeCriteriaAllOfAndAnyOf() {
        StartedMatchContext context = createStartedMatch("support-search-composite-host", "support-search-composite-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String matchedCardId = createMemberCardDefinition(
            "TSEARCH_COMPOSITE_MATCH",
            "複合條件命中",
            "DEBUT",
            90,
            "RED"
        );
        String wrongColorCardId = createMemberCardDefinition(
            "TSEARCH_COMPOSITE_WRONG_COLOR",
            "複合條件命中但顏色錯誤",
            "DEBUT",
            90,
            "BLUE"
        );
        String wrongTagNameCardId = createMemberCardDefinition(
            "TSEARCH_COMPOSITE_WRONG_TAG_NAME",
            "複合條件不命中",
            "DEBUT",
            90,
            "RED"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = ?::jsonb WHERE card_id = ?",
            "[\"#COMPOSITE_OK\"]",
            matchedCardId
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = ?::jsonb WHERE card_id = ?",
            "[\"#COMPOSITE_OK\"]",
            wrongColorCardId
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = ?::jsonb WHERE card_id = ?",
            "[\"#COMPOSITE_OTHER\"]",
            wrongTagNameCardId
        );

        Long matchedInstanceId = insertCardIntoDeckTop(matchId, hostId, matchedCardId);
        Long wrongColorInstanceId = insertCardIntoDeckTop(matchId, hostId, wrongColorCardId);
        Long wrongTagNameInstanceId = insertCardIntoDeckTop(matchId, hostId, wrongTagNameCardId);
        assertThat(matchedInstanceId).isNotNull();
        assertThat(wrongColorInstanceId).isNotNull();
        assertThat(wrongTagNameInstanceId).isNotNull();

        String supportCardId = "TSUP_SEARCH_COMPOSITE_" + System.nanoTime();
        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            supportCardId,
            false,
            "SEARCH",
            "{\"type\":\"SEARCH\",\"value\":1,\"searchCriteria\":{\"cardType\":\"MEMBER\","
                + "\"allOf\":[{\"color\":\"RED\"}],"
                + "\"anyOf\":[{\"tag\":\"#COMPOSITE_OK\"},{\"nameContains\":\"命中\"}]}}",
            "SELF"
        );
        assertThat(supportCardInstanceId).isNotNull();

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        request.setSelectedCardInstanceIds(List.of(matchedInstanceId));
        matchActionService.playSupport(matchId, hostId, request);

        Integer matchedMovedToHand = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            Integer.class,
            matchedInstanceId,
            matchId,
            hostId
        );
        Integer wrongColorStillDeck = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            wrongColorInstanceId,
            matchId,
            hostId
        );
        Integer wrongTagNameStillDeck = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            wrongTagNameInstanceId,
            matchId,
            hostId
        );
        assertThat(matchedMovedToHand).isEqualTo(1);
        assertThat(wrongColorStillDeck).isEqualTo(1);
        assertThat(wrongTagNameStillDeck).isEqualTo(1);
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
    void playSupportSearchShouldCreatePendingDecisionWhenMultipleCandidatesAndNoSelection() {
        StartedMatchContext context = createStartedMatch("support-pending-host", "support-pending-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String searchableCardA = createMemberCardDefinition(
            "TPENDING_SEARCH_A_" + System.nanoTime(),
            "待選卡 A",
            "DEBUT",
            80,
            "RED"
        );
        String searchableCardB = createMemberCardDefinition(
            "TPENDING_SEARCH_B_" + System.nanoTime(),
            "待選卡 B",
            "DEBUT",
            80,
            "RED"
        );
        Long cardInstanceA = insertCardIntoDeckTop(matchId, hostId, searchableCardA);
        Long cardInstanceB = insertCardIntoDeckTop(matchId, hostId, searchableCardB);
        assertThat(cardInstanceA).isNotNull();
        assertThat(cardInstanceB).isNotNull();

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TSUP_PENDING_" + System.nanoTime(),
            false,
            "SEARCH",
            "{\"type\":\"SEARCH\",\"value\":1,\"searchCriteria\":{\"cardType\":\"MEMBER\"}}",
            "SELF"
        );

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        Integer pendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
            """,
            Integer.class,
            matchId,
            hostId
        );
        assertThat(pendingCount).isEqualTo(1);

        Integer supportArchived = jdbcTemplate.queryForObject(
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
        assertThat(supportArchived).isEqualTo(1);

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
        Integer remainedBInDeck = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            cardInstanceB,
            matchId,
            hostId
        );
        assertThat(remainedAInDeck).isEqualTo(1);
        assertThat(remainedBInDeck).isEqualTo(1);

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        assertThat(state.getPendingDecisions()).hasSize(1);
        assertThat(state.getPendingDecisions().get(0).getCandidates()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void playSupportSearchShouldCreateDeckBottomReorderInteractionAndResolveInSpecifiedOrder() {
        StartedMatchContext context = createStartedMatch("support-search-reorder-host", "support-search-reorder-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String cardA = createMemberCardDefinition("TSEARCH_REORDER_A", "排序 A", "DEBUT", 90, "RED");
        String cardB = createMemberCardDefinition("TSEARCH_REORDER_B", "排序 B", "DEBUT", 90, "BLUE");
        String cardC = createMemberCardDefinition("TSEARCH_REORDER_C", "排序 C", "DEBUT", 90, "GREEN");
        String cardD = createMemberCardDefinition("TSEARCH_REORDER_D", "排序 D", "DEBUT", 90, "WHITE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#ORDER_TEST\"]'::jsonb WHERE card_id IN (?, ?, ?, ?)",
            cardA,
            cardB,
            cardC,
            cardD
        );

        insertCardIntoDeckTop(matchId, hostId, cardA);
        insertCardIntoDeckTop(matchId, hostId, cardB);
        insertCardIntoDeckTop(matchId, hostId, cardC);
        insertCardIntoDeckTop(matchId, hostId, cardD);

        List<Long> topFourBefore = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT 4
            """,
            (rs, rowNum) -> rs.getLong("id"),
            matchId,
            hostId
        );
        assertThat(topFourBefore).hasSize(4);
        Long selectedToHand = topFourBefore.get(0);

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TSUP_SEARCH_REORDER_" + System.nanoTime(),
            false,
            "SEARCH",
            "{\"type\":\"SEARCH\",\"value\":1,\"searchCriteria\":{\"cardType\":\"MEMBER\",\"tag\":\"#ORDER_TEST\"},"
                + "\"rawText\":\"自分のデッキの上から4枚を見る。その中から、#ORDER_TESTを持つホロメンを1枚手札に加える。そして残ったカードを好きな順でデッキの下に戻す。\"}",
            "SELF"
        );
        assertThat(supportCardInstanceId).isNotNull();

        PlaySupportActionRequest playSupport = new PlaySupportActionRequest();
        playSupport.setCardInstanceId(supportCardInstanceId);
        playSupport.setSelectedCardInstanceIds(List.of(selectedToHand));
        matchActionService.playSupport(matchId, hostId, playSupport);

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
            selectedToHand,
            matchId,
            hostId
        );
        assertThat(movedToHand).isEqualTo(1);

        Long decisionId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'REORDER_DECK_BOTTOM'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        assertThat(decisionId).isNotNull();

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var reorderInteraction = state.getPendingInteractions().stream()
            .filter(item -> "REORDER_DECK_BOTTOM".equals(item.getInteractionType()))
            .findFirst()
            .orElseThrow();
        assertThat(reorderInteraction.getCards()).hasSize(3);

        List<Long> ordered = reorderInteraction.getCards().stream()
            .map(card -> card.getCardInstanceId())
            .toList();
        List<Long> reversed = new java.util.ArrayList<>(ordered);
        java.util.Collections.reverse(reversed);

        ResolveDecisionRequest resolve = new ResolveDecisionRequest();
        resolve.setDecisionId(decisionId);
        resolve.setSelectedCardInstanceIds(reversed);
        matchActionService.resolveDecision(matchId, hostId, resolve);

        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM match_pending_decisions WHERE id = ?",
            String.class,
            decisionId
        );
        assertThat(status).isEqualTo("RESOLVED");

        List<Long> reorderedAtBottom = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
              AND id IN (?, ?, ?)
            ORDER BY order_index ASC
            """,
            (rs, rowNum) -> rs.getLong("id"),
            matchId,
            hostId,
            reversed.get(0),
            reversed.get(1),
            reversed.get(2)
        );
        assertThat(reorderedAtBottom).containsExactlyElementsOf(reversed);
    }

    @Test
    void resolveDecisionShouldApplySelectedCardAndMarkDecisionResolved() {
        StartedMatchContext context = createStartedMatch("support-resolve-host", "support-resolve-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String searchableCardA = createMemberCardDefinition(
            "TRESOLVE_SEARCH_A_" + System.nanoTime(),
            "決策卡 A",
            "DEBUT",
            80,
            "BLUE"
        );
        String searchableCardB = createMemberCardDefinition(
            "TRESOLVE_SEARCH_B_" + System.nanoTime(),
            "決策卡 B",
            "DEBUT",
            80,
            "BLUE"
        );
        Long cardInstanceA = insertCardIntoDeckTop(matchId, hostId, searchableCardA);
        Long cardInstanceB = insertCardIntoDeckTop(matchId, hostId, searchableCardB);
        assertThat(cardInstanceA).isNotNull();
        assertThat(cardInstanceB).isNotNull();

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TSUP_RESOLVE_" + System.nanoTime(),
            false,
            "SEARCH",
            "{\"type\":\"SEARCH\",\"value\":1,\"searchCriteria\":{\"cardType\":\"MEMBER\"}}",
            "SELF"
        );

        PlaySupportActionRequest playSupport = new PlaySupportActionRequest();
        playSupport.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, playSupport);

        Long decisionId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        assertThat(decisionId).isNotNull();

        ResolveDecisionRequest resolveDecisionRequest = new ResolveDecisionRequest();
        resolveDecisionRequest.setDecisionId(decisionId);
        resolveDecisionRequest.setSelectedCardInstanceIds(List.of(cardInstanceB));
        matchActionService.resolveDecision(matchId, hostId, resolveDecisionRequest);

        Integer decisionResolved = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE id = ?
              AND status = 'RESOLVED'
            """,
            Integer.class,
            decisionId
        );
        assertThat(decisionResolved).isEqualTo(1);

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
    void playSupportShouldPartiallyResolveWhenOneEffectCannotBeApplied() {
        StartedMatchContext context = createStartedMatch("support-partial-host", "support-partial-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        int handBefore = countZone(matchId, hostId, "HAND");
        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TSUP_PARTIAL_" + System.nanoTime(),
            false,
            "DRAW",
            "{\"effects\":[\"DRAW\",\"HEAL\"],\"value\":1,\"heal\":30}",
            "SELF"
        );
        assertThat(supportCardInstanceId).isNotNull();

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        int handAfter = countZone(matchId, hostId, "HAND");
        assertThat(handAfter).isEqualTo(handBefore + 1);

        String payload = jdbcTemplate.queryForObject(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'PLAY_SUPPORT'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payload).contains("\"partiallyResolved\": true");
        assertThat(payload).contains("\"skipped\": true");
        assertThat(payload).contains("\"effectType\": \"HEAL\"");
    }

    @Test
    void resolveDecisionShouldCreateSendCheerInteractionWhenResolvedSupportReducesLife() {
        StartedMatchContext context = createStartedMatch("support-resolve-life-loss-host", "support-resolve-life-loss-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String hostCenterCardId = createMemberCardDefinition("TRL_HOST_C", "決策我方中心", "DEBUT", 120, "WHITE");
        createStageHolomemWithSingleCard(matchId, hostId, hostCenterCardId, "CENTER", "DEBUT", 0);

        String guestCenterCardId = createMemberCardDefinition("TRESOLVE_LIFE_GUEST_CENTER", "決策受擊中心", "DEBUT", 80, "WHITE");
        createStageHolomemWithSingleCard(matchId, guestId, guestCenterCardId, "CENTER", "DEBUT", 0);
        String guestBackCardId = createMemberCardDefinition("TRESOLVE_LIFE_GUEST_BACK", "決策受擊後排", "DEBUT", 120, "WHITE");
        createStageHolomemWithSingleCard(matchId, guestId, guestBackCardId, "BACK", "DEBUT", 0);

        String searchableCardA = createMemberCardDefinition("TRL_A", "決策搜尋 A", "DEBUT", 80, "WHITE");
        String searchableCardB = createMemberCardDefinition("TRL_B", "決策搜尋 B", "DEBUT", 80, "WHITE");
        Long cardInstanceA = insertCardIntoDeckTop(matchId, hostId, searchableCardA);
        Long cardInstanceB = insertCardIntoDeckTop(matchId, hostId, searchableCardB);
        assertThat(cardInstanceA).isNotNull();
        assertThat(cardInstanceB).isNotNull();

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TSUP_RESOLVE_LIFE_" + System.nanoTime(),
            false,
            "SEARCH",
            "{\"effects\":[\"SEARCH\",\"DAMAGE\"],\"searchCriteria\":{\"cardType\":\"MEMBER\"},\"cards\":1,\"damage\":999}",
            "ENEMY"
        );

        forceTopLifeCardToCheer(matchId, guestId);
        int lifeBefore = countZone(matchId, guestId, "LIFE");

        PlaySupportActionRequest playSupport = new PlaySupportActionRequest();
        playSupport.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, playSupport);

        Long decisionId = jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'CARD_SELECTION'
            ORDER BY id DESC
            LIMIT 1
            """,
            Long.class,
            matchId,
            hostId
        );
        assertThat(decisionId).isNotNull();

        ResolveDecisionRequest resolveDecisionRequest = new ResolveDecisionRequest();
        resolveDecisionRequest.setDecisionId(decisionId);
        resolveDecisionRequest.setSelectedCardInstanceIds(List.of(cardInstanceA));
        matchActionService.resolveDecision(matchId, hostId, resolveDecisionRequest);

        int lifeAfter = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfter).isEqualTo(lifeBefore - 1);

        Map<String, Object> sendCheerPending = jdbcTemplate.queryForMap(
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
        assertThat(sendCheerPending.get("decision_type")).isEqualTo("SEND_CHEER");
        assertThat(sendCheerPending.get("source_action_type")).isEqualTo("LIFE_LOSS");
        assertThat(((Number) sendCheerPending.get("source_card_instance_id")).longValue()).isPositive();
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

        executeRequiredTurnActions(matchId, hostId, null);
        matchActionService.endTurn(matchId, hostId);

        Integer remainingTurnEffects = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_turn_effects WHERE match_id = ?",
            Integer.class,
            matchId
        );
        assertThat(remainingTurnEffects).isEqualTo(0);
    }

    @Test
    void attackArtShouldLimitCenterAndCollabToOnceEachPerTurn() {
        StartedMatchContext context = createStartedMatch("attack-zone-limit-host", "attack-zone-limit-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            200,
            "BLUE",
            30,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":30}",
            1,
            "BLUE",
            "TZONE_CENTER"
        );
        Long hostCollabCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "COLLAB",
            200,
            "GREEN",
            40,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":40}",
            1,
            "GREEN",
            "TZONE_COLLAB"
        );
        Long guestCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "CENTER",
            500,
            "RED",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            0,
            "RED",
            "TZONE_GUEST"
        );

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 3,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        AttackArtActionRequest centerAttack = new AttackArtActionRequest();
        centerAttack.setAttackerCardInstanceId(hostCenterCardInstanceId);
        centerAttack.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, centerAttack);

        String phaseAfterCenterAttack = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(phaseAfterCenterAttack).isEqualTo("PERFORMANCE");

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = FALSE
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        assertThatThrownBy(() -> matchActionService.attackArt(matchId, hostId, centerAttack))
            .isInstanceOfAny(IllegalStateException.class, GameRuleException.class)
            .hasMessageContaining("已使用過藝能");

        AttackArtActionRequest collabAttack = new AttackArtActionRequest();
        collabAttack.setAttackerCardInstanceId(hostCollabCardInstanceId);
        collabAttack.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, collabAttack);

        String phaseAfterCollabAttack = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(phaseAfterCollabAttack).isEqualTo("END");

        Integer totalDamageTaken = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
        );
        assertThat(totalDamageTaken).isEqualTo(70);
    }

    @Test
    void batonTouchShouldAllowOnlyOncePerTurn() {
        StartedMatchContext context = createStartedMatch("baton-once-host", "baton-once-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long centerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            30,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":30}",
            1,
            "RED",
            "TBATON_ONCE_CENTER"
        );
        Long backCardAInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "BACK",
            170,
            "GREEN",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            1,
            "GREEN",
            "TBATON_ONCE_BACK_A"
        );
        Long backCardBInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "BACK",
            160,
            "BLUE",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            1,
            "BLUE",
            "TBATON_ONCE_BACK_B"
        );

        BatonTouchActionRequest first = new BatonTouchActionRequest();
        first.setSourceHolomemCardInstanceId(centerCardInstanceId);
        first.setTargetBackHolomemCardInstanceId(backCardAInstanceId);
        matchActionService.batonTouch(matchId, hostId, first);

        BatonTouchActionRequest second = new BatonTouchActionRequest();
        second.setSourceHolomemCardInstanceId(backCardAInstanceId);
        second.setTargetBackHolomemCardInstanceId(backCardBInstanceId);
        assertThatThrownBy(() -> matchActionService.batonTouch(matchId, hostId, second))
            .isInstanceOf(GameRuleException.class)
            .satisfies(ex -> assertThat(((GameRuleException) ex).getCode()).isEqualTo(GameErrorCode.BATON_TOUCH_ALREADY_USED_THIS_TURN));
    }

    @Test
    void batonTouchShouldBeBlockedByActionLockEffect() {
        StartedMatchContext context = createStartedMatch("baton-lock-host", "baton-lock-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long centerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            30,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":30}",
            1,
            "RED",
            "TBATON_LOCK_CENTER"
        );
        Long backCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "BACK",
            170,
            "GREEN",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            1,
            "GREEN",
            "TBATON_LOCK_BACK"
        );
        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TBATON_LOCK_SUPPORT_" + System.nanoTime(),
            false,
            "ACTION_LOCK",
            "{\"type\":\"ACTION_LOCK\",\"rawText\":\"このターンの間、自分のセンターホロメンとコラボホロメンは、バトンタッチ、移動、交代できない。\"}",
            "SELF"
        );

        PlaySupportActionRequest playSupport = new PlaySupportActionRequest();
        playSupport.setCardInstanceId(supportCardInstanceId);
        matchActionService.playSupport(matchId, hostId, playSupport);

        BatonTouchActionRequest batonTouch = new BatonTouchActionRequest();
        batonTouch.setSourceHolomemCardInstanceId(centerCardInstanceId);
        batonTouch.setTargetBackHolomemCardInstanceId(backCardInstanceId);
        assertThatThrownBy(() -> matchActionService.batonTouch(matchId, hostId, batonTouch))
            .isInstanceOf(GameRuleException.class)
            .satisfies(ex -> assertThat(((GameRuleException) ex).getCode()).isEqualTo(GameErrorCode.STAGE_ACTION_LOCKED));
    }

    @Test
    void actionLockUnrestShouldKeepTargetRestedAtNextOpponentResetStep() {
        StartedMatchContext context = createStartedMatch("unrest-lock-host", "unrest-lock-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            findMemberCardIdByLevel("DEBUT"),
            "CENTER",
            "DEBUT",
            0
        );
        Long guestCenterCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            findMemberCardIdByLevel("DEBUT"),
            "CENTER",
            "DEBUT",
            0
        );

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TUNREST_LOCK_SUPPORT_" + System.nanoTime(),
            false,
            "ACTION_LOCK",
            "{\"type\":\"ACTION_LOCK\",\"rawText\":\"相手のセンターホロメンを選ぶ。選んだホロメンは、次の相手のリセットステップでアクティブにならない。\"}",
            "BOTH"
        );

        PlaySupportActionRequest playSupport = new PlaySupportActionRequest();
        playSupport.setCardInstanceId(supportCardInstanceId);
        playSupport.setTargetHolomemCardInstanceId(guestCenterCardInstanceId);
        matchActionService.playSupport(matchId, hostId, playSupport);

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = TRUE
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);

        Boolean guestCenterRestedAfterReset = jdbcTemplate.query(
            """
            SELECT is_rested
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            rs -> rs.next() ? rs.getBoolean("is_rested") : null,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        assertThat(guestCenterRestedAfterReset).isTrue();
    }

    @Test
    void attackArtShouldTriggerGiftDrawOncePerTurnWhenCenterConditionSatisfied() {
        StartedMatchContext context = createStartedMatch("gift-art-host", "gift-art-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String giftHolderCardId = createMemberCardDefinition(
            "TGIFT_ART_CENTER",
            "測試 Gift 中心",
            "DEBUT",
            130,
            "YELLOW",
            "{\"キーワード\":\"ギフトテスト \\n[センターポジション限定][ターンに1回]自分のホロメンがアーツを使った時、自分のデッキを1枚引く。\"}"
        );
        createStageHolomemWithSingleCard(
            matchId,
            hostId,
            giftHolderCardId,
            "CENTER",
            "DEBUT",
            0
        );

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "COLLAB",
            170,
            "BLUE",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            1,
            "BLUE",
            "TGIFT_ART_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            findMemberCardIdByLevel("DEBUT"),
            "CENTER",
            "DEBUT",
            0
        );

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 2,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        Integer forcedTurn = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        assertThat(forcedTurn).isEqualTo(2);
        entityManager.clear();

        int deckBefore = countZone(matchId, hostId, "DECK");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        int deckAfterFirstAttack = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterFirstAttack).isEqualTo(deckBefore - 1);

        List<Map<String, Object>> giftActions = jdbcTemplate.queryForList(
            """
            SELECT payload::text AS payload_text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'GIFT_TRIGGER'
            ORDER BY id ASC
            """,
            matchId,
            hostId,
            2
        );
        assertThat(giftActions).hasSize(1);

        List<Map<String, Object>> secondTrigger = matchEffectService.applyGiftTriggeredEffectsOnArt(
            matchId,
            hostId,
            attackerCardInstanceId,
            targetCardInstanceId,
            2
        );
        assertThat(secondTrigger).isEmpty();
    }

    @Test
    void batonTouchShouldRequireBackTargetNotRested() {
        StartedMatchContext context = createStartedMatch("baton-rest-host", "baton-rest-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long centerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            30,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":30}",
            1,
            "RED",
            "TBATON_REST_CENTER"
        );
        Long backCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "BACK",
            170,
            "GREEN",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            1,
            "GREEN",
            "TBATON_REST_BACK"
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = TRUE
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            backCardInstanceId
        );

        BatonTouchActionRequest batonTouch = new BatonTouchActionRequest();
        batonTouch.setSourceHolomemCardInstanceId(centerCardInstanceId);
        batonTouch.setTargetBackHolomemCardInstanceId(backCardInstanceId);
        assertThatThrownBy(() -> matchActionService.batonTouch(matchId, hostId, batonTouch))
            .isInstanceOfAny(GameRuleException.class, IllegalStateException.class)
            .hasMessageContaining("非休息");
    }

    @Test
    void useOshiSkillShouldConsumeHolopowerAndMarkSkillUsedThisTurn() {
        StartedMatchContext context = createStartedMatch("oshi-skill-host", "oshi-skill-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Integer holopowerCost = jdbcTemplate.query(
            """
            SELECT os.holopower_cost
            FROM match_players mp
            JOIN oshi_skills os
              ON os.oshi_card_id = mp.oshi_card_id
            WHERE mp.match_id = ?
              AND mp.user_id = ?
              AND os.skill_type = 'NORMAL'
            ORDER BY os.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getInt("holopower_cost") : null,
            matchId,
            hostId
        );
        assertThat(holopowerCost).isNotNull();
        seedHolopower(matchId, hostId, holopowerCost);
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");
        int holopowerBefore = countZone(matchId, hostId, "HOLOPOWER");

        UseOshiSkillActionRequest request = new UseOshiSkillActionRequest();
        request.setSkillType("NORMAL");
        matchActionService.useOshiSkill(matchId, hostId, request);

        Boolean usedThisTurn = jdbcTemplate.queryForObject(
            """
            SELECT skill_used_this_turn
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            """,
            Boolean.class,
            matchId,
            hostId
        );
        assertThat(usedThisTurn).isTrue();
        assertThat(countZone(matchId, hostId, "HOLOPOWER")).isEqualTo(holopowerBefore - holopowerCost);
        assertThat(countZone(matchId, hostId, "ARCHIVE")).isEqualTo(archiveBefore + holopowerCost);

        Integer actionCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'USE_OSHI_SKILL'
            """,
            Integer.class,
            matchId,
            hostId
        );
        assertThat(actionCount).isEqualTo(1);
    }

    @Test
    void useOshiSkillShouldAllowReuseAfterTurnCycles() {
        StartedMatchContext context = createStartedMatch("oshi-turn-host", "oshi-turn-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Integer holopowerCost = jdbcTemplate.query(
            """
            SELECT os.holopower_cost
            FROM match_players mp
            JOIN oshi_skills os
              ON os.oshi_card_id = mp.oshi_card_id
            WHERE mp.match_id = ?
              AND mp.user_id = ?
              AND os.skill_type = 'NORMAL'
            ORDER BY os.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getInt("holopower_cost") : null,
            matchId,
            hostId
        );
        assertThat(holopowerCost).isNotNull();
        seedHolopower(matchId, hostId, holopowerCost * 2);

        UseOshiSkillActionRequest request = new UseOshiSkillActionRequest();
        request.setSkillType("NORMAL");
        matchActionService.useOshiSkill(matchId, hostId, request);
        assertThatThrownBy(() -> matchActionService.useOshiSkill(matchId, hostId, request))
            .isInstanceOf(GameRuleException.class)
            .satisfies(ex -> assertThat(((GameRuleException) ex).getCode()).isEqualTo(GameErrorCode.OSHI_SKILL_ALREADY_USED_THIS_TURN));

        createStageHolomemWithSingleCard(matchId, hostId, findMemberCardIdByLevel("DEBUT"), "CENTER", "DEBUT", 0);
        createStageHolomemWithSingleCard(matchId, guestId, findMemberCardIdByLevel("DEBUT"), "CENTER", "DEBUT", 0);
        executeRequiredTurnActions(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
        matchActionService.endTurn(matchId, hostId);

        resolvePendingInteractionIfExists(matchId, guestId, "TURN_START");
        executeRequiredTurnActions(matchId, guestId, loadFirstCenterCardInstanceId(matchId, guestId));
        matchActionService.endTurn(matchId, guestId);

        resolvePendingInteractionIfExists(matchId, hostId, "TURN_START");
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET skill_used_this_turn = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            hostId
        );
        entityManager.clear();
        matchActionService.useOshiSkill(matchId, hostId, request);

        Integer actionCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'USE_OSHI_SKILL'
            """,
            Integer.class,
            matchId,
            hostId
        );
        assertThat(actionCount).isEqualTo(2);
    }

    private StartedMatchContext createStartedMatch(String hostPrefix, String guestPrefix) {
        StartedMatchContext context = createReadyMatch(hostPrefix, guestPrefix);
        lobbyMatchService.startMatch(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.guestId());

        MulliganActionRequest hostMulligan = new MulliganActionRequest();
        hostMulligan.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.hostId(), hostMulligan);

        MulliganActionRequest guestMulligan = new MulliganActionRequest();
        guestMulligan.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.guestId(), guestMulligan);

        resolvePendingInteractionIfExists(context.matchId(), context.hostId(), "TURN_START");

        return context;
    }

    private void resolvePendingInteractionIfExists(Long matchId, Long userId, String decisionType) {
        Long decisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            decisionType
        );
        if (decisionId == null) {
            return;
        }
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setDecisionId(decisionId);
        matchActionService.resolveDecision(matchId, userId, request);
    }

    private void executeRequiredTurnActions(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        resolvePendingInteractionIfExists(matchId, userId, "TURN_START");
        try {
            matchActionService.drawTurn(matchId, userId);
        } catch (IllegalStateException | GameRuleException ex) {
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
        resolvePendingInteractionIfExists(matchId, userId, "DRAW_REVEAL");
        try {
            matchActionService.sendTurnCheer(matchId, userId);
        } catch (IllegalStateException | GameRuleException ex) {
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

    private Long loadFirstStageCardInstanceId(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB', 'BACK')
            ORDER BY CASE zone
                       WHEN 'CENTER' THEN 0
                       WHEN 'COLLAB' THEN 1
                       ELSE 2
                     END,
                     id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            userId
        );
    }

    private Long loadFirstCenterCardInstanceId(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            userId
        );
    }

    private StartedMatchContext createReadyMatch(String hostPrefix, String guestPrefix) {
        User host = createUser(hostPrefix);
        User guest = createUser(guestPrefix);
        deckService.setupQuickDeck(host.getId());
        deckService.setupQuickDeck(guest.getId());

        LobbyMatch created = lobbyMatchService.createMatch(host.getId());
        lobbyMatchService.joinMatch(created.getRoomCode(), guest.getId());
        lobbyMatchService.setReady(created.getId(), host.getId(), true);
        lobbyMatchService.setReady(created.getId(), guest.getId(), true);
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

    private Long findDebutMemberCardFromHand(Long matchId, Long userId) {
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

    private void seedHolopower(Long matchId, Long userId, int count) {
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

    private Long createStageHolomemWithArtAndCheer(
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

    private String createMemberCardDefinition(
        String prefix,
        String displayName,
        String levelType,
        int hp,
        String mainColor
    ) {
        return createMemberCardDefinition(prefix, displayName, levelType, hp, mainColor, null);
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
            passiveEffectJson == null ? "null" : passiveEffectJson
        );
        return cardId;
    }

    private Long insertCardIntoHand(Long matchId, Long ownerUserId, String cardId) {
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

    private Long createStageHolomemWithSingleCard(
        Long matchId,
        Long ownerUserId,
        String cardId,
        String zone,
        String levelType,
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
        Long cardInstanceId = jdbcTemplate.queryForObject(
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
            normalizeHolomemLevel(levelType),
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

    private Map<String, Integer> loadPrimaryArtRequiredCheerCost(Long matchId, Long cardInstanceId) {
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

    private void seedCheerDeckForPrimaryArtCost(Long matchId, Long userId, Map<String, Integer> requiredCheerCost) {
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

    private String normalizeCheerColorForTest(String color) {
        if (color == null || color.isBlank()) {
            return "WHITE";
        }
        String normalized = color.trim().toUpperCase();
        if ("COLORLESS".equals(normalized)) {
            return "WHITE";
        }
        return normalized;
    }

    private Long findTopCheerDeckCard(Long matchId, Long userId) {
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

    private void ensureOpeningHandContainsDebut(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards mc
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'HAND'
              AND m.level_type = 'DEBUT'
            """,
            Integer.class,
            matchId,
            userId
        );
        if (count != null && count > 0) {
            return;
        }
        String debutCardId = findMemberCardIdByLevel("DEBUT");
        Long targetCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (targetCardInstanceId == null) {
            return;
        }
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            debutCardId,
            targetCardInstanceId,
            matchId,
            userId
        );
    }

    private void replaceZoneCardsCardId(Long matchId, Long userId, String zone, String cardId) {
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

    private String findMemberCardIdByLevel(String levelType) {
        return jdbcTemplate.queryForObject(
            """
            SELECT m.card_id
            FROM member_cards m
            WHERE m.level_type = ?
            ORDER BY m.card_id
            LIMIT 1
            """,
            String.class,
            levelType
        );
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

    private Long forceTopLifeCardToCheer(Long matchId, Long userId) {
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

    private void keepTopLifeCards(Long matchId, Long userId, int keepCount) {
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

    private String normalizeHolomemLevel(String rawLevel) {
        if ("FIRST".equals(rawLevel) || "SECOND".equals(rawLevel) || "SPOT".equals(rawLevel) || "BUZZ".equals(rawLevel)) {
            return rawLevel;
        }
        return "DEBUT";
    }

    private int bloomLevelOf(String levelType) {
        return switch (levelType) {
            case "FIRST" -> 1;
            case "SECOND" -> 2;
            case "BUZZ" -> 3;
            default -> 0;
        };
    }

    private Long insertSupportCardIntoHand(
        Long matchId,
        Long userId,
        String supportCardId,
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
            "測試支援卡 " + supportCardId
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
            userId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, 'HAND', ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            userId,
            supportCardId,
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
            userId,
            supportCardId
        );
    }

    private record StartedMatchContext(Long matchId, Long hostId, Long guestId) {
    }
}
