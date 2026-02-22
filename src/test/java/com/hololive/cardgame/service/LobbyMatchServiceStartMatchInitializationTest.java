package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.entity.UserCard;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.UserCardRepository;
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
    private UserCardRepository userCardRepository;

    @Autowired
    private MatchPlayerRepository matchPlayerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startMatchShouldInitializeDeckHandLifeAndCheerDeck() {
        User host = createUser("start-host");
        User guest = createUser("start-guest");

        // 最小可測牌組：1 推し + 7 主牌庫 + 7 エール
        grantCards(host, "OSHI-001", 1);
        grantCards(host, "MEM-001", 6);
        grantCards(host, "SUP-001", 1);
        grantCards(host, "CHE-001", 7);

        grantCards(guest, "OSHI-002", 1);
        grantCards(guest, "MEM-002", 6);
        grantCards(guest, "SUP-001", 1);
        grantCards(guest, "CHE-002", 7);

        LobbyMatch created = lobbyMatchService.createMatch(host.getId());
        lobbyMatchService.joinMatch(created.getRoomCode(), guest.getId());
        lobbyMatchService.setReady(created.getId(), host.getId(), true);
        lobbyMatchService.setReady(created.getId(), guest.getId(), true);

        LobbyMatch started = lobbyMatchService.startMatch(created.getId(), host.getId());

        assertThat(started.getStatus()).isEqualTo(LobbyMatchStatus.STARTED);
        assertThat(started.getCurrentTurnPlayerId()).isEqualTo(host.getId());
        assertThat(started.getTurnNumber()).isEqualTo(1);

        assertZoneCount(created.getId(), host.getId(), "HAND", 5);
        assertZoneCount(created.getId(), host.getId(), "DECK", 2);
        assertZoneCount(created.getId(), host.getId(), "LIFE", 6);
        assertZoneCount(created.getId(), host.getId(), "CHEER_DECK", 1);

        assertZoneCount(created.getId(), guest.getId(), "HAND", 5);
        assertZoneCount(created.getId(), guest.getId(), "DECK", 2);
        assertZoneCount(created.getId(), guest.getId(), "LIFE", 6);
        assertZoneCount(created.getId(), guest.getId(), "CHEER_DECK", 1);

        var hostPlayer = matchPlayerRepository.findByMatchIdAndUserId(created.getId(), host.getId()).orElseThrow();
        assertThat(hostPlayer.getOshiCardId()).isEqualTo("OSHI-001");
        assertThat(hostPlayer.getCurrentLife()).isEqualTo(6);
    }

    @Test
    void startMatchShouldFailWhenDeckIsInsufficient() {
        User host = createUser("invalid-host");
        User guest = createUser("invalid-guest");

        grantCards(host, "OSHI-001", 1);
        grantCards(host, "MEM-001", 1);
        grantCards(host, "CHE-001", 1);

        grantCards(guest, "OSHI-002", 1);
        grantCards(guest, "MEM-002", 1);
        grantCards(guest, "CHE-002", 1);

        LobbyMatch created = lobbyMatchService.createMatch(host.getId());
        lobbyMatchService.joinMatch(created.getRoomCode(), guest.getId());
        lobbyMatchService.setReady(created.getId(), host.getId(), true);
        lobbyMatchService.setReady(created.getId(), guest.getId(), true);

        assertThatThrownBy(() -> lobbyMatchService.startMatch(created.getId(), host.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("主牌庫不足");
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

    private void grantCards(User user, String cardId, int count) {
        UserCard userCard = new UserCard();
        userCard.setUserId(user.getId());
        userCard.setCardId(cardId);
        userCard.setCount(count);
        userCard.setCreatedAt(LocalDateTime.now());
        userCard.setUpdatedAt(LocalDateTime.now());
        userCardRepository.save(userCard);
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
