package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class LobbyMatchServiceStartMatchInitializationTest {

    @Autowired
    private LobbyMatchService lobbyMatchService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DeckService deckService;

    @Autowired
    private MatchPlayerRepository matchPlayerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startMatchShouldInitializeDeckHandLifeAndCheerDeck() {
        User host = createUser("start-host");
        User guest = createUser("start-guest");

        // 測試快捷牌組：1 推し + 主牌庫 50 + エール 20
        deckService.setupQuickDeck(host.getId());
        deckService.setupQuickDeck(guest.getId());

        LobbyMatch created = lobbyMatchService.createMatch(host.getId());
        lobbyMatchService.joinMatch(created.getRoomCode(), guest.getId());
        lobbyMatchService.setReady(created.getId(), host.getId(), true);
        lobbyMatchService.setReady(created.getId(), guest.getId(), true);

        LobbyMatch started = lobbyMatchService.startMatch(created.getId(), host.getId());

        assertThat(started.getStatus()).isEqualTo(LobbyMatchStatus.STARTED);
        assertThat(started.getCurrentTurnPlayerId()).isEqualTo(host.getId());
        assertThat(started.getTurnNumber()).isEqualTo(1);
        String startedPhase = jdbcTemplate.queryForObject(
            "SELECT current_phase FROM matches WHERE id = ?",
            String.class,
            created.getId()
        );
        assertThat(startedPhase).isEqualTo("RESET");

        assertZoneCount(created.getId(), host.getId(), "HAND", 7);
        assertZoneCount(created.getId(), host.getId(), "DECK", 43);
        assertZoneCount(created.getId(), host.getId(), "LIFE", 5);
        assertZoneCount(created.getId(), host.getId(), "CHEER_DECK", 15);

        assertZoneCount(created.getId(), guest.getId(), "HAND", 7);
        assertZoneCount(created.getId(), guest.getId(), "DECK", 43);
        assertZoneCount(created.getId(), guest.getId(), "LIFE", 5);
        assertZoneCount(created.getId(), guest.getId(), "CHEER_DECK", 15);

        var hostPlayer = matchPlayerRepository.findByMatchIdAndUserId(created.getId(), host.getId()).orElseThrow();
        assertThat(hostPlayer.getOshiCardId()).isNotBlank();
        assertThat(hostPlayer.getCurrentLife()).isEqualTo(5);
        assertThat(hostPlayer.isMulliganDone()).isFalse();
        assertThat(hostPlayer.isMulliganUsed()).isFalse();
    }

    @Test
    void startMatchShouldFailWhenDeckIsInsufficient() {
        User host = createUser("invalid-host");
        User guest = createUser("invalid-guest");

        String hostOshiCardId = findFirstCardIdByType("OSHI");
        String hostMemberCardId = findFirstCardIdByType("MEMBER");
        String hostCheerCardId = findFirstCardIdByType("CHEER");
        String guestMemberCardId = findFirstCardIdByType("MEMBER");
        String guestCheerCardId = findFirstCardIdByType("CHEER");

        setupDeckCard(host, hostOshiCardId, 1);
        setupDeckCard(host, hostMemberCardId, 1);
        setupDeckCard(host, hostCheerCardId, 20);

        deckService.setupQuickDeck(guest.getId());
        setupDeckCard(guest, guestMemberCardId, 4);
        setupDeckCard(guest, guestCheerCardId, 20);

        LobbyMatch created = lobbyMatchService.createMatch(host.getId());
        lobbyMatchService.joinMatch(created.getRoomCode(), guest.getId());
        lobbyMatchService.setReady(created.getId(), host.getId(), true);
        lobbyMatchService.setReady(created.getId(), guest.getId(), true);

        assertThatThrownBy(() -> lobbyMatchService.startMatch(created.getId(), host.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("主牌庫必須剛好");
    }

    @Test
    void starterJusticePresetShouldPassDeckValidation() {
        User user = createUser("starter-justice");

        deckService.setupQuickDeck(user.getId(), "STARTER_JUSTICE_ERB");
        DeckService.ActiveDeckForMatch activeDeck = deckService.loadValidatedActiveDeckForMatch(user.getId());

        assertThat(activeDeck.validation().isValid()).isTrue();
        int justiceDebutCount = activeDeck.cards().stream()
            .filter(card -> "HSD13-003".equals(card.cardId()))
            .mapToInt(card -> card.count() == null ? 0 : card.count())
            .sum();
        assertThat(justiceDebutCount).isEqualTo(6);
    }

    @Test
    void bootstrapStarterDecksForNewUserShouldCreateSelectableOfficialDecks() {
        User user = createUser("starter-bootstrap");

        deckService.bootstrapStarterDecksForNewUser(user.getId());
        var deckSummaries = deckService.listDeckSummaries(user.getId());

        assertThat(deckSummaries).isNotEmpty();
        assertThat(deckSummaries).hasSizeGreaterThanOrEqualTo(2);
        assertThat(deckSummaries.stream().anyMatch(deck -> deck.getName().contains("咲き誇る友情"))).isTrue();
        assertThat(deckSummaries.stream().filter(deck -> deck.isActive()).count()).isEqualTo(1);
    }

    @Test
    void bootstrapStarterDecksForExistingUserShouldFillMissingOfficialDecksWithoutDuplicating() {
        User user = createUser("starter-existing");

        deckService.setupQuickDeck(user.getId(), "STARTER_JUSTICE_ERB");
        int beforeCount = deckService.listDeckSummaries(user.getId()).size();

        var firstBootstrap = deckService.bootstrapStarterDecksForUser(user.getId());
        var secondBootstrap = deckService.bootstrapStarterDecksForUser(user.getId());

        assertThat(firstBootstrap.size()).isGreaterThanOrEqualTo(beforeCount);
        assertThat(secondBootstrap.size()).isEqualTo(firstBootstrap.size());
        assertThat(firstBootstrap.stream().anyMatch(deck -> deck.getName().contains("魔法少女ホロウィッチ！"))).isTrue();
        assertThat(firstBootstrap.stream().filter(deck -> deck.isActive()).count()).isEqualTo(1);
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

    private void setupDeckCard(User user, String cardId, int count) {
        deckService.updateActiveDeckCard(user.getId(), cardId, count);
    }

    private String findFirstCardIdByType(String cardType) {
        String cardId = jdbcTemplate.queryForObject(
            "SELECT card_id FROM cards WHERE card_type = ? ORDER BY card_id LIMIT 1",
            String.class,
            cardType
        );
        assertThat(cardId).isNotBlank();
        return cardId;
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
}
