package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HardNpcService {

    private static final Logger log = LoggerFactory.getLogger(HardNpcService.class);
    private static final String HARD_NPC_LINE_USER_ID = "npc-hard-v1";
    private static final int MAX_ACTION_STEPS = 64;
    private static final String TURN_START_INTERACTION_TYPE = "TURN_START";
    private static final String DRAW_REVEAL_INTERACTION_TYPE = "DRAW_REVEAL";
    private static final String CARD_SELECTION_INTERACTION_TYPE = "CARD_SELECTION";
    private static final String PENDING_STATUS = "PENDING";

    private final LobbyMatchService lobbyMatchService;
    private final MatchActionService matchActionService;
    private final MatchGameStateService matchGameStateService;
    private final MatchRepository matchRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 建立 Hard NPC 服務，注入對戰流程所需的服務與資料存取元件。
     */
    public HardNpcService(
        LobbyMatchService lobbyMatchService,
        MatchActionService matchActionService,
        MatchGameStateService matchGameStateService,
        MatchRepository matchRepository,
        UserRepository userRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.lobbyMatchService = lobbyMatchService;
        this.matchActionService = matchActionService;
        this.matchGameStateService = matchGameStateService;
        this.matchRepository = matchRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 執行 Hard NPC 的單回合邏輯主入口。
     * 流程：
     * 1) 驗證請求者是否在房間內且該場存在 NPC。
     * 2) 迴圈處理 pending 與可執行 action（有步數上限避免死循環）。
     * 3) 最後以安全交棒機制收尾，避免 NPC 回合卡住。
     */
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
            GameStateResponse state;
            try {
                state = matchGameStateService.getGameStateForUser(matchId, npcUserId);
            } catch (RuntimeException ex) {
                log.warn(
                    "Hard NPC turn aborted while loading game state. matchId={}, npcUserId={}, step=LOAD_STATE",
                    matchId,
                    npcUserId,
                    ex
                );
                break;
            }
            if (state == null || !"STARTED".equalsIgnoreCase(state.getStatus())) {
                break;
            }
            if (!npcUserId.equals(state.getCurrentTurnPlayerId())) {
                break;
            }
            try {
                if (resolvePending(state, matchId, npcUserId)) {
                    continue;
                }
                if (!executeOneAction(state, matchId, npcUserId)) {
                    break;
                }
            } catch (RuntimeException ex) {
                log.warn(
                    "Hard NPC turn aborted on action loop. matchId={}, npcUserId={}, turnNumber={}, step=ACTION_LOOP",
                    matchId,
                    npcUserId,
                    state.getTurnNumber(),
                    ex
                );
                break;
            }
        }
        safeForceAdvanceTurn(matchId, npcUserId);
        return lobbyMatchService.getMatch(matchId);
    }

    /**
     * 檢查指定對戰是否包含 Hard NPC 玩家。
     */
    @Transactional(readOnly = true)
    public boolean hasHardNpcInMatch(Long matchId) {
        return resolveNpcUserIdInMatch(matchId) != null;
    }

    /**
     * 手動恢復 NPC 可能卡住的回合。
     * 僅允許房內玩家呼叫；若無 NPC 則直接回傳目前對戰。
     */
    @Transactional
    public LobbyMatch recoverIfNpcTurnStuck(Long matchId, Long requesterUserId) {
        if (!lobbyMatchService.isUserInMatch(matchId, requesterUserId)) {
            throw new IllegalStateException("你不在此房間中");
        }
        Long npcUserId = resolveNpcUserIdInMatch(matchId);
        if (npcUserId == null) {
            return lobbyMatchService.getMatch(matchId);
        }
        safeForceAdvanceTurn(matchId, npcUserId);
        return lobbyMatchService.getMatch(matchId);
    }

    /**
     * 以新交易強制嘗試交棒，避免外層交易 rollback 時連恢復動作一起失敗。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void safeForceAdvanceTurn(Long matchId, Long npcUserId) {
        forceAdvanceTurnIfNpcStuck(matchId, npcUserId);
    }

    /**
     * 優先處理 NPC 的 pending interaction / decision。
     * 回傳 true 代表本次已成功處理一筆 pending，呼叫端可進入下一輪 state 刷新。
     */
    private boolean resolvePending(GameStateResponse state, Long matchId, Long npcUserId) {
        if (state.getPendingInteractions() != null && !state.getPendingInteractions().isEmpty()) {
            var pending = state.getPendingInteractions().get(0);
            ResolveDecisionRequest resolve = new ResolveDecisionRequest();
            resolve.setDecisionId(pending.getInteractionId());
            String type = normalize(pending.getInteractionType());
            if (isAutoResolveInteractionType(type)) {
                // no extra selection payload
            } else if ("LOOK_TOP_DECK".equals(type)) {
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
            } else if (CARD_SELECTION_INTERACTION_TYPE.equals(type)) {
                int minSelect = pending.getMinSelect() == null ? 0 : Math.max(pending.getMinSelect(), 0);
                if (minSelect > 0 && pending.getCards() != null && !pending.getCards().isEmpty()) {
                    List<Long> selected = new ArrayList<>();
                    for (var candidate : pending.getCards()) {
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
            }
            matchActionService.resolveDecision(matchId, npcUserId, resolve);
            return true;
        }
        if (state.getPendingDecisions() != null && !state.getPendingDecisions().isEmpty()) {
            var pending = state.getPendingDecisions().get(0);
            ResolveDecisionRequest resolve = new ResolveDecisionRequest();
            resolve.setDecisionId(pending.getDecisionId());
            String type = normalize(pending.getDecisionType());
            if (isAutoResolveInteractionType(type)) {
                matchActionService.resolveDecision(matchId, npcUserId, resolve);
                return true;
            }
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

    /**
     * NPC 單步決策引擎。
     * 會根據 phase 與場面狀態，依序嘗試：抽牌 -> 回合エール -> 補位/出牌 -> Bloom -> 攻擊 -> 結束回合。
     */
    private boolean executeOneAction(GameStateResponse state, Long matchId, Long npcUserId) {
        int turnNumber = state.getTurnNumber() == null ? 1 : state.getTurnNumber();
        String phase = normalize(state.getPhase() == null ? null : state.getPhase().name());
        if ("END".equals(phase)) {
            return tryEndTurn(matchId, npcUserId, turnNumber);
        }
        if ("RESET".equals(phase)) {
            ensureTurnStartPendingInteraction(matchId, npcUserId, turnNumber);
            return true;
        }
        if (!"MAIN".equals(phase) && !"PERFORMANCE".equals(phase)) {
            return false;
        }

        if (!hasActionInTurn(matchId, npcUserId, turnNumber, "DRAW_TURN")) {
            try {
                matchActionService.drawTurn(matchId, npcUserId);
                return true;
            } catch (RuntimeException ex) {
                log.warn(
                    "Hard NPC action failed. matchId={}, npcUserId={}, turnNumber={}, step=DRAW",
                    matchId,
                    npcUserId,
                    turnNumber,
                    ex
                );
            }
        }

        if (!hasActionInTurn(matchId, npcUserId, turnNumber, "TURN_CHEER") && canSendTurnCheer(matchId, npcUserId)) {
            try {
                matchActionService.sendTurnCheer(matchId, npcUserId);
                return true;
            } catch (RuntimeException ex) {
                log.warn(
                    "Hard NPC action failed. matchId={}, npcUserId={}, turnNumber={}, step=SEND_TURN_CHEER",
                    matchId,
                    npcUserId,
                    turnNumber,
                    ex
                );
            }
        }

        GameStateResponse fresh = matchGameStateService.getGameStateForUser(matchId, npcUserId);
        Long centerCard = findZoneCardInstance(fresh, npcUserId, "CENTER");
        Long collabCard = findZoneCardInstance(fresh, npcUserId, "COLLAB");
        Long backCard = findHighestHpCardInstanceInZone(fresh, npcUserId, "BACK");

        if (centerCard == null) {
            if (backCard != null) {
                try {
                    MoveStageHolomemActionRequest move = new MoveStageHolomemActionRequest();
                    move.setCardInstanceId(backCard);
                    move.setTargetZone("CENTER");
                    matchActionService.moveStageHolomem(matchId, npcUserId, move);
                    return true;
                } catch (RuntimeException ex) {
                    log.warn(
                        "Hard NPC action failed. matchId={}, npcUserId={}, turnNumber={}, step=MOVE_BACK_TO_CENTER, cardInstanceId={}",
                        matchId,
                        npcUserId,
                        turnNumber,
                        backCard,
                        ex
                    );
                }
            }
            Long handHolomem = findPlayableHandHolomemCardInstanceId(matchId, npcUserId);
            if (handHolomem != null) {
                try {
                    PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
                    playToStage.setCardInstanceId(handHolomem);
                    playToStage.setTargetZone("BACK");
                    matchActionService.playToStage(matchId, npcUserId, playToStage);
                    return true;
                } catch (RuntimeException ex) {
                    log.warn(
                        "Hard NPC action failed. matchId={}, npcUserId={}, turnNumber={}, step=PLAY_TO_STAGE_FOR_CENTER, cardInstanceId={}",
                        matchId,
                        npcUserId,
                        turnNumber,
                        handHolomem,
                        ex
                    );
                }
            }
        }

        if (hasBackCapacity(matchId, npcUserId)) {
            Long handHolomem = findPlayableHandHolomemCardInstanceId(matchId, npcUserId);
            if (handHolomem != null) {
                try {
                    PlayToStageActionRequest playToStage = new PlayToStageActionRequest();
                    playToStage.setCardInstanceId(handHolomem);
                    playToStage.setTargetZone("BACK");
                    matchActionService.playToStage(matchId, npcUserId, playToStage);
                    return true;
                } catch (RuntimeException ex) {
                    log.warn(
                        "Hard NPC action failed. matchId={}, npcUserId={}, turnNumber={}, step=PLAY_TO_STAGE, cardInstanceId={}",
                        matchId,
                        npcUserId,
                        turnNumber,
                        handHolomem,
                        ex
                    );
                }
            }
        }

        Long bloomCardInHand = findHandBloomCardInstance(matchId, npcUserId);
        if (bloomCardInHand != null) {
            Long bloomTarget = pickBloomTarget(matchId, npcUserId, bloomCardInHand, turnNumber);
            if (bloomTarget != null) {
                try {
                    BloomActionRequest bloom = new BloomActionRequest();
                    bloom.setBloomCardInstanceId(bloomCardInHand);
                    bloom.setTargetHolomemCardInstanceId(bloomTarget);
                    matchActionService.bloom(matchId, npcUserId, bloom);
                    return true;
                } catch (RuntimeException ex) {
                    log.warn(
                        "Hard NPC action failed. matchId={}, npcUserId={}, turnNumber={}, step=BLOOM, bloomCardInstanceId={}, targetCardInstanceId={}",
                        matchId,
                        npcUserId,
                        turnNumber,
                        bloomCardInHand,
                        bloomTarget,
                        ex
                    );
                }
            }
        }

        fresh = matchGameStateService.getGameStateForUser(matchId, npcUserId);
        centerCard = findZoneCardInstance(fresh, npcUserId, "CENTER");
        collabCard = findZoneCardInstance(fresh, npcUserId, "COLLAB");
        Long opponentTarget = pickPreferredOpponentTarget(fresh, npcUserId);
        for (Long attacker : new Long[] { centerCard, collabCard }) {
            if (attacker == null || !canUsePrimaryArt(matchId, npcUserId, attacker, turnNumber)) {
                continue;
            }
            try {
                AttackArtActionRequest attack = new AttackArtActionRequest();
                attack.setAttackerCardInstanceId(attacker);
                attack.setTargetCardInstanceId(opponentTarget);
                matchActionService.attackArt(matchId, npcUserId, attack);
                return true;
            } catch (RuntimeException ex) {
                log.warn(
                    "Hard NPC action failed. matchId={}, npcUserId={}, turnNumber={}, step=ATTACK_ART, attackerCardInstanceId={}, targetCardInstanceId={}",
                    matchId,
                    npcUserId,
                    turnNumber,
                    attacker,
                    opponentTarget,
                    ex
                );
            }
        }

        return tryEndTurn(matchId, npcUserId, turnNumber);
    }

    /**
     * 嘗試執行 END_TURN；失敗時只記錄並回傳 false，不丟例外中斷外層流程。
     */
    private boolean tryEndTurn(Long matchId, Long npcUserId, int turnNumber) {
        try {
            matchActionService.endTurn(matchId, npcUserId);
            return true;
        } catch (RuntimeException ex) {
            log.warn(
                "Hard NPC action failed. matchId={}, npcUserId={}, turnNumber={}, step=END_TURN",
                matchId,
                npcUserId,
                turnNumber,
                ex
            );
            return false;
        }
    }

    /**
     * 判斷指定回合是否已執行過某個 action type。
     */
    private boolean hasActionInTurn(Long matchId, Long userId, int turnNumber, String actionType) {
        if (matchId == null || userId == null || turnNumber <= 0) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = ?
            """,
            Integer.class,
            matchId,
            userId,
            turnNumber,
            normalize(actionType)
        );
        return count != null && count > 0;
    }

    /**
     * 判斷是否具備「發送回合 Cheer」前提：
     * 1) Cheer 牌庫仍有牌
     * 2) 場上至少有一張 Holomem 可附加
     */
    private boolean canSendTurnCheer(Long matchId, Long userId) {
        Integer cheerDeckCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CHEER_DECK'
            """,
            Integer.class,
            matchId,
            userId
        );
        if (cheerDeckCount == null || cheerDeckCount <= 0) {
            return false;
        }
        Integer stageHolomemCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB', 'BACK')
            """,
            Integer.class,
            matchId,
            userId
        );
        return stageHolomemCount != null && stageHolomemCount > 0;
    }

    /**
     * 判斷 BACK 區是否還有可放置空間（上限 5）。
     */
    private boolean hasBackCapacity(Long matchId, Long userId) {
        Integer backCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
            """,
            Integer.class,
            matchId,
            userId
        );
        return backCount == null || backCount < 5;
    }

    /**
     * 從對戰中解析 Hard NPC 的 userId。
     * 找不到 NPC 帳號或該對戰不包含 NPC 時回傳 null。
     */
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

    /**
     * 當 NPC 回合疑似卡住時，直接以資料庫狀態判斷並交棒給對手。
     * 這裡刻意不依賴 game-state 組裝，避免狀態查詢異常導致恢復失效。
     */
    private void forceAdvanceTurnIfNpcStuck(Long matchId, Long npcUserId) {
        // 防止 NPC 回合因例外中斷而卡住：直接以 matches 狀態判斷是否仍停在 NPC 回合。
        // 不依賴 getGameStateForUser，避免 game-state 組裝異常時連恢復流程也失效。
        var matchOpt = matchRepository.findByIdForUpdate(matchId);
        if (matchOpt.isEmpty()) {
            return;
        }
        var match = matchOpt.get();
        if (
            !"active".equalsIgnoreCase(normalize(match.getStatus())) ||
            !"STARTED".equalsIgnoreCase(normalize(match.getLobbyStatus())) ||
            !npcUserId.equals(match.getCurrentTurnPlayerId())
        ) {
            return;
        }
        Long opponentUserId = null;
        if (match.getPlayerAId() != null && !match.getPlayerAId().equals(npcUserId)) {
            opponentUserId = match.getPlayerAId();
        } else if (match.getPlayerBId() != null && !match.getPlayerBId().equals(npcUserId)) {
            opponentUserId = match.getPlayerBId();
        }
        if (opponentUserId == null || opponentUserId <= 0) {
            return;
        }

        int currentTurn = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        int nextTurn = currentTurn + 1;
        jdbcTemplate.update(
            """
            UPDATE matches
            SET current_turn_player_id = ?,
                turn_number = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND status = 'active'
              AND lobby_status = 'STARTED'
            """,
            opponentUserId,
            nextTurn,
            matchId
        );
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET skill_used_this_turn = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            opponentUserId
        );
        ensureTurnStartPendingInteraction(matchId, opponentUserId, nextTurn);
    }

    /**
     * 確保指定玩家有 TURN_START pending。
     * 若已有 PENDING 決策則不重複新增，避免互動重疊。
     */
    private void ensureTurnStartPendingInteraction(Long matchId, Long userId, int turnNumber) {
        if (matchId == null || userId == null || userId <= 0) {
            return;
        }
        Integer hasPending = jdbcTemplate.query(
            """
            SELECT CASE WHEN EXISTS (
                SELECT 1
                FROM match_pending_decisions
                WHERE match_id = ?
                  AND user_id = ?
                  AND status = 'PENDING'
            ) THEN 1 ELSE 0 END AS has_pending
            """,
            rs -> rs.next() ? rs.getInt("has_pending") : 0,
            matchId,
            userId
        );
        if (hasPending != null && hasPending > 0) {
            return;
        }

        String contextJson = """
            {"interactionType":"TURN_START","title":"回合開始","message":"現在是你的回合。請先確認，再由你手動執行抽牌與吶喊操作。","turnNumber":%d}
            """.formatted(Math.max(turnNumber, 1));
        jdbcTemplate.update(
            """
            INSERT INTO match_pending_decisions (
                match_id,
                user_id,
                decision_type,
                source_action_type,
                source_card_instance_id,
                source_card_id,
                effect_type,
                min_select,
                max_select,
                status,
                context_json
            ) VALUES (?, ?, ?, ?, NULL, NULL, ?, 1, 1, ?, CAST(? AS jsonb))
            """,
            matchId,
            userId,
            TURN_START_INTERACTION_TYPE,
            TURN_START_INTERACTION_TYPE,
            TURN_START_INTERACTION_TYPE,
            PENDING_STATUS,
            contextJson
        );
    }

    /**
     * SEND_CHEER 目標選擇策略：
     * 優先 CENTER，其次 BACK 中 HP 最高者，最後 COLLAB。
     */
    private Long pickPreferredHolomemCardInstanceId(GameStateResponse state, Long userId) {
        Long center = findZoneCardInstance(state, userId, "CENTER");
        if (center != null) {
            return center;
        }
        Long backHighestHp = findHighestHpCardInstanceInZone(state, userId, "BACK");
        if (backHighestHp != null) {
            return backHighestHp;
        }
        return findZoneCardInstance(state, userId, "COLLAB");
    }

    /**
     * 取得指定 zone 中目前 HP 最高的卡片實例 id。
     */
    private Long findHighestHpCardInstanceInZone(GameStateResponse state, Long userId, String zone) {
        PlayerZoneStateResponse player = findPlayerState(state, userId);
        if (player == null || player.getBoardZones() == null) {
            return null;
        }
        String normalizedZone = normalize(zone);
        for (var boardZone : player.getBoardZones()) {
            if (!normalizedZone.equals(normalize(boardZone.getZone()))) {
                continue;
            }
            if (boardZone.getCards() == null || boardZone.getCards().isEmpty()) {
                return null;
            }
            return boardZone.getCards().stream()
                .filter(card -> card.getCardInstanceId() != null && card.getCardInstanceId() > 0)
                .max(
                    Comparator.comparingInt((com.hololive.cardgame.dto.ZoneCardInstanceResponse card) ->
                        card.getCurrentHp() == null ? Integer.MIN_VALUE : card.getCurrentHp()
                    ).thenComparingInt(card -> card.getMaxHp() == null ? Integer.MIN_VALUE : card.getMaxHp())
                )
                .map(card -> card.getCardInstanceId())
                .orElse(null);
        }
        return null;
    }

    /**
     * 判斷是否屬於 NPC 可直接確認的互動類型。
     */
    private boolean isAutoResolveInteractionType(String type) {
        return TURN_START_INTERACTION_TYPE.equals(type) || DRAW_REVEAL_INTERACTION_TYPE.equals(type);
    }

    /**
     * 從指定玩家的指定 zone 取第一張卡片實例 id。
     */
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

    /**
     * 取得對手指定 zone 的第一張卡片實例 id。
     */
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

    /**
     * 依 CENTER -> COLLAB -> BACK 順序找任一對手 Holomem。
     */
    private Long resolveOpponentAnyHolomemCardInstance(GameStateResponse state, Long userId) {
        for (String zone : List.of("CENTER", "COLLAB", "BACK")) {
            Long cardInstanceId = resolveOpponentZoneCardInstance(state, userId, zone);
            if (cardInstanceId != null) {
                return cardInstanceId;
            }
        }
        return null;
    }

    /**
     * NPC 攻擊目標策略：
     * 1) 優先對手 CENTER
     * 2) 否則選擇對手場上目前 HP 最低者
     * 3) 再退回任一可用 Holomem
     */
    private Long pickPreferredOpponentTarget(GameStateResponse state, Long userId) {
        Long center = resolveOpponentZoneCardInstance(state, userId, "CENTER");
        if (center != null) {
            return center;
        }
        for (PlayerZoneStateResponse player : state.getPlayers()) {
            if (player == null || userId.equals(player.getUserId()) || player.getBoardZones() == null) {
                continue;
            }
            Long candidate = player.getBoardZones().stream()
                .filter(boardZone -> boardZone != null && boardZone.getCards() != null)
                .flatMap(boardZone -> boardZone.getCards().stream())
                .filter(card -> card != null && card.getCardInstanceId() != null && card.getCardInstanceId() > 0)
                .min(
                    Comparator.comparingInt((com.hololive.cardgame.dto.ZoneCardInstanceResponse card) ->
                        card.getCurrentHp() == null ? Integer.MAX_VALUE : card.getCurrentHp()
                    ).thenComparingLong(card -> card.getCardInstanceId() == null ? Long.MAX_VALUE : card.getCardInstanceId())
                )
                .map(card -> card.getCardInstanceId())
                .orElse(null);
            if (candidate != null) {
                return candidate;
            }
        }
        return resolveOpponentAnyHolomemCardInstance(state, userId);
    }

    /**
     * 從 GameState 中找出指定 user 的玩家狀態區塊。
     */
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

    /**
     * 從手牌選可直接上場的 Holomem（DEBUT/SPOT）。
     */
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

    /**
     * 從手牌選可用於 Bloom 的卡（FIRST/SECOND/BUZZ）。
     */
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

    /**
     * 根據手牌 Bloom 卡推導可疊放目標（DEBUT->FIRST->SECOND 鏈），並排除本回合剛上場目標。
     */
    private Long pickBloomTarget(Long matchId, Long userId, Long bloomCardInstanceId, int currentTurn) {
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
              AND (h.entered_turn_number IS NULL OR h.entered_turn_number < ?)
            ORDER BY CASE h.zone WHEN 'CENTER' THEN 0 WHEN 'COLLAB' THEN 1 ELSE 2 END, h.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong(1) : null,
            matchId,
            userId,
            requiredTargetLevel,
            Math.max(currentTurn, 1)
        );
    }

    /**
     * 判斷指定攻擊者是否可合法發動主藝能。
     * 檢查包含：
     * - 回合限制（第一回合不可）
     * - 區位與休息狀態
     * - 本回合該區位是否已攻擊
     * - 藝能費用是否可支付
     */
    private boolean canUsePrimaryArt(Long matchId, Long userId, Long attackerCardInstanceId, int turnNumber) {
        if (matchId == null || userId == null || attackerCardInstanceId == null || attackerCardInstanceId <= 0) {
            return false;
        }
        if (turnNumber <= 1) {
            return false;
        }
        Map<String, Object> attacker = jdbcTemplate.query(
            """
            SELECT h.id AS holomem_id, h.zone, h.is_rested, h.card_id
            FROM match_holomems h
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("holomem_id", rs.getLong("holomem_id"));
                row.put("zone", rs.getString("zone"));
                row.put("is_rested", rs.getObject("is_rested"));
                row.put("card_id", rs.getString("card_id"));
                return row;
            },
            matchId,
            userId,
            attackerCardInstanceId
        );
        if (attacker == null) {
            return false;
        }
        String zone = normalize((String) attacker.get("zone"));
        if (!Set.of("CENTER", "COLLAB").contains(zone) || toBoolean(attacker.get("is_rested"))) {
            return false;
        }
        if (hasUsedArtInZoneThisTurn(matchId, userId, turnNumber, zone)) {
            return false;
        }
        Long holomemId = asLong(attacker.get("holomem_id"));
        String cardId = (String) attacker.get("card_id");
        if (holomemId == null || holomemId <= 0 || cardId == null || cardId.isBlank()) {
            return false;
        }
        String costJson = jdbcTemplate.query(
            """
            SELECT ma.cost_cheer_json::text
            FROM member_arts ma
            WHERE ma.member_card_id = ?
            ORDER BY ma.order_index ASC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString(1) : null,
            cardId
        );
        return canPayArtCost(holomemId, costJson);
    }

    /**
     * 判斷本回合某區位（CENTER/COLLAB）是否已執行 ATTACK_ART。
     */
    private boolean hasUsedArtInZoneThisTurn(Long matchId, Long userId, int turnNumber, String zone) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions ma
            WHERE ma.match_id = ?
              AND ma.user_id = ?
              AND ma.turn_number = ?
              AND ma.action_type = 'ATTACK_ART'
              AND ma.payload::text LIKE ?
            """,
            Integer.class,
            matchId,
            userId,
            turnNumber,
            "%\"attackerZone\":\"" + normalize(zone) + "\"%"
        );
        return count != null && count > 0;
    }

    /**
     * 驗證主藝能費用是否可由目前附加 Cheer 支付。
     * 規則：先滿足有色，再用剩餘數量支付無色。
     */
    private boolean canPayArtCost(Long matchHolomemId, String costJson) {
        if (matchHolomemId == null || matchHolomemId <= 0) {
            return false;
        }
        Map<String, Integer> required = parseCostMap(costJson);
        if (required.isEmpty()) {
            return true;
        }
        Map<String, Integer> available = jdbcTemplate.query(
            """
            SELECT cc.color, COUNT(*) AS cnt
            FROM match_holomem_cheers mhc
            JOIN cheer_cards cc ON cc.card_id = mhc.cheer_card_id
            WHERE mhc.match_holomem_id = ?
            GROUP BY cc.color
            """,
            rs -> {
                Map<String, Integer> rows = new LinkedHashMap<>();
                while (rs.next()) {
                    rows.put(normalize(rs.getString("color")), rs.getInt("cnt"));
                }
                return rows;
            },
            matchHolomemId
        );
        int totalAvailable = available.values().stream().mapToInt(Integer::intValue).sum();
        int usedColored = 0;
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            String color = normalize(entry.getKey());
            if ("COLORLESS".equals(color)) {
                continue;
            }
            int req = Math.max(entry.getValue(), 0);
            int have = Math.max(available.getOrDefault(color, 0), 0);
            if (have < req) {
                return false;
            }
            usedColored += req;
        }
        int requiredColorless = Math.max(required.getOrDefault("COLORLESS", 0), 0);
        return totalAvailable - usedColored >= requiredColorless;
    }

    /**
     * 將 cost_cheer_json 解析為 {COLOR -> requiredCount}。
     * 解析失敗時回傳不可支付的保守值，避免 NPC 誤判可行動。
     */
    private Map<String, Integer> parseCostMap(String costJson) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (costJson == null || costJson.isBlank()) {
            return result;
        }
        try {
            JsonNode node = objectMapper.readTree(costJson);
            if (node == null || !node.isObject()) {
                return result;
            }
            node.fields().forEachRemaining(entry -> {
                int value = entry.getValue() == null ? 0 : Math.max(entry.getValue().asInt(0), 0);
                if (value > 0) {
                    result.put(normalize(entry.getKey()), value);
                }
            });
        } catch (Exception ignored) {
            // keep safe default: unknown format -> treat as unpayable by NPC
            return Map.of("COLORLESS", Integer.MAX_VALUE);
        }
        return result;
    }

    /**
     * 寬鬆轉型：將任意值轉為 Long，無法解析則回傳 null。
     */
    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 寬鬆轉型：支援 Boolean / "true" / "t" / "1"。
     */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "t".equalsIgnoreCase(text) || "1".equals(text);
    }

    /**
     * 字串正規化：trim + upper case；null 轉空字串。
     */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
