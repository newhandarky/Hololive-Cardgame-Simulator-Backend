package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.dto.MulliganActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.repository.UserRepository;
import com.hololive.cardgame.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class MatchIntegrationTestSupport extends AbstractPostgresIntegrationTest {

    @Autowired
    protected LobbyMatchService lobbyMatchService;

    @Autowired
    protected MatchActionService matchActionService;

    @Autowired
    protected MatchGameStateService matchGameStateService;

    @Autowired
    protected MatchEffectService matchEffectService;

    @Autowired
    protected MatchEffectDamageService matchEffectDamageService;

    @Autowired
    protected MatchEffectCombatModifierService matchEffectCombatModifierService;

    @Autowired
    protected MatchTriggeredCardEffectService matchTriggeredCardEffectService;

    @Autowired
    protected MatchGiftTriggerService matchGiftTriggerService;

    @Autowired
    protected DeckService deckService;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected EntityManager entityManager;

    @MockBean
    protected DiceService diceService;

    @BeforeEach
    void setupDefaultDiceRoll() {
        Mockito.when(diceService.rollD6()).thenReturn(6);
    }

    protected record StartedMatchContext(Long matchId, Long hostId, Long guestId) {
    }

    protected StartedMatchContext createReadyMatch(String hostPrefix, String guestPrefix) {
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

    protected StartedMatchContext createStartedMatch(String hostPrefix, String guestPrefix) {
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

    protected abstract void executeRequiredTurnActions(
        Long matchId,
        Long userId,
        Long sendCheerTargetCardInstanceId
    );

    protected Long playOpeningCenter(Long matchId, Long userId) {
        Long memberCardInstanceId = findDebutMemberCardFromHand(matchId, userId);
        assertThat(memberCardInstanceId).isNotNull();

        PlayToStageActionRequest play = new PlayToStageActionRequest();
        play.setCardInstanceId(memberCardInstanceId);
        play.setTargetZone("CENTER");
        matchActionService.playToStage(matchId, userId, play);
        return memberCardInstanceId;
    }

    protected Long findDebutMemberCardFromHand(Long matchId, Long userId) {
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

    protected void ensureOpeningHandContainsDebut(Long matchId, Long userId) {
        if (findDebutMemberCardFromHand(matchId, userId) != null) {
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
        assertThat(targetCardInstanceId).isNotNull();
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

    protected void resolvePendingInteractionIfExists(Long matchId, Long userId, String decisionType) {
        Long decisionId = findPendingDecision(matchId, userId, decisionType);
        if (decisionId == null) {
            return;
        }
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setDecisionId(decisionId);
        matchActionService.resolveDecision(matchId, userId, request);
    }

    protected Long findPendingDecision(Long matchId, Long userId, String decisionType) {
        return jdbcTemplate.query(
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
    }

    protected Long loadFirstStageCardInstanceId(Long matchId, Long userId) {
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

    protected Long loadFirstCenterCardInstanceId(Long matchId, Long userId) {
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

    protected String findMemberCardIdByLevel(String levelType) {
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

    protected int countZone(Long matchId, Long userId, String zone) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = ?",
            Integer.class,
            matchId,
            userId,
            zone
        );
        return value == null ? 0 : value;
    }

    protected Long loadHolomemIdByCardInstanceId(Long matchId, Long ownerUserId, Long cardInstanceId) {
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

    protected String createFixedMemberCardDefinition(
        String cardId,
        String displayName,
        String levelType,
        int hp,
        String mainColor
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (card_id) DO NOTHING
            """,
            cardId,
            displayName
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, ?, ?, ?, NULL, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (card_id) DO NOTHING
            """,
            cardId,
            hp,
            levelType,
            mainColor
        );
        return cardId;
    }

    protected String createGeneratedMemberCardDefinition(
        String prefix,
        String displayName,
        String levelType,
        int hp,
        String mainColor
    ) {
        return createGeneratedMemberCardDefinition(prefix, displayName, levelType, hp, mainColor, null);
    }

    protected String createGeneratedMemberCardDefinition(
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
            bloomLevelForFixture(levelType),
            passiveEffectJson == null ? "null" : passiveEffectJson
        );
        return cardId;
    }

    protected Long insertCardIntoZone(Long matchId, Long ownerUserId, String cardId, String zone, boolean faceDown) {
        int nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            ownerUserId,
            zone
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            ownerUserId,
            cardId,
            zone,
            nextOrder,
            faceDown
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
            ownerUserId,
            cardId,
            zone
        );
    }

    protected Long createStageHolomemWithSingleCard(Long matchId, Long ownerUserId, String cardId, String zone) {
        Long cardInstanceId = insertCardIntoZone(matchId, ownerUserId, cardId, "STAGE", false);
        return createStageHolomemEntry(
            matchId,
            ownerUserId,
            cardInstanceId,
            cardId,
            zone,
            "DEBUT",
            0
        );
    }

    protected Long createStageHolomemWithSingleCard(
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
        return createStageHolomemEntry(
            matchId,
            ownerUserId,
            cardInstanceId,
            cardId,
            zone,
            normalizeHolomemLevelForFixture(levelType),
            enteredTurnNumber
        );
    }

    protected Long insertCheerCardIntoZone(Long matchId, Long userId, String color, String zone) {
        String normalizedColor = normalizeCheerColorForFixture(color);
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
        insertCheerCardDefinition(cheerCardId, "測試區域 Cheer " + normalizedColor, normalizedColor);
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

    protected Long insertCheerCardIntoZone(
        Long matchId,
        Long userId,
        String color,
        String zone,
        int index,
        int sequence
    ) {
        String normalizedZone = zone == null ? "CHEER_DECK" : zone.trim().toUpperCase();
        String cheerCardId = "TSMOKE_CHEER_" + normalizedZone + "_" + index + "_" + sequence + "_" + System.nanoTime();
        insertCheerCardDefinition(cheerCardId, "Smoke cheer " + normalizedZone, color);
        return insertCardIntoZone(matchId, userId, cheerCardId, normalizedZone, "CHEER_DECK".equals(normalizedZone));
    }

    private Long createStageHolomemEntry(
        Long matchId,
        Long ownerUserId,
        Long cardInstanceId,
        String cardId,
        String zone,
        String currentLevel,
        int enteredTurnNumber
    ) {
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

    private void insertCheerCardDefinition(String cardId, String cardName, String color) {
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cardId,
            cardName
        );
        jdbcTemplate.update(
            """
            INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cardId,
            color
        );
    }

    private String normalizeCheerColorForFixture(String color) {
        if (color == null || color.isBlank()) {
            return "WHITE";
        }
        String normalized = color.trim().toUpperCase();
        if ("COLORLESS".equals(normalized)) {
            return "WHITE";
        }
        return normalized;
    }

    private String normalizeHolomemLevelForFixture(String rawLevel) {
        if ("FIRST".equals(rawLevel) || "SECOND".equals(rawLevel) || "SPOT".equals(rawLevel) || "BUZZ".equals(rawLevel)) {
            return rawLevel;
        }
        return "DEBUT";
    }

    private int bloomLevelForFixture(String levelType) {
        if ("FIRST".equals(levelType)) {
            return 1;
        }
        if ("SECOND".equals(levelType)) {
            return 2;
        }
        if ("BUZZ".equals(levelType)) {
            return 3;
        }
        return 0;
    }

    protected User createUser(String prefix) {
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
