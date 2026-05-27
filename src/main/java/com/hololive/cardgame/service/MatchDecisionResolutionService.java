package com.hololive.cardgame.service;

import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
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

    private static final String DECISION_TYPE_DRAW_REVEAL = "DRAW_REVEAL";
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

    MatchDecisionResolutionService(
        JdbcTemplate jdbcTemplate,
        PendingDecisionStore pendingDecisionStore,
        MatchRepository matchRepository,
        MatchActionRepository matchActionRepository,
        MatchPayloadJsonService matchPayloadJsonService,
        InteractionConfirmedPayloadBuilder interactionConfirmedPayloadBuilder,
        MatchTimestampService matchTimestampService,
        MatchTurnLifecycleService matchTurnLifecycleService,
        MainStepGiftFollowupPayloadAppender mainStepGiftFollowupPayloadAppender
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
        if (DECISION_TYPE_DRAW_REVEAL.equals(decisionType)) {
            resolveDrawRevealDecision(matchId, userId, turnNumber, match, pending);
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
