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
import com.hololive.cardgame.support.AbstractPostgresIntegrationTest;
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
class MatchActionServiceIntegrationTest extends AbstractPostgresIntegrationTest {

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
    void playToStageShouldKeepResetPhaseUntilOpeningSetupFinished() {
        StartedMatchContext context = createReadyMatch("reset-auto-host", "reset-auto-guest");
        lobbyMatchService.startMatch(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.guestId());

        MulliganActionRequest hostMulligan = new MulliganActionRequest();
        hostMulligan.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.hostId(), hostMulligan);

        MulliganActionRequest guestMulligan = new MulliganActionRequest();
        guestMulligan.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.guestId(), guestMulligan);

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
        assertThat(phase).isEqualTo("RESET");
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
        assertThat(turnStartPendingCount).isZero();
    }

    @Test
    void mulliganShouldKeepTurnOnCurrentPlayerUntilTheyFinishAndThenPassToNextPlayer() {
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
        assertThat(turnAfterHost).isEqualTo(context.hostId());

        MulliganActionRequest hostFinishRequest = new MulliganActionRequest();
        hostFinishRequest.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.hostId(), hostFinishRequest);

        String phaseAfterHostFinish = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        Long turnAfterHostFinish = jdbcTemplate.queryForObject(
            "SELECT current_turn_player_id FROM matches WHERE id = ?",
            Long.class,
            context.matchId()
        );
        assertThat(phaseAfterHostFinish).isEqualTo("RESET");
        assertThat(turnAfterHostFinish).isEqualTo(context.guestId());

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
        assertThat(phaseAfterGuest).isEqualTo("RESET");
        assertThat(turnAfterGuest).isEqualTo(context.hostId());
    }

    @Test
    void advancePhaseShouldHandOpeningSetupToGuestAndThenCreateLiveStart() {
        StartedMatchContext context = createReadyMatch("opening-advance-host", "opening-advance-guest");
        lobbyMatchService.startMatch(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.guestId());

        MulliganActionRequest hostMulligan = new MulliganActionRequest();
        hostMulligan.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.hostId(), hostMulligan);

        MulliganActionRequest guestMulligan = new MulliganActionRequest();
        guestMulligan.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.guestId(), guestMulligan);

        playOpeningCenter(context.matchId(), context.hostId());
        matchActionService.advancePhase(context.matchId(), context.hostId());

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

        playOpeningCenter(context.matchId(), context.guestId());
        matchActionService.advancePhase(context.matchId(), context.guestId());

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
        assertThat(phaseAfterGuest).isEqualTo("RESET");
        assertThat(turnAfterGuest).isEqualTo(context.hostId());

        Integer liveStartPendingCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'LIVE_START'
            """,
            Integer.class,
            context.matchId(),
            context.hostId()
        );
        assertThat(liveStartPendingCount).isEqualTo(1);
    }

    @Test
    void resolveLiveStartShouldFlipOpeningCardsAndCreateTurnStart() {
        StartedMatchContext context = createReadyMatch("live-start-host", "live-start-guest");
        lobbyMatchService.startMatch(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.guestId());

        MulliganActionRequest hostMulligan = new MulliganActionRequest();
        hostMulligan.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.hostId(), hostMulligan);

        MulliganActionRequest guestMulligan = new MulliganActionRequest();
        guestMulligan.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.guestId(), guestMulligan);

        Long hostCenterCardInstanceId = playOpeningCenter(context.matchId(), context.hostId());
        Long hostBackCardInstanceId = playOpeningBack(context.matchId(), context.hostId());
        matchActionService.advancePhase(context.matchId(), context.hostId());
        Long guestCenterCardInstanceId = playOpeningCenter(context.matchId(), context.guestId());
        matchActionService.advancePhase(context.matchId(), context.guestId());

        Long liveStartDecisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'LIVE_START'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            context.matchId(),
            context.hostId()
        );
        assertThat(liveStartDecisionId).isNotNull();

        Integer hiddenHolomemsBefore = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND match_card_id IN (?, ?, ?)
              AND is_face_down = TRUE
            """,
            Integer.class,
            context.matchId(),
            hostCenterCardInstanceId,
            hostBackCardInstanceId,
            guestCenterCardInstanceId
        );
        assertThat(hiddenHolomemsBefore).isEqualTo(3);

        ResolveDecisionRequest resolve = new ResolveDecisionRequest();
        resolve.setDecisionId(liveStartDecisionId);
        matchActionService.resolveDecision(context.matchId(), context.hostId(), resolve);

        Integer hiddenHolomemsAfter = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND match_card_id IN (?, ?, ?)
              AND is_face_down = TRUE
            """,
            Integer.class,
            context.matchId(),
            hostCenterCardInstanceId,
            hostBackCardInstanceId,
            guestCenterCardInstanceId
        );
        assertThat(hiddenHolomemsAfter).isZero();

        Integer hiddenStageCardsAfter = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE match_id = ?
              AND id IN (?, ?, ?)
              AND zone = 'STAGE'
              AND is_face_down = TRUE
            """,
            Integer.class,
            context.matchId(),
            hostCenterCardInstanceId,
            hostBackCardInstanceId,
            guestCenterCardInstanceId
        );
        assertThat(hiddenStageCardsAfter).isZero();

        String phaseAfterLiveStart = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            context.matchId()
        );
        assertThat(phaseAfterLiveStart).isEqualTo("MAIN");

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
        advanceToPerformancePhase(matchId, hostId, centerCardInstanceId);

        AttackArtActionRequest attackArt = new AttackArtActionRequest();
        attackArt.setAttackerCardInstanceId(centerCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attackArt);

        assertZoneCount(matchId, guestId, "LIFE", 4);
        String phaseAfterAttack = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(phaseAfterAttack).isEqualTo("PERFORMANCE");

        matchActionService.advancePhase(matchId, hostId);

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

        advanceToEndPhase(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
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

        advanceToEndPhase(matchId, guestId, loadFirstCenterCardInstanceId(matchId, guestId));
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

        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

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

        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
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
        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
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

        advanceToEndPhase(matchId, hostId, activeBackCardInstanceId);
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

        advanceToEndPhase(matchId, hostId, restedBackCardInstanceId);
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

        advanceToEndPhase(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
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

        Integer matchFinishedActions = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'MATCH_FINISHED'
            """,
            Integer.class,
            matchId
        );
        Integer matchFinishedRuleEvents = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND action_type = 'RULE_EVENT'
              AND payload::text LIKE '%"eventType":"MATCH_FINISHED"%'
            """,
            Integer.class,
            matchId
        );
        assertThat(matchFinishedActions).isZero();
        assertThat(matchFinishedRuleEvents).isZero();
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
        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
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
        String phaseAfterResolve = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(phaseAfterResolve).isEqualTo("CHEER");
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
        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
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
        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
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
        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
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
        String phaseAfterSendCheer = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(phaseAfterSendCheer).isEqualTo("MAIN");
    }

    @Test
    void resolveDecisionShouldMoveTurnStartToDrawPhase() {
        StartedMatchContext context = createStartedMatch("turn-start-draw-host", "turn-start-draw-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        String debutCardId = findMemberCardIdByLevel("DEBUT");

        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, debutCardId, "CENTER", "DEBUT", 0);
        createStageHolomemWithSingleCard(matchId, guestId, debutCardId, "CENTER", "DEBUT", 0);
        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 1,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);

        Long decisionId = jdbcTemplate.query(
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
        assertThat(decisionId).isNotNull();

        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setDecisionId(decisionId);
        matchActionService.resolveDecision(matchId, guestId, request);

        String phaseAfterResolve = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(phaseAfterResolve).isEqualTo("DRAW");
    }

    @Test
    void advancePhaseShouldMoveMainToPerformanceAndThenEnd() {
        StartedMatchContext context = createStartedMatch("advance-phase-host", "advance-phase-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            40,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":40}",
            0,
            "RED",
            "TADV_PHASE_HOST_CENTER"
        );
        createStageHolomemWithSingleCard(matchId, guestId, findMemberCardIdByLevel("DEBUT"), "CENTER", "DEBUT", 0);

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

        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.advancePhase(matchId, hostId);
        assertThat(jdbcTemplate.queryForObject("SELECT current_phase FROM matches WHERE id = ?", String.class, matchId))
            .isEqualTo("PERFORMANCE");

        matchActionService.advancePhase(matchId, hostId);
        assertThat(jdbcTemplate.queryForObject("SELECT current_phase FROM matches WHERE id = ?", String.class, matchId))
            .isEqualTo("END");
    }

    @Test
    void advancePhaseShouldSkipPerformanceForFirstPlayerFirstTurn() {
        StartedMatchContext context = createStartedMatch("advance-phase-first-host", "advance-phase-first-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        String debutCardId = findMemberCardIdByLevel("DEBUT");
        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, debutCardId, "CENTER", "DEBUT", 0);
        createStageHolomemWithSingleCard(matchId, context.guestId(), debutCardId, "CENTER", "DEBUT", 0);
        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 1,
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
        matchActionService.advancePhase(matchId, hostId);

        assertThat(jdbcTemplate.queryForObject("SELECT current_phase FROM matches WHERE id = ?", String.class, matchId))
            .isEqualTo("END");
    }

    @Test
    void attackArtShouldRejectMainPhaseAfterTurnActionsComplete() {
        StartedMatchContext context = createStartedMatch("attack-main-reject-host", "attack-main-reject-guest");
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
            0,
            "RED",
            "TATTACK_MAIN_REJECT_HOST"
        );
        Long guestCenterCardInstanceId = createStageHolomemWithSingleCard(
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

        assertThatThrownBy(() -> matchActionService.attackArt(matchId, hostId, request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("表演階段");
    }

    @Test
    void endTurnShouldRejectMainPhaseBeforeAdvanceToEnd() {
        StartedMatchContext context = createStartedMatch("end-main-reject-host", "end-main-reject-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String debutCardId = findMemberCardIdByLevel("DEBUT");
        Long hostCenterCardInstanceId = createStageHolomemWithSingleCard(matchId, hostId, debutCardId, "CENTER", "DEBUT", 0);
        createStageHolomemWithSingleCard(matchId, guestId, debutCardId, "CENTER", "DEBUT", 0);

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);

        assertThat(jdbcTemplate.queryForObject("SELECT current_phase FROM matches WHERE id = ?", String.class, matchId))
            .isEqualTo("MAIN");
        assertThatThrownBy(() -> matchActionService.endTurn(matchId, hostId))
            .isInstanceOfAny(IllegalStateException.class, GameRuleException.class)
            .hasMessageContaining("phase=MAIN");
    }

    @Test
    void attackArtShouldRequireTurnDrawAndCheerBeforeUse() {
        StartedMatchContext context = createStartedMatch("attack-phase-host", "attack-phase-guest");
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
            0,
            "RED",
            "TPHASE_HOST_CENTER"
        );
        Long guestCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "CENTER",
            180,
            "BLUE",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            0,
            "BLUE",
            "TPHASE_GUEST_CENTER"
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
        request.setAttackerCardInstanceId(hostCenterCardInstanceId);
        request.setTargetCardInstanceId(guestCenterCardInstanceId);

        assertThatThrownBy(() -> matchActionService.attackArt(matchId, hostId, request))
            .isInstanceOf(GameRuleException.class)
            .satisfies(ex -> assertThat(((GameRuleException) ex).getCode()).isEqualTo(GameErrorCode.TURN_ACTIONS_INCOMPLETE))
            .hasMessageContaining("抽卡");

        matchActionService.drawTurn(matchId, hostId);
        resolvePendingInteractionIfExists(matchId, hostId, "DRAW_REVEAL");

        assertThatThrownBy(() -> matchActionService.attackArt(matchId, hostId, request))
            .isInstanceOf(GameRuleException.class)
            .satisfies(ex -> assertThat(((GameRuleException) ex).getCode()).isEqualTo(GameErrorCode.TURN_ACTIONS_INCOMPLETE))
            .hasMessageContaining("發送吶喊");

        matchActionService.sendTurnCheer(matchId, hostId);
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
            hostId
        );
        assertThat(sendCheerDecisionId).isNotNull();
        ResolveDecisionRequest resolveSendCheer = new ResolveDecisionRequest();
        resolveSendCheer.setDecisionId(sendCheerDecisionId);
        resolveSendCheer.setSelectedCardInstanceIds(List.of(hostCenterCardInstanceId));
        matchActionService.resolveDecision(matchId, hostId, resolveSendCheer);
        matchActionService.advancePhase(matchId, hostId);

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
        assertThat(guestDamageTaken).isEqualTo(60);
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

        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
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

        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
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
        advanceToEndPhase(matchId, guestId, guestCenterCardInstanceId);
        matchActionService.endTurn(matchId, guestId);
        resolvePendingInteractionIfExists(matchId, hostId, "TURN_START");
        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

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

        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
        matchActionService.endTurn(matchId, hostId);
        resolvePendingInteractionIfExists(matchId, guestId, "TURN_START");
        advanceToEndPhase(matchId, guestId, guestCenterCardInstanceId);
        matchActionService.endTurn(matchId, guestId);
        resolvePendingInteractionIfExists(matchId, hostId, "TURN_START");
        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

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
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

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
              AND action_type = 'RESOLVE_DECISION'
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
                current_phase = 'MAIN',
                current_turn_player_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

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
        int deckBefore = countZone(matchId, hostId, "DECK");
        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(backCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);

        // 連動效果本身是 deferred confirm，不會在 moveStageHolomem 當下直接抽牌。
        // 當前這一步只會先把牌庫頂 1 張送進 holopower。
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
              AND action_type = 'COLLAB'
            ORDER BY id DESC
            LIMIT 1
            """,
            String.class,
            matchId,
            hostId
        );
        assertThat(payload).contains("\"triggerResolutionOrder\"");
        assertThat(payload).contains("\"pendingInteractionDecisionType\": \"TRIGGER_EFFECT_CONFIRM\"");
        assertThat(payload).contains("\"holopowerCardInstanceId\"");
        assertThat(payload).contains("\"step\": \"COLLAB_TRIGGER\"");
        assertThat(payload).contains("\"step\": \"COLLAB_EVENT_HOOK\"");
        assertThat(payload).contains("\"priority\": 100");
        assertThat(payload).contains("\"priority\": 200");
    }

    @Test
    void collabShouldCreateGiftConfirmWhenGiftTriggeredByOwnHolomemCollab() {
        StartedMatchContext context = createStartedMatch("collab-gift-host", "collab-gift-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String giftHolderCardId = createMemberCardDefinition(
            "TCOLLAB_GIFT_HOLDER",
            "連動 Gift 持有者",
            "DEBUT",
            180,
            "GREEN",
            "{\"キーワード\":\"ギフト連動抽牌 \\n[センターポジション限定]自分のホロメンがコラボした時、自分のデッキを1枚引く。\"}"
        );
        Long centerCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            giftHolderCardId,
            matchId,
            hostId,
            centerCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            giftHolderCardId,
            matchId,
            hostId,
            centerCardInstanceId
        );

        String collabCardId = createMemberCardDefinition(
            "TCOLLAB_GIFT_SRC",
            "連動來源",
            "DEBUT",
            120,
            "WHITE"
        );
        Long backCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );
        int deckBefore = countZone(matchId, hostId, "DECK");

        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(backCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);

        // COLLAB 本身會先把牌庫頂 1 張送進 holopower，這不是 Gift 抽牌。
        // 這裡先固定住第一段扣牌，避免之後把 holopower 消耗誤判成 trigger 回歸。
        int deckAfterCollab = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterCollab).isEqualTo(deckBefore - 1);

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
        assertThat(pendingContextText).containsPattern("\"giftTriggers\"");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"COLLAB\"");

        String collabPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'COLLAB'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(collabPayloadText).containsPattern("\"collabGiftEffect\"\\s*:\\s*\\{");
        assertThat(collabPayloadText).containsPattern("\"pendingInteractionDecisionType\"\\s*:\\s*\"TRIGGER_EFFECT_CONFIRM\"");
        assertThat(collabPayloadText).containsPattern("\"holopowerCardInstanceId\"\\s*:\\s*\\d+");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int deckAfterConfirm = countZone(matchId, hostId, "DECK");
        // confirm 後才是 Gift 的抽 1，因此總計會比原始 deck 少 2：
        // 1 張進 holopower，1 張因 Gift 抽到手牌。
        assertThat(deckAfterConfirm).isEqualTo(deckBefore - 2);

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
            hostId
        );
        assertThat(latestGiftPayload).containsPattern("\"triggerType\"\\s*:\\s*\"COLLAB\"");
    }

    @Test
    void collabGiftHbp06026ShouldAttachCheerToCollabWhenHandCountIsAtLeastFive() {
        StartedMatchContext context = createStartedMatch("collab-hbp06026-host", "collab-hbp06026-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String giftHolderCardId = "HBP06-026";
        Long centerCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            giftHolderCardId,
            matchId,
            hostId,
            centerCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'FIRST',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            giftHolderCardId,
            matchId,
            hostId,
            centerCardInstanceId
        );

        String collabCardId = createMemberCardDefinition(
            "THBP06026_SRC",
            "HBP06-026 連動來源",
            "DEBUT",
            120,
            "GREEN"
        );
        Long backCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );
        setExactHandCount(matchId, hostId, 5, "THBP06026_FILLER");

        int cheerDeckBefore = countZone(matchId, hostId, "CHEER_DECK");

        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(backCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);

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
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"COLLAB\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        Long collabHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
              AND zone = 'COLLAB'
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            backCardInstanceId
        );
        assertThat(collabHolomemId).isNotNull();

        int collabCheerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            collabHolomemId
        );
        assertThat(collabCheerCount).isEqualTo(1);
        assertThat(countZone(matchId, hostId, "CHEER_DECK")).isEqualTo(cheerDeckBefore - 1);
    }

    @Test
    void collabGiftHbp06026ShouldSkipWhenHandCountIsBelowFive() {
        StartedMatchContext context = createStartedMatch("collab-hbp06026-skip-host", "collab-hbp06026-skip-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String giftHolderCardId = "HBP06-026";
        Long centerCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            giftHolderCardId,
            matchId,
            hostId,
            centerCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'FIRST',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            giftHolderCardId,
            matchId,
            hostId,
            centerCardInstanceId
        );

        String collabCardId = createMemberCardDefinition(
            "THBP06026_SKIP_SRC",
            "HBP06-026 失敗來源",
            "DEBUT",
            120,
            "GREEN"
        );
        Long backCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );
        setExactHandCount(matchId, hostId, 4, "THBP06026_SKIP_FILLER");

        int cheerDeckBefore = countZone(matchId, hostId, "CHEER_DECK");

        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(backCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);

        Long pendingDecisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId
        );
        assertThat(pendingDecisionId).isNull();

        Long collabHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
              AND zone = 'COLLAB'
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            backCardInstanceId
        );
        assertThat(collabHolomemId).isNotNull();

        int collabCheerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            collabHolomemId
        );
        assertThat(collabCheerCount).isZero();
        assertThat(countZone(matchId, hostId, "CHEER_DECK")).isEqualTo(cheerDeckBefore);
    }

    @Test
    void attackArtShouldTriggerOfficialGiftHbp05035WhenOwnSakuraMikoDowned() {
        StartedMatchContext context = createStartedMatch("gift-hbp05035-host", "gift-hbp05035-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HBP05-035',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HBP05-035',
                current_level = 'SECOND',
                damage_taken = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        createStageHolomemWithSingleCard(
            matchId,
            hostId,
            findMemberCardIdByLevel("DEBUT"),
            "BACK",
            "DEBUT",
            0
        );

        Long searchableSupportCardInstanceId = insertSupportCardIntoDeckTop(
            matchId,
            hostId,
            "THBP05035_SUPPORT",
            "み俺恥",
            false,
            "DRAW",
            "{\"type\":\"DRAW\",\"value\":1}",
            "SELF"
        );

        String attackerCardId = createMemberCardDefinition("THBP05035_ATTACKER", "HBP05-035 測試攻擊者", "DEBUT", 180, "BLUE");
        insertPrimaryArtForMember(
            attackerCardId,
            "HBP05-035 測試傷害 220",
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220,\"rawHeader\":\"測試藝能 220\"}"
        );
        Long attackerCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            attackerCardId,
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
            guestId,
            matchId
        );
        entityManager.clear();

        int hostHandBefore = countZone(matchId, hostId, "HAND");
        int hostDeckBefore = countZone(matchId, hostId, "DECK");

        advanceToPerformancePhase(matchId, guestId, attackerCardInstanceId);

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(hostCenterCardInstanceId);
        matchActionService.attackArt(matchId, guestId, attack);

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
        assertThat(pendingContextText).contains("HBP05-035");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"SELF_DOWNED\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        String executedPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );

        assertThat(loadCardZone(searchableSupportCardInstanceId))
            .describedAs(executedPayloadText)
            .isEqualTo("HAND");
        assertThat(executedPayloadText)
            .describedAs(executedPayloadText)
            .containsPattern("\"searchApplied\"\\s*:\\s*1");
        assertThat(countZone(matchId, hostId, "HAND")).isEqualTo(hostHandBefore + 1);
        assertThat(countZone(matchId, hostId, "DECK")).isEqualTo(hostDeckBefore - 1);
    }

    @Test
    void attackArtShouldNotTriggerOfficialGiftHbp05035WhenDownedHolomemIsNotSakuraMiko() {
        StartedMatchContext context = createStartedMatch("gift-hbp05035-fail-host", "gift-hbp05035-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String nonSakuraCenterCardId = createMemberCardDefinition(
            "THBP05035_FAIL_TARGET",
            "不是櫻巫女的測試 Holomem",
            "DEBUT",
            120,
            "WHITE"
        );
        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            nonSakuraCenterCardId,
            matchId,
            hostId,
            hostCenterCardInstanceId
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
            nonSakuraCenterCardId,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        createStageHolomemWithSingleCard(
            matchId,
            hostId,
            "HBP05-035",
            "COLLAB",
            "SECOND",
            0
        );

        Long searchableSupportCardInstanceId = insertSupportCardIntoDeckTop(
            matchId,
            hostId,
            "THBP05035_FAIL_SUPPORT",
            "み俺恥",
            false,
            "DRAW",
            "{\"type\":\"DRAW\",\"value\":1}",
            "SELF"
        );

        String attackerCardId = createMemberCardDefinition("THBP05035_FAIL_ATTACKER", "HBP05-035 失敗攻擊者", "DEBUT", 180, "BLUE");
        insertPrimaryArtForMember(
            attackerCardId,
            "HBP05-035 失敗傷害 220",
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220,\"rawHeader\":\"測試藝能 220\"}"
        );
        Long attackerCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            attackerCardId,
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
            guestId,
            matchId
        );
        entityManager.clear();

        int hostHandBefore = countZone(matchId, hostId, "HAND");
        int hostDeckBefore = countZone(matchId, hostId, "DECK");

        advanceToPerformancePhase(matchId, guestId, attackerCardInstanceId);

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(hostCenterCardInstanceId);
        matchActionService.attackArt(matchId, guestId, attack);

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
            hostId
        );

        assertThat(pendingCount).isZero();
        assertThat(countZone(matchId, hostId, "HAND")).isEqualTo(hostHandBefore);
        assertThat(countZone(matchId, hostId, "DECK")).isEqualTo(hostDeckBefore);
        assertThat(loadCardZone(searchableSupportCardInstanceId)).isEqualTo("DECK");
    }

    @Test
    void collabTriggerConfirmShouldCreateLookTopDeckFollowupInteraction() {
        StartedMatchContext context = createStartedMatch("collab-followup-host", "collab-followup-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String collabCardId = createMemberCardDefinition(
            "TCOLLAB_FOLLOWUP",
            "連動查看牌庫頂",
            "DEBUT",
            120,
            "WHITE",
            "{\"collabEffect\":{\"effects\":[\"LOOK_TOP_DECK\"],\"rawText\":\"自分のデッキの上から1枚を見る。\"}}"
        );
        Long backCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );

        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(backCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);

        Long triggerDecisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId
        );
        assertThat(triggerDecisionId).isNotNull();

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        Long lookTopDecisionId = jdbcTemplate.query(
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
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId
        );
        assertThat(lookTopDecisionId).isNotNull();

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var lookTop = state.getPendingInteractions().stream()
            .filter(item -> "LOOK_TOP_DECK".equals(item.getInteractionType()))
            .findFirst()
            .orElseThrow();
        assertThat(lookTop.getCards()).hasSize(1);
        assertThat(lookTop.getPlacementOptions()).containsExactly("TOP", "BOTTOM");

        String triggerPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(triggerPayloadText).containsPattern("\"pendingInteractionDecisionType\"\\s*:\\s*\"LOOK_TOP_DECK\"");
        assertThat(triggerPayloadText).containsPattern("\"pendingLookTopDeckDecisionId\"\\s*:\\s*" + lookTopDecisionId);

        ResolveDecisionRequest resolveLookTop = new ResolveDecisionRequest();
        resolveLookTop.setDecisionId(lookTopDecisionId);
        resolveLookTop.setPlacement("BOTTOM");
        matchActionService.resolveDecision(matchId, hostId, resolveLookTop);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM match_pending_decisions WHERE id = ?",
            String.class,
            lookTopDecisionId
        )).isEqualTo("RESOLVED");
    }

    @Test
    void triggerEffectConfirmShouldNotCreateFollowupInteractionWhenSkipped() {
        StartedMatchContext context = createStartedMatch("trigger-skip-followup-host", "trigger-skip-followup-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String collabCardId = createMemberCardDefinition(
            "TCOLLAB_SKIP_FOLLOWUP",
            "連動查看牌庫頂跳過",
            "DEBUT",
            120,
            "WHITE",
            "{\"collabEffect\":{\"effects\":[\"LOOK_TOP_DECK\"],\"rawText\":\"自分のデッキの上から1枚を見る。\"}}"
        );
        Long backCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );

        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(backCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);

        Long triggerDecisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId
        );
        assertThat(triggerDecisionId).isNotNull();

        ResolveDecisionRequest resolve = new ResolveDecisionRequest();
        resolve.setDecisionId(triggerDecisionId);
        resolve.setConfirmed(false);
        matchActionService.resolveDecision(matchId, hostId, resolve);

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
            hostId
        );
        assertThat(lookTopPendingCount).isZero();

        String payloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_SKIPPED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(payloadText).containsPattern("\"confirmed\"\\s*:\\s*false");
        assertThat(payloadText).doesNotContain("pendingInteractionDecisionType");
        assertThat(payloadText).doesNotContain("pendingLookTopDeckDecisionId");
    }

    @Test
    void collabTriggerConfirmShouldCreateDeckBottomReorderFollowupInteraction() {
        StartedMatchContext context = createStartedMatch("collab-reorder-followup-host", "collab-reorder-followup-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String matchCard = createMemberCardDefinition("TCOLLAB_REORDER_MATCH", "重排命中", "DEBUT", 90, "RED");
        String missCardB = createMemberCardDefinition("TCOLLAB_REORDER_B", "重排 B", "DEBUT", 90, "BLUE");
        String missCardC = createMemberCardDefinition("TCOLLAB_REORDER_C", "重排 C", "DEBUT", 90, "GREEN");
        String missCardD = createMemberCardDefinition("TCOLLAB_REORDER_D", "重排 D", "DEBUT", 90, "WHITE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#ORDER_TEST\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            matchCard
        );

        insertCardIntoDeckTop(matchId, hostId, matchCard);
        insertCardIntoDeckTop(matchId, hostId, missCardB);
        insertCardIntoDeckTop(matchId, hostId, missCardC);
        insertCardIntoDeckTop(matchId, hostId, missCardD);

        String collabCardId = createMemberCardDefinition(
            "TCOLLAB_REORDER_SRC",
            "連動排序牌庫底",
            "DEBUT",
            120,
            "WHITE",
            "{\"collabEffect\":{\"effects\":[\"SEARCH\"],\"value\":1,"
                + "\"searchCriteria\":{\"cardType\":\"MEMBER\",\"tag\":\"#ORDER_TEST\"},"
                + "\"rawText\":\"自分のデッキの上から4枚を見る。その中から、#ORDER_TESTを持つホロメンを1枚手札に加える。そして残ったカードを好きな順でデッキの下に戻す。\"}}"
        );
        Long backCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );

        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(backCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        Long reorderDecisionId = jdbcTemplate.query(
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
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId
        );
        assertThat(reorderDecisionId).isNotNull();

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var reorderInteraction = state.getPendingInteractions().stream()
            .filter(item -> "REORDER_DECK_BOTTOM".equals(item.getInteractionType()))
            .findFirst()
            .orElseThrow();
        assertThat(reorderInteraction.getCards()).hasSize(3);

        String triggerPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(triggerPayloadText).containsPattern("\"pendingInteractionDecisionType\"\\s*:\\s*\"REORDER_DECK_BOTTOM\"");

        List<Long> reversed = new java.util.ArrayList<>(reorderInteraction.getCards().stream()
            .map(card -> card.getCardInstanceId())
            .toList());
        java.util.Collections.reverse(reversed);

        ResolveDecisionRequest resolve = new ResolveDecisionRequest();
        resolve.setDecisionId(reorderDecisionId);
        resolve.setSelectedCardInstanceIds(reversed);
        matchActionService.resolveDecision(matchId, hostId, resolve);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM match_pending_decisions WHERE id = ?",
            String.class,
            reorderDecisionId
        )).isEqualTo("RESOLVED");
    }

    @Test
    void collabTriggerConfirmShouldCreateLookOpponentHandFollowupInteraction() {
        StartedMatchContext context = createStartedMatch("collab-look-opponent-hand-host", "collab-look-opponent-hand-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String collabCardId = createMemberCardDefinition(
            "TCOLLAB_LOOK_OPP_HAND",
            "連動查看對手手牌",
            "DEBUT",
            120,
            "BLUE",
            "{\"collabEffect\":{\"effects\":[\"LOOK_OPPONENT_HAND\"],\"rawText\":\"相手の手札を見る。\"}}"
        );
        Long backCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );

        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(backCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        Long decisionId = jdbcTemplate.query(
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
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId
        );
        assertThat(decisionId).isNotNull();

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var interaction = state.getPendingInteractions().stream()
            .filter(item -> "LOOK_OPPONENT_HAND".equals(item.getInteractionType()))
            .findFirst()
            .orElseThrow();
        assertThat(interaction.getCards()).isNotEmpty();

        String triggerPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(triggerPayloadText).containsPattern("\"pendingInteractionDecisionType\"\\s*:\\s*\"LOOK_OPPONENT_HAND\"");

        ResolveDecisionRequest resolve = new ResolveDecisionRequest();
        resolve.setDecisionId(decisionId);
        matchActionService.resolveDecision(matchId, hostId, resolve);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM match_pending_decisions WHERE id = ?",
            String.class,
            decisionId
        )).isEqualTo("RESOLVED");
    }

    @Test
    void collabTriggerConfirmShouldCreateLookHolopowerFollowupInteraction() {
        StartedMatchContext context = createStartedMatch("collab-look-holopower-host", "collab-look-holopower-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        seedHolopower(matchId, hostId, 2);

        String collabCardId = createMemberCardDefinition(
            "TCOLLAB_LOOK_HOLOPOWER",
            "連動查看 Holopower",
            "SECOND",
            180,
            "WHITE",
            "{\"collabEffect\":{\"effects\":[\"LOOK_HOLOPOWER\"],\"rawText\":\"自分のホロパワーを見る。\"}}"
        );
        Long backCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "SECOND",
            0
        );

        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(backCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        Long decisionId = jdbcTemplate.query(
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
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId
        );
        assertThat(decisionId).isNotNull();

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var interaction = state.getPendingInteractions().stream()
            .filter(item -> "LOOK_HOLOPOWER".equals(item.getInteractionType()))
            .findFirst()
            .orElseThrow();
        assertThat(interaction.getCards()).hasSizeGreaterThanOrEqualTo(1);

        String triggerPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(triggerPayloadText).containsPattern("\"pendingInteractionDecisionType\"\\s*:\\s*\"LOOK_HOLOPOWER\"");

        ResolveDecisionRequest resolve = new ResolveDecisionRequest();
        resolve.setDecisionId(decisionId);
        matchActionService.resolveDecision(matchId, hostId, resolve);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM match_pending_decisions WHERE id = ?",
            String.class,
            decisionId
        )).isEqualTo("RESOLVED");
    }

    @Test
    void collabHsd01015ShouldChooseAzkiBranchOnly() {
        StartedMatchContext context = createStartedMatch("collab-hsd01015-azki-host", "collab-hsd01015-azki-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String centerAzkiCardId = createMemberCardDefinition("TCOLLAB_AZKI_CENTER", "AZKi", "DEBUT", 120, "WHITE");
        Long centerCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            centerAzkiCardId,
            "CENTER",
            "DEBUT",
            0
        );
        String collabCardId = createMemberCardDefinition(
            "HSD01-015",
            "分支連動測試",
            "DEBUT",
            120,
            "WHITE",
            "{\"キーワード\":\"コラボエフェクト分岐テスト \\nセンターホロメンが〈AZKi〉なら、このホロメンを含む自分のホロメン1人に自分のエールデッキの上から1枚を送る。センターホロメンが〈ときのそら〉なら、自分のデッキを1枚引く。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );

        Long centerHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            centerCardInstanceId
        );
        assertThat(centerHolomemId).isNotNull();
        int centerCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            centerHolomemId
        );
        int deckBefore = countZone(matchId, hostId, "DECK");

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        assertThat(requestedEffects).containsExactly("ADD_CHEER");

        int centerCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            centerHolomemId
        );
        int deckAfter = countZone(matchId, hostId, "DECK");
        assertThat(centerCheerAfter).isEqualTo(centerCheerBefore + 1);
        assertThat(deckAfter).isEqualTo(deckBefore);
    }

    @Test
    void collabHsd01015ShouldChooseSoraBranchOnly() {
        StartedMatchContext context = createStartedMatch("collab-hsd01015-sora-host", "collab-hsd01015-sora-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String centerSoraCardId = createMemberCardDefinition("TCOLLAB_SORA_CENTER", "ときのそら", "DEBUT", 120, "WHITE");
        Long centerCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            centerSoraCardId,
            "CENTER",
            "DEBUT",
            0
        );
        String collabCardId = createMemberCardDefinition(
            "HSD01-015",
            "分支連動測試",
            "DEBUT",
            120,
            "WHITE",
            "{\"キーワード\":\"コラボエフェクト分岐テスト \\nセンターホロメンが〈AZKi〉なら、このホロメンを含む自分のホロメン1人に自分のエールデッキの上から1枚を送る。センターホロメンが〈ときのそら〉なら、自分のデッキを1枚引く。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );

        Long centerHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            centerCardInstanceId
        );
        assertThat(centerHolomemId).isNotNull();
        int centerCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            centerHolomemId
        );
        int deckBefore = countZone(matchId, hostId, "DECK");

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        assertThat(requestedEffects).containsExactly("DRAW");

        int centerCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            centerHolomemId
        );
        int deckAfter = countZone(matchId, hostId, "DECK");
        assertThat(centerCheerAfter).isEqualTo(centerCheerBefore);
        assertThat(deckAfter).isEqualTo(deckBefore - 1);
    }

    @Test
    void collabHsd13009ShouldTriggerOnlyOnSecondPlayerFirstTurn() {
        StartedMatchContext context = createStartedMatch("collab-hsd13009-host", "collab-hsd13009-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String collabCardId = createMemberCardDefinition(
            "HSD13-009",
            "後攻首回合連動測試",
            "DEBUT",
            120,
            "YELLOW",
            "{\"キーワード\":\"コラボエフェクトイタズラ増殖！ \\n自分が後攻で最初のターンなら、自分のデッキから、#Justiceを持つ1stホロメン1枚を公開し、ステージに出す。そしてデッキをシャッフルする。\"}"
        );
        Long hostCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long guestCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 1,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        Map<String, Object> hostSummary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            hostCardInstanceId
        );
        assertThat(hostSummary.get("hasCollabEffect")).isEqualTo(false);

        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 2,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            guestId,
            matchId
        );
        Map<String, Object> guestSummary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            guestId,
            collabCardId,
            guestCardInstanceId
        );
        assertThat(guestSummary.get("hasCollabEffect")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) guestSummary.get("requestedEffects");
        assertThat(requestedEffects).contains("SUMMON_TO_STAGE");
    }

    @Test
    void collabHsd01009ShouldSendCheerAndMoveSelfToBackWhenDiceIsOne() {
        Mockito.when(diceService.rollD6()).thenReturn(1);
        StartedMatchContext context = createStartedMatch("collab-hsd01009-roll1-host", "collab-hsd01009-roll1-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String backTargetCardId = createMemberCardDefinition("TCOLLAB_HSD01009_BACK", "回收目標 BACK", "DEBUT", 120, "GREEN");
        Long backTargetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            backTargetCardId,
            "BACK",
            "DEBUT",
            0
        );
        String collabCardId = createMemberCardDefinition(
            "HSD01-009",
            "廣がる地図測試",
            "DEBUT",
            120,
            "GREEN",
            "{\"キーワード\":\"コラボエフェクト広がる地図 \\nサイコロを１回振れる：４以下の時、自分のエールデッキの上から１枚を、自分のバックホロメンに送る。１の時、さらに、このホロメンをバックポジションに移動できる。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "COLLAB",
            "DEBUT",
            0
        );

        Long backTargetHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            backTargetCardInstanceId
        );
        assertThat(backTargetHolomemId).isNotNull();
        int backCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            backTargetHolomemId
        );

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        assertThat(summary.get("diceRoll")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        assertThat(requestedEffects).containsExactly("ADD_CHEER", "MOVE_ZONE");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> addCheerEffect = executedEffects.stream()
            .filter(effect -> "ADD_CHEER".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        Map<String, Object> moveZoneEffect = executedEffects.stream()
            .filter(effect -> "MOVE_ZONE".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);

        assertThat(addCheerEffect).isNotNull();
        assertThat(((Number) addCheerEffect.get("attachApplied")).intValue()).isEqualTo(1);
        assertThat(moveZoneEffect).isNotNull();
        assertThat(moveZoneEffect.get("moved")).isEqualTo(true);
        assertThat(moveZoneEffect.get("fromZone")).isEqualTo("COLLAB");
        assertThat(moveZoneEffect.get("toZone")).isEqualTo("BACK");

        int backCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            backTargetHolomemId
        );
        assertThat(backCheerAfter).isEqualTo(backCheerBefore + 1);

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
        assertThat(collabZoneAfter).isEqualTo("BACK");
    }

    @Test
    void collabHsd01009ShouldSkipBothEffectsWhenDiceAboveFour() {
        Mockito.when(diceService.rollD6()).thenReturn(5);
        StartedMatchContext context = createStartedMatch("collab-hsd01009-roll5-host", "collab-hsd01009-roll5-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String backTargetCardId = createMemberCardDefinition("TCOLLAB_HSD01009_BACK_FAIL", "回收目標 BACK", "DEBUT", 120, "GREEN");
        Long backTargetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            backTargetCardId,
            "BACK",
            "DEBUT",
            0
        );
        String collabCardId = createMemberCardDefinition(
            "HSD01-009",
            "廣がる地図測試",
            "DEBUT",
            120,
            "GREEN",
            "{\"キーワード\":\"コラボエフェクト広がる地図 \\nサイコロを１回振れる：４以下の時、自分のエールデッキの上から１枚を、自分のバックホロメンに送る。１の時、さらに、このホロメンをバックポジションに移動できる。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "COLLAB",
            "DEBUT",
            0
        );

        Long backTargetHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            backTargetCardInstanceId
        );
        assertThat(backTargetHolomemId).isNotNull();
        int backCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            backTargetHolomemId
        );

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        assertThat(summary.get("diceRoll")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        assertThat(executedEffects).isNotEmpty();
        assertThat(executedEffects).allSatisfy(effect -> assertThat(effect.get("applied")).isEqualTo(false));

        int backCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            backTargetHolomemId
        );
        assertThat(backCheerAfter).isEqualTo(backCheerBefore);

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
        assertThat(collabZoneAfter).isEqualTo("COLLAB");
    }

    @Test
    void collabHsd10008ShouldSkipDrawWhenOpponentHandHasNoSupport() {
        StartedMatchContext context = createStartedMatch("collab-hsd10008-host", "collab-hsd10008-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String collabCardId = createMemberCardDefinition(
            "HSD10-008",
            "看手牌後抽牌測試",
            "DEBUT",
            120,
            "BLUE",
            "{\"キーワード\":\"コラボエフェクトテスト \\n相手の手札を見る。相手の手札にサポートがあるなら、自分のデッキを1枚引く。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'DECK',
                order_index = COALESCE(order_index, 0) + 1000,
                is_face_down = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            matchId,
            guestId
        );
        moveOneMemberFromDeckToHand(matchId, guestId);

        int deckBefore = countZone(matchId, hostId, "DECK");
        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );
        int deckAfter = countZone(matchId, hostId, "DECK");

        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        assertThat(requestedEffects).contains("LOOK_OPPONENT_HAND");
        assertThat(requestedEffects).doesNotContain("DRAW");
        assertThat(deckAfter).isEqualTo(deckBefore);
    }

    @Test
    void collabHsd10009ShouldUseOpponentHandCountAsLookTopCount() {
        StartedMatchContext context = createStartedMatch("collab-hsd10009-host", "collab-hsd10009-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String collabCardId = createMemberCardDefinition(
            "HSD10-009",
            "按對手手牌看牌庫頂測試",
            "DEBUT",
            120,
            "BLUE",
            "{\"キーワード\":\"コラボエフェクトテスト \\n相手の手札の枚数ぶん、自分のデッキの上からカードを見る。その中から1枚を手札に加える。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'DECK',
                order_index = COALESCE(order_index, 0) + 1000,
                is_face_down = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            matchId,
            guestId
        );
        moveOneMemberFromDeckToHand(matchId, guestId);
        moveOneMemberFromDeckToHand(matchId, guestId);
        moveOneMemberFromDeckToHand(matchId, guestId);

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> searchEffect = executedEffects.stream()
            .filter(effect -> "SEARCH".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        assertThat(searchEffect).isNotNull();
        assertThat(((Number) searchEffect.get("lookTopCount")).intValue()).isEqualTo(3);
    }

    @Test
    void collabHbp01031ShouldTakeOneCardFromHolopowerThenRefillFromDeckTop() {
        StartedMatchContext context = createStartedMatch("collab-hbp01031-host", "collab-hbp01031-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = COALESCE(order_index, 0) + 1000,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HOLOPOWER'
            """,
            matchId,
            hostId
        );

        String collabCardId = createMemberCardDefinition(
            "HBP01-031",
            "希望の庭園測試",
            "SECOND",
            200,
            "WHITE",
            "{\"キーワード\":\"コラボエフェクト希望の庭園 \\n自分のホロパワーを見る。その中から１枚を公開し、手札に加える。そして自分のデッキの上から１枚をホロパワーにする。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "SECOND",
            0
        );

        String holopowerPickCardId = createMemberCardDefinition("TCOLLAB_HBP01031_PICK", "Holopower Pick", "DEBUT", 90, "WHITE");
        String holopowerStayCardId = createMemberCardDefinition("TCOLLAB_HBP01031_STAY", "Holopower Stay", "DEBUT", 90, "WHITE");
        String deckRefillCardId = createMemberCardDefinition("TCOLLAB_HBP01031_REFILL", "Deck Refill", "DEBUT", 90, "WHITE");

        Long holopowerPickInstanceId = insertCardIntoDeckTop(matchId, hostId, holopowerPickCardId);
        Long holopowerStayInstanceId = insertCardIntoDeckTop(matchId, hostId, holopowerStayCardId);
        Long deckRefillInstanceId = insertCardIntoDeckTop(matchId, hostId, deckRefillCardId);
        assertThat(holopowerPickInstanceId).isNotNull();
        assertThat(holopowerStayInstanceId).isNotNull();
        assertThat(deckRefillInstanceId).isNotNull();

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'HOLOPOWER',
                order_index = 1,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            holopowerPickInstanceId,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'HOLOPOWER',
                order_index = 2,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            holopowerStayInstanceId,
            matchId,
            hostId
        );

        int handBefore = countZone(matchId, hostId, "HAND");
        int holopowerBefore = countZone(matchId, hostId, "HOLOPOWER");

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        assertThat(requestedEffects).containsExactly("LOOK_HOLOPOWER", "SEARCH", "MOVE_TO_HOLOPOWER");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> searchEffect = executedEffects.stream()
            .filter(effect -> "SEARCH".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        Map<String, Object> moveToHolopowerEffect = executedEffects.stream()
            .filter(effect -> "MOVE_TO_HOLOPOWER".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);

        assertThat(searchEffect).isNotNull();
        assertThat(searchEffect.get("searchSourceZone")).isEqualTo("HOLOPOWER");
        assertThat(((Number) searchEffect.get("searchApplied")).intValue()).isEqualTo(1);
        assertThat(moveToHolopowerEffect).isNotNull();
        assertThat(((Number) moveToHolopowerEffect.get("moveApplied")).intValue()).isEqualTo(1);

        int handAfter = countZone(matchId, hostId, "HAND");
        int holopowerAfter = countZone(matchId, hostId, "HOLOPOWER");
        assertThat(handAfter).isEqualTo(handBefore + 1);
        assertThat(holopowerAfter).isEqualTo(holopowerBefore);

        String pickedCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ? AND match_id = ? AND owner_user_id = ?",
            String.class,
            holopowerPickInstanceId,
            matchId,
            hostId
        );
        String refillCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ? AND match_id = ? AND owner_user_id = ?",
            String.class,
            deckRefillInstanceId,
            matchId,
            hostId
        );
        assertThat(pickedCardZone).isEqualTo("HAND");
        assertThat(refillCardZone).isEqualTo("HOLOPOWER");
    }

    @Test
    void collabHbp01031ShouldStillMoveDeckTopToHolopowerWhenNoHolopowerCardToPick() {
        StartedMatchContext context = createStartedMatch("collab-hbp01031-empty-host", "collab-hbp01031-empty-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = COALESCE(order_index, 0) + 1000,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HOLOPOWER'
            """,
            matchId,
            hostId
        );

        String collabCardId = createMemberCardDefinition(
            "HBP01-031",
            "希望の庭園測試",
            "SECOND",
            200,
            "WHITE",
            "{\"キーワード\":\"コラボエフェクト希望の庭園 \\n自分のホロパワーを見る。その中から１枚を公開し、手札に加える。そして自分のデッキの上から１枚をホロパワーにする。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "SECOND",
            0
        );

        String deckRefillCardId = createMemberCardDefinition("TCOLLAB_HBP01031_REFILL_ONLY", "Deck Refill Only", "DEBUT", 90, "WHITE");
        Long deckRefillInstanceId = insertCardIntoDeckTop(matchId, hostId, deckRefillCardId);
        assertThat(deckRefillInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");
        int holopowerBefore = countZone(matchId, hostId, "HOLOPOWER");

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> searchEffect = executedEffects.stream()
            .filter(effect -> "SEARCH".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        Map<String, Object> moveToHolopowerEffect = executedEffects.stream()
            .filter(effect -> "MOVE_TO_HOLOPOWER".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);

        assertThat(searchEffect).isNotNull();
        assertThat(searchEffect.get("searchSourceZone")).isEqualTo("HOLOPOWER");
        assertThat(((Number) searchEffect.get("searchApplied")).intValue()).isEqualTo(0);
        assertThat(moveToHolopowerEffect).isNotNull();
        assertThat(((Number) moveToHolopowerEffect.get("moveApplied")).intValue()).isEqualTo(1);

        int handAfter = countZone(matchId, hostId, "HAND");
        int holopowerAfter = countZone(matchId, hostId, "HOLOPOWER");
        assertThat(handAfter).isEqualTo(handBefore);
        assertThat(holopowerAfter).isEqualTo(holopowerBefore + 1);

        String refillCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ? AND match_id = ? AND owner_user_id = ?",
            String.class,
            deckRefillInstanceId,
            matchId,
            hostId
        );
        assertThat(refillCardZone).isEqualTo("HOLOPOWER");
    }

    @Test
    void collabHsd04011ShouldTakeFromHolopowerThenSendOneHandCardToHolopower() {
        StartedMatchContext context = createStartedMatch("collab-hsd04011-host", "collab-hsd04011-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = COALESCE(order_index, 0) + 1000,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('HAND','HOLOPOWER')
            """,
            matchId,
            hostId
        );

        String collabCardId = createMemberCardDefinition(
            "HSD04-011",
            "ちょこてんて～測試",
            "SPOT",
            60,
            "COLORLESS",
            "{\"キーワード\":\"コラボエフェクトちょこてんて～ \\n自分のホロパワーを見る。その中から1枚を公開し、手札に加える。そして自分の手札1枚をホロパワーにする。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "SPOT",
            0
        );

        String holopowerPickCardId = createMemberCardDefinition("TCOLLAB_HSD04011_PICK", "Holopower Pick", "DEBUT", 80, "WHITE");
        String handSeedCardId = createMemberCardDefinition("TCOLLAB_HSD04011_HAND", "Hand Seed", "DEBUT", 80, "WHITE");

        Long holopowerPickInstanceId = insertCardIntoDeckTop(matchId, hostId, holopowerPickCardId);
        Long handSeedInstanceId = insertCardIntoHand(matchId, hostId, handSeedCardId);
        assertThat(holopowerPickInstanceId).isNotNull();
        assertThat(handSeedInstanceId).isNotNull();

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'HOLOPOWER',
                order_index = 1,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            holopowerPickInstanceId,
            matchId,
            hostId
        );

        int handBefore = countZone(matchId, hostId, "HAND");
        int holopowerBefore = countZone(matchId, hostId, "HOLOPOWER");

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        assertThat(requestedEffects).containsExactly("LOOK_HOLOPOWER", "SEARCH", "MOVE_TO_HOLOPOWER");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> searchEffect = executedEffects.stream()
            .filter(effect -> "SEARCH".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        Map<String, Object> moveToHolopowerEffect = executedEffects.stream()
            .filter(effect -> "MOVE_TO_HOLOPOWER".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);

        assertThat(searchEffect).isNotNull();
        assertThat(searchEffect.get("searchSourceZone")).isEqualTo("HOLOPOWER");
        assertThat(((Number) searchEffect.get("searchApplied")).intValue()).isEqualTo(1);
        assertThat(moveToHolopowerEffect).isNotNull();
        assertThat(moveToHolopowerEffect.get("sourceZone")).isEqualTo("HAND");
        assertThat(((Number) moveToHolopowerEffect.get("moveApplied")).intValue()).isEqualTo(1);

        int handAfter = countZone(matchId, hostId, "HAND");
        int holopowerAfter = countZone(matchId, hostId, "HOLOPOWER");
        assertThat(handAfter).isEqualTo(handBefore);
        assertThat(holopowerAfter).isEqualTo(holopowerBefore);

        String pickedCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ? AND match_id = ? AND owner_user_id = ?",
            String.class,
            holopowerPickInstanceId,
            matchId,
            hostId
        );
        String handSeedCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ? AND match_id = ? AND owner_user_id = ?",
            String.class,
            handSeedInstanceId,
            matchId,
            hostId
        );
        assertThat(pickedCardZone).isEqualTo("HAND");
        assertThat(handSeedCardZone).isEqualTo("HOLOPOWER");
    }

    @Test
    void collabHsd04011ShouldMoveHandToHolopowerEvenWhenNoHolopowerCardToPick() {
        StartedMatchContext context = createStartedMatch("collab-hsd04011-empty-host", "collab-hsd04011-empty-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = COALESCE(order_index, 0) + 1000,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('HAND','HOLOPOWER')
            """,
            matchId,
            hostId
        );

        String collabCardId = createMemberCardDefinition(
            "HSD04-011",
            "ちょこてんて～測試",
            "SPOT",
            60,
            "COLORLESS",
            "{\"キーワード\":\"コラボエフェクトちょこてんて～ \\n自分のホロパワーを見る。その中から1枚を公開し、手札に加える。そして自分の手札1枚をホロパワーにする。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "SPOT",
            0
        );

        String handSeedCardId = createMemberCardDefinition("TCOLLAB_HSD04011_HAND_ONLY", "Hand Seed", "DEBUT", 80, "WHITE");
        Long handSeedInstanceId = insertCardIntoHand(matchId, hostId, handSeedCardId);
        assertThat(handSeedInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");
        int holopowerBefore = countZone(matchId, hostId, "HOLOPOWER");

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> searchEffect = executedEffects.stream()
            .filter(effect -> "SEARCH".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        Map<String, Object> moveToHolopowerEffect = executedEffects.stream()
            .filter(effect -> "MOVE_TO_HOLOPOWER".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);

        assertThat(searchEffect).isNotNull();
        assertThat(searchEffect.get("searchSourceZone")).isEqualTo("HOLOPOWER");
        assertThat(((Number) searchEffect.get("searchApplied")).intValue()).isEqualTo(0);
        assertThat(moveToHolopowerEffect).isNotNull();
        assertThat(moveToHolopowerEffect.get("sourceZone")).isEqualTo("HAND");
        assertThat(((Number) moveToHolopowerEffect.get("moveApplied")).intValue()).isEqualTo(1);

        int handAfter = countZone(matchId, hostId, "HAND");
        int holopowerAfter = countZone(matchId, hostId, "HOLOPOWER");
        assertThat(handAfter).isEqualTo(handBefore - 1);
        assertThat(holopowerAfter).isEqualTo(holopowerBefore + 1);

        String handSeedCardZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ? AND match_id = ? AND owner_user_id = ?",
            String.class,
            handSeedInstanceId,
            matchId,
            hostId
        );
        assertThat(handSeedCardZone).isEqualTo("HOLOPOWER");
    }

    @Test
    void collabHbp06078ShouldPayAttachedCheerCostThenSearchOshiSameNameDebut() {
        StartedMatchContext context = createStartedMatch("collab-hbp06078-host", "collab-hbp06078-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String oshiName = jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_players mp
            JOIN cards c ON c.card_id = mp.oshi_card_id
            WHERE mp.match_id = ?
              AND mp.user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("name") : null,
            matchId,
            hostId
        );
        assertThat(oshiName).isNotBlank();

        String collabCardId = createMemberCardDefinition(
            "HBP06-078",
            "地球&テラ測試",
            "DEBUT",
            100,
            "YELLOW",
            "{\"キーワード\":\"コラボエフェクト地球&テラ \\nこのホロメンのエール1枚をアーカイブできる：自分のデッキから、自分の推しホロメンと同じカード名のDebutホロメン1枚を公開し、手札に加える。そしてデッキをシャッフルする。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long collabHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            collabCardInstanceId
        );
        assertThat(collabHolomemId).isNotNull();

        String cheerCardId = "TCOLLAB_HBP06078_CHEER_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId,
            "測試 Cheer 成本"
        );
        jdbcTemplate.update(
            """
            INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
            VALUES (?, 'YELLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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
        Long attachedCheerInstanceId = jdbcTemplate.query(
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
            hostId,
            cheerCardId
        );
        assertThat(attachedCheerInstanceId).isNotNull();
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
            VALUES (?, ?, FALSE)
            """,
            collabHolomemId,
            cheerCardId
        );

        String searchableCardId = createMemberCardDefinition("TCOLLAB_HBP06078_TARGET", oshiName, "DEBUT", 100, "YELLOW");
        Long searchableInstanceId = insertCardIntoDeckTop(matchId, hostId, searchableCardId);
        assertThat(searchableInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        assertThat(requestedEffects).containsExactly("REMOVE_CHEER", "SEARCH");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> removeCheerEffect = executedEffects.stream()
            .filter(effect -> "REMOVE_CHEER".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        Map<String, Object> searchEffect = executedEffects.stream()
            .filter(effect -> "SEARCH".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);

        assertThat(removeCheerEffect).isNotNull();
        assertThat(((Number) removeCheerEffect.get("removeApplied")).intValue()).isEqualTo(1);
        assertThat(searchEffect).isNotNull();
        assertThat(((Number) searchEffect.get("searchApplied")).intValue()).isEqualTo(1);

        int handAfter = countZone(matchId, hostId, "HAND");
        int archiveAfter = countZone(matchId, hostId, "ARCHIVE");
        assertThat(handAfter).isEqualTo(handBefore + 1);
        assertThat(archiveAfter).isEqualTo(archiveBefore + 1);

        String searchableZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ? AND match_id = ? AND owner_user_id = ?",
            String.class,
            searchableInstanceId,
            matchId,
            hostId
        );
        String attachedCheerZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ? AND match_id = ? AND owner_user_id = ?",
            String.class,
            attachedCheerInstanceId,
            matchId,
            hostId
        );
        assertThat(searchableZone).isEqualTo("HAND");
        assertThat(attachedCheerZone).isEqualTo("ARCHIVE");
    }

    @Test
    void collabHbp06078ShouldSkipSearchWhenNoAttachedCheerCost() {
        StartedMatchContext context = createStartedMatch("collab-hbp06078-empty-host", "collab-hbp06078-empty-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String oshiName = jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_players mp
            JOIN cards c ON c.card_id = mp.oshi_card_id
            WHERE mp.match_id = ?
              AND mp.user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("name") : null,
            matchId,
            hostId
        );
        assertThat(oshiName).isNotBlank();

        String collabCardId = createMemberCardDefinition(
            "HBP06-078",
            "地球&テラ測試",
            "DEBUT",
            100,
            "YELLOW",
            "{\"キーワード\":\"コラボエフェクト地球&テラ \\nこのホロメンのエール1枚をアーカイブできる：自分のデッキから、自分の推しホロメンと同じカード名のDebutホロメン1枚を公開し、手札に加える。そしてデッキをシャッフルする。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "DEBUT",
            0
        );

        String searchableCardId = createMemberCardDefinition("TCOLLAB_HBP06078_TARGET_EMPTY", oshiName, "DEBUT", 100, "YELLOW");
        Long searchableInstanceId = insertCardIntoDeckTop(matchId, hostId, searchableCardId);
        assertThat(searchableInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> removeCheerEffect = executedEffects.stream()
            .filter(effect -> "REMOVE_CHEER".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        Map<String, Object> searchEffect = executedEffects.stream()
            .filter(effect -> "SEARCH".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);

        assertThat(removeCheerEffect).isNotNull();
        assertThat(((Number) removeCheerEffect.get("removeApplied")).intValue()).isEqualTo(0);
        assertThat(searchEffect).isNotNull();
        assertThat(searchEffect.get("applied")).isEqualTo(false);
        assertThat((String) searchEffect.get("reason")).contains("未支付此卡附屬エール成本");

        int handAfter = countZone(matchId, hostId, "HAND");
        assertThat(handAfter).isEqualTo(handBefore);

        String searchableZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ? AND match_id = ? AND owner_user_id = ?",
            String.class,
            searchableInstanceId,
            matchId,
            hostId
        );
        assertThat(searchableZone).isEqualTo("DECK");
    }

    @Test
    void collabHsd13015ShouldReturnStageCheerThenAddCheer() {
        StartedMatchContext context = createStartedMatch("collab-hsd13015-ok-host", "collab-hsd13015-ok-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            140,
            "BLUE",
            20,
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":20}",
            1,
            "BLUE",
            "TCOLLAB_HSD13015_CENTER"
        );
        String collabCardId = createMemberCardDefinition(
            "HSD13-015",
            "正義の諧調測試",
            "SPOT",
            160,
            "COLORLESS",
            "{\"キーワード\":\"コラボエフェクト正義の諧調 \\n自分のステージのエール1枚をエールデッキの下に戻せる。戻したなら、自分のエールデッキから、エール1枚を公開し、自分のホロメンに送る。そしてエールデッキをシャッフルする。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "SPOT",
            0
        );

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        assertThat(summary.get("hasCollabEffect")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        assertThat(requestedEffects).contains("RETURN_CHEER_TO_DECK_BOTTOM");
        assertThat(requestedEffects).contains("ADD_CHEER");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> returnedEffect = executedEffects.stream()
            .filter(effect -> "RETURN_CHEER_TO_DECK_BOTTOM".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        Map<String, Object> addCheerEffect = executedEffects.stream()
            .filter(effect -> "ADD_CHEER".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);

        assertThat(returnedEffect).isNotNull();
        assertThat(returnedEffect.get("sourceZone")).isEqualTo("STAGE");
        assertThat(((Number) returnedEffect.get("returnApplied")).intValue()).isEqualTo(1);
        assertThat(addCheerEffect).isNotNull();
        assertThat(((Number) addCheerEffect.get("attachApplied")).intValue()).isEqualTo(1);
    }

    @Test
    void collabHsd13015ShouldNotTriggerWhenNoStageCheerToReturn() {
        StartedMatchContext context = createStartedMatch("collab-hsd13015-empty-host", "collab-hsd13015-empty-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String collabCardId = createMemberCardDefinition(
            "HSD13-015",
            "正義の諧調測試",
            "SPOT",
            160,
            "COLORLESS",
            "{\"キーワード\":\"コラボエフェクト正義の諧調 \\n自分のステージのエール1枚をエールデッキの下に戻せる。戻したなら、自分のエールデッキから、エール1枚を公開し、自分のホロメンに送る。そしてエールデッキをシャッフルする。\"}"
        );
        Long collabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            collabCardId,
            "BACK",
            "SPOT",
            0
        );
        assertThat(collabCardInstanceId).isNotNull();

        Map<String, Object> summary = matchEffectService.applyCollabTriggeredEffects(
            matchId,
            hostId,
            collabCardId,
            collabCardInstanceId
        );

        assertThat(summary.get("hasCollabEffect")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        assertThat(requestedEffects).isEmpty();
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
    void bloomHsd02007ShouldTakeOneFromTopTwoAndArchiveRemainder() {
        StartedMatchContext context = createStartedMatch("bloom-hsd02007-host", "bloom-hsd02007-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "百鬼あやめ";
        String debutCardId = createMemberCardDefinition("THSD02007_DEBUT", displayName, "DEBUT", 100, "RED");
        String bloomCardId = createMemberCardDefinition(
            "HSD02-007",
            displayName,
            "FIRST",
            120,
            "RED",
            "{\"キーワード\":\"ブルームエフェクトどーっちどっち♪ \\nDebutからBloomした時、自分のデッキの上から2枚を見る。その中から、1枚を公開し、手札に加える。そして残ったカードをアーカイブする。\"}"
        );

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, bloomCardId);

        String archiveTargetCardId = createMemberCardDefinition("THSD02007_ARCHIVE", "剩餘送墓目標", "DEBUT", 90, "RED");
        String handTargetCardId = createMemberCardDefinition("THSD02007_HAND", "抽到手牌目標", "DEBUT", 90, "RED");
        Long archiveTargetCardInstanceId = insertCardIntoDeckTop(matchId, hostId, archiveTargetCardId);
        Long handTargetCardInstanceId = insertCardIntoDeckTop(matchId, hostId, handTargetCardId);
        assertThat(archiveTargetCardInstanceId).isNotNull();
        assertThat(handTargetCardInstanceId).isNotNull();

        jdbcTemplate.update(
            """
            UPDATE matches
            SET current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        String handTargetZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            handTargetCardInstanceId
        );
        String archiveTargetZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            archiveTargetCardInstanceId
        );
        assertThat(List.of(handTargetZone, archiveTargetZone))
            .containsExactlyInAnyOrder("HAND", "ARCHIVE");

        String payload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(payload).contains("\"searchApplied\": 1");
        assertThat(payload).contains("\"archiveRemainderApplied\": 1");
    }

    @Test
    void bloomHsd02007ShouldNotArchiveRemainderWhenDeckTopWindowIsEmpty() {
        StartedMatchContext context = createStartedMatch("bloom-hsd02007-empty-host", "bloom-hsd02007-empty-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = COALESCE(order_index, 0) + 1000,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            matchId,
            hostId
        );

        String displayName = "百鬼あやめ";
        String debutCardId = createMemberCardDefinition("THSD02007_EMPTY_DEBUT", displayName, "DEBUT", 100, "RED");
        String bloomCardId = createMemberCardDefinition(
            "HSD02-007",
            displayName,
            "FIRST",
            120,
            "RED",
            "{\"キーワード\":\"ブルームエフェクトどーっちどっち♪ \\nDebutからBloomした時、自分のデッキの上から2枚を見る。その中から、1枚を公開し、手札に加える。そして残ったカードをアーカイブする。\"}"
        );

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, bloomCardId);

        jdbcTemplate.update(
            """
            UPDATE matches
            SET current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        String payload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(payload).contains("\"searchApplied\": 0");
        assertThat(payload).contains("\"archiveRemainderApplied\": 0");
    }

    @Test
    void bloomHsd13011ShouldArchiveStackedDebutAndDamageOpponentCollab() {
        StartedMatchContext context = createStartedMatch("bloom-hsd13011-host", "bloom-hsd13011-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String displayName = "ジジ・ムリン";
        String debutCardId = createMemberCardDefinition("THSD13011_DEBUT", displayName, "DEBUT", 120, "YELLOW");
        String bloomCardId = createMemberCardDefinition(
            "HSD13-011",
            displayName,
            "FIRST",
            140,
            "YELLOW",
            "{\"キーワード\":\"ブルームエフェクトI am Gonathan G. \\nこのホロメンに重なっているDebutホロメン1枚をアーカイブできる：相手のコラボホロメンに特殊ダメージ20を与える。\"}"
        );

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, bloomCardId);
        Long opponentCollabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            createMemberCardDefinition("THSD13011_GUEST_COLLAB", "測試對手 COLLAB", "DEBUT", 120, "BLUE"),
            "COLLAB",
            "DEBUT",
            0
        );

        jdbcTemplate.update(
            """
            UPDATE matches
            SET current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        String stackedDebutZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            targetHolomemCardInstanceId
        );
        Integer collabDamageTaken = jdbcTemplate.queryForObject(
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
            opponentCollabCardInstanceId
        );
        assertThat(stackedDebutZone).isEqualTo("ARCHIVE");
        assertThat(collabDamageTaken).isEqualTo(20);

        Integer archiveApplied = jdbcTemplate.query(
            """
            SELECT CAST(effect ->> 'archiveApplied' AS INTEGER)
            FROM match_actions ma
            CROSS JOIN LATERAL jsonb_array_elements(ma.payload -> 'effect' -> 'executedEffects') effect
            WHERE ma.match_id = ?
              AND ma.user_id = ?
              AND ma.action_type = 'TRIGGER_EFFECT_EXECUTED'
              AND effect ->> 'effectType' = 'ARCHIVE_STACK_CARD'
            ORDER BY ma.id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getInt(1) : null,
            matchId,
            hostId
        );
        Integer damageApplied = jdbcTemplate.query(
            """
            SELECT CAST(effect ->> 'damageApplied' AS INTEGER)
            FROM match_actions ma
            CROSS JOIN LATERAL jsonb_array_elements(ma.payload -> 'effect' -> 'executedEffects') effect
            WHERE ma.match_id = ?
              AND ma.user_id = ?
              AND ma.action_type = 'TRIGGER_EFFECT_EXECUTED'
              AND effect ->> 'effectType' = 'DAMAGE'
            ORDER BY ma.id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getInt(1) : null,
            matchId,
            hostId
        );
        assertThat(archiveApplied).isEqualTo(1);
        assertThat(damageApplied).isEqualTo(20);
    }

    @Test
    void bloomHsd13011ShouldSkipDamageWhenNoStackedDebutCost() {
        StartedMatchContext context = createStartedMatch("bloom-hsd13011-nocost-host", "bloom-hsd13011-nocost-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String displayName = "ジジ・ムリン";
        String debutCardId = createMemberCardDefinition("THSD13011_NC_DEBUT", displayName, "DEBUT", 120, "YELLOW");
        String bloomCardId = createMemberCardDefinition(
            "HSD13-011",
            displayName,
            "FIRST",
            140,
            "YELLOW",
            "{\"キーワード\":\"ブルームエフェクトI am Gonathan G. \\nこのホロメンに重なっているDebutホロメン1枚をアーカイブできる：相手のコラボホロメンに特殊ダメージ20を与える。\"}"
        );

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, bloomCardId);
        Long opponentCollabCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            createMemberCardDefinition("THSD13011_NC_GUEST_COLLAB", "測試對手 COLLAB", "DEBUT", 120, "BLUE"),
            "COLLAB",
            "DEBUT",
            0
        );

        jdbcTemplate.update(
            """
            UPDATE matches
            SET current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);

        Long hostCenterHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId
        );
        assertThat(hostCenterHolomemId).isNotNull();
        jdbcTemplate.update(
            """
            DELETE FROM match_holomem_stack_cards
            WHERE match_holomem_id = ?
              AND match_card_id = ?
            """,
            hostCenterHolomemId,
            targetHolomemCardInstanceId
        );

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        String stackedDebutZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            targetHolomemCardInstanceId
        );
        Integer collabDamageTaken = jdbcTemplate.queryForObject(
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
            opponentCollabCardInstanceId
        );
        assertThat(stackedDebutZone).isEqualTo("STAGE");
        assertThat(collabDamageTaken).isEqualTo(0);

        Integer archiveApplied = jdbcTemplate.query(
            """
            SELECT CAST(effect ->> 'archiveApplied' AS INTEGER)
            FROM match_actions ma
            CROSS JOIN LATERAL jsonb_array_elements(ma.payload -> 'effect' -> 'executedEffects') effect
            WHERE ma.match_id = ?
              AND ma.user_id = ?
              AND ma.action_type = 'TRIGGER_EFFECT_EXECUTED'
              AND effect ->> 'effectType' = 'ARCHIVE_STACK_CARD'
            ORDER BY ma.id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getInt(1) : null,
            matchId,
            hostId
        );
        String damageSkipReason = jdbcTemplate.query(
            """
            SELECT effect ->> 'reason'
            FROM match_actions ma
            CROSS JOIN LATERAL jsonb_array_elements(ma.payload -> 'effect' -> 'executedEffects') effect
            WHERE ma.match_id = ?
              AND ma.user_id = ?
              AND ma.action_type = 'TRIGGER_EFFECT_EXECUTED'
              AND effect ->> 'effectType' = 'DAMAGE'
            ORDER BY ma.id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString(1) : null,
            matchId,
            hostId
        );
        assertThat(archiveApplied).isZero();
        assertThat(damageSkipReason).contains("未支付重疊 Debut 成本");
    }

    @Test
    void bloomHbp02016ShouldSearchSanseikiDebutFirstOrSpotWhenBloomedFromDebut() {
        StartedMatchContext context = createStartedMatch("bloom-hbp02016-host", "bloom-hbp02016-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String displayName = "白銀ノエル";
        String debutCardId = createMemberCardDefinition("THBP02016_DEBUT", displayName, "DEBUT", 110, "WHITE");
        String bloomCardId = createMemberCardDefinition(
            "HBP02-016",
            displayName,
            "FIRST",
            130,
            "WHITE",
            "{\"キーワード\":\"ブルームエフェクトノエちゃんの勇姿…… \\nDebutからBloomした時、自分のデッキから、#3期生を持つ[Debutホロメンか1stホロメンかSpotホロメン]1枚を公開し、手札に加える。そしてデッキをシャッフルする。\"}"
        );

        Long targetHolomemCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, bloomCardId);

        String validDebutCardId = createMemberCardDefinition("THBP02016_TARGET_D", "三期生 Debut", "DEBUT", 90, "WHITE");
        String validFirstCardId = createMemberCardDefinition("THBP02016_TARGET_F", "三期生 1st", "FIRST", 120, "WHITE");
        String validSpotCardId = createMemberCardDefinition("THBP02016_TARGET_S", "三期生 Spot", "SPOT", 70, "WHITE");
        String invalidSecondCardId = createMemberCardDefinition("THBP02016_TARGET_X", "三期生 2nd", "SECOND", 160, "WHITE");

        jdbcTemplate.update(
            "UPDATE cards SET tags_json = CAST(? AS jsonb) WHERE card_id IN (?, ?, ?, ?)",
            "[\"#JP\", \"#3期生\"]",
            validDebutCardId,
            validFirstCardId,
            validSpotCardId,
            invalidSecondCardId
        );

        Long validDebutInstanceId = insertCardIntoDeckTop(matchId, hostId, validDebutCardId);
        Long validFirstInstanceId = insertCardIntoDeckTop(matchId, hostId, validFirstCardId);
        Long validSpotInstanceId = insertCardIntoDeckTop(matchId, hostId, validSpotCardId);
        Long invalidSecondInstanceId = insertCardIntoDeckTop(matchId, hostId, invalidSecondCardId);
        assertThat(validDebutInstanceId).isNotNull();
        assertThat(validFirstInstanceId).isNotNull();
        assertThat(validSpotInstanceId).isNotNull();
        assertThat(invalidSecondInstanceId).isNotNull();

        jdbcTemplate.update(
            """
            UPDATE matches
            SET current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            hostId,
            matchId
        );
        entityManager.clear();

        BloomActionRequest request = new BloomActionRequest();
        request.setBloomCardInstanceId(bloomCardInstanceId);
        request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
        matchActionService.bloom(matchId, hostId, request);
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        List<Map<String, Object>> handRows = jdbcTemplate.queryForList(
            """
            SELECT id, card_id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            matchId,
            hostId
        );
        List<Long> searchedToHandIds = handRows.stream()
            .map(row -> ((Number) row.get("id")).longValue())
            .filter(id -> List.of(validDebutInstanceId, validFirstInstanceId, validSpotInstanceId).contains(id))
            .toList();

        assertThat(searchedToHandIds).hasSize(1);

        String invalidSecondZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            invalidSecondInstanceId
        );
        assertThat(invalidSecondZone).isEqualTo("DECK");

        String payload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(payload).contains("\"searchApplied\": 1");
        assertThat(payload).contains("\"#3期生\"");
        assertThat(payload).contains("\"SPOT\"");
    }

    @Test
    void bloomHbp02016ShouldSkipSearchWhenSourceLevelIsNotDebut() {
        StartedMatchContext context = createStartedMatch("bloom-hbp02016-skip-host", "bloom-hbp02016-skip-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String bloomCardId = createMemberCardDefinition(
            "HBP02-016",
            "白銀ノエル",
            "FIRST",
            130,
            "WHITE",
            "{\"キーワード\":\"ブルームエフェクトノエちゃんの勇姿…… \\nDebutからBloomした時、自分のデッキから、#3期生を持つ[Debutホロメンか1stホロメンかSpotホロメン]1枚を公開し、手札に加える。そしてデッキをシャッフルする。\"}"
        );
        String validTargetCardId = createMemberCardDefinition("THBP02016_SKIP_TARGET", "三期生 Debut", "DEBUT", 90, "WHITE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = CAST(? AS jsonb) WHERE card_id = ?",
            "[\"#JP\", \"#3期生\"]",
            validTargetCardId
        );
        Long validTargetInstanceId = insertCardIntoDeckTop(matchId, hostId, validTargetCardId);
        assertThat(validTargetInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");
        Map<String, Object> summary = matchEffectService.applyBloomTriggeredEffects(
            matchId,
            hostId,
            bloomCardId,
            null,
            "FIRST"
        );

        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");

        assertThat(summary.get("hasBloomEffect")).isEqualTo(false);
        assertThat(requestedEffects).isEmpty();
        assertThat(executedEffects).isEmpty();
        assertThat(countZone(matchId, hostId, "HAND")).isEqualTo(handBefore);

        String validTargetZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            validTargetInstanceId
        );
        assertThat(validTargetZone).isEqualTo("DECK");
    }

    @Test
    void bloomHbp06081ShouldRequireSubaruOshiAndArchiveStageCheerBeforeSearch() {
        StartedMatchContext context = createStartedMatch("bloom-hbp06081-host", "bloom-hbp06081-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        jdbcTemplate.update(
            """
            UPDATE match_players
            SET oshi_card_id = ?
            WHERE match_id = ?
              AND user_id = ?
            """,
            "HBP04-006",
            matchId,
            hostId
        );

        String bloomCardId = createMemberCardDefinition(
            "HBP06-081",
            "大空スバル",
            "SECOND",
            200,
            "YELLOW",
            "{\"キーワード\":\"ブルームエフェクト出動！ ドタバタ大空警察!! \\n自分の推しホロメンが〈大空スバル〉なら、自分のステージのエール1枚をアーカイブできる：自分のデッキから、〈大空スバル〉1枚を公開し、手札に加える。そしてデッキをシャッフルする。\"}"
        );
        Long bloomCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            bloomCardId,
            "CENTER",
            "SECOND",
            0
        );
        Long sourceHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            bloomCardInstanceId
        );
        assertThat(sourceHolomemId).isNotNull();
        clearAttachedStageCheers(matchId, hostId);

        String cheerCardId = "TBLOOM06081_CHEER_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId,
            "測試 Stage Cheer"
        );
        jdbcTemplate.update(
            """
            INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
            VALUES (?, 'YELLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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
        Long attachedCheerInstanceId = jdbcTemplate.query(
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
            hostId,
            cheerCardId
        );
        assertThat(attachedCheerInstanceId).isNotNull();
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
            VALUES (?, ?, ?, FALSE)
            """,
            sourceHolomemId,
            attachedCheerInstanceId,
            cheerCardId
        );

        String searchableCardId = createMemberCardDefinition("TBLOOM06081_TARGET", "大空スバル", "DEBUT", 100, "YELLOW");
        Long searchableInstanceId = insertCardIntoDeckTop(matchId, hostId, searchableCardId);
        assertThat(searchableInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");

        Map<String, Object> summary = matchEffectService.applyBloomTriggeredEffects(
            matchId,
            hostId,
            bloomCardId,
            bloomCardInstanceId,
            "FIRST"
        );

        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> removeCheerEffect = executedEffects.stream()
            .filter(effect -> "REMOVE_STAGE_CHEER".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        Map<String, Object> searchEffect = executedEffects.stream()
            .filter(effect -> "SEARCH".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);

        assertThat(requestedEffects).containsExactly("REMOVE_STAGE_CHEER", "SEARCH");
        assertThat(removeCheerEffect).isNotNull();
        assertThat(((Number) removeCheerEffect.get("removeApplied")).intValue()).isEqualTo(1);
        assertThat(searchEffect).isNotNull();
        assertThat(((Number) searchEffect.get("searchApplied")).intValue()).isEqualTo(1);
        assertThat(countZone(matchId, hostId, "HAND")).isEqualTo(handBefore + 1);
        assertThat(countZone(matchId, hostId, "ARCHIVE")).isEqualTo(archiveBefore + 1);

        String searchableZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            searchableInstanceId
        );
        String attachedCheerZone = jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            attachedCheerInstanceId
        );
        Integer remainingAttachedCheer = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            sourceHolomemId
        );
        assertThat(searchableZone).isEqualTo("HAND");
        assertThat(attachedCheerZone).isEqualTo("ARCHIVE");
        assertThat(remainingAttachedCheer).isZero();
    }

    @Test
    void bloomHbp06081ShouldSkipWhenOshiIsNotSubaru() {
        StartedMatchContext context = createStartedMatch("bloom-hbp06081-noshi-host", "bloom-hbp06081-noshi-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String bloomCardId = createMemberCardDefinition(
            "HBP06-081",
            "大空スバル",
            "SECOND",
            200,
            "YELLOW",
            "{\"キーワード\":\"ブルームエフェクト出動！ ドタバタ大空警察!! \\n自分の推しホロメンが〈大空スバル〉なら、自分のステージのエール1枚をアーカイブできる：自分のデッキから、〈大空スバル〉1枚を公開し、手札に加える。そしてデッキをシャッフルする。\"}"
        );
        Long bloomCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            bloomCardId,
            "CENTER",
            "SECOND",
            0
        );
        String searchableCardId = createMemberCardDefinition("TBLOOM06081_NOSHI_TARGET", "大空スバル", "DEBUT", 100, "YELLOW");
        Long searchableInstanceId = insertCardIntoDeckTop(matchId, hostId, searchableCardId);
        assertThat(searchableInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");
        Map<String, Object> summary = matchEffectService.applyBloomTriggeredEffects(
            matchId,
            hostId,
            bloomCardId,
            bloomCardInstanceId,
            "FIRST"
        );

        assertThat(summary.get("hasBloomEffect")).isEqualTo(false);
        assertThat(((List<?>) summary.get("requestedEffects"))).isEmpty();
        assertThat(((List<?>) summary.get("executedEffects"))).isEmpty();
        assertThat(countZone(matchId, hostId, "HAND")).isEqualTo(handBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT zone FROM match_cards WHERE id = ?", String.class, searchableInstanceId))
            .isEqualTo("DECK");
    }

    @Test
    void bloomHbp06081ShouldSkipWhenNoStageCheerAvailable() {
        StartedMatchContext context = createStartedMatch("bloom-hbp06081-nocheer-host", "bloom-hbp06081-nocheer-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        jdbcTemplate.update(
            """
            UPDATE match_players
            SET oshi_card_id = ?
            WHERE match_id = ?
              AND user_id = ?
            """,
            "HBP04-006",
            matchId,
            hostId
        );

        String bloomCardId = createMemberCardDefinition(
            "HBP06-081",
            "大空スバル",
            "SECOND",
            200,
            "YELLOW",
            "{\"キーワード\":\"ブルームエフェクト出動！ ドタバタ大空警察!! \\n自分の推しホロメンが〈大空スバル〉なら、自分のステージのエール1枚をアーカイブできる：自分のデッキから、〈大空スバル〉1枚を公開し、手札に加える。そしてデッキをシャッフルする。\"}"
        );
        Long bloomCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            bloomCardId,
            "CENTER",
            "SECOND",
            0
        );
        clearAttachedStageCheers(matchId, hostId);
        String searchableCardId = createMemberCardDefinition("TBLOOM06081_NOCHEER_TARGET", "大空スバル", "DEBUT", 100, "YELLOW");
        Long searchableInstanceId = insertCardIntoDeckTop(matchId, hostId, searchableCardId);
        assertThat(searchableInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");
        Map<String, Object> summary = matchEffectService.applyBloomTriggeredEffects(
            matchId,
            hostId,
            bloomCardId,
            bloomCardInstanceId,
            "FIRST"
        );

        assertThat(summary.get("hasBloomEffect")).isEqualTo(false);
        assertThat(((List<?>) summary.get("requestedEffects"))).isEmpty();
        assertThat(((List<?>) summary.get("executedEffects"))).isEmpty();
        assertThat(countZone(matchId, hostId, "HAND")).isEqualTo(handBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT zone FROM match_cards WHERE id = ?", String.class, searchableInstanceId))
            .isEqualTo("DECK");
    }

    @Test
    void bloomHsd07007ShouldSwapWithLowHpCollab() {
        StartedMatchContext context = createStartedMatch("bloom-hsd07007-host", "bloom-hsd07007-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String debutCardId = createMemberCardDefinition("TBLOOM_HSD07007_DEBUT", "不知火フレア", "DEBUT", 120, "YELLOW");
        String collabCardId = createMemberCardDefinition("TBLOOM_HSD07007_COLLAB", "測試低血 COLLAB", "DEBUT", 100, "YELLOW");

        Long sourceBackCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, "HSD07-007");
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
            SET damage_taken = 30
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
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        String sourceZoneAfter = jdbcTemplate.queryForObject(
            """
            SELECT zone
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            String.class,
            matchId,
            hostId,
            bloomCardInstanceId
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
    }

    @Test
    void bloomHsd07007ShouldSkipWhenCollabHpIsTooHigh() {
        StartedMatchContext context = createStartedMatch("bloom-hsd07007-skip-host", "bloom-hsd07007-skip-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String debutCardId = createMemberCardDefinition("TBLOOM_HSD07007_SKIP_DEBUT", "不知火フレア", "DEBUT", 120, "YELLOW");
        String collabCardId = createMemberCardDefinition("TBLOOM_HSD07007_SKIP_COLLAB", "測試滿血 COLLAB", "DEBUT", 120, "YELLOW");

        Long sourceBackCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            debutCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long bloomCardInstanceId = insertCardIntoHand(matchId, hostId, "HSD07-007");
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
            SET damage_taken = 20
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
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        String sourceZoneAfter = jdbcTemplate.queryForObject(
            """
            SELECT zone
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            String.class,
            matchId,
            hostId,
            bloomCardInstanceId
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

        assertThat(sourceZoneAfter).isEqualTo("BACK");
        assertThat(collabZoneAfter).isEqualTo("COLLAB");
    }

    @Test
    void bloomHbp04059ShouldDiscardOneHandAndDrawByOddRollCount() {
        Mockito.when(diceService.rollD6()).thenReturn(1, 2, 3);

        StartedMatchContext context = createStartedMatch("bloom-hbp04059-host", "bloom-hbp04059-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        clearHandToArchive(matchId, hostId);

        Long bloomCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            "HBP04-059",
            "CENTER",
            "SECOND",
            0
        );
        String discardCardId = createMemberCardDefinition("TBLOOM_HBP04059_DISCARD", "測試棄牌", "DEBUT", 80, "PURPLE");
        Long discardCardInstanceId = insertCardIntoHand(matchId, hostId, discardCardId);
        assertThat(discardCardInstanceId).isNotNull();

        int handBefore = countZone(matchId, hostId, "HAND");
        int deckBefore = countZone(matchId, hostId, "DECK");
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");

        Map<String, Object> summary = matchEffectService.applyBloomTriggeredEffects(
            matchId,
            hostId,
            "HBP04-059",
            bloomCardInstanceId,
            "FIRST"
        );

        @SuppressWarnings("unchecked")
        List<String> requestedEffects = (List<String>) summary.get("requestedEffects");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executedEffects = (List<Map<String, Object>>) summary.get("executedEffects");
        Map<String, Object> discardEffect = executedEffects.stream()
            .filter(effect -> "DISCARD_HAND".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);
        Map<String, Object> drawEffect = executedEffects.stream()
            .filter(effect -> "DRAW".equals(effect.get("effectType")))
            .findFirst()
            .orElse(null);

        assertThat(requestedEffects).containsExactly("DISCARD_HAND", "DRAW");
        assertThat(summary.get("oddRollCount")).isEqualTo(2);
        assertThat(summary.get("diceRolls")).isEqualTo(List.of(1, 2, 3));
        assertThat(discardEffect).isNotNull();
        assertThat(((Number) discardEffect.get("discardApplied")).intValue()).isEqualTo(1);
        assertThat(drawEffect).isNotNull();
        assertThat(((Number) drawEffect.get("drawApplied")).intValue()).isEqualTo(2);
        assertThat(countZone(matchId, hostId, "HAND")).isEqualTo(handBefore + 1);
        assertThat(countZone(matchId, hostId, "DECK")).isEqualTo(deckBefore - 2);
        assertThat(countZone(matchId, hostId, "ARCHIVE")).isEqualTo(archiveBefore + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT zone FROM match_cards WHERE id = ?", String.class, discardCardInstanceId))
            .isEqualTo("ARCHIVE");
    }

    @Test
    void bloomHbp04059ShouldSkipWhenNoHandCardAvailableForCost() {
        StartedMatchContext context = createStartedMatch("bloom-hbp04059-nohand-host", "bloom-hbp04059-nohand-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        clearHandToArchive(matchId, hostId);

        Long bloomCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            "HBP04-059",
            "CENTER",
            "SECOND",
            0
        );

        Map<String, Object> summary = matchEffectService.applyBloomTriggeredEffects(
            matchId,
            hostId,
            "HBP04-059",
            bloomCardInstanceId,
            "FIRST"
        );

        assertThat(summary.get("hasBloomEffect")).isEqualTo(false);
        assertThat(((List<?>) summary.get("requestedEffects"))).isEmpty();
        assertThat(((List<?>) summary.get("executedEffects"))).isEmpty();
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
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

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

        advanceToPerformancePhase(matchId, hostId, hostCardInstanceId);

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

        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

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
    void attackArtShouldRequireSpecificColorBeforeColorlessCost() {
        StartedMatchContext context = createStartedMatch("attack-cost-color-host", "attack-cost-color-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            50,
            "{\"RED\":1,\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":50}",
            0,
            "RED",
            "TCOST_COLOR_HOST_CENTER"
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
            "TCOST_COLOR_GUEST_CENTER"
        );

        resolvePendingInteractionIfExists(matchId, hostId, "TURN_START");
        matchActionService.drawTurn(matchId, hostId);
        resolvePendingInteractionIfExists(matchId, hostId, "DRAW_REVEAL");

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

        Long hostHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        assertThat(hostHolomemId).isNotNull();
        jdbcTemplate.update("DELETE FROM match_holomem_cheers WHERE match_holomem_id = ?", hostHolomemId);
        jdbcTemplate.update(
            "INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down) VALUES (?, 'HY04-001', FALSE)",
            hostHolomemId
        );
        jdbcTemplate.update(
            "INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down) VALUES (?, 'HY04-001', FALSE)",
            hostHolomemId
        );
        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

        AttackArtActionRequest request = new AttackArtActionRequest();
        request.setAttackerCardInstanceId(hostCenterCardInstanceId);
        request.setTargetCardInstanceId(guestCenterCardInstanceId);
        assertThatThrownBy(() -> matchActionService.attackArt(matchId, hostId, request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("需要 RED Cheer x1");
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

        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

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
    void playSupportDamageShouldRequireTriggerConfirmBeforeApplyingDownEventExtraLifeLoss() {
        StartedMatchContext context = createStartedMatch("support-down-event-host", "support-down-event-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String hostCenterCardId = createMemberCardDefinition("TSUP_DOWN_EVENT_HOST_C", "支援來源中心", "DEBUT", 140, "RED");
        createStageHolomemWithSingleCard(matchId, hostId, hostCenterCardId, "CENTER", "DEBUT", 0);

        String guestCenterCardId = createMemberCardDefinition(
            "TSUP_DOWN_EVENT_GUEST_CENTER",
            "支援擊倒目標",
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
        String guestBackCardId = createMemberCardDefinition("TSUP_DOWN_EVENT_GUEST_BACK", "支援擊倒後排", "DEBUT", 120, "WHITE");
        createStageHolomemWithSingleCard(matchId, guestId, guestBackCardId, "BACK", "DEBUT", 0);

        Long supportCardInstanceId = insertSupportCardIntoHand(
            matchId,
            hostId,
            "TSUP_DOWN_EVENT_" + System.nanoTime(),
            false,
            "DAMAGE",
            "{\"type\":\"DAMAGE\",\"value\":999}",
            "ENEMY"
        );
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
        jdbcTemplate.update(
            """
            DELETE FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
            """,
            matchId,
            hostId
        );
        entityManager.clear();
        int lifeBefore = countZone(matchId, guestId, "LIFE");

        PlaySupportActionRequest request = new PlaySupportActionRequest();
        request.setCardInstanceId(supportCardInstanceId);
        request.setTargetHolomemCardInstanceId(guestCenterCardInstanceId);
        matchActionService.playSupport(matchId, hostId, request);

        int lifeAfterSupport = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfterSupport).isEqualTo(lifeBefore - 1);

        Long pendingDecisionId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = 'TRIGGER_EFFECT_CONFIRM'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId
        );
        assertThat(pendingDecisionId).isNotNull();

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int lifeAfterConfirm = countZone(matchId, guestId, "LIFE");
        assertThat(lifeAfterConfirm).isEqualTo(lifeBefore - 3);

        String playSupportPayload = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'PLAY_SUPPORT'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(playSupportPayload).containsPattern(
            "\"pendingInteractionDecisionType\"\\s*:\\s*\"TRIGGER_EFFECT_CONFIRM\""
        );
        assertThat(playSupportPayload).containsPattern("\"downEvent\"\\s*:\\s*\\{");
        assertThat(playSupportPayload).containsPattern("\"deferred\"\\s*:\\s*true");
        assertThat(playSupportPayload).containsPattern("\"appliedLifeLoss\"\\s*:\\s*0");
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

        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

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

        advanceToEndPhase(matchId, hostId, null);
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

        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

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
        assertThat(phaseAfterCollabAttack).isEqualTo("PERFORMANCE");

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
        first.setSourceHolomemCardInstanceId(backCardAInstanceId);
        first.setTargetCenterHolomemCardInstanceId(centerCardInstanceId);
        matchActionService.batonTouch(matchId, hostId, first);

        BatonTouchActionRequest second = new BatonTouchActionRequest();
        second.setSourceHolomemCardInstanceId(backCardBInstanceId);
        second.setTargetCenterHolomemCardInstanceId(backCardAInstanceId);
        assertThatThrownBy(() -> matchActionService.batonTouch(matchId, hostId, second))
            .isInstanceOf(GameRuleException.class)
            .satisfies(ex -> assertThat(((GameRuleException) ex).getCode()).isEqualTo(GameErrorCode.BATON_TOUCH_ALREADY_USED_THIS_TURN));
    }

    @Test
    void batonTouchShouldSwapBackWithCenterAndPayCostFromBackHolomem() {
        StartedMatchContext context = createStartedMatch("baton-swap-host", "baton-swap-guest");
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
            0,
            "RED",
            "TBATON_SWAP_CENTER"
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
            "TBATON_SWAP_BACK"
        );
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");

        BatonTouchActionRequest batonTouch = new BatonTouchActionRequest();
        batonTouch.setSourceHolomemCardInstanceId(backCardInstanceId);
        batonTouch.setTargetCenterHolomemCardInstanceId(centerCardInstanceId);
        matchActionService.batonTouch(matchId, hostId, batonTouch);

        String backZoneAfter = jdbcTemplate.queryForObject(
            """
            SELECT zone
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            String.class,
            matchId,
            hostId,
            backCardInstanceId
        );
        String centerZoneAfter = jdbcTemplate.queryForObject(
            """
            SELECT zone
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            String.class,
            matchId,
            hostId,
            centerCardInstanceId
        );
        assertThat(backZoneAfter).isEqualTo("CENTER");
        assertThat(centerZoneAfter).isEqualTo("BACK");
        assertThat(countZone(matchId, hostId, "ARCHIVE")).isEqualTo(archiveBefore + 1);

        String payloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'BATON_TOUCH'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : null,
            matchId,
            hostId
        );
        assertThat(payloadText).contains("\"sourceToZone\": \"CENTER\"");
        assertThat(payloadText).contains("\"targetToZone\": \"BACK\"");
        assertThat(payloadText).contains("\"paidTotal\": 1");
    }

    @Test
    void batonTouchShouldCreateGiftConfirmWhenTargetMovedBackTriggersGift() {
        StartedMatchContext context = createStartedMatch("baton-gift-host", "baton-gift-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String targetGiftCardId = createMemberCardDefinition(
            "TBATON_GIFT_TARGET",
            "バトンタッチ Gift 目標",
            "SPOT",
            150,
            "COLORLESS",
            "{\"キーワード\":\"ギフト退到後排抽牌 \\nこのホロメンがバトンタッチしてバックポジションに移動した時、自分のデッキを1枚引く。\"}"
        );
        Long centerCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            targetGiftCardId,
            "CENTER",
            "SPOT",
            0
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
            "TBATON_GIFT_BACK"
        );
        int deckBefore = countZone(matchId, hostId, "DECK");

        BatonTouchActionRequest batonTouch = new BatonTouchActionRequest();
        batonTouch.setSourceHolomemCardInstanceId(backCardInstanceId);
        batonTouch.setTargetCenterHolomemCardInstanceId(centerCardInstanceId);
        matchActionService.batonTouch(matchId, hostId, batonTouch);

        int deckAfterBatonTouch = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterBatonTouch).isEqualTo(deckBefore);

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
        assertThat(pendingContextText).containsPattern("\"giftTriggers\"");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"BATON_TOUCH_BACK\"");

        String payloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'BATON_TOUCH'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(payloadText).containsPattern("\"batonTouchGiftEffect\"\\s*:\\s*\\{");
        assertThat(payloadText).containsPattern("\"pendingInteractionDecisionType\"\\s*:\\s*\"TRIGGER_EFFECT_CONFIRM\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int deckAfterConfirm = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterConfirm).isEqualTo(deckBefore - 1);

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
            hostId
        );
        assertThat(latestGiftPayload).containsPattern("\"triggerType\"\\s*:\\s*\"BATON_TOUCH_BACK\"");
    }

    @Test
    void batonTouchGiftHbp06084ShouldBuffOnlyUniqueHakuiKoyoriArtThisTurn() {
        StartedMatchContext context = createStartedMatch("baton-hbp06084-host", "baton-hbp06084-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long holderCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        Long holderHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, holderCardInstanceId);
        assertThat(holderHolomemId).isNotNull();

        // 這裡直接把既有中心位改成官方卡，是為了把測試焦點鎖在
        // 「BATON_TOUCH_BACK 觸發後是否只加成唯一的〈博衣こより〉」，
        // 而不是讓 setup 被額外的中心位建立流程稀釋。
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HBP06-084',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            holderCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HBP06-084',
                current_level = 'SPOT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            holderCardInstanceId
        );
        jdbcTemplate.update("DELETE FROM match_holomem_cheers WHERE match_holomem_id = ?", holderHolomemId);

        String koyoriCardId = createMemberCardDefinition("THBP06084_TARGET", "博衣こより", "DEBUT", 180, "GREEN");
        insertPrimaryArtForMember(
            koyoriCardId,
            "こより測試藝能 30",
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":30}"
        );
        Long attackerCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            koyoriCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long attackerHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, attackerCardInstanceId);
        assertThat(attackerHolomemId).isNotNull();
        attachDirectTestCheers(matchId, hostId, attackerHolomemId, 2, "WHITE", "hbp06084-target");

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
            createMemberCardDefinition("THBP06084_DEFENDER", "測試受擊中心", "DEBUT", 220, "BLUE"),
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = (
                    SELECT card_id
                    FROM match_cards
                    WHERE id = ?
                ),
                current_level = 'DEBUT',
                damage_taken = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            guestCenterCardInstanceId,
            matchId,
            guestId,
            guestCenterCardInstanceId
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
        entityManager.clear();

        BatonTouchActionRequest batonTouch = new BatonTouchActionRequest();
        batonTouch.setSourceHolomemCardInstanceId(attackerCardInstanceId);
        batonTouch.setTargetCenterHolomemCardInstanceId(holderCardInstanceId);
        matchActionService.batonTouch(matchId, hostId, batonTouch);

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
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"BATON_TOUCH_BACK\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");
        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        String attackPayloadText = jdbcTemplate.queryForObject(
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
        assertThat(attackPayloadText).containsPattern("\"turnArtDamageModifier\"\\s*:\\s*20");
        assertThat(attackPayloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*50");
    }

    @Test
    void batonTouchGiftHbp06084ShouldSkipWhenHakuiKoyoriTargetIsNotUnique() {
        StartedMatchContext context = createStartedMatch("baton-hbp06084-amb-host", "baton-hbp06084-amb-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long holderCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        Long holderHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, holderCardInstanceId);
        assertThat(holderHolomemId).isNotNull();

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HBP06-084',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            holderCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HBP06-084',
                current_level = 'SPOT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            holderCardInstanceId
        );
        jdbcTemplate.update("DELETE FROM match_holomem_cheers WHERE match_holomem_id = ?", holderHolomemId);

        String koyoriCardId = createMemberCardDefinition("THBP06084_AMB_TARGET", "博衣こより", "DEBUT", 180, "GREEN");
        insertPrimaryArtForMember(
            koyoriCardId,
            "こより測試藝能 30",
            "{\"COLORLESS\":1}",
            "{\"type\":\"DAMAGE\",\"value\":30}"
        );
        Long attackerCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            koyoriCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long attackerHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, attackerCardInstanceId);
        assertThat(attackerHolomemId).isNotNull();
        attachDirectTestCheers(matchId, hostId, attackerHolomemId, 2, "WHITE", "hbp06084-amb-main");

        String secondKoyoriCardId = createMemberCardDefinition("THBP06084_AMB_OTHER", "博衣こより", "DEBUT", 150, "GREEN");
        Long extraKoyoriCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            secondKoyoriCardId,
            "BACK",
            "DEBUT",
            0
        );
        assertThat(extraKoyoriCardInstanceId).isNotNull();

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
            createMemberCardDefinition("THBP06084_AMB_DEF", "測試受擊中心", "DEBUT", 220, "BLUE"),
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = (
                    SELECT card_id
                    FROM match_cards
                    WHERE id = ?
                ),
                current_level = 'DEBUT',
                damage_taken = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            guestCenterCardInstanceId,
            matchId,
            guestId,
            guestCenterCardInstanceId
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
        entityManager.clear();

        BatonTouchActionRequest batonTouch = new BatonTouchActionRequest();
        batonTouch.setSourceHolomemCardInstanceId(attackerCardInstanceId);
        batonTouch.setTargetCenterHolomemCardInstanceId(holderCardInstanceId);
        matchActionService.batonTouch(matchId, hostId, batonTouch);

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
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"BATON_TOUCH_BACK\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");
        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(guestCenterCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        String attackPayloadText = jdbcTemplate.queryForObject(
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
        assertThat(attackPayloadText).containsPattern("\"turnArtDamageModifier\"\\s*:\\s*0");
        assertThat(attackPayloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*30");
    }

    @Test
    void batonTouchShouldApplyColorlessModifierBeforeCostValidation() {
        StartedMatchContext context = createStartedMatch("baton-modifier-host", "baton-modifier-guest");
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
            "TBATON_MOD_CENTER"
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
            "TBATON_MOD_BACK"
        );
        Long sourceHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            backCardInstanceId
        );
        assertThat(sourceHolomemId).isNotNull();

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
            ) VALUES (?, ?, ?, 'BATON_TOUCH_COST_MODIFIER', 'BATON_TOUCH_COLORLESS_MODIFIER', 1, 3, CAST(? AS jsonb))
            """,
            matchId,
            hostId,
            hostId,
            "{\"targetHolomemId\":" + sourceHolomemId + "}"
        );

        BatonTouchActionRequest batonTouch = new BatonTouchActionRequest();
        batonTouch.setSourceHolomemCardInstanceId(backCardInstanceId);
        batonTouch.setTargetCenterHolomemCardInstanceId(centerCardInstanceId);
        assertThatThrownBy(() -> matchActionService.batonTouch(matchId, hostId, batonTouch))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("需要無色 Cheer x2");
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
        batonTouch.setSourceHolomemCardInstanceId(backCardInstanceId);
        batonTouch.setTargetCenterHolomemCardInstanceId(centerCardInstanceId);
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

        advanceToEndPhase(matchId, hostId, hostCenterCardInstanceId);
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

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int deckBefore = countZone(matchId, hostId, "DECK");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        int deckAfterFirstAttack = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterFirstAttack).isEqualTo(deckBefore);

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int deckAfterGiftConfirm = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterGiftConfirm).isEqualTo(deckBefore - 1);

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
    void attackArtShouldTriggerGiftWhenOpponentHolomemDowned() {
        StartedMatchContext context = createStartedMatch("gift-down-host", "gift-down-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String giftHolderCardId = createMemberCardDefinition(
            "TGIFT_DOWNED",
            "測試 Gift 擊倒觸發",
            "DEBUT",
            130,
            "GREEN",
            "{\"キーワード\":\"ギフトテスト \\n[ターンに1回]自分のホロメンが相手のホロメンをダウンさせた時、自分のデッキを1枚引く。\"}"
        );
        createStageHolomemWithSingleCard(
            matchId,
            hostId,
            giftHolderCardId,
            "BACK",
            "DEBUT",
            0
        );

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "RED",
            "TGIFT_DOWNED_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int deckBefore = countZone(matchId, hostId, "DECK");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        int deckAfterAttack = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterAttack).isEqualTo(deckBefore);

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
        assertThat(pendingContextText).containsPattern("\"sectionType\"\\s*:\\s*\"GIFT\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int deckAfterGiftConfirm = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterGiftConfirm).isEqualTo(deckBefore - 1);

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
            hostId
        );
        assertThat(latestGiftPayload).containsPattern("\"triggerType\"\\s*:\\s*\"OPPONENT_DOWNED\"");
    }

    @Test
    void attackArtShouldTriggerOfficialGiftHbp05061WhenSelfDownsOpponent() {
        StartedMatchContext context = createStartedMatch("gift-hbp05061-host", "gift-hbp05061-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            200,
            "PURPLE",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            3,
            "PURPLE",
            "THBP05061_ATTACKER"
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            "HBP05-061",
            matchId,
            hostId,
            attackerCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            "HBP05-061",
            matchId,
            hostId,
            attackerCardInstanceId
        );

        Long targetCardInstanceId = createStageHolomemWithSingleCard(
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int hostDeckBefore = countZone(matchId, hostId, "DECK");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
        assertThat(pendingContextText).contains("HBP05-061");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"OPPONENT_DOWNED\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int hostDeckAfterConfirm = countZone(matchId, hostId, "DECK");
        assertThat(hostDeckAfterConfirm).isEqualTo(hostDeckBefore - 2);
    }

    @Test
    void attackArtShouldNotTriggerOfficialGiftHbp05061WhenAnotherAllyDownsOpponent() {
        StartedMatchContext context = createStartedMatch("gift-hbp05061-fail-host", "gift-hbp05061-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        createStageHolomemWithSingleCard(
            matchId,
            hostId,
            "HBP05-061",
            "BACK",
            "SECOND",
            0
        );
        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            200,
            "PURPLE",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "PURPLE",
            "THBP05061_OTHER_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int hostDeckBefore = countZone(matchId, hostId, "DECK");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
            hostId
        );
        assertThat(pendingCount).isZero();
        assertThat(countZone(matchId, hostId, "DECK")).isEqualTo(hostDeckBefore);
    }

    @Test
    void attackArtShouldTriggerOfficialGiftHbp05023UsingArchiveCheerAndTaggedTarget() {
        StartedMatchContext context = createStartedMatch("gift-hbp05023-host", "gift-hbp05023-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long attackerCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            "HBP05-023",
            matchId,
            hostId,
            attackerCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            "HBP05-023",
            matchId,
            hostId,
            attackerCardInstanceId
        );

        // 測試重點是「目標必須看 tag 過濾」，因此暫時把持有者本身的 tag 拿掉，
        // 讓效果不能偷懶貼回自己，只能找到真正符合 #ID1期生 的其他目標。
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#NOT_ID1\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            "HBP05-023"
        );

        String taggedTargetCardId = createMemberCardDefinition(
            "THBP05023_ID1_TARGET",
            "HBP05-023 指定目標",
            "DEBUT",
            100,
            "GREEN"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#ID1期生\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            taggedTargetCardId
        );
        Long taggedTargetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            taggedTargetCardId,
            "BACK",
            "DEBUT",
            0
        );
        Long taggedTargetHolomemId = jdbcTemplate.queryForObject(
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
            taggedTargetCardInstanceId
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
            attackerCardInstanceId
        );

        Long archiveCheerInstanceId = insertCheerCardIntoZone(matchId, hostId, "GREEN", "ARCHIVE");
        Long cheerDeckCheerInstanceId = insertCheerCardIntoZone(matchId, hostId, "GREEN", "CHEER_DECK");
        assertThat(archiveCheerInstanceId).isNotNull();
        assertThat(cheerDeckCheerInstanceId).isNotNull();

        Long targetCardInstanceId = createStageHolomemWithSingleCard(
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
        entityManager.clear();

        attachPrimaryArtCostFromCheerDeck(matchId, hostId, attackerCardInstanceId);
        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");
        int cheerDeckBefore = countZone(matchId, hostId, "CHEER_DECK");
        int targetCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            taggedTargetHolomemId
        );
        int attackerCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            attackerHolomemId
        );

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
        assertThat(pendingContextText).contains("HBP05-023");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"OPPONENT_DOWNED\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int archiveAfter = countZone(matchId, hostId, "ARCHIVE");
        int cheerDeckAfter = countZone(matchId, hostId, "CHEER_DECK");
        int targetCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            taggedTargetHolomemId
        );
        int attackerCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            attackerHolomemId
        );
        assertThat(archiveAfter).isEqualTo(archiveBefore - 1);
        assertThat(cheerDeckAfter).isEqualTo(cheerDeckBefore);
        assertThat(targetCheerAfter).isEqualTo(targetCheerBefore + 1);
        assertThat(attackerCheerAfter).isEqualTo(attackerCheerBefore);
    }

    @Test
    void attackArtShouldNotUseCheerDeckForOfficialGiftHbp05023WhenArchiveHasNoCheer() {
        StartedMatchContext context = createStartedMatch("gift-hbp05023-no-archive-host", "gift-hbp05023-no-archive-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long attackerCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            "HBP05-023",
            matchId,
            hostId,
            attackerCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            "HBP05-023",
            matchId,
            hostId,
            attackerCardInstanceId
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
            attackerCardInstanceId
        );
        insertCheerCardIntoZone(matchId, hostId, "GREEN", "CHEER_DECK");

        Long targetCardInstanceId = createStageHolomemWithSingleCard(
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
        entityManager.clear();

        attachPrimaryArtCostFromCheerDeck(matchId, hostId, attackerCardInstanceId);
        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");
        int cheerDeckBefore = countZone(matchId, hostId, "CHEER_DECK");
        int attackerCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            attackerHolomemId
        );

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int archiveAfter = countZone(matchId, hostId, "ARCHIVE");
        int cheerDeckAfter = countZone(matchId, hostId, "CHEER_DECK");
        int attackerCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            attackerHolomemId
        );
        assertThat(archiveAfter).isEqualTo(archiveBefore);
        assertThat(cheerDeckAfter).isEqualTo(cheerDeckBefore);
        assertThat(attackerCheerAfter).isEqualTo(attackerCheerBefore);
    }

    @Test
    void attackArtShouldApplyOfficialPassiveGiftHsd08004ToTaggedDebutCollabHolomem() {
        StartedMatchContext context = createStartedMatch("passive-hsd08004-host", "passive-hsd08004-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD08-004',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD08-004',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );

        Long collabAttackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "COLLAB",
            160,
            "WHITE",
            30,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":30,\"rawHeader\":\"測試藝能 30\"}",
            0,
            "WHITE",
            "hsd08004-collab"
        );
        String collabAttackerCardId = jdbcTemplate.queryForObject(
            "SELECT card_id FROM match_cards WHERE id = ?",
            String.class,
            collabAttackerCardInstanceId
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#4期生\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            collabAttackerCardId
        );

        Long guestCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        Long guestCenterHolomemId = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
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
        entityManager.clear();
        advanceToPerformancePhase(matchId, hostId, collabAttackerCardInstanceId);
        String phaseAfterSetup = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        if (!"PERFORMANCE".equals(phaseAfterSetup)) {
            // 這個案例直接預置了 COLLAB 攻擊者，與一般「從 MAIN 推進到 PERFORMANCE」路徑不同。
            // 若共用 helper 因測試盤面差異把 phase 推到 END，這裡只把 phase 拉回本案例真正要驗證的
            // `PERFORMANCE`，避免去影響其他既有測試 helper 的語意。
            jdbcTemplate.update(
                """
                UPDATE matches
                SET current_phase = 'PERFORMANCE',
                    current_turn_player_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                hostId,
                matchId
            );
            entityManager.clear();
        }

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(collabAttackerCardInstanceId);
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
        Integer guestDamageTaken = jdbcTemplate.queryForObject(
            "SELECT damage_taken FROM match_holomems WHERE id = ?",
            Integer.class,
            guestCenterHolomemId
        );

        assertThat(payloadText).containsPattern("\"passiveGiftArtBonus\"\\s*:\\s*40");
        assertThat(payloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*70");
        assertThat(guestDamageTaken).isEqualTo(70);
    }

    @Test
    void attackArtShouldNotApplyOfficialPassiveGiftHsd08004WhenCollabHolomemIsNotDebut() {
        StartedMatchContext context = createStartedMatch("passive-hsd08004-fail-host", "passive-hsd08004-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD08-004',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD08-004',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );

        Long collabAttackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "COLLAB",
            160,
            "WHITE",
            30,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":30,\"rawHeader\":\"測試藝能 30\"}",
            0,
            "WHITE",
            "hsd08004-collab-fail"
        );
        String collabAttackerCardId = jdbcTemplate.queryForObject(
            "SELECT card_id FROM match_cards WHERE id = ?",
            String.class,
            collabAttackerCardInstanceId
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#4期生\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            collabAttackerCardId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET current_level = 'FIRST',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            collabAttackerCardInstanceId
        );

        Long guestCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        Long guestCenterHolomemId = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
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
        entityManager.clear();
        advanceToPerformancePhase(matchId, hostId, collabAttackerCardInstanceId);
        String phaseAfterSetup = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        if (!"PERFORMANCE".equals(phaseAfterSetup)) {
            // 同上：這裡測的是常駐 Gift 條件失敗，不是 phase helper 本身。
            jdbcTemplate.update(
                """
                UPDATE matches
                SET current_phase = 'PERFORMANCE',
                    current_turn_player_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                hostId,
                matchId
            );
            entityManager.clear();
        }

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(collabAttackerCardInstanceId);
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
        Integer guestDamageTaken = jdbcTemplate.queryForObject(
            "SELECT damage_taken FROM match_holomems WHERE id = ?",
            Integer.class,
            guestCenterHolomemId
        );

        assertThat(payloadText).containsPattern("\"passiveGiftArtBonus\"\\s*:\\s*0");
        assertThat(payloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*30");
        assertThat(guestDamageTaken).isEqualTo(30);
    }

    @Test
    void gameStateShouldApplyOfficialPassiveGiftHsd13007HpBonusPerAttachedCheer() {
        StartedMatchContext context = createStartedMatch("passive-hsd13007-host", "passive-hsd13007-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD13-007',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD13-007',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        Long hostCenterHolomemId = jdbcTemplate.queryForObject(
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
            hostCenterCardInstanceId
        );
        jdbcTemplate.update("DELETE FROM match_holomem_cheers WHERE match_holomem_id = ?", hostCenterHolomemId);
        attachDirectTestCheers(matchId, hostId, hostCenterHolomemId, 2, "RED", "hsd13007-state");

        GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, hostId);
        var playerState = state.getPlayers().stream()
            .filter(player -> hostId.equals(player.getUserId()))
            .findFirst()
            .orElseThrow();
        var centerZone = playerState.getBoardZones().stream()
            .filter(zone -> "CENTER".equals(zone.getZone()))
            .findFirst()
            .orElseThrow();

        assertThat(centerZone.getCards()).hasSize(1);
        assertThat(centerZone.getCards().get(0).getCheerCount()).isEqualTo(2);
        assertThat(centerZone.getCards().get(0).getMaxHp()).isEqualTo(200);
        assertThat(centerZone.getCards().get(0).getCurrentHp()).isEqualTo(200);
    }

    @Test
    void attackArtShouldNotDownOfficialPassiveGiftHsd13007WhenCheerHpBonusKeepsItAlive() {
        StartedMatchContext context = createStartedMatch("passive-hsd13007-survive-host", "passive-hsd13007-survive-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD13-007',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD13-007',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        Long hostCenterHolomemId = jdbcTemplate.queryForObject(
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
            hostCenterCardInstanceId
        );
        jdbcTemplate.update("DELETE FROM match_holomem_cheers WHERE match_holomem_id = ?", hostCenterHolomemId);
        attachDirectTestCheers(matchId, hostId, hostCenterHolomemId, 2, "RED", "hsd13007-survive");

        Long guestOldCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        jdbcTemplate.update(
            """
            DELETE FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestOldCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            guestOldCenterCardInstanceId,
            matchId,
            guestId
        );

        Long guestAttackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "CENTER",
            160,
            "RED",
            190,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":190,\"rawHeader\":\"測試藝能 190\"}",
            0,
            "RED",
            "hsd13007-attacker"
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
            guestId,
            matchId
        );
        entityManager.clear();

        advanceToPerformancePhase(matchId, guestId, guestAttackerCardInstanceId);

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(guestAttackerCardInstanceId);
        attack.setTargetCardInstanceId(hostCenterCardInstanceId);
        matchActionService.attackArt(matchId, guestId, attack);

        Integer hostDamageTaken = jdbcTemplate.queryForObject(
            "SELECT damage_taken FROM match_holomems WHERE id = ?",
            Integer.class,
            hostCenterHolomemId
        );
        Integer hostAliveCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomems WHERE id = ?",
            Integer.class,
            hostCenterHolomemId
        );

        assertThat(hostAliveCount).isEqualTo(1);
        assertThat(hostDamageTaken).isEqualTo(190);
    }

    @Test
    void attackArtShouldApplyOfficialArtBonusAndAttachCheerForHsd13007WhenOpponentIsDowned() {
        StartedMatchContext context = createStartedMatch("art-hsd13007-down-host", "art-hsd13007-down-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD13-007',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD13-007',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        Long hostCenterHolomemId = jdbcTemplate.queryForObject(
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
            hostCenterCardInstanceId
        );
        jdbcTemplate.update("DELETE FROM match_holomem_cheers WHERE match_holomem_id = ?", hostCenterHolomemId);
        attachDirectTestCheers(matchId, hostId, hostCenterHolomemId, 3, "RED", "hsd13007-art-down");

        Long guestOldCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        jdbcTemplate.update(
            """
            DELETE FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestOldCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            guestOldCenterCardInstanceId,
            matchId,
            guestId
        );

        Long guestTargetCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "CENTER",
            150,
            "RED",
            30,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":30,\"rawHeader\":\"測試藝能 30\"}",
            0,
            "RED",
            "hsd13007-down-target"
        );
        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 2,
                current_turn_player_id = ?,
                current_phase = 'PERFORMANCE',
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
        entityManager.clear();

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(hostCenterCardInstanceId);
        attack.setTargetCardInstanceId(guestTargetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        Integer targetAliveCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomems WHERE match_card_id = ?",
            Integer.class,
            guestTargetCardInstanceId
        );
        Integer attachedCheerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            hostCenterHolomemId
        );
        String attackPayloadText = jdbcTemplate.queryForObject(
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

        assertThat(targetAliveCount).isEqualTo(0);
        assertThat(attachedCheerCount).isEqualTo(4);
        assertThat(attackPayloadText).contains("\"artTextDamageBonus\": 60");
        assertThat(attackPayloadText).contains("\"effectType\": \"ADD_CHEER\"");
    }

    @Test
    void attackArtShouldNotAttachCheerForHsd13007WhenOpponentSurvives() {
        StartedMatchContext context = createStartedMatch("art-hsd13007-live-host", "art-hsd13007-live-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD13-007',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD13-007',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        Long hostCenterHolomemId = jdbcTemplate.queryForObject(
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
            hostCenterCardInstanceId
        );
        jdbcTemplate.update("DELETE FROM match_holomem_cheers WHERE match_holomem_id = ?", hostCenterHolomemId);
        attachDirectTestCheers(matchId, hostId, hostCenterHolomemId, 3, "RED", "hsd13007-art-live");

        Long guestOldCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        jdbcTemplate.update(
            """
            DELETE FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestOldCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            guestOldCenterCardInstanceId,
            matchId,
            guestId
        );

        Long guestTargetCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "CENTER",
            170,
            "RED",
            30,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":30,\"rawHeader\":\"測試藝能 30\"}",
            0,
            "RED",
            "hsd13007-live-target"
        );
        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = 2,
                current_turn_player_id = ?,
                current_phase = 'PERFORMANCE',
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
        entityManager.clear();

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(hostCenterCardInstanceId);
        attack.setTargetCardInstanceId(guestTargetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        Integer targetAliveCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomems WHERE match_card_id = ?",
            Integer.class,
            guestTargetCardInstanceId
        );
        Integer targetDamageTaken = jdbcTemplate.queryForObject(
            "SELECT damage_taken FROM match_holomems WHERE match_card_id = ?",
            Integer.class,
            guestTargetCardInstanceId
        );
        Integer attachedCheerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            hostCenterHolomemId
        );

        assertThat(targetAliveCount).isEqualTo(1);
        assertThat(targetDamageTaken).isEqualTo(160);
        assertThat(attachedCheerCount).isEqualTo(3);
    }

    @Test
    void attackArtShouldTriggerOfficialGiftHsd08005WhenAllyDownedAndLifeIsNotHigher() {
        StartedMatchContext context = createStartedMatch("gift-hsd08005-host", "gift-hsd08005-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "RED",
            "THSD08005_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
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
            "HSD08-005",
            "COLLAB",
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

        Long matchedArchiveSupportId = insertSupportCardIntoZone(
            matchId,
            guestId,
            "THSD08005_PC",
            "スゴイパソコン",
            "ARCHIVE",
            false,
            "DRAW",
            "{\"type\":\"DRAW\",\"value\":1}",
            "SELF"
        );
        Long otherArchiveSupportId = insertSupportCardIntoZone(
            matchId,
            guestId,
            "THSD08005_OTHER",
            "普通の道具",
            "ARCHIVE",
            false,
            "DRAW",
            "{\"type\":\"DRAW\",\"value\":1}",
            "SELF"
        );

        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = CASE
                WHEN user_id = ? THEN 3
                WHEN user_id = ? THEN 2
                ELSE current_life
            END,
            updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id IN (?, ?)
            """,
            hostId,
            guestId,
            matchId,
            hostId,
            guestId
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int guestHandBefore = countZone(matchId, guestId, "HAND");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
            guestId
        );
        assertThat(pendingContextText).contains("HSD08-005");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"ALLY_DOWNED\"");

        resolvePendingInteractionIfExists(matchId, guestId, "TRIGGER_EFFECT_CONFIRM");

        assertThat(countZone(matchId, guestId, "HAND")).isEqualTo(guestHandBefore + 1);
        assertThat(loadCardZone(matchedArchiveSupportId)).isEqualTo("HAND");
        assertThat(loadCardZone(otherArchiveSupportId)).isEqualTo("ARCHIVE");
    }

    @Test
    void attackArtShouldNotTriggerOfficialGiftHsd08005WhenOwnerLifeIsHigherThanOpponent() {
        StartedMatchContext context = createStartedMatch("gift-hsd08005-fail-host", "gift-hsd08005-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "RED",
            "THSD08005_FAIL_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
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
            "HSD08-005",
            "COLLAB",
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

        Long archiveSupportId = insertSupportCardIntoZone(
            matchId,
            guestId,
            "THSD08005_FAIL_PC",
            "スゴイパソコン",
            "ARCHIVE",
            false,
            "DRAW",
            "{\"type\":\"DRAW\",\"value\":1}",
            "SELF"
        );

        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = CASE
                WHEN user_id = ? THEN 2
                WHEN user_id = ? THEN 4
                ELSE current_life
            END,
            updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id IN (?, ?)
            """,
            hostId,
            guestId,
            matchId,
            hostId,
            guestId
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int guestHandBefore = countZone(matchId, guestId, "HAND");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
            guestId
        );
        assertThat(pendingCount).isZero();
        assertThat(countZone(matchId, guestId, "HAND")).isEqualTo(guestHandBefore);
        assertThat(loadCardZone(archiveSupportId)).isEqualTo("ARCHIVE");
    }

    @Test
    void attackArtShouldApplyOfficialPassiveGiftHsd07009DamageReductionOnCenter() {
        StartedMatchContext context = createStartedMatch("gift-hsd07009-reduce-host", "gift-hsd07009-reduce-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long guestCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        Long guestCenterHolomemId = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD07-009',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD07-009',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "COLLAB",
            180,
            "RED",
            100,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":100,\"rawHeader\":\"測試藝能 100\"}",
            0,
            "RED",
            "THSD07009_REDUCE_ATTACKER"
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
        entityManager.clear();
        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
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
        Integer damageTaken = jdbcTemplate.queryForObject(
            "SELECT damage_taken FROM match_holomems WHERE id = ?",
            Integer.class,
            guestCenterHolomemId
        );

        assertThat(payloadText).containsPattern("\"passiveGiftIncomingDamageReduction\"\\s*:\\s*10");
        assertThat(payloadText).containsPattern("\"incomingDamageReduction\"\\s*:\\s*10");
        assertThat(payloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*90");
        assertThat(damageTaken).isEqualTo(90);
    }

    @Test
    void attackArtShouldNotApplyOfficialPassiveGiftHsd07009DamageReductionOutsideCenter() {
        StartedMatchContext context = createStartedMatch("gift-hsd07009-reduce-fail-host", "gift-hsd07009-reduce-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long collabTargetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            "HSD07-009",
            "COLLAB",
            "SECOND",
            0
        );
        Long collabTargetHolomemId = jdbcTemplate.queryForObject(
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
            collabTargetCardInstanceId
        );
        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "COLLAB",
            180,
            "RED",
            100,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":100,\"rawHeader\":\"測試藝能 100\"}",
            0,
            "RED",
            "T07009_RED_FAIL"
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
        entityManager.clear();
        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(collabTargetCardInstanceId);
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
        Integer damageTaken = jdbcTemplate.queryForObject(
            "SELECT damage_taken FROM match_holomems WHERE id = ?",
            Integer.class,
            collabTargetHolomemId
        );

        assertThat(payloadText).containsPattern("\"passiveGiftIncomingDamageReduction\"\\s*:\\s*0");
        assertThat(payloadText).containsPattern("\"incomingDamageReduction\"\\s*:\\s*0");
        assertThat(payloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*100");
        assertThat(damageTaken).isEqualTo(100);
    }

    @Test
    void attackArtShouldApplyOfficialPassiveGiftHbp06009DamageReductionToOwnCollab() {
        StartedMatchContext context = createStartedMatch("gift-hbp06009-collab-host", "gift-hbp06009-collab-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long guestCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HBP06-009',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HBP06-009',
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );

        // 這裡刻意把真正受擊者放在 COLLAB，而不是 holder 自己。
        // 目的是驗證 `HBP06-009` 的保護對象是「自己的 COLLAB Holomem」，
        // 而不是沿用 `HSD07-009` 那種「這張卡自己受傷 -10」的舊邏輯。
        Long guestCollabCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            guestId,
            "COLLAB",
            200,
            "WHITE",
            30,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":30,\"rawHeader\":\"測試藝能 30\"}",
            0,
            "WHITE",
            "T609_TGT"
        );
        Long guestCollabHolomemId = loadHolomemIdByCardInstanceId(matchId, guestId, guestCollabCardInstanceId);

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "COLLAB",
            180,
            "RED",
            100,
            "{}", 
            "{\"type\":\"DAMAGE\",\"target\":\"ANY_HOLOMEM\",\"value\":100,\"rawHeader\":\"測試藝能 100\"}",
            0,
            "RED",
            "T609_OK"
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
        entityManager.clear();
        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(guestCollabCardInstanceId);
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
        Integer damageTaken = jdbcTemplate.queryForObject(
            "SELECT damage_taken FROM match_holomems WHERE id = ?",
            Integer.class,
            guestCollabHolomemId
        );

        assertThat(payloadText).containsPattern("\"passiveGiftIncomingDamageReduction\"\\s*:\\s*10");
        assertThat(payloadText).containsPattern("\"incomingDamageReduction\"\\s*:\\s*10");
        assertThat(payloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*90");
        assertThat(damageTaken).isEqualTo(90);
    }

    @Test
    void attackArtShouldNotApplyOfficialPassiveGiftHbp06009DamageReductionToCenterHolderItself() {
        StartedMatchContext context = createStartedMatch("gift-hbp06009-center-fail-host", "gift-hbp06009-center-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long guestCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        Long guestCenterHolomemId = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HBP06-009',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HBP06-009',
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "COLLAB",
            180,
            "RED",
            100,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":100,\"rawHeader\":\"測試藝能 100\"}",
            0,
            "RED",
            "T609_NG"
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
        entityManager.clear();
        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
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
        Integer damageTaken = jdbcTemplate.queryForObject(
            "SELECT damage_taken FROM match_holomems WHERE id = ?",
            Integer.class,
            guestCenterHolomemId
        );

        assertThat(payloadText).containsPattern("\"passiveGiftIncomingDamageReduction\"\\s*:\\s*0");
        assertThat(payloadText).containsPattern("\"incomingDamageReduction\"\\s*:\\s*0");
        assertThat(payloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*100");
        assertThat(damageTaken).isEqualTo(100);
    }

    @Test
    void attackArtShouldApplyOfficialArtBonusHsd07009WhenLifeIsThreeOrLess() {
        StartedMatchContext context = createStartedMatch("art-hsd07009-host", "art-hsd07009-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        Long hostCenterHolomemId = jdbcTemplate.queryForObject(
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
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD07-009',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD07-009',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update("DELETE FROM match_holomem_cheers WHERE match_holomem_id = ?", hostCenterHolomemId);
        attachDirectTestCheers(matchId, hostId, hostCenterHolomemId, 3, "YELLOW", "hsd07009-art");

        Long guestCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        Long guestCenterHolomemId = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD03-008',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD03-008',
                current_level = 'FIRST',
                damage_taken = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
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
        entityManager.clear();
        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

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
        Integer damageTaken = jdbcTemplate.queryForObject(
            "SELECT damage_taken FROM match_holomems WHERE id = ?",
            Integer.class,
            guestCenterHolomemId
        );

        assertThat(payloadText).containsPattern("\"artTextDamageBonus\"\\s*:\\s*70");
        assertThat(payloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*140");
        assertThat(damageTaken).isEqualTo(140);
    }

    @Test
    void attackArtShouldNotApplyOfficialArtBonusHsd07009WhenLifeIsAboveThree() {
        StartedMatchContext context = createStartedMatch("art-hsd07009-fail-host", "art-hsd07009-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        Long hostCenterHolomemId = jdbcTemplate.queryForObject(
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
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD07-009',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD07-009',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update("DELETE FROM match_holomem_cheers WHERE match_holomem_id = ?", hostCenterHolomemId);
        attachDirectTestCheers(matchId, hostId, hostCenterHolomemId, 3, "YELLOW", "hsd07009-art-fail");

        Long guestCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        Long guestCenterHolomemId = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD03-008',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD03-008',
                current_level = 'FIRST',
                damage_taken = 0,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            guestId,
            guestCenterCardInstanceId
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
        entityManager.clear();
        advanceToPerformancePhase(matchId, hostId, hostCenterCardInstanceId);

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
        Integer damageTaken = jdbcTemplate.queryForObject(
            "SELECT damage_taken FROM match_holomems WHERE id = ?",
            Integer.class,
            guestCenterHolomemId
        );

        assertThat(payloadText).containsPattern("\"artTextDamageBonus\"\\s*:\\s*0");
        assertThat(payloadText).containsPattern("\"artTotalDamage\"\\s*:\\s*70");
        assertThat(damageTaken).isEqualTo(70);
    }

    @Test
    void advancePhaseShouldTriggerOfficialGiftHsd11006UsingMatchingHandMemberAndYellowArchiveCheer() {
        StartedMatchContext context = createStartedMatch("gift-hsd11006-host", "gift-hsd11006-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long holderCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        assertThat(holderCardInstanceId).isNotNull();

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD11-006',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            holderCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD11-006',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            holderCardInstanceId
        );
        Long holderHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            holderCardInstanceId
        );
        assertThat(holderHolomemId).isNotNull();

        clearHandToArchive(matchId, hostId);

        String nonMatchingHandCardId = createMemberCardDefinition(
            "THSD11006_NONMATCH",
            "不符合條件的手牌",
            "DEBUT",
            90,
            "WHITE"
        );
        String matchingHandCardId = createMemberCardDefinition(
            "THSD11006_MATCH",
            "符合條件的 FLOW GLOW 手牌",
            "DEBUT",
            100,
            "YELLOW"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#FLOW GLOW\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            matchingHandCardId
        );

        // 先插入不符合條件的手牌，驗證效果不是單純拿手牌第一張當成本。
        Long nonMatchingHandInstanceId = insertCardIntoHand(matchId, hostId, nonMatchingHandCardId);
        Long matchingHandInstanceId = insertCardIntoHand(matchId, hostId, matchingHandCardId);
        assertThat(nonMatchingHandInstanceId).isNotNull();
        assertThat(matchingHandInstanceId).isNotNull();

        // Archive 也先放錯色 Cheer，確認效果會真的挑到 `黄エール`。
        Long redArchiveCheerInstanceId = insertCheerCardIntoZone(matchId, hostId, "RED", "ARCHIVE");
        Long yellowArchiveCheerInstanceId = insertCheerCardIntoZone(matchId, hostId, "YELLOW", "ARCHIVE");
        assertThat(redArchiveCheerInstanceId).isNotNull();
        assertThat(yellowArchiveCheerInstanceId).isNotNull();

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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, holderCardInstanceId);
        int attachedCheerBeforeResolve = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            holderHolomemId
        );

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
        assertThat(pendingContextText).contains("HSD11-006");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"PERFORMANCE_START_SELF\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int attachedCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            holderHolomemId
        );

        assertThat(attachedCheerAfter).isEqualTo(attachedCheerBeforeResolve + 1);
        assertThat(loadCardZone(nonMatchingHandInstanceId)).isEqualTo("HAND");
        assertThat(loadCardZone(matchingHandInstanceId)).isEqualTo("ARCHIVE");
        assertThat(loadCardZone(redArchiveCheerInstanceId)).isEqualTo("ARCHIVE");
        assertThat(loadCardZone(yellowArchiveCheerInstanceId)).isEqualTo("STAGE");

        String executedPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(executedPayloadText).containsPattern("\"discardApplied\"\\s*:\\s*1");
        assertThat(executedPayloadText).containsPattern("\"attachApplied\"\\s*:\\s*1");
    }

    @Test
    void advancePhaseShouldSkipOfficialGiftHsd11006WhenHandHasNoMatchingFlowGlowMember() {
        StartedMatchContext context = createStartedMatch("gift-hsd11006-no-hand-match-host", "gift-hsd11006-no-hand-match-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long holderCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        assertThat(holderCardInstanceId).isNotNull();

        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = 'HSD11-006',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            holderCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = 'HSD11-006',
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            matchId,
            hostId,
            holderCardInstanceId
        );
        Long holderHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            hostId,
            holderCardInstanceId
        );
        assertThat(holderHolomemId).isNotNull();

        clearHandToArchive(matchId, hostId);

        String nonMatchingHandCardId = createMemberCardDefinition(
            "THSD11006_NO_MATCH",
            "沒有 FLOW GLOW tag 的手牌",
            "DEBUT",
            90,
            "YELLOW"
        );
        Long nonMatchingHandInstanceId = insertCardIntoHand(matchId, hostId, nonMatchingHandCardId);
        Long yellowArchiveCheerInstanceId = insertCheerCardIntoZone(matchId, hostId, "YELLOW", "ARCHIVE");
        assertThat(nonMatchingHandInstanceId).isNotNull();
        assertThat(yellowArchiveCheerInstanceId).isNotNull();

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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, holderCardInstanceId);
        int attachedCheerBeforeResolve = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            holderHolomemId
        );
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int attachedCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            holderHolomemId
        );

        assertThat(attachedCheerAfter).isEqualTo(attachedCheerBeforeResolve);
        assertThat(loadCardZone(nonMatchingHandInstanceId)).isEqualTo("HAND");
        assertThat(loadCardZone(yellowArchiveCheerInstanceId)).isEqualTo("ARCHIVE");

        String executedPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        // HSD11-006 的後段加 Cheer 是建立在前段棄牌成本成功的前提上。
        // 當手牌沒有符合 #FLOW GLOW 的 Holomem 時，現在的泛用 Gift 順序邏輯
        // 會把 ADD_CHEER 標成 skipped，而不是硬執行一個 attachApplied=0 的空效果。
        assertThat(executedPayloadText).containsPattern("\"discardApplied\"\\s*:\\s*0");
        assertThat(executedPayloadText).containsPattern("\"effectType\"\\s*:\\s*\"ADD_CHEER\"");
        assertThat(executedPayloadText).contains("前置成本未支付");
    }

    @Test
    void attackArtShouldTriggerOfficialGiftHsd12007ReturningNonLimitedSupportFromArchive() {
        StartedMatchContext context = createStartedMatch("gift-hsd12007-host", "gift-hsd12007-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long attackerCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            "HSD12-007",
            matchId,
            hostId,
            attackerCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            "HSD12-007",
            matchId,
            hostId,
            attackerCardInstanceId
        );

        // 先放一張可回手、再放一張 LIMITED，確認官方卡只會撿回合法那張。
        Long returnedSupportCardInstanceId = insertSupportCardIntoZone(
            matchId,
            hostId,
            "THSD12007_SUPPORT_OK",
            "HSD12-007 可回手 Support",
            "ARCHIVE",
            false,
            "NO_OP",
            "{\"type\":\"NO_OP\"}",
            "SELF"
        );
        Long limitedSupportCardInstanceId = insertSupportCardIntoZone(
            matchId,
            hostId,
            "THSD12007_SUPPORT_LIMITED",
            "HSD12-007 LIMITED Support",
            "ARCHIVE",
            true,
            "NO_OP",
            "{\"type\":\"NO_OP\"}",
            "SELF"
        );

        Long targetCardInstanceId = createStageHolomemWithSingleCard(
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
        entityManager.clear();

        executeRequiredTurnActions(matchId, hostId, attackerCardInstanceId);
        // HSD12-007 使用官方藝能費用，這裡在 MAIN 補齊費用，讓測試焦點留在 Gift 回手規則。
        attachPrimaryArtCostFromCheerDeck(matchId, hostId, attackerCardInstanceId);
        matchActionService.advancePhase(matchId, hostId);
        int handBefore = countZone(matchId, hostId, "HAND");
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
        assertThat(pendingContextText).contains("HSD12-007");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"OPPONENT_DOWNED\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int handAfter = countZone(matchId, hostId, "HAND");
        int archiveAfter = countZone(matchId, hostId, "ARCHIVE");
        assertThat(handAfter).isEqualTo(handBefore + 1);
        assertThat(archiveAfter).isEqualTo(archiveBefore - 1);
        assertThat(loadCardZone(returnedSupportCardInstanceId)).isEqualTo("HAND");
        assertThat(loadCardZone(limitedSupportCardInstanceId)).isEqualTo("ARCHIVE");

        String executedPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(executedPayloadText).containsPattern("\"returnApplied\"\\s*:\\s*1");
        assertThat(executedPayloadText).containsPattern("\"excludeLimitedSupport\"\\s*:\\s*true");
    }

    @Test
    void attackArtShouldSkipLimitedSupportWhenOfficialGiftHsd12007ReturnsArchiveSupport() {
        StartedMatchContext context = createStartedMatch("gift-hsd12007-limited-host", "gift-hsd12007-limited-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long attackerCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            "HSD12-007",
            matchId,
            hostId,
            attackerCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            "HSD12-007",
            matchId,
            hostId,
            attackerCardInstanceId
        );

        Long limitedSupportCardInstanceId = insertSupportCardIntoZone(
            matchId,
            hostId,
            "THSD12007_ONLY_LIMITED",
            "HSD12-007 僅 LIMITED Support",
            "ARCHIVE",
            true,
            "NO_OP",
            "{\"type\":\"NO_OP\"}",
            "SELF"
        );

        Long targetCardInstanceId = createStageHolomemWithSingleCard(
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
        entityManager.clear();

        executeRequiredTurnActions(matchId, hostId, attackerCardInstanceId);
        // 這個案例驗證的是 LIMITED 過濾，不是藝能支付，所以同樣在 MAIN 先補齊攻擊費用。
        attachPrimaryArtCostFromCheerDeck(matchId, hostId, attackerCardInstanceId);
        matchActionService.advancePhase(matchId, hostId);
        int handBefore = countZone(matchId, hostId, "HAND");
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int handAfter = countZone(matchId, hostId, "HAND");
        int archiveAfter = countZone(matchId, hostId, "ARCHIVE");
        assertThat(handAfter).isEqualTo(handBefore);
        assertThat(archiveAfter).isEqualTo(archiveBefore);
        assertThat(loadCardZone(limitedSupportCardInstanceId)).isEqualTo("ARCHIVE");

        String executedPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(executedPayloadText).containsPattern("\"returnApplied\"\\s*:\\s*0");
        assertThat(executedPayloadText).containsPattern("\"excludeLimitedSupport\"\\s*:\\s*true");
    }

    @Test
    void collabShouldTriggerOfficialEffectHsd08007UsingArchiveCheerOnTaggedSecondHolomem() {
        StartedMatchContext context = createStartedMatch("collab-hsd08007-host", "collab-hsd08007-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long validTargetCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        String validTargetCardId = createMemberCardDefinition(
            "THSD08007_TARGET",
            "HSD08-007 2nd 目標",
            "SECOND",
            180,
            "WHITE"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#4期生\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            validTargetCardId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            validTargetCardId,
            matchId,
            hostId,
            validTargetCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'SECOND',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            validTargetCardId,
            matchId,
            hostId,
            validTargetCardInstanceId
        );
        Long validTargetHolomemId = jdbcTemplate.queryForObject(
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
            validTargetCardInstanceId
        );

        String invalidBackCardId = createMemberCardDefinition(
            "THSD08007_INVALID_BACK",
            "HSD08-007 無效 1st 目標",
            "FIRST",
            120,
            "WHITE"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#4期生\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            invalidBackCardId
        );
        Long invalidBackCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            invalidBackCardId,
            "BACK",
            "FIRST",
            0
        );
        Long invalidBackHolomemId = jdbcTemplate.queryForObject(
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
            invalidBackCardInstanceId
        );

        Long collabSourceCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            "HSD08-007",
            "BACK",
            "DEBUT",
            0
        );
        Long archiveCheerInstanceId = insertCheerCardIntoZone(matchId, hostId, "YELLOW", "ARCHIVE");
        Long cheerDeckCheerInstanceId = insertCheerCardIntoZone(matchId, hostId, "YELLOW", "CHEER_DECK");
        assertThat(archiveCheerInstanceId).isNotNull();
        assertThat(cheerDeckCheerInstanceId).isNotNull();

        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");
        int cheerDeckBefore = countZone(matchId, hostId, "CHEER_DECK");
        int validTargetCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            validTargetHolomemId
        );
        int invalidBackCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            invalidBackHolomemId
        );

        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(collabSourceCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int archiveAfter = countZone(matchId, hostId, "ARCHIVE");
        int cheerDeckAfter = countZone(matchId, hostId, "CHEER_DECK");
        int validTargetCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            validTargetHolomemId
        );
        int invalidBackCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            invalidBackHolomemId
        );
        assertThat(archiveAfter).isEqualTo(archiveBefore - 1);
        assertThat(cheerDeckAfter).isEqualTo(cheerDeckBefore);
        assertThat(validTargetCheerAfter).isEqualTo(validTargetCheerBefore + 1);
        assertThat(invalidBackCheerAfter).isEqualTo(invalidBackCheerBefore);

        String executedPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(executedPayloadText).contains("\"attachApplied\": 1");
        assertThat(executedPayloadText).contains("\"sourceZones\": [\"ARCHIVE\"]");
    }

    @Test
    void collabShouldNotTriggerOfficialEffectHsd08007WhenNoTaggedSecondHolomemExists() {
        StartedMatchContext context = createStartedMatch("collab-hsd08007-fail-host", "collab-hsd08007-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long invalidCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        String invalidCenterCardId = createMemberCardDefinition(
            "THSD08007_FAIL_CENTER",
            "HSD08-007 無效中心",
            "FIRST",
            140,
            "WHITE"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#4期生\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            invalidCenterCardId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            invalidCenterCardId,
            matchId,
            hostId,
            invalidCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'FIRST',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            invalidCenterCardId,
            matchId,
            hostId,
            invalidCenterCardInstanceId
        );
        Long invalidCenterHolomemId = jdbcTemplate.queryForObject(
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
            invalidCenterCardInstanceId
        );

        Long collabSourceCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            "HSD08-007",
            "BACK",
            "DEBUT",
            0
        );
        insertCheerCardIntoZone(matchId, hostId, "YELLOW", "ARCHIVE");
        int archiveBefore = countZone(matchId, hostId, "ARCHIVE");
        int invalidCenterCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            invalidCenterHolomemId
        );

        MoveStageHolomemActionRequest request = new MoveStageHolomemActionRequest();
        request.setCardInstanceId(collabSourceCardInstanceId);
        request.setTargetZone("COLLAB");
        matchActionService.moveStageHolomem(matchId, hostId, request);
        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int archiveAfter = countZone(matchId, hostId, "ARCHIVE");
        int invalidCenterCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            invalidCenterHolomemId
        );
        assertThat(archiveAfter).isEqualTo(archiveBefore);
        assertThat(invalidCenterCheerAfter).isEqualTo(invalidCenterCheerBefore);

        String executedPayloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'TRIGGER_EFFECT_EXECUTED'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : "",
            matchId,
            hostId
        );
        assertThat(executedPayloadText).contains("\"effectType\": \"ADD_CHEER\"");
        assertThat(executedPayloadText).contains("\"skipped\": true");
        assertThat(executedPayloadText).contains("ADD_CHEER 需要指定可用的我方 Holomen");
    }

    @Test
    void attackArtShouldCreateDefenderGiftConfirmWhenSelfDownedGiftTriggered() {
        StartedMatchContext context = createStartedMatch("gift-self-down-host", "gift-self-down-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String guestGiftHolderCardId = createMemberCardDefinition(
            "TGIFT_SELF_DOWNED",
            "測試 Gift 自身 down 觸發",
            "DEBUT",
            110,
            "PURPLE",
            "{\"キーワード\":\"ギフト自分が倒れた時 \\n相手のターンで、このホロメンがダウンした時、自分のデッキを1枚引く。\"}"
        );
        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "RED",
            "TGIFT_SELF_DOWNED_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            guestGiftHolderCardId,
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int guestDeckBefore = countZone(matchId, guestId, "DECK");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

        int guestDeckAfterAttack = countZone(matchId, guestId, "DECK");
        assertThat(guestDeckAfterAttack).isEqualTo(guestDeckBefore);

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
            guestId
        );
        assertThat(pendingContextText).containsPattern("\"giftTriggers\"");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"SELF_DOWNED\"");

        String attackPayloadText = jdbcTemplate.query(
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
        assertThat(attackPayloadText).containsPattern("\"defenderGiftEffects\"\\s*:\\s*\\{");
        assertThat(attackPayloadText).containsPattern("\"defenderPendingInteractionDecisionType\"\\s*:\\s*\"TRIGGER_EFFECT_CONFIRM\"");

        resolvePendingInteractionIfExists(matchId, guestId, "TRIGGER_EFFECT_CONFIRM");

        int guestDeckAfterConfirm = countZone(matchId, guestId, "DECK");
        assertThat(guestDeckAfterConfirm).isEqualTo(guestDeckBefore - 1);

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
            guestId
        );
        assertThat(latestGiftPayload).containsPattern("\"triggerType\"\\s*:\\s*\"SELF_DOWNED\"");
    }

    @Test
    void attackArtShouldTriggerOfficialGiftHbp04063WhenSelfDownedOnOpponentTurn() {
        StartedMatchContext context = createStartedMatch("gift-hbp04063-host", "gift-hbp04063-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "RED",
            "THBP04063_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            "HBP04-063",
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int guestDeckBefore = countZone(matchId, guestId, "DECK");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
            guestId
        );
        assertThat(pendingContextText).contains("HBP04-063");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"SELF_DOWNED\"");

        resolvePendingInteractionIfExists(matchId, guestId, "TRIGGER_EFFECT_CONFIRM");

        int guestDeckAfterConfirm = countZone(matchId, guestId, "DECK");
        assertThat(guestDeckAfterConfirm).isEqualTo(guestDeckBefore - 1);
    }

    @Test
    void attackArtShouldNotTriggerOfficialGiftHbp04063WhenAnotherAllyDowned() {
        StartedMatchContext context = createStartedMatch("gift-hbp04063-no-self-host", "gift-hbp04063-no-self-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "RED",
            "THBP04063_OTHER_ATTACKER"
        );
        createStageHolomemWithSingleCard(
            matchId,
            guestId,
            "HBP04-063",
            "BACK",
            "DEBUT",
            0
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int guestDeckBefore = countZone(matchId, guestId, "DECK");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
            guestId
        );
        assertThat(pendingCount).isZero();
        assertThat(countZone(matchId, guestId, "DECK")).isEqualTo(guestDeckBefore);
    }

    @Test
    void attackArtShouldCreateDefenderGiftConfirmWhenAllyDownedGiftTriggeredByTag() {
        StartedMatchContext context = createStartedMatch("gift-ally-tag-host", "gift-ally-tag-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String guestGiftHolderCardId = createMemberCardDefinition(
            "TGIFT_ALLY_TAG",
            "測試 Gift 友方 tag down 觸發",
            "DEBUT",
            150,
            "RED",
            "{\"キーワード\":\"ギフト正義的犧牲 \\n相手のターンで、自分の#Justiceを持つホロメンがダウンした時、自分のデッキを1枚引く。\"}"
        );
        createStageHolomemWithSingleCard(
            matchId,
            guestId,
            guestGiftHolderCardId,
            "BACK",
            "DEBUT",
            0
        );
        String downedCardId = createMemberCardDefinition(
            "TGIFT_ALLY_TAG_TARGET",
            "Justice Target",
            "DEBUT",
            100,
            "BLUE"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#Justice\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            downedCardId
        );
        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "RED",
            "TGIFT_ALLY_TAG_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            downedCardId,
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int guestDeckBefore = countZone(matchId, guestId, "DECK");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
            guestId
        );
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"ALLY_DOWNED\"");

        resolvePendingInteractionIfExists(matchId, guestId, "TRIGGER_EFFECT_CONFIRM");

        int guestDeckAfterConfirm = countZone(matchId, guestId, "DECK");
        assertThat(guestDeckAfterConfirm).isEqualTo(guestDeckBefore - 1);
    }

    @Test
    void attackArtShouldTriggerOfficialGiftHsd13005WhenJusticeAllyDowned() {
        StartedMatchContext context = createStartedMatch("gift-hsd13005-host", "gift-hsd13005-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long holderCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            "HSD13-005",
            "BACK",
            "FIRST",
            0
        );
        Long holderHolomemId = jdbcTemplate.queryForObject(
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
            holderCardInstanceId
        );

        String downedCardId = createMemberCardDefinition(
            "THSD13005_JUSTICE_TARGET",
            "Justice Target",
            "DEBUT",
            100,
            "BLUE"
        );
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#Justice\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            downedCardId
        );

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "RED",
            "THSD13005_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            downedCardId,
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int guestCheerDeckBefore = countZone(matchId, guestId, "CHEER_DECK");
        int holderCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            holderHolomemId
        );

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
            guestId
        );
        assertThat(pendingContextText).contains("HSD13-005");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"ALLY_DOWNED\"");

        resolvePendingInteractionIfExists(matchId, guestId, "TRIGGER_EFFECT_CONFIRM");

        int guestCheerDeckAfter = countZone(matchId, guestId, "CHEER_DECK");
        int holderCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            holderHolomemId
        );
        assertThat(guestCheerDeckAfter).isEqualTo(guestCheerDeckBefore - 1);
        assertThat(holderCheerAfter).isEqualTo(holderCheerBefore + 1);
    }

    @Test
    void attackArtShouldNotTriggerOfficialGiftHsd13005WhenDownedAllyIsNotJustice() {
        StartedMatchContext context = createStartedMatch("gift-hsd13005-fail-host", "gift-hsd13005-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        Long holderCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            "HSD13-005",
            "BACK",
            "FIRST",
            0
        );
        Long holderHolomemId = jdbcTemplate.queryForObject(
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
            holderCardInstanceId
        );

        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "RED",
            "THSD13005_FAIL_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int guestCheerDeckBefore = countZone(matchId, guestId, "CHEER_DECK");
        int holderCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            holderHolomemId
        );

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
            guestId
        );
        assertThat(pendingCount).isZero();
        assertThat(countZone(matchId, guestId, "CHEER_DECK")).isEqualTo(guestCheerDeckBefore);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            holderHolomemId
        )).isEqualTo(holderCheerBefore);
    }

    @Test
    void attackArtShouldCreateDefenderGiftConfirmWhenAllyDownedGiftTriggeredByName() {
        StartedMatchContext context = createStartedMatch("gift-ally-name-host", "gift-ally-name-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String guestGiftHolderCardId = createMemberCardDefinition(
            "TGIFT_ALLY_NAME",
            "測試 Gift 指定名稱 down 觸發",
            "DEBUT",
            150,
            "RED",
            "{\"キーワード\":\"ギフト指名救援 \\n相手のターンで、自分の〈測試名字〉がダウンした時、自分のデッキを1枚引く。\"}"
        );
        createStageHolomemWithSingleCard(
            matchId,
            guestId,
            guestGiftHolderCardId,
            "CENTER",
            "DEBUT",
            0
        );
        String downedCardId = createMemberCardDefinition(
            "TGIFT_ALLY_NAME_TARGET",
            "測試名字",
            "DEBUT",
            100,
            "BLUE"
        );
        Long attackerCardInstanceId = createStageHolomemWithArtAndCheer(
            matchId,
            hostId,
            "CENTER",
            180,
            "RED",
            220,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":220}",
            0,
            "RED",
            "TGIFT_ALLY_NAME_ATTACKER"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            guestId,
            downedCardId,
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
        entityManager.clear();

        advanceToPerformancePhase(matchId, hostId, attackerCardInstanceId);
        int guestDeckBefore = countZone(matchId, guestId, "DECK");

        AttackArtActionRequest attack = new AttackArtActionRequest();
        attack.setAttackerCardInstanceId(attackerCardInstanceId);
        attack.setTargetCardInstanceId(targetCardInstanceId);
        matchActionService.attackArt(matchId, hostId, attack);

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
            guestId
        );
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"ALLY_DOWNED\"");

        resolvePendingInteractionIfExists(matchId, guestId, "TRIGGER_EFFECT_CONFIRM");

        int guestDeckAfterConfirm = countZone(matchId, guestId, "DECK");
        assertThat(guestDeckAfterConfirm).isEqualTo(guestDeckBefore - 1);
    }

    @Test
    void playToStageShouldTriggerGiftWhenQualifiedHolomemEntersStage() {
        StartedMatchContext context = createStartedMatch("gift-enter-host", "gift-enter-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String giftHolderCardId = createMemberCardDefinition(
            "TGIFT_ENTER_HOLDER",
            "進場 Gift 持有者",
            "DEBUT",
            150,
            "YELLOW",
            "{\"キーワード\":\"ギフト正義的進場測試 \\n[センターポジション・コラボポジション限定][ターンに1回]自分の#Justiceを持つ[DebutホロメンかSpotホロメン]がステージに出た時、自分のデッキを1枚引く。\"}"
        );
        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            giftHolderCardId,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            giftHolderCardId,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );

        String enteredCardId = createMemberCardDefinition("TGIFT_ENTER_SRC", "Justice Debut", "DEBUT", 120, "BLUE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#Justice\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            enteredCardId
        );
        Long enteredCardInstanceId = insertCardIntoHand(matchId, hostId, enteredCardId);
        int deckBefore = countZone(matchId, hostId, "DECK");

        PlayToStageActionRequest play = new PlayToStageActionRequest();
        play.setCardInstanceId(enteredCardInstanceId);
        play.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, play);

        int deckAfterPlay = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterPlay).isEqualTo(deckBefore);

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
        assertThat(pendingContextText).containsPattern("\"giftTriggers\"");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"STAGE_ENTER\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int deckAfterConfirm = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterConfirm).isEqualTo(deckBefore - 1);

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
            hostId
        );
        assertThat(latestGiftPayload).containsPattern("\"triggerType\"\\s*:\\s*\"STAGE_ENTER\"");
    }

    @Test
    void playToStageShouldTriggerOfficialGiftHsd13014WhenJusticeDebutEntersStage() {
        StartedMatchContext context = createStartedMatch("gift-hsd13014-host", "gift-hsd13014-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            "HSD13-014",
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'SPOT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            "HSD13-014",
            matchId,
            hostId,
            hostCenterCardInstanceId
        );

        String enteredCardId = createMemberCardDefinition("THSD13014_SRC", "Justice Debut", "DEBUT", 120, "BLUE");
        jdbcTemplate.update(
            "UPDATE cards SET tags_json = '[\"#Justice\"]'::jsonb, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?",
            enteredCardId
        );
        Long enteredCardInstanceId = insertCardIntoHand(matchId, hostId, enteredCardId);
        int deckBefore = countZone(matchId, hostId, "DECK");

        PlayToStageActionRequest play = new PlayToStageActionRequest();
        play.setCardInstanceId(enteredCardInstanceId);
        play.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, play);

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
        assertThat(pendingContextText).contains("HSD13-014");
        assertThat(pendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"STAGE_ENTER\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        int deckAfterConfirm = countZone(matchId, hostId, "DECK");
        assertThat(deckAfterConfirm).isEqualTo(deckBefore - 1);
    }

    @Test
    void playToStageShouldNotTriggerOfficialGiftHsd13014WhenEnteringHolomemIsNotJusticeDebutOrSpot() {
        StartedMatchContext context = createStartedMatch("gift-hsd13014-fail-host", "gift-hsd13014-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            "HSD13-014",
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'SPOT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            "HSD13-014",
            matchId,
            hostId,
            hostCenterCardInstanceId
        );

        String enteredCardId = createMemberCardDefinition("THSD13014_BAD_SRC", "Not Justice Spot", "SPOT", 160, "BLUE");
        Long enteredCardInstanceId = insertCardIntoHand(matchId, hostId, enteredCardId);
        int deckBefore = countZone(matchId, hostId, "DECK");

        PlayToStageActionRequest play = new PlayToStageActionRequest();
        play.setCardInstanceId(enteredCardInstanceId);
        play.setTargetZone("BACK");
        matchActionService.playToStage(matchId, hostId, play);

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
            hostId
        );
        assertThat(pendingCount).isZero();
        assertThat(countZone(matchId, hostId, "DECK")).isEqualTo(deckBefore);
    }

    @Test
    void advancePhaseShouldCreatePerformanceStartGiftConfirmForBothPlayers() {
        StartedMatchContext context = createStartedMatch("gift-performance-host", "gift-performance-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String hostGiftHolderCardId = createMemberCardDefinition(
            "TGIFT_PERF_SELF",
            "自方表演開始 Gift",
            "DEBUT",
            140,
            "YELLOW",
            "{\"キーワード\":\"ギフト自己的表演開始 \\n自分のパフォーマンスステップが開始する時、自分のデッキを1枚引く。\"}"
        );
        String guestGiftHolderCardId = createMemberCardDefinition(
            "TGIFT_PERF_OPP",
            "對手表演開始 Gift",
            "DEBUT",
            140,
            "GREEN",
            "{\"キーワード\":\"ギフト對手的表演開始 \\n相手のパフォーマンスステップが開始する時に使える：自分のデッキを1枚引く。\"}"
        );

        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
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
            hostGiftHolderCardId,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            hostGiftHolderCardId,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            guestGiftHolderCardId,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            guestGiftHolderCardId,
            matchId,
            guestId,
            guestCenterCardInstanceId
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
        entityManager.clear();

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        int hostDeckBefore = countZone(matchId, hostId, "DECK");
        int guestDeckBefore = countZone(matchId, guestId, "DECK");

        matchActionService.advancePhase(matchId, hostId);

        String currentPhase = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            matchId
        );
        assertThat(currentPhase).isEqualTo("PERFORMANCE");

        String hostPendingContextText = jdbcTemplate.query(
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
        assertThat(hostPendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"PERFORMANCE_START_SELF\"");

        String guestPendingContextText = jdbcTemplate.query(
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
            guestId
        );
        assertThat(guestPendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"PERFORMANCE_START_OPPONENT\"");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");
        assertThat(jdbcTemplate.queryForObject("SELECT current_phase FROM matches WHERE id = ?", String.class, matchId))
            .isEqualTo("PERFORMANCE");
        assertThatThrownBy(() -> matchActionService.advancePhase(matchId, hostId))
            .isInstanceOfAny(GameRuleException.class, IllegalStateException.class)
            .hasMessageContaining("待處理的互動");
        resolvePendingInteractionIfExists(matchId, guestId, "TRIGGER_EFFECT_CONFIRM");
        assertThat(jdbcTemplate.queryForObject("SELECT current_phase FROM matches WHERE id = ?", String.class, matchId))
            .isEqualTo("PERFORMANCE");

        int hostDeckAfterConfirm = countZone(matchId, hostId, "DECK");
        int guestDeckAfterConfirm = countZone(matchId, guestId, "DECK");
        assertThat(hostDeckAfterConfirm).isEqualTo(hostDeckBefore - 1);
        assertThat(guestDeckAfterConfirm).isEqualTo(guestDeckBefore - 1);
    }

    @Test
    void advancePhaseShouldCreateOpponentPerformanceEndGiftConfirmWhenLifeReducedDuringPerformance() {
        StartedMatchContext context = createStartedMatch("gift-performance-end-life-host", "gift-performance-end-life-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String guestGiftHolderCardId = createMemberCardDefinition(
            "TGIFT_PERF_END_LIFE",
            "對手表演結束掉血 Gift",
            "DEBUT",
            150,
            "YELLOW",
            "{\"キーワード\":\"ギフト掉血檢查 \\n相手のパフォーマンスステップが終了する時、そのパフォーマンスステップに自分のライフが減っていたら、自分のデッキを1枚引く。\"}"
        );
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
            guestGiftHolderCardId,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            guestGiftHolderCardId,
            matchId,
            guestId,
            guestCenterCardInstanceId
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
        entityManager.clear();

        executeRequiredTurnActions(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
        int guestDeckBefore = countZone(matchId, guestId, "DECK");

        matchActionService.advancePhase(matchId, hostId);

        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = GREATEST(COALESCE(current_life, 0) - 1, 0),
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            guestId
        );

        matchActionService.advancePhase(matchId, hostId);

        String guestPendingContextText = jdbcTemplate.query(
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
            guestId
        );
        assertThat(guestPendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"PERFORMANCE_END_OPPONENT\"");

        assertThat(jdbcTemplate.queryForObject("SELECT current_phase FROM matches WHERE id = ?", String.class, matchId))
            .isEqualTo("END");
        resolvePendingInteractionIfExists(matchId, guestId, "TRIGGER_EFFECT_CONFIRM");
        assertThat(jdbcTemplate.queryForObject("SELECT current_phase FROM matches WHERE id = ?", String.class, matchId))
            .isEqualTo("END");

        int guestDeckAfterConfirm = countZone(matchId, guestId, "DECK");
        assertThat(guestDeckAfterConfirm).isEqualTo(guestDeckBefore - 1);
    }

    @Test
    void advancePhaseShouldCreateOwnPerformanceEndGiftConfirm() {
        StartedMatchContext context = createStartedMatch("gift-performance-end-self-host", "gift-performance-end-self-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();

        String hostGiftHolderCardId = createMemberCardDefinition(
            "TGIFT_PERF_END_SELF",
            "自方表演結束 Gift",
            "DEBUT",
            150,
            "BLUE",
            "{\"キーワード\":\"ギフト自己的表演結束 \\n自分のパフォーマンスステップが終了する時、自分のデッキを1枚引く。\"}"
        );
        Long hostCenterCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND id = ?
            """,
            hostGiftHolderCardId,
            matchId,
            hostId,
            hostCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            hostGiftHolderCardId,
            matchId,
            hostId,
            hostCenterCardInstanceId
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
        entityManager.clear();

        executeRequiredTurnActions(matchId, hostId, hostCenterCardInstanceId);
        int hostDeckBefore = countZone(matchId, hostId, "DECK");

        matchActionService.advancePhase(matchId, hostId);
        matchActionService.advancePhase(matchId, hostId);

        String hostPendingContextText = jdbcTemplate.query(
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
        assertThat(hostPendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"PERFORMANCE_END_SELF\"");
        assertThat(jdbcTemplate.queryForObject("SELECT current_phase FROM matches WHERE id = ?", String.class, matchId))
            .isEqualTo("END");

        resolvePendingInteractionIfExists(matchId, hostId, "TRIGGER_EFFECT_CONFIRM");

        assertThat(jdbcTemplate.queryForObject("SELECT current_phase FROM matches WHERE id = ?", String.class, matchId))
            .isEqualTo("END");
        int hostDeckAfterConfirm = countZone(matchId, hostId, "DECK");
        assertThat(hostDeckAfterConfirm).isEqualTo(hostDeckBefore - 1);
    }

    @Test
    void advancePhaseShouldCreateOpponentPerformanceEndGiftConfirmWhenHolderHpUnchanged() {
        StartedMatchContext context = createStartedMatch("gift-performance-end-hp-host", "gift-performance-end-hp-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

        String guestGiftHolderCardId = createMemberCardDefinition(
            "TGIFT_PERF_END_HP",
            "對手表演結束 HP 未下降 Gift",
            "DEBUT",
            150,
            "PURPLE",
            "{\"キーワード\":\"ギフト沒受傷 \\n[センターポジション限定]相手のパフォーマンスステップが終了する時、このホロメンのHPが減っていないなら、自分のデッキを1枚引く。\"}"
        );
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
            guestGiftHolderCardId,
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            guestGiftHolderCardId,
            matchId,
            guestId,
            guestCenterCardInstanceId
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
        entityManager.clear();

        executeRequiredTurnActions(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
        int guestDeckBefore = countZone(matchId, guestId, "DECK");

        matchActionService.advancePhase(matchId, hostId);
        matchActionService.advancePhase(matchId, hostId);

        String guestPendingContextText = jdbcTemplate.query(
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
            guestId
        );
        assertThat(guestPendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"PERFORMANCE_END_OPPONENT\"");

        resolvePendingInteractionIfExists(matchId, guestId, "TRIGGER_EFFECT_CONFIRM");

        int guestDeckAfterConfirm = countZone(matchId, guestId, "DECK");
        assertThat(guestDeckAfterConfirm).isEqualTo(guestDeckBefore - 1);
    }

    @Test
    void advancePhaseShouldTriggerOfficialGiftHbp05055WhenOpponentPerformanceEndsWithHpUnchanged() {
        StartedMatchContext context = createStartedMatch("gift-hbp05055-host", "gift-hbp05055-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

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
            "HBP05-055",
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'FIRST',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            "HBP05-055",
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        Long guestHolderHolomemId = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
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
        entityManager.clear();

        executeRequiredTurnActions(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
        int guestCheerDeckBefore = countZone(matchId, guestId, "CHEER_DECK");
        int holderCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            guestHolderHolomemId
        );

        matchActionService.advancePhase(matchId, hostId);
        matchActionService.advancePhase(matchId, hostId);

        String guestPendingContextText = jdbcTemplate.query(
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
            guestId
        );
        assertThat(guestPendingContextText).contains("HBP05-055");
        assertThat(guestPendingContextText).containsPattern("\"triggerType\"\\s*:\\s*\"PERFORMANCE_END_OPPONENT\"");

        resolvePendingInteractionIfExists(matchId, guestId, "TRIGGER_EFFECT_CONFIRM");

        int guestCheerDeckAfter = countZone(matchId, guestId, "CHEER_DECK");
        int holderCheerAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            guestHolderHolomemId
        );
        assertThat(guestCheerDeckAfter).isEqualTo(guestCheerDeckBefore - 1);
        assertThat(holderCheerAfter).isEqualTo(holderCheerBefore + 1);
    }

    @Test
    void advancePhaseShouldNotTriggerOfficialGiftHbp05055WhenHolderHpDecreased() {
        StartedMatchContext context = createStartedMatch("gift-hbp05055-fail-host", "gift-hbp05055-fail-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();

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
            "HBP05-055",
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'FIRST',
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            "HBP05-055",
            matchId,
            guestId,
            guestCenterCardInstanceId
        );
        Long guestHolderHolomemId = jdbcTemplate.queryForObject(
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
            guestCenterCardInstanceId
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
        entityManager.clear();

        executeRequiredTurnActions(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
        int guestCheerDeckBefore = countZone(matchId, guestId, "CHEER_DECK");
        int holderCheerBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            guestHolderHolomemId
        );

        matchActionService.advancePhase(matchId, hostId);
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = COALESCE(damage_taken, 0) + 20,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            guestHolderHolomemId
        );
        matchActionService.advancePhase(matchId, hostId);

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
            guestId
        );
        assertThat(pendingCount).isZero();
        assertThat(countZone(matchId, guestId, "CHEER_DECK")).isEqualTo(guestCheerDeckBefore);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            guestHolderHolomemId
        )).isEqualTo(holderCheerBefore);
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
        batonTouch.setSourceHolomemCardInstanceId(backCardInstanceId);
        batonTouch.setTargetCenterHolomemCardInstanceId(centerCardInstanceId);
        assertThatThrownBy(() -> matchActionService.batonTouch(matchId, hostId, batonTouch))
            .isInstanceOfAny(GameRuleException.class, IllegalStateException.class)
            .hasMessageContaining("來源必須是非休息");
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
        advanceToEndPhase(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
        matchActionService.endTurn(matchId, hostId);

        resolvePendingInteractionIfExists(matchId, guestId, "TURN_START");
        advanceToEndPhase(matchId, guestId, loadFirstCenterCardInstanceId(matchId, guestId));
        matchActionService.endTurn(matchId, guestId);

        resolvePendingInteractionIfExists(matchId, hostId, "TURN_START");
        executeRequiredTurnActions(matchId, hostId, loadFirstCenterCardInstanceId(matchId, hostId));
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

        playOpeningCenter(context.matchId(), context.hostId());
        matchActionService.advancePhase(context.matchId(), context.hostId());
        playOpeningCenter(context.matchId(), context.guestId());
        matchActionService.advancePhase(context.matchId(), context.guestId());
        resolvePendingInteractionIfExists(context.matchId(), context.hostId(), "LIVE_START");
        executeRequiredTurnActions(
            context.matchId(),
            context.hostId(),
            loadFirstCenterCardInstanceId(context.matchId(), context.hostId())
        );

        return context;
    }

    private Long playOpeningCenter(Long matchId, Long userId) {
        Long memberCardInstanceId = findMemberCardFromHand(matchId, userId);
        assertThat(memberCardInstanceId).isNotNull();

        PlayToStageActionRequest play = new PlayToStageActionRequest();
        play.setCardInstanceId(memberCardInstanceId);
        play.setTargetZone("CENTER");
        matchActionService.playToStage(matchId, userId, play);
        return memberCardInstanceId;
    }

    private Long playOpeningBack(Long matchId, Long userId) {
        Long memberCardInstanceId = findOpeningBackMemberFromHand(matchId, userId);
        assertThat(memberCardInstanceId).isNotNull();

        PlayToStageActionRequest play = new PlayToStageActionRequest();
        play.setCardInstanceId(memberCardInstanceId);
        play.setTargetZone("BACK");
        matchActionService.playToStage(matchId, userId, play);
        return memberCardInstanceId;
    }

    private Long findOpeningBackMemberFromHand(Long matchId, Long userId) {
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

    private void clearAttachedStageCheers(Long matchId, Long userId) {
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

    private void clearHandToArchive(Long matchId, Long userId) {
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
    private void setExactHandCount(Long matchId, Long userId, int targetCount, String cardPrefix) {
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

    private void advanceToPerformancePhase(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
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

    private void advanceToEndPhase(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
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

    /**
     * 依 match card instance 反查目前場上的 Holomem id。
     *
     * <p>測試常需要同時操作：
     *
     * <p>- `match_cards.id`：大多數 action request 都吃 card instance id
     * <p>- `match_holomems.id`：像直接附著 Cheer、查 damage_taken 這類底層狀態會用 holomem id
     *
     * <p>把這層轉換抽成 helper，能讓測試描述更專注在規則本身，而不是每次都重寫相同查詢。
     */
    private Long loadHolomemIdByCardInstanceId(Long matchId, Long ownerUserId, Long cardInstanceId) {
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            cardInstanceId
        );
    }

    /**
     * 幫既有 member card definition 補一個主藝能。
     *
     * <p>`HBP06-084` 這類測試需要「名稱固定為官方條件要匹配的角色」，但又要自訂一個可穩定驗證的
     * 藝能傷害值。直接提供這個 helper，可以把「卡名條件」與「藝能數值 setup」拆開，不必為了測一張
     * Gift 再額外建立一套專用建卡流程。
     */
    private void insertPrimaryArtForMember(
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

    /**
     * 依主藝能費用自動從 Cheer Deck 補足並貼到指定 Holomem。
     *
     * 這個 helper 的目的不是測藝能費用本身，而是讓 Gift/觸發測試可以快速進到
     * 「確實能出招」的狀態，避免每個案例都重複手寫 attach 流程。
     */
    private void attachPrimaryArtCostFromCheerDeck(Long matchId, Long userId, Long holomemCardInstanceId) {
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

    /**
     * 建立測試用 Cheer 卡並直接放入指定區域。
     *
     * 這個 helper 主要用在驗證「效果必須從哪個來源區取 Cheer」。
     * 因此不附著到 Holomem，只建立 match_cards 的區域狀態。
     */
    private Long insertCheerCardIntoZone(Long matchId, Long userId, String color, String zone) {
        String normalizedColor = normalizeCheerColorForTest(color);
        String normalizedZone = zone == null ? "CHEER_DECK" : zone.trim().toUpperCase();
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            normalizedZone
        );

        String cheerCardId = "TCHEER_ZONE_" + normalizedColor + "_" + normalizedZone + "_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId,
            "測試區域 Cheer " + normalizedColor
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
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            userId,
            cheerCardId,
            normalizedZone,
            nextOrder == null ? 1 : nextOrder,
            "CHEER_DECK".equals(normalizedZone)
        );
        return jdbcTemplate.query(
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
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            cheerCardId,
            normalizedZone
        );
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
    private void attachDirectTestCheers(
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
            jdbcTemplate.update(
                """
                INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                VALUES (?, ?, FALSE)
                """,
                matchHolomemId,
                cheerCardId
            );
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

    /**
     * 讀取單張 match card 當前所在區域。
     *
     * <p>很多卡效測試最後只在意「有沒有真的被移到正確 zone」，直接抽成 helper 可以讓 assertion
     * 聚焦在規則結果，而不是每個測試都重覆拼同一段 SQL。
     */
    private String loadCardZone(Long cardInstanceId) {
        return jdbcTemplate.queryForObject(
            "SELECT zone FROM match_cards WHERE id = ?",
            String.class,
            cardInstanceId
        );
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

    private Long insertSupportCardIntoHand(
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
    private void createSupportCardDefinition(
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
    private Long insertSupportCardIntoDeckTop(
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
    private Long insertSupportCardIntoZone(
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

    private record StartedMatchContext(Long matchId, Long hostId, Long guestId) {
    }
}
