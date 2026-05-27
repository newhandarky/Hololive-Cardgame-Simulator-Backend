package com.hololive.cardgame.service;

import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.game.action.SendCheerAction;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

class MatchDecisionResolutionService {

    private static final String ACTION_TYPE_TURN_CHEER = "TURN_CHEER";
    private static final String DECISION_TYPE_LIVE_START = "LIVE_START";
    private static final String DECISION_TYPE_DRAW_REVEAL = "DRAW_REVEAL";
    private static final String DECISION_TYPE_SEND_CHEER = "SEND_CHEER";
    private static final String DECISION_TYPE_LOOK_TOP_DECK = "LOOK_TOP_DECK";
    private static final String DECISION_TYPE_LOOK_OPPONENT_HAND = "LOOK_OPPONENT_HAND";
    private static final String DECISION_TYPE_LOOK_HOLOPOWER = "LOOK_HOLOPOWER";
    private static final String DECISION_TYPE_REORDER_DECK_BOTTOM = "REORDER_DECK_BOTTOM";

    private final JdbcTemplate jdbcTemplate;
    private final PendingDecisionStore pendingDecisionStore;
    private final MatchRepository matchRepository;
    private final MatchActionRepository matchActionRepository;
    private final MatchPayloadJsonService matchPayloadJsonService;
    private final InteractionConfirmedPayloadBuilder interactionConfirmedPayloadBuilder;
    private final MatchTimestampService matchTimestampService;
    private final MatchTurnLifecycleService matchTurnLifecycleService;
    private final MainStepGiftFollowupPayloadAppender mainStepGiftFollowupPayloadAppender;
    private final GameActionExecutor gameActionExecutor;
    private final SendCheerInteractionPayloadBuilder sendCheerInteractionPayloadBuilder;

    MatchDecisionResolutionService(
        JdbcTemplate jdbcTemplate,
        PendingDecisionStore pendingDecisionStore,
        MatchRepository matchRepository,
        MatchActionRepository matchActionRepository,
        MatchPayloadJsonService matchPayloadJsonService,
        InteractionConfirmedPayloadBuilder interactionConfirmedPayloadBuilder,
        MatchTimestampService matchTimestampService,
        MatchTurnLifecycleService matchTurnLifecycleService,
        MainStepGiftFollowupPayloadAppender mainStepGiftFollowupPayloadAppender,
        GameActionExecutor gameActionExecutor,
        SendCheerInteractionPayloadBuilder sendCheerInteractionPayloadBuilder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.pendingDecisionStore = pendingDecisionStore;
        this.matchRepository = matchRepository;
        this.matchActionRepository = matchActionRepository;
        this.matchPayloadJsonService = matchPayloadJsonService;
        this.interactionConfirmedPayloadBuilder = interactionConfirmedPayloadBuilder;
        this.matchTimestampService = matchTimestampService;
        this.matchTurnLifecycleService = matchTurnLifecycleService;
        this.mainStepGiftFollowupPayloadAppender = mainStepGiftFollowupPayloadAppender;
        this.gameActionExecutor = gameActionExecutor;
        this.sendCheerInteractionPayloadBuilder = sendCheerInteractionPayloadBuilder;
    }

    boolean resolveLowCouplingDecision(
        Long matchId,
        Long userId,
        int turnNumber,
        MatchEntity match,
        PendingDecision pending,
        ResolveDecisionRequest request
    ) {
        String decisionType = MatchEffectValueHelper.normalize(pending == null ? null : pending.decisionType());
        if (DECISION_TYPE_LIVE_START.equals(decisionType)) {
            resolveLiveStartDecision(userId, turnNumber, match, pending);
            return true;
        }
        if (DECISION_TYPE_DRAW_REVEAL.equals(decisionType)) {
            resolveDrawRevealDecision(matchId, userId, turnNumber, match, pending);
            return true;
        }
        if (DECISION_TYPE_SEND_CHEER.equals(decisionType)) {
            resolveSendCheerDecision(matchId, userId, turnNumber, match, pending, request);
            return true;
        }
        if (DECISION_TYPE_LOOK_TOP_DECK.equals(decisionType)) {
            resolveLookTopDeckDecision(matchId, userId, turnNumber, match, pending, request);
            return true;
        }
        if (DECISION_TYPE_LOOK_OPPONENT_HAND.equals(decisionType) || DECISION_TYPE_LOOK_HOLOPOWER.equals(decisionType)) {
            resolveLookZoneDecision(userId, turnNumber, match, pending, decisionType);
            return true;
        }
        if (DECISION_TYPE_REORDER_DECK_BOTTOM.equals(decisionType)) {
            resolveReorderDeckBottomDecision(matchId, userId, turnNumber, match, pending, request);
            return true;
        }
        return false;
    }

