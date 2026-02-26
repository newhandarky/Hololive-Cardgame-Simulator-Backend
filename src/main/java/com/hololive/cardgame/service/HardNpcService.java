package com.hololive.cardgame.service;

import com.hololive.cardgame.dto.AttachCheerActionRequest;
import com.hololive.cardgame.dto.BloomActionRequest;
import com.hololive.cardgame.dto.GameStateResponse;
import com.hololive.cardgame.dto.MoveStageHolomemActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.dto.AttackArtActionRequest;
import com.hololive.cardgame.dto.PlayerZoneStateResponse;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.repository.MatchRepository;
import com.hololive.cardgame.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HardNpcService {

    private static final String HARD_NPC_LINE_USER_ID = "npc-hard-v1";
    private static final int MAX_ACTION_STEPS = 24;

    private final LobbyMatchService lobbyMatchService;
    private final MatchActionService matchActionService;
    private final MatchGameStateService matchGameStateService;
    private final MatchRepository matchRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public HardNpcService(
        LobbyMatchService lobbyMatchService,
        MatchActionService matchActionService,
        MatchGameStateService matchGameStateService,
        MatchRepository matchRepository,
        UserRepository userRepository,
        JdbcTemplate jdbcTemplate
    ) {
        this.lobbyMatchService = lobbyMatchService;
        this.matchActionService = matchActionService;
        this.matchGameStateService = matchGameStateService;
        this.matchRepository = matchRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public LobbyMatch executeHardNpcTurn(Long matchId, Long requesterUserId) {
        if (!lobbyMatchService.isUserInMatch(matchId, requesterUserId)) {
            throw new IllegalStateException("你不在此房間中");
        }
        Long npcUserId = resolveNpcUserIdInMatch(matchId);
        if (npcUserId == null) {
            throw new IllegalStateException("此對戰沒有 Hard NPC");
        }
        for (int i = 0; i < MAX_ACTION_STEPS; i++) {
            GameStateResponse state = matchGameStateService.getGameStateForUser(matchId, npcUserId);
            if (state == null || !"STARTED".equalsIgnoreCase(state.getStatus())) {
                break;
            }
            if (!npcUserId.equals(state.getCurrentTurnPlayerId())) {
                break;
            }
            if (resolvePending(state, matchId, npcUserId)) {
                continue;
            }
            if (!executeOneAction(state, matchId, npcUserId)) {
                break;
            }
        }
        return lobbyMatchService.getMatch(matchId);
    }

    @Transactional(readOnly = true)
    public boolean hasHardNpcInMatch(Long matchId) {
        return resolveNpcUserIdInMatch(matchId) != null;
    }

    private boolean resolvePending(GameStateResponse state, Long matchId, Long npcUserId) {
        if (state.getPendingInteractions() != null && !state.getPendingInteractions().isEmpty()) {
            var pending = state.getPendingInteractions().get(0);
            ResolveDecisionRequest resolve = new ResolveDecisionRequest();
            resolve.setDecisionId(pending.getInteractionId());
            String type = normalize(pending.getInteractionType());
            if ("LOOK_TOP_DECK".equals(type)) {
                resolve.setPlacement("TOP");
            } else if ("SEND_CHEER".equals(type)) {
                Long targetCardInstanceId = pickPreferredHolomemCardInstanceId(state, npcUserId);
                if (targetCardInstanceId != null) {
                    resolve.setSelectedCardInstanceIds(List.of(targetCardInstanceId));
                }
            } else if ("REORDER_DECK_BOTTOM".equals(type)) {
                List<Long> ids = pending.getCards().stream()
                    .map(card -> card.getCardInstanceId())
                    .filter(id -> id != null && id > 0)
                    .toList();
                resolve.setSelectedCardInstanceIds(ids);
            }
            matchActionService.resolveDecision(matchId, npcUserId, resolve);
            return true;
        }
        if (state.getPendingDecisions() != null && !state.getPendingDecisions().isEmpty()) {
            var pending = state.getPendingDecisions().get(0);
            ResolveDecisionRequest resolve = new ResolveDecisionRequest();
            resolve.setDecisionId(pending.getDecisionId());
            int minSelect = pending.getMinSelect() == null ? 0 : Math.max(pending.getMinSelect(), 0);
            if (minSelect > 0 && pending.getCandidates() != null && !pending.getCandidates().isEmpty()) {
                List<Long> selected = new ArrayList<>();
                for (var candidate : pending.getCandidates()) {
                    Long cardInstanceId = candidate.getCardInstanceId();
                    if (cardInstanceId == null || cardInstanceId <= 0) {
                        continue;
                    }
                    selected.add(cardInstanceId);
                    if (selected.size() >= minSelect) {
                        break;
                    }
                }
                resolve.setSelectedCardInstanceIds(selected);
            }
            matchActionService.resolveDecision(matchId, npcUserId, resolve);
            return true;
        }
        return false;
    }

    private boolean executeOneAction(GameStateResponse state, Long matchId, Long npcUserId) {
        try {
            matchActionService.drawTurn(matchId, npcUserId);
            return true;
        } catch (RuntimeException ignored) {
            // keep going
        }
        try {
            matchActionService.sendTurnCheer(matchId, npcUserId);
            return true;
        } catch (RuntimeException ignored) {
            // keep going
        }
        Long handHolomem = findPlayableHandHolomemCardInstanceId(matchId, npcUserId);
        if (handHolomem != null) {
            try {
                PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
                playToStage.setCardInstanceId(handHolomem);
                playToStage.setTargetZone("BACK");
                matchActionService.playToStage(matchId, npcUserId, playToStage);
                return true;
            } catch (RuntimeException ignored) {
                // keep going
            }
        }
        Long centerCard = findZoneCardInstance(state, npcUserId, "CENTER");
        Long backCard = findZoneCardInstance(state, npcUserId, "BACK");
        if (centerCard == null && backCard != null) {
            try {
                MoveStageHolomemActionRequest move = new MoveStageHolomemActionRequest();
                move.setCardInstanceId(backCard);
                move.setTargetZone("CENTER");
                matchActionService.moveStageHolomem(matchId, npcUserId, move);
                return true;
            } catch (RuntimeException ignored) {
                // keep going
            }
        }
        if (centerCard == null) {
            centerCard = findZoneCardInstance(
                matchGameStateService.getGameStateForUser(matchId, npcUserId),
                npcUserId,
                "CENTER"
            );
        }
        if (centerCard != null) {
            Long cheerInHand = findHandCardInstanceByType(matchId, npcUserId, "CHEER");
            if (cheerInHand != null) {
                try {
                    AttachCheerActionRequest attachCheer = new AttachCheerActionRequest();
                    attachCheer.setCheerCardInstanceId(cheerInHand);
                    attachCheer.setTargetHolomemCardInstanceId(centerCard);
                    matchActionService.attachCheer(matchId, npcUserId, attachCheer);
                    return true;
                } catch (RuntimeException ignored) {
                    // keep going
                }
            }
        }
        // Try bloom before attack to mimic stronger behavior.
        Long bloomCardInHand = findHandBloomCardInstance(matchId, npcUserId);
        if (bloomCardInHand != null) {
            Long bloomTarget = pickBloomTarget(matchId, npcUserId, bloomCardInHand);
            if (bloomTarget != null) {
                try {
                    BloomActionRequest bloom = new BloomActionRequest();
                    bloom.setBloomCardInstanceId(bloomCardInHand);
                    bloom.setTargetHolomemCardInstanceId(bloomTarget);
                    matchActionService.bloom(matchId, npcUserId, bloom);
                    return true;
                } catch (RuntimeException ignored) {
                    // keep going
                }
            }
        }

        GameStateResponse fresh = matchGameStateService.getGameStateForUser(matchId, npcUserId);
        Long attackerCenter = findZoneCardInstance(fresh, npcUserId, "CENTER");
        Long attackerCollab = findZoneCardInstance(fresh, npcUserId, "COLLAB");
        Long opponentCenter = resolveOpponentZoneCardInstance(fresh, npcUserId, "CENTER");
        Long opponentAny = opponentCenter != null ? opponentCenter : resolveOpponentAnyHolomemCardInstance(fresh, npcUserId);

        for (Long attacker : List.of(attackerCenter, attackerCollab)) {
            if (attacker == null || opponentAny == null) {
                continue;
            }
            try {
                AttackArtActionRequest attack = new AttackArtActionRequest();
                attack.setAttackerCardInstanceId(attacker);
                attack.setTargetCardInstanceId(opponentAny);
                matchActionService.attackArt(matchId, npcUserId, attack);
                return true;
            } catch (RuntimeException ignored) {
                // try next attacker
            }
        }

        try {
            matchActionService.endTurn(matchId, npcUserId);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Long resolveNpcUserIdInMatch(Long matchId) {
        Long hardNpcUserId = userRepository.findByLineUserId(HARD_NPC_LINE_USER_ID)
            .map(user -> user.getId())
            .orElse(null);
        if (hardNpcUserId == null) {
            return null;
        }
        boolean exists = matchRepository.findById(matchId)
            .map(match -> hardNpcUserId.equals(match.getPlayerAId()) || hardNpcUserId.equals(match.getPlayerBId()))
            .orElse(false);
        return exists ? hardNpcUserId : null;
    }

    private Long pickPreferredHolomemCardInstanceId(GameStateResponse state, Long userId) {
        Long center = findZoneCardInstance(state, userId, "CENTER");
        if (center != null) {
            return center;
        }
        Long collab = findZoneCardInstance(state, userId, "COLLAB");
        if (collab != null) {
            return collab;
        }
        return findZoneCardInstance(state, userId, "BACK");
    }

    private Long findZoneCardInstance(GameStateResponse state, Long userId, String zone) {
        PlayerZoneStateResponse player = findPlayerState(state, userId);
        if (player == null) {
            return null;
        }
        String normalizedZone = normalize(zone);
        for (var boardZone : player.getBoardZones()) {
            if (!normalizedZone.equals(normalize(boardZone.getZone()))) {
                continue;
            }
            if (boardZone.getCards() == null || boardZone.getCards().isEmpty()) {
                continue;
            }
            return boardZone.getCards().get(0).getCardInstanceId();
        }
        return null;
    }

    private Long resolveOpponentZoneCardInstance(GameStateResponse state, Long userId, String zone) {
        for (PlayerZoneStateResponse player : state.getPlayers()) {
            if (player == null || userId.equals(player.getUserId())) {
                continue;
            }
            String normalizedZone = normalize(zone);
            for (var boardZone : player.getBoardZones()) {
                if (!normalizedZone.equals(normalize(boardZone.getZone()))) {
                    continue;
                }
                if (boardZone.getCards() == null || boardZone.getCards().isEmpty()) {
                    continue;
                }
                return boardZone.getCards().get(0).getCardInstanceId();
            }
        }
        return null;
    }

    private Long resolveOpponentAnyHolomemCardInstance(GameStateResponse state, Long userId) {
        for (String zone : List.of("CENTER", "COLLAB", "BACK")) {
            Long cardInstanceId = resolveOpponentZoneCardInstance(state, userId, zone);
            if (cardInstanceId != null) {
                return cardInstanceId;
            }
        }
        return null;
    }

    private PlayerZoneStateResponse findPlayerState(GameStateResponse state, Long userId) {
        if (state == null || state.getPlayers() == null) {
            return null;
        }
        for (PlayerZoneStateResponse player : state.getPlayers()) {
            if (player != null && userId.equals(player.getUserId())) {
                return player;
            }
        }
        return null;
    }

    private Long findPlayableHandHolomemCardInstanceId(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id
            FROM match_cards mc
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'HAND'
              AND m.level_type IN ('DEBUT', 'SPOT')
            ORDER BY mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong(1) : null,
            matchId,
            userId
        );
    }

    private Long findHandCardInstanceByType(Long matchId, Long userId, String cardType) {
        return jdbcTemplate.query(
            """
            SELECT mc.id
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'HAND'
              AND c.card_type = ?
            ORDER BY mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong(1) : null,
            matchId,
            userId,
            normalize(cardType)
        );
    }

    private Long findHandBloomCardInstance(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id
            FROM match_cards mc
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'HAND'
              AND m.level_type IN ('FIRST', 'SECOND', 'BUZZ')
            ORDER BY mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong(1) : null,
            matchId,
            userId
        );
    }

    private Long pickBloomTarget(Long matchId, Long userId, Long bloomCardInstanceId) {
        String bloomCardId = jdbcTemplate.query(
            "SELECT card_id FROM match_cards WHERE id = ? AND match_id = ? AND owner_user_id = ?",
            rs -> rs.next() ? rs.getString("card_id") : null,
            bloomCardInstanceId,
            matchId,
            userId
        );
        if (bloomCardId == null) {
            return null;
        }
        String levelType = jdbcTemplate.query(
            "SELECT level_type FROM member_cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getString("level_type") : null,
            bloomCardId
        );
        if (levelType == null) {
            return null;
        }
        String requiredTargetLevel = switch (normalize(levelType)) {
            case "FIRST" -> "DEBUT";
            case "SECOND" -> "FIRST";
            case "BUZZ" -> "SECOND";
            default -> null;
        };
        if (requiredTargetLevel == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT h.match_card_id
            FROM match_holomems h
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER', 'BACK', 'COLLAB')
              AND h.current_level = ?
            ORDER BY CASE h.zone WHEN 'CENTER' THEN 0 WHEN 'COLLAB' THEN 1 ELSE 2 END, h.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong(1) : null,
            matchId,
            userId,
            requiredTargetLevel
        );
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