    private void resolveLiveStartDecision(
        Long userId,
        int turnNumber,
        MatchEntity match,
        PendingDecision pending
    ) {
        pendingDecisionStore.markResolved(pending.decisionId());
        matchTurnLifecycleService.confirmLiveStartDecision(
            match,
            userId,
            turnNumber,
            pending.decisionId()
        );
    }

    private void resolveDrawRevealDecision(
        Long matchId,
        Long userId,
        int turnNumber,
        MatchEntity match,
        PendingDecision pending
    ) {
        pendingDecisionStore.markResolved(pending.decisionId());
        boolean requiresTurnCheer = canPerformTurnCheerAction(matchId, userId);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!requiresTurnCheer) {
            mainStepGiftFollowupPayloadAppender.append(payload, matchId, userId, turnNumber);
        }
        matchTurnLifecycleService.confirmDrawRevealDecision(
            match,
            userId,
            turnNumber,
            pending.decisionId(),
            requiresTurnCheer ? MatchPhase.CHEER : MatchPhase.MAIN,
            pending.sourceCardInstanceId(),
            pending.sourceCardId(),
            payload
        );
    }

    private void resolveSendCheerDecision(
        Long matchId,
        Long userId,
        int turnNumber,
        MatchEntity match,
        PendingDecision pending,
        ResolveDecisionRequest request
    ) {
        List<Long> selectedCardInstanceIds = sanitizeSelectedCardInstanceIds(
            request == null ? null : request.getSelectedCardInstanceIds()
        );
        if (selectedCardInstanceIds.size() < pending.minSelect()) {
            throw new IllegalArgumentException("選擇卡片數量不足，至少需要 " + pending.minSelect() + " 張");
        }
        if (selectedCardInstanceIds.size() > pending.maxSelect()) {
            throw new IllegalArgumentException("選擇卡片數量超過上限，最多只能選 " + pending.maxSelect() + " 張");
        }
        validateSelectedCardsWithinCandidates(selectedCardInstanceIds, pending.candidateCardInstanceIds());
        Long targetHolomemCardInstanceId = selectedCardInstanceIds.get(0);
        Long targetHolomemId = jdbcTemplate.query(
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
            userId,
            targetHolomemCardInstanceId
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("指定的 Holomem 不存在或已離場");
        }
        Long sourceCardInstanceId = pending.sourceCardInstanceId();
        if (sourceCardInstanceId == null || sourceCardInstanceId <= 0) {
            throw new IllegalStateException("待處理吶喊互動缺少來源卡");
        }
        Map<String, Object> sourceCard = loadOwnedCardInstance(matchId, userId, sourceCardInstanceId);
        String sourceZone = MatchEffectValueHelper.normalize(sourceCard.get("zone"));
        if (!Set.of("CHEER_DECK", "ARCHIVE", "HAND").contains(sourceZone)) {
            throw new IllegalStateException("來源 Cheer 已失效，請重新整理狀態");
        }
        String cheerCardId = MatchEffectValueHelper.asText(sourceCard.get("card_id"));
        Integer cheerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM cheer_cards WHERE card_id = ?",
            Integer.class,
            cheerCardId
        );
        if (cheerCount == null || cheerCount <= 0) {
            throw new IllegalStateException("來源卡不是 Cheer 卡");
        }
        EffectContext effectContext = new EffectContext(
            matchId,
            userId,
            turnNumber,
            pending.sourceActionType(),
            sourceCardInstanceId,
            cheerCardId
        );
        SendCheerAction sendCheerAction = new SendCheerAction(
            sourceCardInstanceId,
            targetHolomemId,
            pending.sourceActionType()
        );
        List<ActionResult> actionResults = gameActionExecutor.execute(effectContext, List.of(sendCheerAction));
        if (actionResults.isEmpty() || !actionResults.get(0).success()) {
            String reason = actionResults.isEmpty()
                ? "UNKNOWN"
                : MatchEffectValueHelper.asText(actionResults.get(0).details().get("reason"));
            throw new IllegalStateException("發送吶喊失敗：" + reason);
        }
        pendingDecisionStore.markResolved(pending.decisionId());

        match.setCurrentPhase(resolvePhaseAfterSendCheer(parseMatchPhase(match), pending.sourceActionType()).name());
        matchTimestampService.touchUpdatedAt(match);
        matchRepository.saveAndFlush(match);

        Map<String, Object> payload = sendCheerInteractionPayloadBuilder.buildInteractionConfirmedPayload(
            pending.decisionId(),
            pending.sourceActionType(),
            sourceCardInstanceId,
            cheerCardId,
            targetHolomemCardInstanceId
        );
        if (ACTION_TYPE_TURN_CHEER.equals(pending.sourceActionType())) {
            mainStepGiftFollowupPayloadAppender.append(payload, matchId, userId, turnNumber);
        }
        appendAction(match, userId, "INTERACTION_CONFIRMED", toJson(payload), turnNumber);
        if (!ACTION_TYPE_TURN_CHEER.equals(pending.sourceActionType())) {
            return;
        }
        Map<String, Object> turnCheerPayload = sendCheerInteractionPayloadBuilder.buildTurnCheerActionPayload(
            sourceCardInstanceId,
            cheerCardId,
            targetHolomemCardInstanceId
        );
        appendAction(match, userId, ACTION_TYPE_TURN_CHEER, toJson(turnCheerPayload), turnNumber);
    }

    private void resolveLookTopDeckDecision(
        Long matchId,
        Long userId,
        int turnNumber,
        MatchEntity match,
        PendingDecision pending,
        ResolveDecisionRequest request
    ) {
        String requestedPlacement = normalizeDecisionPlacement(request == null ? null : request.getPlacement());
        List<Long> selectedCardInstanceIds = sanitizeSelectedCardInstanceIds(
            request == null ? null : request.getSelectedCardInstanceIds()
        );
        if (requestedPlacement != null) {
            if ("TOP".equals(requestedPlacement)) {
                Long lookedCardInstanceId = pending.candidateCardInstanceIds().isEmpty()
                    ? null
                    : pending.candidateCardInstanceIds().get(0);
                selectedCardInstanceIds = lookedCardInstanceId == null
                    ? List.of()
                    : List.of(lookedCardInstanceId);
            } else if ("BOTTOM".equals(requestedPlacement)) {
                selectedCardInstanceIds = List.of();
            } else {
                throw new IllegalArgumentException("placement 只支援 TOP 或 BOTTOM");
            }
        }
        if (selectedCardInstanceIds.size() > pending.maxSelect()) {
            throw new IllegalArgumentException("選擇卡片數量超過上限，最多只能選 " + pending.maxSelect() + " 張");
        }
        validateSelectedCardsWithinCandidates(selectedCardInstanceIds, pending.candidateCardInstanceIds());
        Long lookedCardInstanceId = pending.candidateCardInstanceIds().isEmpty()
            ? null
            : pending.candidateCardInstanceIds().get(0);
        boolean keepOnTop = lookedCardInstanceId != null && selectedCardInstanceIds.contains(lookedCardInstanceId);
        if (lookedCardInstanceId != null && !keepOnTop) {
            moveDeckCardToBottom(matchId, userId, lookedCardInstanceId);
        }
        pendingDecisionStore.markResolved(pending.decisionId());

        transitionMatchToMainAndSave(match);

        Map<String, Object> payload = interactionConfirmedPayloadBuilder.buildLookTopDeckPayload(
            pending.decisionId(),
            DECISION_TYPE_LOOK_TOP_DECK,
            pending.sourceActionType(),
            lookedCardInstanceId,
            keepOnTop
        );
        appendAction(match, userId, "INTERACTION_CONFIRMED", toJson(payload), turnNumber);
    }

    private void resolveLookZoneDecision(
        Long userId,
        int turnNumber,
        MatchEntity match,
        PendingDecision pending,
        String decisionType
    ) {
        pendingDecisionStore.markResolved(pending.decisionId());

        transitionMatchToMainAndSave(match);

        Map<String, Object> payload = interactionConfirmedPayloadBuilder.buildLookZonePayload(
            pending.decisionId(),
            decisionType,
            pending.sourceActionType(),
            pending.candidateCardInstanceIds().size()
        );
        appendAction(match, userId, "INTERACTION_CONFIRMED", toJson(payload), turnNumber);
    }

    private void resolveReorderDeckBottomDecision(
        Long matchId,
        Long userId,
        int turnNumber,
        MatchEntity match,
        PendingDecision pending,
        ResolveDecisionRequest request
    ) {
        List<Long> selectedCardInstanceIds = sanitizeSelectedCardInstanceIds(
            request == null ? null : request.getSelectedCardInstanceIds()
        );
        List<Long> candidateCardInstanceIds = pending.candidateCardInstanceIds();
        List<Long> orderedCardInstanceIds = selectedCardInstanceIds.isEmpty()
            ? candidateCardInstanceIds
            : selectedCardInstanceIds;
        validateDeckBottomReorderSelection(orderedCardInstanceIds, candidateCardInstanceIds);
        for (Long cardInstanceId : orderedCardInstanceIds) {
            moveDeckCardToBottom(matchId, userId, cardInstanceId);
        }

        pendingDecisionStore.markResolved(pending.decisionId());
        transitionMatchToMainAndSave(match);

        Map<String, Object> payload = interactionConfirmedPayloadBuilder.buildReorderDeckBottomPayload(
            pending.decisionId(),
            DECISION_TYPE_REORDER_DECK_BOTTOM,
            pending.sourceActionType(),
            orderedCardInstanceIds
        );
        appendAction(match, userId, "INTERACTION_CONFIRMED", toJson(payload), turnNumber);
    }

    private Map<String, Object> loadOwnedCardInstance(Long matchId, Long userId, Long cardInstanceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT id, card_id, zone
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            cardInstanceId,
            matchId,
            userId
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("找不到指定卡片實例");
        }
        return rows.get(0);
    }

    private MatchPhase resolvePhaseAfterSendCheer(MatchPhase currentPhase, String sourceActionType) {
        if (ACTION_TYPE_TURN_CHEER.equals(MatchEffectValueHelper.normalize(sourceActionType))) {
            return MatchPhase.MAIN;
        }
        return currentPhase == null ? MatchPhase.MAIN : currentPhase;
    }

    private MatchPhase parseMatchPhase(MatchEntity match) {
        String phase = MatchEffectValueHelper.normalize(match == null ? null : match.getCurrentPhase());
        if (!StringUtils.hasText(phase)) {
            return null;
        }
        try {
            return MatchPhase.valueOf(phase);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean canPerformTurnCheerAction(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return false;
        }
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

    private void moveDeckCardToBottom(Long matchId, Long userId, Long cardInstanceId) {
        if (matchId == null || userId == null || cardInstanceId == null || cardInstanceId <= 0) {
            return;
        }
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            matchId,
            userId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET order_index = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            nextOrder == null ? 1 : nextOrder,
            cardInstanceId,
            matchId,
            userId
        );
    }

    private void validateDeckBottomReorderSelection(List<Long> orderedCardInstanceIds, List<Long> candidateCardInstanceIds) {
        List<Long> ordered = orderedCardInstanceIds == null ? List.of() : orderedCardInstanceIds;
        List<Long> candidates = candidateCardInstanceIds == null ? List.of() : candidateCardInstanceIds;
        if (ordered.size() != candidates.size()) {
            throw new IllegalArgumentException("排序卡片數量不符，需包含全部候選卡");
        }
        Set<Long> candidateSet = new LinkedHashSet<>(candidates);
        Set<Long> orderedSet = new LinkedHashSet<>(ordered);
        if (orderedSet.size() != ordered.size()) {
            throw new IllegalArgumentException("排序卡片包含重複 cardInstanceId");
        }
        if (!orderedSet.equals(candidateSet)) {
            throw new IllegalArgumentException("排序卡片必須完整且僅包含候選卡");
        }
    }

    private String normalizeDecisionPlacement(String placement) {
        if (!StringUtils.hasText(placement)) {
            return null;
        }
        return placement.trim().toUpperCase(Locale.ROOT);
    }

    private List<Long> sanitizeSelectedCardInstanceIds(List<Long> selectedCardInstanceIds) {
        if (selectedCardInstanceIds == null || selectedCardInstanceIds.isEmpty()) {
            return List.of();
        }
        List<Long> normalized = new ArrayList<>();
        for (Long value : selectedCardInstanceIds) {
            if (value == null || value <= 0 || normalized.contains(value)) {
                continue;
            }
            normalized.add(value);
        }
        return normalized;
    }

    private void validateSelectedCardsWithinCandidates(List<Long> selected, List<Long> candidates) {
        if (selected == null || selected.isEmpty() || candidates == null || candidates.isEmpty()) {
            return;
        }
        Set<Long> candidateSet = Set.copyOf(candidates);
        for (Long selectedId : selected) {
            if (!candidateSet.contains(selectedId)) {
                throw new IllegalArgumentException("選擇的卡片不在候選清單內: " + selectedId);
            }
        }
    }

    private MatchActionEntity appendAction(
        MatchEntity match,
        Long userId,
        String actionType,
        String payload,
        int turnNumber
    ) {
        MatchActionEntity action = new MatchActionEntity();
        action.setMatchId(match.getId());
        action.setUserId(userId);
        action.setActionType(actionType);
        action.setPayload(payload);
        action.setTurnNumber(turnNumber);
        action.setActionOrder(matchActionRepository.findMaxActionOrderByTurn(match.getId(), turnNumber) + 1);
        action.setExecutedAt(LocalDateTime.now());
        return matchActionRepository.save(action);
    }

    private void transitionMatchToMainAndSave(MatchEntity match) {
        match.setCurrentPhase(MatchPhase.MAIN.name());
        matchTimestampService.touchUpdatedAt(match);
        matchRepository.saveAndFlush(match);
    }

    private String toJson(Map<String, Object> payload) {
        return matchPayloadJsonService.toJson(payload);
    }
}
