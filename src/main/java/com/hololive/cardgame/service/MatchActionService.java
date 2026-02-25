package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.dto.AttachCheerActionRequest;
import com.hololive.cardgame.dto.AttackArtActionRequest;
import com.hololive.cardgame.dto.BatonTouchActionRequest;
import com.hololive.cardgame.dto.BloomActionRequest;
import com.hololive.cardgame.dto.MulliganActionRequest;
import com.hololive.cardgame.dto.MoveStageHolomemActionRequest;
import com.hololive.cardgame.dto.PlaySupportActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.dto.UseOshiSkillActionRequest;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.entity.MatchPlayerEntity;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MatchActionService {

    private static final Set<String> PLAY_TO_STAGE_ZONES = Set.of("CENTER", "BACK");
    private static final Set<String> CHEER_SOURCE_ZONES = Set.of("HAND", "CHEER_DECK");
    private static final String SUPPORT_DECISION_TYPE_CARD_SELECTION = "CARD_SELECTION";
    private static final String INTERACTION_TYPE_TURN_START = "TURN_START";
    private static final String INTERACTION_TYPE_DRAW_REVEAL = "DRAW_REVEAL";
    private static final String INTERACTION_TYPE_SEND_CHEER = "SEND_CHEER";
    private static final String DECISION_TYPE_LOOK_TOP_DECK = "LOOK_TOP_DECK";
    private static final String ACTION_TYPE_DRAW_TURN = "DRAW_TURN";
    private static final String ACTION_TYPE_TURN_CHEER = "TURN_CHEER";
    private static final String ACTION_TYPE_USE_OSHI_SKILL = "USE_OSHI_SKILL";
    private static final String ACTION_TYPE_BATON_TOUCH = "BATON_TOUCH";
    private static final String PENDING_STATUS = "PENDING";
    private static final String SUPPORT_TYPE_MASCOT = "MASCOT";
    private static final String SUPPORT_TYPE_TOOL = "TOOL";
    private static final String SUPPORT_TYPE_FAN = "FAN";
    private static final String SUPPORT_TYPE_OTHER = "OTHER";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern ART_CRITICAL_PATTERN = Pattern.compile("([赤青黄緑紫白])\\s*[+＋]\\s*(\\d+)");
    private static final int OPENING_HAND_SIZE = 7;

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final MatchActionRepository matchActionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MatchEffectService matchEffectService;
    private final MatchEventHookService matchEventHookService;
    private final SecureRandom random = new SecureRandom();

    public MatchActionService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        MatchActionRepository matchActionRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchEffectService matchEffectService,
        MatchEventHookService matchEventHookService
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.matchActionRepository = matchActionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.matchEffectService = matchEffectService;
        this.matchEventHookService = matchEventHookService;
    }

    @Transactional
    public void playToStage(Long matchId, Long userId, PlayToStageActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        Long cardInstanceId = requirePositiveId(request == null ? null : request.getCardInstanceId(), "cardInstanceId");
        String targetZone = normalizeZone(request == null ? null : request.getTargetZone());
        if (!PLAY_TO_STAGE_ZONES.contains(targetZone)) {
            throw new IllegalArgumentException("targetZone 只支援 CENTER 或 BACK");
        }

        Map<String, Object> card = loadOwnedCardInstance(matchId, userId, cardInstanceId);
        String sourceZone = normalizeZone(card.get("zone"));
        if (!"HAND".equals(sourceZone)) {
            throw new IllegalStateException("只能從手牌打出 Holomen");
        }

        String cardId = asString(card.get("card_id"));
        String levelType = jdbcTemplate.query(
            "SELECT level_type FROM member_cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getString("level_type") : null,
            cardId
        );
        if (!StringUtils.hasText(levelType)) {
            throw new IllegalStateException("只有 MEMBER 卡可以打到場上");
        }
        String normalizedLevelType = normalizeLevel(levelType);
        if (!Set.of("DEBUT", "SPOT").contains(normalizedLevelType)) {
            throw new GameRuleException(
                GameErrorCode.PLAY_TO_STAGE_LEVEL_NOT_ALLOWED,
                "只有 DEBUT 或 SPOT Holomem 可以從手牌放置到場上；FIRST/SECOND/BUZZ 請改用 BLOOM"
            );
        }
        if (!"BACK".equals(targetZone)) {
            throw new IllegalStateException("手牌 Holomem 只能放置到 BACK");
        }

        int occupiedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomems WHERE match_id = ? AND owner_user_id = ? AND zone = ?",
            Integer.class,
            matchId,
            userId,
            targetZone
        );
        if ("BACK".equals(targetZone) && occupiedCount >= 5) {
            throw new IllegalStateException("BACK 已滿（最多 5 張）");
        }

        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            cardInstanceId,
            matchId,
            userId
        );
        if (updated != 1) {
            throw new IllegalStateException("打牌失敗，請重新整理對戰狀態");
        }

        Long matchHolomemId = jdbcTemplate.query(
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
            userId,
            cardInstanceId,
            cardId,
            targetZone,
            normalizeLevel(levelType),
            context.turnNumber
        );
        if (matchHolomemId == null) {
            throw new IllegalStateException("建立場上 Holomen 失敗");
        }
        recordHolomemStackCard(matchHolomemId, cardInstanceId);

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> triggerSummary = matchEventHookService.onHolomemEnter(
            matchId,
            userId,
            cardId,
            cardInstanceId,
            targetZone
        );

        appendAction(
            context.match,
            userId,
            "PLAY_TO_STAGE",
            toJson(
                Map.of(
                    "cardInstanceId", cardInstanceId,
                    "cardId", cardId,
                    "targetZone", targetZone,
                    "enteredTurn", context.turnNumber,
                    "triggerSummary", triggerSummary
                )
            ),
            context.turnNumber
        );
    }

    @Transactional
    public void bloom(Long matchId, Long userId, BloomActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        Long bloomCardInstanceId = requirePositiveId(
            request == null ? null : request.getBloomCardInstanceId(),
            "bloomCardInstanceId"
        );
        Long targetHolomemCardInstanceId = requirePositiveId(
            request == null ? null : request.getTargetHolomemCardInstanceId(),
            "targetHolomemCardInstanceId"
        );

        Map<String, Object> bloomCard = loadOwnedCardInstance(matchId, userId, bloomCardInstanceId);
        String sourceZone = normalizeZone(bloomCard.get("zone"));
        if (!"HAND".equals(sourceZone)) {
            throw new IllegalStateException("BLOOM 卡必須從手牌使用");
        }
        String bloomCardId = asString(bloomCard.get("card_id"));
        Map<String, Object> bloomCardSpec = loadMemberCardSpec(bloomCardId);
        if (bloomCardSpec == null) {
            throw new IllegalStateException("只有 MEMBER 卡可以執行 BLOOM");
        }
        String bloomCardName = asString(bloomCardSpec.get("name"));
        String bloomLevel = normalizeLevel(asString(bloomCardSpec.get("level_type")));
        int bloomHp = asInt(bloomCardSpec.get("hp"));
        if (isSpecialOrUnbloomableLevel(bloomLevel)) {
            throw new IllegalStateException("此卡不可作為 BLOOM 卡");
        }
        if (bloomHp <= 0) {
            throw new IllegalStateException("BLOOM 卡片缺少有效 HP");
        }

        BloomTarget target = loadOwnedBloomTarget(matchId, userId, targetHolomemCardInstanceId);
        if (target == null) {
            throw new GameRuleException(GameErrorCode.BLOOM_NO_TARGET, "找不到要 BLOOM 的目標 Holomem");
        }
        if (isSpecialOrUnbloomableLevel(target.topLevelType())) {
            throw new GameRuleException(GameErrorCode.BLOOM_INVALID_TARGET, "Spot Holomem 不能作為 BLOOM 目標");
        }
        if (target.enteredTurnNumber() == context.turnNumber) {
            throw new GameRuleException(GameErrorCode.BLOOM_INVALID_TARGET, "本回合剛上場的 Holomem 不能 BLOOM");
        }
        Long extraBloomAllowanceId = null;
        if (target.lastBloomTurn() != null && target.lastBloomTurn() == context.turnNumber) {
            extraBloomAllowanceId = findExtraBloomAllowanceId(
                matchId,
                userId,
                context.turnNumber,
                target.holomemId()
            );
            if (extraBloomAllowanceId == null) {
                throw new GameRuleException(GameErrorCode.BLOOM_INVALID_TARGET, "此 Holomem 本回合已執行過 BLOOM");
            }
        }
        if (!StringUtils.hasText(target.topCardName()) || !target.topCardName().equals(bloomCardName)) {
            throw new GameRuleException(GameErrorCode.BLOOM_INVALID_TARGET, "BLOOM 需要與目標 Holomem 同名");
        }
        if (!isBloomLevelNextStep(target.topLevelType(), bloomLevel)) {
            throw new GameRuleException(
                GameErrorCode.BLOOM_INVALID_TARGET,
                "BLOOM 只能依序遞進：DEBUT→FIRST、FIRST→SECOND、SECOND→BUZZ"
            );
        }
        if (bloomHp < target.damageTaken()) {
            throw new GameRuleException(GameErrorCode.BLOOM_INVALID_TARGET, "BLOOM 卡 HP 不足以承受目標目前傷害");
        }

        int moved = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            bloomCardInstanceId,
            matchId,
            userId
        );
        if (moved != 1) {
            throw new IllegalStateException("BLOOM 失敗：卡片移動異常");
        }

        recordHolomemStackCard(target.holomemId(), bloomCardInstanceId);
        int updated = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET match_card_id = ?,
                card_id = ?,
                current_level = ?,
                last_bloom_turn = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            bloomCardInstanceId,
            bloomCardId,
            bloomLevel,
            context.turnNumber,
            target.holomemId(),
            matchId,
            userId
        );
        if (updated != 1) {
            throw new IllegalStateException("BLOOM 失敗：目標 Holomem 更新異常");
        }
        if (extraBloomAllowanceId != null) {
            consumeExtraBloomAllowance(extraBloomAllowanceId, matchId, userId);
        }

        int stackDepth = countHolomemStackDepth(target.holomemId());
        Map<String, Object> bloomEffectSummary = matchEffectService.applyBloomTriggeredEffects(
            matchId,
            userId,
            bloomCardId,
            bloomCardInstanceId
        );
        Map<String, Object> triggerSummary = matchEventHookService.onHolomemBloom(
            matchId,
            userId,
            bloomCardId,
            bloomCardInstanceId,
            targetHolomemCardInstanceId,
            target.zone()
        );

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        payload.put("fromCardId", target.topCardId());
        payload.put("fromLevel", target.topLevelType());
        payload.put("toCardInstanceId", bloomCardInstanceId);
        payload.put("toCardId", bloomCardId);
        payload.put("toLevel", bloomLevel);
        payload.put("damageCarried", target.damageTaken());
        payload.put("stackDepth", stackDepth);
        payload.put("bloomEffect", bloomEffectSummary);
        payload.put("triggerSummary", triggerSummary);
        Long bloomLookTopDeckDecisionId = createLookTopDeckPendingDecisionIfNeeded(
            matchId,
            userId,
            "BLOOM",
            bloomCardInstanceId,
            bloomCardId,
            "LOOK_TOP_DECK",
            bloomEffectSummary
        );
        if (bloomLookTopDeckDecisionId != null) {
            payload.put("pendingLookTopDeckDecisionId", bloomLookTopDeckDecisionId);
        }

        appendAction(
            context.match,
            userId,
            "BLOOM",
            toJson(payload),
            context.turnNumber
        );
        if (evaluateCardEffectMatchFinish(context.match, userId, context.turnNumber, bloomEffectSummary)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (hasLifeReduced(bloomEffectSummary) && evaluateLifeDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (
            hasHolomemDowned(bloomEffectSummary) &&
            evaluateNoHolomemDefeat(context.match, userId, context.turnNumber)
        ) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        }
        enqueueLifeLossSendCheerInteractions(context.match, matchId, bloomEffectSummary, context.turnNumber);
    }

    @Transactional
    public void playSupport(Long matchId, Long userId, PlaySupportActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        Long cardInstanceId = requirePositiveId(request == null ? null : request.getCardInstanceId(), "cardInstanceId");
        Long targetHolomemCardInstanceId = request == null ? null : request.getTargetHolomemCardInstanceId();
        List<Long> selectedCardInstanceIds = request == null ? null : request.getSelectedCardInstanceIds();

        Map<String, Object> card = loadOwnedCardInstance(matchId, userId, cardInstanceId);
        String sourceZone = normalizeZone(card.get("zone"));
        if (!"HAND".equals(sourceZone)) {
            throw new IllegalStateException("SUPPORT 只能從手牌使用");
        }

        String cardId = asString(card.get("card_id"));
        String cardType = jdbcTemplate.query(
            "SELECT card_type FROM cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getString("card_type") : null,
            cardId
        );
        if (!"SUPPORT".equals(normalizeZone(cardType))) {
            throw new IllegalStateException("指定卡片不是 SUPPORT");
        }

        Map<String, Object> supportRow = jdbcTemplate.query(
            """
            SELECT is_limited, effect_type, effect_json::text AS effect_json_text, target_type
            FROM support_cards
            WHERE card_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("is_limited", rs.getObject("is_limited"));
                row.put("effect_type", rs.getString("effect_type"));
                row.put("effect_json_text", rs.getString("effect_json_text"));
                row.put("target_type", rs.getString("target_type"));
                return row;
            },
            cardId
        );
        if (supportRow == null) {
            throw new IllegalStateException("找不到 SUPPORT 效果定義");
        }
        String supportType = resolveSupportAttachmentType(asString(supportRow.get("effect_json_text")));
        boolean attachableSupport = isAttachableSupportType(supportType);
        boolean isLimited = toBoolean(supportRow.get("is_limited"));
        if (isLimited) {
            if (context.turnNumber == 1 && userId.equals(context.match.getPlayerAId())) {
                throw new GameRuleException(
                    GameErrorCode.LIMITED_FIRST_TURN,
                    "LIMITED SUPPORT 只能在先攻玩家的第一回合後使用；後攻玩家第一回合可使用"
                );
            }
            if (hasUsedLimitedSupportThisTurn(matchId, userId, context.turnNumber)) {
                throw new GameRuleException(GameErrorCode.LIMITED_ALREADY_USED_THIS_TURN, "本回合已使用過 LIMITED SUPPORT");
            }
        }
        if (attachableSupport) {
            Long normalizedTargetHolomemCardInstanceId = requirePositiveId(
                targetHolomemCardInstanceId,
                "targetHolomemCardInstanceId"
            );
            Long targetHolomemId = resolveOwnedHolomemIdByCardInstance(
                matchId,
                userId,
                normalizedTargetHolomemCardInstanceId
            );
            if (targetHolomemId == null) {
                throw new IllegalArgumentException("附加 SUPPORT 需要指定場上的我方 Holomem");
            }
            validateAttachableSupportLimit(targetHolomemId, supportType);

            int updated = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'STAGE',
                    order_index = NULL,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'HAND'
                """,
                cardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                throw new IllegalStateException("附加 SUPPORT 失敗，請重新整理對戰狀態");
            }

            jdbcTemplate.update(
                """
                INSERT INTO match_holomem_supports (
                    match_holomem_id,
                    match_card_id,
                    support_card_id,
                    support_type
                ) VALUES (?, ?, ?, ?)
                """,
                targetHolomemId,
                cardInstanceId,
                cardId,
                supportType
            );

            context.match.setCurrentPhase(MatchPhase.MAIN.name());
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cardInstanceId", cardInstanceId);
            payload.put("cardId", cardId);
            payload.put("limited", isLimited);
            payload.put("attached", true);
            payload.put("supportType", supportType);
            payload.put("targetHolomemCardInstanceId", normalizedTargetHolomemCardInstanceId);
            payload.put("selectedCardInstanceIds", List.of());
            payload.put("effect", Map.of(
                "effectType", "ATTACH_SUPPORT",
                "applied", true,
                "note", "附加型 SUPPORT 已掛到 Holomem，持續效果待後續回合/事件觸發"
            ));
            appendAction(
                context.match,
                userId,
                "PLAY_SUPPORT",
                toJson(payload),
                context.turnNumber
            );
            return;
        }

        MatchEffectService.SupportDecisionPlan decisionPlan = null;
        if (selectedCardInstanceIds == null || selectedCardInstanceIds.isEmpty()) {
            decisionPlan = matchEffectService.buildSupportDecisionPlan(
                matchId,
                userId,
                asString(supportRow.get("effect_type")),
                asString(supportRow.get("effect_json_text"))
            );
        }

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
            userId
        );
        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = ?,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            nextArchiveOrder,
            cardInstanceId,
            matchId,
            userId
        );
        if (updated != 1) {
            throw new IllegalStateException("使用 SUPPORT 失敗，請重新整理對戰狀態");
        }

        if (decisionPlan != null) {
            Long decisionId = createCardSelectionPendingDecision(
                context.match.getId(),
                userId,
                "PLAY_SUPPORT",
                cardInstanceId,
                cardId,
                asString(supportRow.get("effect_type")),
                asString(supportRow.get("effect_json_text")),
                asString(supportRow.get("target_type")),
                targetHolomemCardInstanceId,
                decisionPlan,
                hasSupportDefinitionLimitedFlag(cardId)
            );
            if (decisionId == null) {
                throw new IllegalStateException("建立效果選擇決策失敗");
            }

            context.match.setCurrentPhase(MatchPhase.MAIN.name());
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);

            Map<String, Object> pendingPayload = new LinkedHashMap<>();
            pendingPayload.put("decisionId", decisionId);
            pendingPayload.put("decisionType", SUPPORT_DECISION_TYPE_CARD_SELECTION);
            pendingPayload.put("cardInstanceId", cardInstanceId);
            pendingPayload.put("cardId", cardId);
            pendingPayload.put("effectType", decisionPlan.effectType());
            pendingPayload.put("candidateCount", decisionPlan.candidates().size());
            pendingPayload.put("minSelect", decisionPlan.minSelect());
            pendingPayload.put("maxSelect", decisionPlan.maxSelect());
            appendAction(
                context.match,
                userId,
                "SUPPORT_DECISION_PENDING",
                toJson(pendingPayload),
                context.turnNumber
            );
            return;
        }

        Map<String, Object> effectSummary = matchEffectService.applySupportEffect(
            matchId,
            userId,
            asString(supportRow.get("effect_type")),
            asString(supportRow.get("effect_json_text")),
            asString(supportRow.get("target_type")),
            selectedCardInstanceIds,
            targetHolomemCardInstanceId
        );

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardInstanceId", cardInstanceId);
        payload.put("cardId", cardId);
        payload.put("limited", isLimited);
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        payload.put("selectedCardInstanceIds", selectedCardInstanceIds);
        payload.put("effect", effectSummary);
        Long lookTopDeckDecisionId = createLookTopDeckPendingDecisionIfNeeded(
            matchId,
            userId,
            "PLAY_SUPPORT",
            cardInstanceId,
            cardId,
            asString(supportRow.get("effect_type")),
            effectSummary
        );
        if (lookTopDeckDecisionId != null) {
            payload.put("pendingLookTopDeckDecisionId", lookTopDeckDecisionId);
        }
        appendAction(
            context.match,
            userId,
            "PLAY_SUPPORT",
            toJson(payload),
            context.turnNumber
        );
        if (evaluateCardEffectMatchFinish(context.match, userId, context.turnNumber, effectSummary)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (hasLifeReduced(effectSummary) && evaluateLifeDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (hasHolomemDowned(effectSummary) && evaluateNoHolomemDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        }
        enqueueLifeLossSendCheerInteractions(context.match, matchId, effectSummary, context.turnNumber);
    }

    @Transactional
    public void resolveDecision(Long matchId, Long userId, ResolveDecisionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN), true);
        Long decisionId = requirePositiveId(request == null ? null : request.getDecisionId(), "decisionId");
        PendingDecision pending = loadPendingDecisionForUpdate(matchId, userId, decisionId);
        if (pending == null) {
            throw new IllegalArgumentException("找不到待處理的決策");
        }
        String decisionType = normalizeZone(pending.decisionType());
        if (INTERACTION_TYPE_TURN_START.equals(decisionType)) {
            markDecisionResolved(pending.decisionId());
            returnCollabToBackAsRested(matchId, userId);

            context.match.setCurrentPhase(MatchPhase.MAIN.name());
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);

            Map<String, Object> confirmedPayload = new LinkedHashMap<>();
            confirmedPayload.put("decisionId", pending.decisionId());
            confirmedPayload.put("interactionType", INTERACTION_TYPE_TURN_START);
            confirmedPayload.put("sourceActionType", INTERACTION_TYPE_TURN_START);
            appendAction(
                context.match,
                userId,
                "INTERACTION_CONFIRMED",
                toJson(confirmedPayload),
                context.turnNumber
            );
            return;
        }
        if (INTERACTION_TYPE_DRAW_REVEAL.equals(decisionType)) {
            markDecisionResolved(pending.decisionId());

            context.match.setCurrentPhase(MatchPhase.MAIN.name());
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decisionId", pending.decisionId());
            payload.put("interactionType", INTERACTION_TYPE_DRAW_REVEAL);
            payload.put("sourceActionType", "DRAW_TURN");
            payload.put("drawnCardInstanceId", pending.sourceCardInstanceId());
            payload.put("drawnCardId", pending.sourceCardId());
            appendAction(
                context.match,
                userId,
                "INTERACTION_CONFIRMED",
                toJson(payload),
                context.turnNumber
            );
            return;
        }
        if (INTERACTION_TYPE_SEND_CHEER.equals(decisionType)) {
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
            String sourceZone = normalizeZone(sourceCard.get("zone"));
            if (!Set.of("CHEER_DECK", "ARCHIVE", "HAND").contains(sourceZone)) {
                throw new IllegalStateException("來源 Cheer 已失效，請重新整理狀態");
            }
            String cheerCardId = asString(sourceCard.get("card_id"));
            Integer cheerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cheer_cards WHERE card_id = ?",
                Integer.class,
                cheerCardId
            );
            if (cheerCount == null || cheerCount <= 0) {
                throw new IllegalStateException("來源卡不是 Cheer 卡");
            }
            int moved = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'STAGE',
                    order_index = NULL,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone IN ('CHEER_DECK','ARCHIVE','HAND')
                """,
                sourceCardInstanceId,
                matchId,
                userId
            );
            if (moved != 1) {
                throw new IllegalStateException("發送吶喊失敗，來源卡片狀態已變更");
            }
            jdbcTemplate.update(
                """
                INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                VALUES (?, ?, FALSE)
                """,
                targetHolomemId,
                cheerCardId
            );
            markDecisionResolved(pending.decisionId());

            context.match.setCurrentPhase(MatchPhase.MAIN.name());
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decisionId", pending.decisionId());
            payload.put("interactionType", INTERACTION_TYPE_SEND_CHEER);
            payload.put("sourceActionType", pending.sourceActionType());
            payload.put("sourceCardInstanceId", sourceCardInstanceId);
            payload.put("sourceCardId", cheerCardId);
            payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
            appendAction(
                context.match,
                userId,
                "INTERACTION_CONFIRMED",
                toJson(payload),
                context.turnNumber
            );
            if (ACTION_TYPE_TURN_CHEER.equals(pending.sourceActionType())) {
                Map<String, Object> turnCheerPayload = new LinkedHashMap<>();
                turnCheerPayload.put("sourceCardInstanceId", sourceCardInstanceId);
                turnCheerPayload.put("sourceCardId", cheerCardId);
                turnCheerPayload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
                appendAction(
                    context.match,
                    userId,
                    ACTION_TYPE_TURN_CHEER,
                    toJson(turnCheerPayload),
                    context.turnNumber
                );
            }
            return;
        }
        if (DECISION_TYPE_LOOK_TOP_DECK.equals(decisionType)) {
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
            markDecisionResolved(pending.decisionId());

            context.match.setCurrentPhase(MatchPhase.MAIN.name());
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decisionId", pending.decisionId());
            payload.put("decisionType", DECISION_TYPE_LOOK_TOP_DECK);
            payload.put("sourceActionType", pending.sourceActionType());
            payload.put("lookedCardInstanceId", lookedCardInstanceId);
            payload.put("placement", keepOnTop ? "TOP" : "BOTTOM");
            appendAction(
                context.match,
                userId,
                "INTERACTION_CONFIRMED",
                toJson(payload),
                context.turnNumber
            );
            return;
        }
        if (!SUPPORT_DECISION_TYPE_CARD_SELECTION.equals(decisionType)) {
            throw new IllegalStateException("目前不支援此類型決策: " + decisionType);
        }

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

        Map<String, Object> effectSummary = matchEffectService.applySupportEffect(
            matchId,
            userId,
            pending.effectType(),
            pending.effectJson(),
            pending.targetType(),
            selectedCardInstanceIds,
            pending.targetHolomemCardInstanceId()
        );
        markDecisionResolved(pending.decisionId());

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        String sourceActionType = normalizeZone(pending.sourceActionType());
        String resolvedActionType = ACTION_TYPE_USE_OSHI_SKILL.equals(sourceActionType)
            ? ACTION_TYPE_USE_OSHI_SKILL
            : "PLAY_SUPPORT";
        payload.put("decisionId", pending.decisionId());
        payload.put("sourceActionType", sourceActionType);
        payload.put("targetHolomemCardInstanceId", pending.targetHolomemCardInstanceId());
        payload.put("selectedCardInstanceIds", selectedCardInstanceIds);
        payload.put("effect", effectSummary);
        if (ACTION_TYPE_USE_OSHI_SKILL.equals(sourceActionType)) {
            payload.put("oshiCardInstanceId", pending.sourceCardInstanceId());
            payload.put("oshiCardId", pending.sourceCardId());
        } else {
            payload.put("cardInstanceId", pending.sourceCardInstanceId());
            payload.put("cardId", pending.sourceCardId());
            payload.put("limited", pending.limited());
        }
        Long lookTopDeckDecisionId = createLookTopDeckPendingDecisionIfNeeded(
            matchId,
            userId,
            sourceActionType,
            pending.sourceCardInstanceId(),
            pending.sourceCardId(),
            pending.effectType(),
            effectSummary
        );
        if (lookTopDeckDecisionId != null) {
            payload.put("pendingLookTopDeckDecisionId", lookTopDeckDecisionId);
        }
        appendAction(
            context.match,
            userId,
            resolvedActionType,
            toJson(payload),
            context.turnNumber
        );
        if (evaluateCardEffectMatchFinish(context.match, userId, context.turnNumber, effectSummary)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (hasLifeReduced(effectSummary) && evaluateLifeDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (hasHolomemDowned(effectSummary) && evaluateNoHolomemDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        }
        enqueueLifeLossSendCheerInteractions(context.match, matchId, effectSummary, context.turnNumber);
    }

    @Transactional
    public void mulligan(Long matchId, Long userId, MulliganActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.RESET));
        MatchPlayerEntity player = matchPlayerRepository.findByMatchIdAndUserId(matchId, userId)
            .orElseThrow(() -> new IllegalArgumentException("你不在此房間中"));
        if (player.isMulliganDone()) {
            throw new IllegalStateException("你已完成起手調度");
        }

        boolean useMulligan = request != null && request.isUseMulligan();
        if (useMulligan) {
            redrawOpeningHand(matchId, userId, OPENING_HAND_SIZE);
        }
        MulliganResolution resolution = enforceOpeningDebutRule(matchId, userId);
        boolean defeatedByNoDebut = !resolution.hasDebut();
        player.setMulliganUsed(player.isMulliganUsed() || useMulligan);
        player.setMulliganDone(true);
        player.setUpdatedAt(LocalDateTime.now());
        matchPlayerRepository.save(player);

        int handCountAfter = resolution.finalHandCount();
        Map<String, Object> mulliganPayload = new LinkedHashMap<>();
        mulliganPayload.put("useMulligan", useMulligan);
        mulliganPayload.put("handCountAfter", handCountAfter);
        mulliganPayload.put("forcedRedrawCount", resolution.forcedRedrawCount());
        mulliganPayload.put("forcedDrawSequence", resolution.forcedDrawSequence());
        mulliganPayload.put("hasDebutInHand", resolution.hasDebut());
        mulliganPayload.put("defeatedByNoDebut", defeatedByNoDebut);
        appendAction(
            context.match,
            userId,
            "MULLIGAN",
            toJson(mulliganPayload),
            context.turnNumber
        );

        if (defeatedByNoDebut) {
            finishMatchByDefeat(context.match, userId, "OPENING_NO_DEBUT", context.turnNumber);
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
            return;
        }

        List<MatchPlayerEntity> players = matchPlayerRepository.findByMatchIdOrderByIdAsc(matchId);
        boolean allDone = players.stream().allMatch(MatchPlayerEntity::isMulliganDone);
        if (allDone) {
            context.match.setCurrentTurnPlayerId(context.match.getPlayerAId());
            context.match.setCurrentPhase(MatchPhase.MAIN.name());
        } else {
            Long nextUserId = resolveNextMulliganUser(context.match, players);
            if (nextUserId == null) {
                throw new IllegalStateException("找不到下一位調度玩家");
            }
            context.match.setCurrentTurnPlayerId(nextUserId);
            context.match.setCurrentPhase(MatchPhase.RESET.name());
        }

        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);
        if (allDone) {
            Long interactionId = createTurnStartPendingInteraction(matchId, context.match.getPlayerAId(), context.turnNumber);
            if (interactionId != null) {
                Map<String, Object> interactionPayload = new LinkedHashMap<>();
                interactionPayload.put("interactionId", interactionId);
                interactionPayload.put("interactionType", INTERACTION_TYPE_TURN_START);
                interactionPayload.put("sourceActionType", INTERACTION_TYPE_TURN_START);
                appendAction(
                    context.match,
                    context.match.getPlayerAId(),
                    "INTERACTION_PENDING",
                    toJson(interactionPayload),
                    context.turnNumber
                );
            }
        }
    }

    @Transactional
    public void drawTurn(Long matchId, Long userId) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        if (hasDrawTurnAction(matchId, userId, context.turnNumber)) {
            throw new GameRuleException(GameErrorCode.TURN_DRAW_ALREADY_USED, "這回合你已經抽過卡了");
        }

        Long drawnCardInstanceId = drawTopDeckCardToHand(matchId, userId);
        if (drawnCardInstanceId == null) {
            finishMatchByDefeat(context.match, userId, "DRAW_DECK_OUT", context.turnNumber);
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
            return;
        }

        Map<String, Object> drawPayload = new LinkedHashMap<>();
        drawPayload.put("drawCount", 1);
        drawPayload.put("drawnCardInstanceIds", List.of(drawnCardInstanceId));
        appendAction(
            context.match,
            userId,
            ACTION_TYPE_DRAW_TURN,
            toJson(drawPayload),
            context.turnNumber
        );

        Long drawInteractionId = createDrawRevealPendingInteraction(matchId, userId, drawnCardInstanceId);
        if (drawInteractionId != null) {
            Map<String, Object> interactionPayload = new LinkedHashMap<>();
            interactionPayload.put("interactionId", drawInteractionId);
            interactionPayload.put("interactionType", INTERACTION_TYPE_DRAW_REVEAL);
            interactionPayload.put("sourceActionType", ACTION_TYPE_DRAW_TURN);
            interactionPayload.put("drawnCardInstanceId", drawnCardInstanceId);
            appendAction(
                context.match,
                userId,
                "INTERACTION_PENDING",
                toJson(interactionPayload),
                context.turnNumber
            );
        }
    }

    @Transactional
    public void sendTurnCheer(Long matchId, Long userId) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        if (hasTurnCheerAction(matchId, userId, context.turnNumber)) {
            throw new GameRuleException(GameErrorCode.TURN_CHEER_ALREADY_USED, "這回合你已經發送過吶喊了");
        }

        Long interactionId = createTurnSendCheerPendingInteraction(matchId, userId);
        if (interactionId == null) {
            throw new IllegalStateException("目前無法發送吶喊：請確認你有可用吶喊卡且場上有 Holomem");
        }
        Map<String, Object> interactionPayload = new LinkedHashMap<>();
        interactionPayload.put("interactionId", interactionId);
        interactionPayload.put("interactionType", INTERACTION_TYPE_SEND_CHEER);
        interactionPayload.put("sourceActionType", ACTION_TYPE_TURN_CHEER);
        appendAction(
            context.match,
            userId,
            "INTERACTION_PENDING",
            toJson(interactionPayload),
            context.turnNumber
        );
    }

    @Transactional
    public void moveStageHolomem(Long matchId, Long userId, MoveStageHolomemActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        Long cardInstanceId = requirePositiveId(request == null ? null : request.getCardInstanceId(), "cardInstanceId");
        String targetZone = normalizeZone(request == null ? null : request.getTargetZone());
        if (!Set.of("CENTER", "COLLAB").contains(targetZone)) {
            throw new IllegalArgumentException("targetZone 只支援 CENTER 或 COLLAB");
        }
        if (isStageActionLocked(matchId, userId, context.turnNumber, "MOVE_STAGE", null, null)) {
            throw new GameRuleException(GameErrorCode.STAGE_ACTION_LOCKED, "目前效果限制：不可移動");
        }

        Map<String, Object> currentHolomem = jdbcTemplate.query(
            """
            SELECT id, zone, card_id, is_rested
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("zone", rs.getString("zone"));
                row.put("card_id", rs.getString("card_id"));
                row.put("is_rested", rs.getObject("is_rested"));
                return row;
            },
            matchId,
            userId,
            cardInstanceId
        );
        if (currentHolomem == null) {
            throw new IllegalStateException("找不到指定的場上 Holomem");
        }
        String sourceZone = normalizeZone(currentHolomem.get("zone"));
        if (!"BACK".equals(sourceZone)) {
            throw new IllegalStateException("目前只支援從 BACK 移動 Holomem");
        }
        boolean sourceRested = toBoolean(currentHolomem.get("is_rested"));
        if (targetZone.equals(sourceZone)) {
            throw new IllegalStateException("Holomem 已在目標區位");
        }

        int targetOccupied = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            targetZone
        );
        if ("CENTER".equals(targetZone) && targetOccupied > 0) {
            throw new IllegalStateException("CENTER 已有 Holomem");
        }
        if ("COLLAB".equals(targetZone) && targetOccupied > 0) {
            throw new IllegalStateException("COLLAB 已有 Holomem");
        }
        if ("COLLAB".equals(targetZone)) {
            if (sourceRested) {
                throw new IllegalStateException("休息中的 Holomem 不能執行連動");
            }
            if (hasUsedCollabThisTurn(matchId, userId, context.turnNumber)) {
                throw new IllegalStateException("本回合已執行過連動");
            }
        }

        int moved = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
              AND zone = 'BACK'
            """,
            targetZone,
            matchId,
            userId,
            cardInstanceId
        );
        if (moved != 1) {
            throw new IllegalStateException("移動 Holomem 失敗，請重新整理");
        }

        Long holopowerCardInstanceId = null;
        Map<String, Object> collabEffectSummary = null;
        Map<String, Object> collabTriggerSummary = null;
        if ("COLLAB".equals(targetZone)) {
            holopowerCardInstanceId = moveTopDeckCardToHolopower(matchId, userId);
            collabEffectSummary = matchEffectService.applyCollabTriggeredEffects(
                matchId,
                userId,
                asString(currentHolomem.get("card_id")),
                cardInstanceId
            );
            collabTriggerSummary = matchEventHookService.onHolomemCollab(
                matchId,
                userId,
                asString(currentHolomem.get("card_id")),
                cardInstanceId
            );
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardInstanceId", cardInstanceId);
        payload.put("cardId", asString(currentHolomem.get("card_id")));
        payload.put("sourceZone", sourceZone);
        payload.put("targetZone", targetZone);
        if (holopowerCardInstanceId != null) {
            payload.put("holopowerCardInstanceId", holopowerCardInstanceId);
        }
        if (collabEffectSummary != null) {
            payload.put("collabEffect", collabEffectSummary);
            Long collabLookTopDeckDecisionId = createLookTopDeckPendingDecisionIfNeeded(
                matchId,
                userId,
                "COLLAB",
                cardInstanceId,
                asString(currentHolomem.get("card_id")),
                "LOOK_TOP_DECK",
                collabEffectSummary
            );
            if (collabLookTopDeckDecisionId != null) {
                payload.put("pendingLookTopDeckDecisionId", collabLookTopDeckDecisionId);
            }
        }
        if (collabTriggerSummary != null) {
            payload.put("triggerSummary", collabTriggerSummary);
        }
        appendAction(
            context.match,
            userId,
            "COLLAB".equals(targetZone) ? "COLLAB" : "MOVE_STAGE_HOLOMEM",
            toJson(payload),
            context.turnNumber
        );
        if (collabEffectSummary != null) {
            if (evaluateCardEffectMatchFinish(context.match, userId, context.turnNumber, collabEffectSummary)) {
                touchUpdatedAt(context.match);
                matchRepository.saveAndFlush(context.match);
            } else if (
                hasLifeReduced(collabEffectSummary) &&
                evaluateLifeDefeat(context.match, userId, context.turnNumber)
            ) {
                touchUpdatedAt(context.match);
                matchRepository.saveAndFlush(context.match);
            } else if (
                hasHolomemDowned(collabEffectSummary) &&
                evaluateNoHolomemDefeat(context.match, userId, context.turnNumber)
            ) {
                touchUpdatedAt(context.match);
                matchRepository.saveAndFlush(context.match);
            }
            enqueueLifeLossSendCheerInteractions(context.match, matchId, collabEffectSummary, context.turnNumber);
        }
    }

    @Transactional
    public void useOshiSkill(Long matchId, Long userId, UseOshiSkillActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        String requestedSkillType = normalizeZone(request == null ? null : request.getSkillType());
        if (!Set.of("NORMAL", "SP").contains(requestedSkillType)) {
            throw new GameRuleException(
                GameErrorCode.OSHI_SKILL_INVALID_TYPE,
                "skillType 只支援 NORMAL 或 SP"
            );
        }
        MatchPlayerEntity player = matchPlayerRepository.findByMatchIdAndUserId(matchId, userId)
            .orElseThrow(() -> new IllegalArgumentException("你不在此房間中"));
        if (player.isSkillUsedThisTurn()) {
            throw new GameRuleException(
                GameErrorCode.OSHI_SKILL_ALREADY_USED_THIS_TURN,
                "本回合已使用過 OSHI 技能"
            );
        }
        if ("SP".equals(requestedSkillType) && player.isSpSkillUsed()) {
            throw new GameRuleException(
                GameErrorCode.OSHI_SKILL_SP_ALREADY_USED,
                "SP OSHI 技能一場對戰只能使用 1 次"
            );
        }

        Map<String, Object> oshiSkill = loadOwnedOshiSkill(matchId, userId, requestedSkillType);
        if (oshiSkill == null) {
            throw new GameRuleException(
                GameErrorCode.OSHI_SKILL_NOT_FOUND,
                "找不到可使用的 OSHI 技能: " + requestedSkillType
            );
        }
        String effectJson = asString(oshiSkill.get("effect_json_text"));
        String effectType = resolvePrimaryEffectType(effectJson);
        String targetType = resolveEffectTargetType(effectJson);
        int holopowerCost = Math.max(asInt(oshiSkill.get("holopower_cost")), 0);
        Map<String, Object> holopowerPayment = consumeHolopowerCostToArchive(matchId, userId, holopowerCost);

        List<Long> selectedCardInstanceIds = request == null ? null : request.getSelectedCardInstanceIds();
        Long targetHolomemCardInstanceId = request == null ? null : request.getTargetHolomemCardInstanceId();
        MatchEffectService.SupportDecisionPlan decisionPlan = null;
        if (selectedCardInstanceIds == null || selectedCardInstanceIds.isEmpty()) {
            decisionPlan = matchEffectService.buildSupportDecisionPlan(
                matchId,
                userId,
                effectType,
                effectJson
            );
        }

        player.setSkillUsedThisTurn(true);
        if ("SP".equals(requestedSkillType)) {
            player.setSpSkillUsed(true);
        }
        player.setUpdatedAt(LocalDateTime.now());
        matchPlayerRepository.save(player);

        Long oshiCardInstanceId = asLong(oshiSkill.get("oshi_card_instance_id"));
        String oshiCardId = asString(oshiSkill.get("oshi_card_id"));
        String skillName = asString(oshiSkill.get("skill_name"));
        if (decisionPlan != null) {
            Long decisionId = createCardSelectionPendingDecision(
                context.match.getId(),
                userId,
                ACTION_TYPE_USE_OSHI_SKILL,
                oshiCardInstanceId,
                oshiCardId,
                effectType,
                effectJson,
                targetType,
                targetHolomemCardInstanceId,
                decisionPlan,
                false
            );
            if (decisionId == null) {
                throw new IllegalStateException("建立 OSHI 技能決策失敗");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decisionId", decisionId);
            payload.put("decisionType", SUPPORT_DECISION_TYPE_CARD_SELECTION);
            payload.put("skillType", requestedSkillType);
            payload.put("skillName", skillName);
            payload.put("oshiCardInstanceId", oshiCardInstanceId);
            payload.put("oshiCardId", oshiCardId);
            payload.put("effectType", decisionPlan.effectType());
            payload.put("holopowerCost", holopowerCost);
            payload.put("holopowerPayment", holopowerPayment);
            payload.put("candidateCount", decisionPlan.candidates().size());
            payload.put("minSelect", decisionPlan.minSelect());
            payload.put("maxSelect", decisionPlan.maxSelect());
            appendAction(
                context.match,
                userId,
                "OSHI_SKILL_DECISION_PENDING",
                toJson(payload),
                context.turnNumber
            );
            return;
        }

        Map<String, Object> effectSummary = matchEffectService.applySupportEffect(
            matchId,
            userId,
            effectType,
            effectJson,
            targetType,
            selectedCardInstanceIds,
            targetHolomemCardInstanceId
        );
        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skillType", requestedSkillType);
        payload.put("skillName", skillName);
        payload.put("oshiCardInstanceId", oshiCardInstanceId);
        payload.put("oshiCardId", oshiCardId);
        payload.put("holopowerCost", holopowerCost);
        payload.put("holopowerPayment", holopowerPayment);
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        payload.put("selectedCardInstanceIds", selectedCardInstanceIds);
        payload.put("effect", effectSummary);
        Long lookTopDeckDecisionId = createLookTopDeckPendingDecisionIfNeeded(
            matchId,
            userId,
            ACTION_TYPE_USE_OSHI_SKILL,
            oshiCardInstanceId,
            oshiCardId,
            effectType,
            effectSummary
        );
        if (lookTopDeckDecisionId != null) {
            payload.put("pendingLookTopDeckDecisionId", lookTopDeckDecisionId);
        }
        appendAction(
            context.match,
            userId,
            ACTION_TYPE_USE_OSHI_SKILL,
            toJson(payload),
            context.turnNumber
        );
        if (evaluateCardEffectMatchFinish(context.match, userId, context.turnNumber, effectSummary)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (hasLifeReduced(effectSummary) && evaluateLifeDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (hasHolomemDowned(effectSummary) && evaluateNoHolomemDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        }
        enqueueLifeLossSendCheerInteractions(context.match, matchId, effectSummary, context.turnNumber);
    }

    @Transactional
    public void batonTouch(Long matchId, Long userId, BatonTouchActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        if (hasUsedBatonTouchThisTurn(matchId, userId, context.turnNumber)) {
            throw new GameRuleException(GameErrorCode.BATON_TOUCH_ALREADY_USED_THIS_TURN, "本回合已使用過バトンタッチ");
        }
        Long sourceHolomemCardInstanceId = requirePositiveId(
            request == null ? null : request.getSourceHolomemCardInstanceId(),
            "sourceHolomemCardInstanceId"
        );
        Long targetBackHolomemCardInstanceId = requirePositiveId(
            request == null ? null : request.getTargetBackHolomemCardInstanceId(),
            "targetBackHolomemCardInstanceId"
        );

        Map<String, Object> source = jdbcTemplate.query(
            """
            SELECT id, zone, card_id, is_rested
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("zone", rs.getString("zone"));
                row.put("card_id", rs.getString("card_id"));
                row.put("is_rested", rs.getObject("is_rested"));
                return row;
            },
            matchId,
            userId,
            sourceHolomemCardInstanceId
        );
        if (source == null) {
            throw new IllegalArgumentException("找不到要執行バトンタッチ的 Holomem");
        }
        String sourceZone = normalizeZone(source.get("zone"));
        if (!Set.of("CENTER", "COLLAB").contains(sourceZone)) {
            throw new IllegalStateException("バトンタッチ 來源必須是 CENTER 或 COLLAB");
        }
        Long sourceHolomemId = asLong(source.get("id"));
        if (sourceHolomemId == null) {
            throw new IllegalStateException("來源 Holomem 資料異常");
        }
        if (isStageActionLocked(matchId, userId, context.turnNumber, "BATON_TOUCH", sourceZone, sourceHolomemId)) {
            throw new GameRuleException(GameErrorCode.STAGE_ACTION_LOCKED, "目前效果限制：不可バトンタッチ");
        }

        Map<String, Object> target = jdbcTemplate.query(
            """
            SELECT id, zone, card_id, is_rested
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("zone", rs.getString("zone"));
                row.put("card_id", rs.getString("card_id"));
                row.put("is_rested", rs.getObject("is_rested"));
                return row;
            },
            matchId,
            userId,
            targetBackHolomemCardInstanceId
        );
        if (target == null) {
            throw new IllegalArgumentException("找不到要交換上場的 BACK Holomem");
        }
        String targetZone = normalizeZone(target.get("zone"));
        if (!"BACK".equals(targetZone)) {
            throw new IllegalStateException("バトンタッチ 目標必須是 BACK Holomem");
        }
        if (toBoolean(target.get("is_rested"))) {
            throw new IllegalStateException("バトンタッチ 目標必須是非休息狀態的 BACK Holomem");
        }
        Long targetHolomemId = asLong(target.get("id"));
        if (targetHolomemId == null) {
            throw new IllegalStateException("目標 Holomem 資料異常");
        }

        int currentTurn = context.turnNumber;
        int batonTouchModifier = resolveBatonTouchColorlessModifier(matchId, userId, sourceHolomemId, currentTurn);
        int requiredColorless = Math.max(1 + batonTouchModifier, 0);
        Map<String, Object> costSummary = payBatonTouchCost(
            matchId,
            userId,
            sourceHolomemId,
            requiredColorless
        );

        int moveSource = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'BACK',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            sourceHolomemId,
            matchId,
            userId
        );
        int moveTarget = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            sourceZone,
            targetHolomemId,
            matchId,
            userId
        );
        if (moveSource != 1 || moveTarget != 1) {
            throw new IllegalStateException("バトンタッチ 移動失敗，請重新整理後重試");
        }

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceHolomemCardInstanceId", sourceHolomemCardInstanceId);
        payload.put("sourceCardId", asString(source.get("card_id")));
        payload.put("sourceFromZone", sourceZone);
        payload.put("targetHolomemCardInstanceId", targetBackHolomemCardInstanceId);
        payload.put("targetCardId", asString(target.get("card_id")));
        payload.put("targetToZone", sourceZone);
        payload.put("requiredColorless", requiredColorless);
        payload.put("modifierColorless", batonTouchModifier);
        payload.put("cost", costSummary);

        appendAction(
            context.match,
            userId,
            ACTION_TYPE_BATON_TOUCH,
            toJson(payload),
            context.turnNumber
        );
    }

    @Transactional
    public void attachCheer(Long matchId, Long userId, AttachCheerActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        Long cheerCardInstanceId = requirePositiveId(
            request == null ? null : request.getCheerCardInstanceId(),
            "cheerCardInstanceId"
        );
        Long targetHolomemCardInstanceId = requirePositiveId(
            request == null ? null : request.getTargetHolomemCardInstanceId(),
            "targetHolomemCardInstanceId"
        );

        Long matchHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            targetHolomemCardInstanceId
        );
        if (matchHolomemId == null) {
            throw new IllegalArgumentException("找不到要附加 Cheer 的 Holomen");
        }

        Map<String, Object> cheerCard = loadOwnedCardInstance(matchId, userId, cheerCardInstanceId);
        String sourceZone = normalizeZone(cheerCard.get("zone"));
        if (!CHEER_SOURCE_ZONES.contains(sourceZone)) {
            throw new IllegalStateException("Cheer 只能從 HAND 或 CHEER_DECK 附加");
        }
        String cheerCardId = asString(cheerCard.get("card_id"));
        Integer cheerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM cheer_cards WHERE card_id = ?",
            Integer.class,
            cheerCardId
        );
        if (cheerCount == null || cheerCount == 0) {
            throw new IllegalStateException("指定卡片不是 Cheer 卡");
        }

        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone IN ('HAND','CHEER_DECK')
            """,
            cheerCardInstanceId,
            matchId,
            userId
        );
        if (updated != 1) {
            throw new IllegalStateException("附加 Cheer 失敗，請重新整理對戰狀態");
        }

        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
            VALUES (?, ?, FALSE)
            """,
            matchHolomemId,
            cheerCardId
        );

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        appendAction(
            context.match,
            userId,
            "ATTACH_CHEER",
            toJson(
                Map.of(
                    "cheerCardInstanceId", cheerCardInstanceId,
                    "cheerCardId", cheerCardId,
                    "targetHolomemCardInstanceId", targetHolomemCardInstanceId
                )
            ),
            context.turnNumber
        );
    }

    @Transactional
    public void attackArt(Long matchId, Long userId, AttackArtActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN, MatchPhase.PERFORMANCE));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        if (context.turnNumber == 1) {
            throw new IllegalStateException("先攻玩家第一回合不可使用藝能");
        }
        Long attackerCardInstanceId = requirePositiveId(
            request == null ? null : request.getAttackerCardInstanceId(),
            "attackerCardInstanceId"
        );
        Long targetCardInstanceId = request == null ? null : request.getTargetCardInstanceId();

        Map<String, Object> attacker = jdbcTemplate.query(
            """
            SELECT h.id, h.zone, h.is_rested, h.card_id
            FROM match_holomems h
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
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
            throw new IllegalArgumentException("找不到攻擊中的 Holomen");
        }
        String attackerZone = normalizeZone(attacker.get("zone"));
        if (!Set.of("CENTER", "COLLAB").contains(attackerZone)) {
            throw new IllegalStateException("目前僅支援由 CENTER 或 COLLAB 發動藝能");
        }
        if (countArtUsedByZoneThisTurn(matchId, userId, context.turnNumber, attackerZone) > 0) {
            throw new IllegalStateException("本回合 " + attackerZone + " 已使用過藝能");
        }
        if (toBoolean(attacker.get("is_rested"))) {
            throw new IllegalStateException("休息狀態的 Holomen 不能使用藝能");
        }
        String attackerCardId = asString(attacker.get("card_id"));
        Map<String, Object> art = loadPrimaryArt(attackerCardId);
        if (art == null) {
            throw new IllegalStateException("找不到可使用的藝能");
        }
        int baseDamage = resolveArtDamage(asString(art.get("effect_json_text")));
        int attachedSupportArtBonus = matchEffectService.resolveAttachedSupportArtBonus(
            matchId,
            asLong(attacker.get("id"))
        );
        Map<String, Integer> requiredCheerCost = resolveArtCheerCost(asString(art.get("cost_cheer_json_text")));
        Map<String, Object> costSummary = payArtCost(
            matchId,
            userId,
            asLong(attacker.get("id")),
            requiredCheerCost
        );
        Integer opponentHolomemCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
            """,
            Integer.class,
            matchId,
            context.opponentUserId
        );
        boolean hasOpponentHolomem = opponentHolomemCount != null && opponentHolomemCount > 0;
        TargetHolomem targetHolomem = null;
        if (hasOpponentHolomem) {
            targetHolomem = resolveOpponentTargetHolomem(matchId, context.opponentUserId, targetCardInstanceId);
            if (targetHolomem == null) {
                throw new IllegalStateException("DAMAGE 找不到可攻擊的對手 Holomen");
            }
        }
        Long effectiveTargetCardInstanceId = targetHolomem == null ? targetCardInstanceId : targetHolomem.matchCardInstanceId();
        ArtCritical artCritical = resolveArtCritical(asString(art.get("effect_json_text")));
        int criticalBonus = 0;
        boolean criticalApplied = false;
        if (artCritical != null && artCritical.bonus() > 0) {
            String targetColor = targetHolomem == null ? "" : targetHolomem.mainColor();
            if (artCritical.color().equals(targetColor)) {
                criticalApplied = true;
                criticalBonus = artCritical.bonus();
            }
        }
        int totalDamage = Math.max(baseDamage + attachedSupportArtBonus + criticalBonus, 0);
        if (totalDamage <= 0) {
            throw new IllegalStateException("此藝能目前未解析出可造成的傷害");
        }
        Map<String, Object> artSummary;
        Long lostLifeCardInstanceId = null;
        if (hasOpponentHolomem) {
            artSummary = matchEffectService.applyArtDamage(matchId, userId, totalDamage, effectiveTargetCardInstanceId);
            lostLifeCardInstanceId = asLong(artSummary.get("lostLifeCardInstanceId"));
        } else {
            lostLifeCardInstanceId = loseLifeOnce(matchId, context.opponentUserId);
            if (lostLifeCardInstanceId == null) {
                throw new IllegalStateException("對手沒有可失去的 LIFE");
            }
            artSummary = new LinkedHashMap<>();
            artSummary.put("effectType", "ART_DAMAGE_FALLBACK");
            artSummary.put("damageRequested", totalDamage);
            artSummary.put("damageApplied", 0);
            artSummary.put("reason", "對手場上無 Holomen，改為扣除 1 點 LIFE");
            artSummary.put("lifeReduced", true);
            artSummary.put("lostLifeCardInstanceId", lostLifeCardInstanceId);
        }

        int attackerRested = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND is_rested = FALSE
            """,
            asLong(attacker.get("id")),
            matchId,
            userId
        );
        if (attackerRested != 1) {
            throw new IllegalStateException("藝能結算失敗，請重新整理後再試");
        }

        boolean hasNextPerformanceAction = hasAvailableArtAttacker(matchId, userId, context.turnNumber);
        context.match.setCurrentPhase(hasNextPerformanceAction ? MatchPhase.PERFORMANCE.name() : MatchPhase.END.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attackerCardInstanceId", attackerCardInstanceId);
        payload.put("attackerCardId", attackerCardId);
        payload.put("attackerZone", attackerZone);
        payload.put("targetCardInstanceId", effectiveTargetCardInstanceId);
        payload.put("targetMainColor", targetHolomem == null ? null : targetHolomem.mainColor());
        payload.put("artName", asString(art.get("name")));
        payload.put("artOrderIndex", art.get("order_index"));
        payload.put("artCost", requiredCheerCost);
        payload.put("costPayment", costSummary);
        payload.put("artBaseDamage", baseDamage);
        payload.put("attachedSupportArtBonus", attachedSupportArtBonus);
        payload.put("criticalColor", artCritical == null ? null : artCritical.color());
        payload.put("criticalBonus", criticalBonus);
        payload.put("criticalApplied", criticalApplied);
        payload.put("artTotalDamage", totalDamage);
        payload.put("effect", artSummary);
        payload.put("lostLifeCardInstanceId", lostLifeCardInstanceId);

        appendAction(
            context.match,
            userId,
            "ATTACK_ART",
            toJson(payload),
            context.turnNumber
        );
        if (evaluateCardEffectMatchFinish(context.match, userId, context.turnNumber, artSummary)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (hasLifeReduced(artSummary) && evaluateLifeDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (hasHolomemDowned(artSummary) && evaluateNoHolomemDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        }
        enqueueLifeLossSendCheerInteractions(context.match, matchId, artSummary, context.turnNumber);
    }

    @Transactional
    public void endTurn(Long matchId, Long userId) {
        ActionContext context = loadActionContext(
            matchId,
            userId,
            Set.of(MatchPhase.MAIN, MatchPhase.PERFORMANCE, MatchPhase.END)
        );
        if (context.blockedByPendingInteraction()) {
            return;
        }
        List<String> missingActions = new ArrayList<>();
        if (!hasDrawTurnAction(matchId, userId, context.turnNumber)) {
            missingActions.add("抽卡");
        }
        if (canPerformTurnCheerAction(matchId, userId) && !hasTurnCheerAction(matchId, userId, context.turnNumber)) {
            missingActions.add("發送吶喊");
        }
        if (!missingActions.isEmpty()) {
            throw new GameRuleException(
                GameErrorCode.TURN_ACTIONS_INCOMPLETE,
                "回合尚未完成：" + String.join("、", missingActions) + "。請先完成後再結束回合"
            );
        }
        int clearedEffectCount = matchEffectService.clearExpiredTurnEffects(matchId, context.turnNumber);
        int resetRestedCount = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND is_rested = TRUE
            """,
            matchId,
            context.opponentUserId
        );
        Map<String, Object> centerReplenishSummary = resolveEndTurnCenterReplenishCycle(matchId, userId);
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET skill_used_this_turn = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            context.opponentUserId
        );

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUserId", userId);
        payload.put("toUserId", context.opponentUserId);
        payload.put("clearedExpiredTurnEffects", clearedEffectCount);
        payload.put("resetRestedCount", resetRestedCount);
        payload.put("centerReplenish", centerReplenishSummary);

        int nextTurnNumber = context.turnNumber + 1;
        payload.put("nextTurnNumber", nextTurnNumber);

        appendAction(
            context.match,
            userId,
            "END_TURN",
            toJson(payload),
            context.turnNumber
        );
        if (evaluateNoHolomemDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
            return;
        }

        context.match.setCurrentTurnPlayerId(context.opponentUserId);
        context.match.setTurnNumber(nextTurnNumber);
        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Long interactionId = createTurnStartPendingInteraction(matchId, context.opponentUserId, nextTurnNumber);
        if (interactionId != null) {
            Map<String, Object> interactionPayload = new LinkedHashMap<>();
            interactionPayload.put("interactionId", interactionId);
            interactionPayload.put("interactionType", INTERACTION_TYPE_TURN_START);
            interactionPayload.put("sourceActionType", INTERACTION_TYPE_TURN_START);
            appendAction(
                context.match,
                context.opponentUserId,
                "INTERACTION_PENDING",
                toJson(interactionPayload),
                nextTurnNumber
            );
        }
    }

    @Transactional
    public void concede(Long matchId, Long userId) {
        MatchEntity match = matchRepository.findByIdForUpdate(matchId)
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));
        if (!"active".equalsIgnoreCase(asString(match.getStatus()))) {
            throw new IllegalStateException("對戰已結束");
        }
        if (!LobbyMatchStatus.STARTED.name().equals(match.getLobbyStatus())) {
            throw new IllegalStateException("對戰尚未開始");
        }
        if (!matchPlayerRepository.existsByMatchIdAndUserId(matchId, userId)) {
            throw new IllegalArgumentException("你不在此房間中");
        }

        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        finishMatchByDefeat(match, userId, "CONCEDE", turnNumber);
        touchUpdatedAt(match);
        matchRepository.saveAndFlush(match);
    }

    private ActionContext loadActionContext(Long matchId, Long userId, Set<MatchPhase> allowedPhases) {
        return loadActionContext(matchId, userId, allowedPhases, false);
    }

    private ActionContext loadActionContext(
        Long matchId,
        Long userId,
        Set<MatchPhase> allowedPhases,
        boolean allowPendingDecision
    ) {
        MatchEntity match = matchRepository.findByIdForUpdate(matchId)
            .orElseThrow(() -> new IllegalArgumentException("找不到對戰"));

        if (!"active".equalsIgnoreCase(asString(match.getStatus()))) {
            throw new IllegalStateException("對戰已結束");
        }
        if (!LobbyMatchStatus.STARTED.name().equals(match.getLobbyStatus())) {
            throw new IllegalStateException("對戰尚未開始");
        }
        if (!matchPlayerRepository.existsByMatchIdAndUserId(matchId, userId)) {
            throw new IllegalArgumentException("你不在此房間中");
        }
        if (match.getCurrentTurnPlayerId() == null || !match.getCurrentTurnPlayerId().equals(userId)) {
            throw new GameRuleException(GameErrorCode.NOT_YOUR_TURN, "現在不是你的回合");
        }

        MatchPhase phase = parsePhase(match.getCurrentPhase());
        boolean autoResolvedOpeningReset = false;
        if (phase == MatchPhase.RESET && !allowedPhases.contains(MatchPhase.RESET)) {
            autoResolveOpeningResetAndStartTurn(matchId, match);
            autoResolvedOpeningReset = true;
            if (!"active".equalsIgnoreCase(asString(match.getStatus()))) {
                throw new IllegalStateException("對戰已結束");
            }
            if (match.getCurrentTurnPlayerId() == null || !match.getCurrentTurnPlayerId().equals(userId)) {
                throw new GameRuleException(GameErrorCode.NOT_YOUR_TURN, "現在不是你的回合");
            }
            phase = parsePhase(match.getCurrentPhase());
        }
        if (!allowedPhases.contains(phase)) {
            throw new GameRuleException(
                GameErrorCode.PHASE_ACTION_NOT_ALLOWED,
                "目前 phase=" + phase + "，無法執行此操作",
                Map.of("phase", phase.name())
            );
        }
        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        Long opponentUserId = resolveOpponent(match, userId);
        if (!allowPendingDecision && hasBlockingPendingDecision(matchId, userId)) {
            if (autoResolvedOpeningReset) {
                return new ActionContext(match, phase, turnNumber, opponentUserId, true);
            }
            throw new GameRuleException(GameErrorCode.PENDING_INTERACTION_BLOCKED, "你有待處理的互動，請先完成確認");
        }

        return new ActionContext(match, phase, turnNumber, opponentUserId, false);
    }

    private void autoResolveOpeningResetAndStartTurn(Long matchId, MatchEntity match) {
        if (matchId == null || match == null || parsePhase(match.getCurrentPhase()) != MatchPhase.RESET) {
            return;
        }
        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        List<MatchPlayerEntity> players = matchPlayerRepository.findByMatchIdOrderByIdAsc(matchId);
        List<Long> openingOrder = List.of(match.getPlayerAId(), match.getPlayerBId());
        for (Long playerUserId : openingOrder) {
            if (playerUserId == null || isMulliganDone(players, playerUserId)) {
                continue;
            }
            MatchPlayerEntity player = matchPlayerRepository.findByMatchIdAndUserId(matchId, playerUserId)
                .orElseThrow(() -> new IllegalStateException("找不到起手調度玩家"));
            MulliganResolution resolution = enforceOpeningDebutRule(matchId, playerUserId);
            boolean defeatedByNoDebut = !resolution.hasDebut();
            player.setMulliganDone(true);
            player.setUpdatedAt(LocalDateTime.now());
            matchPlayerRepository.save(player);

            Map<String, Object> mulliganPayload = new LinkedHashMap<>();
            mulliganPayload.put("useMulligan", false);
            mulliganPayload.put("autoResolved", true);
            mulliganPayload.put("handCountAfter", resolution.finalHandCount());
            mulliganPayload.put("forcedRedrawCount", resolution.forcedRedrawCount());
            mulliganPayload.put("forcedDrawSequence", resolution.forcedDrawSequence());
            mulliganPayload.put("hasDebutInHand", resolution.hasDebut());
            mulliganPayload.put("defeatedByNoDebut", defeatedByNoDebut);
            appendAction(
                match,
                playerUserId,
                "MULLIGAN",
                toJson(mulliganPayload),
                turnNumber
            );

            if (defeatedByNoDebut) {
                finishMatchByDefeat(match, playerUserId, "OPENING_NO_DEBUT", turnNumber);
                touchUpdatedAt(match);
                matchRepository.saveAndFlush(match);
                return;
            }
        }

        match.setCurrentTurnPlayerId(match.getPlayerAId());
        match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(match);
        matchRepository.saveAndFlush(match);

        Long interactionId = createTurnStartPendingInteraction(matchId, match.getPlayerAId(), turnNumber);
        if (interactionId == null) {
            return;
        }
        Map<String, Object> interactionPayload = new LinkedHashMap<>();
        interactionPayload.put("interactionId", interactionId);
        interactionPayload.put("interactionType", INTERACTION_TYPE_TURN_START);
        interactionPayload.put("sourceActionType", INTERACTION_TYPE_TURN_START);
        appendAction(
            match,
            match.getPlayerAId(),
            "INTERACTION_PENDING",
            toJson(interactionPayload),
            turnNumber
        );
    }

    private boolean hasDrawTurnAction(Long matchId, Long userId, int turnNumber) {
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
            ACTION_TYPE_DRAW_TURN
        );
        return count != null && count > 0;
    }

    private Map<String, Object> resolveEndTurnCenterReplenishCycle(Long matchId, Long userId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("applied", false);
        if (matchId == null || userId == null) {
            summary.put("reason", "INVALID_ARGUMENTS");
            summary.put("settled", false);
            summary.put("iterations", List.of());
            return summary;
        }
        List<Map<String, Object>> iterations = new ArrayList<>();
        boolean settled = false;
        boolean appliedAny = false;
        String reason = "CENTER_EXISTS";
        final int maxIterations = 8;
        for (int i = 0; i < maxIterations; i++) {
            if (hasCenterHolomem(matchId, userId)) {
                settled = true;
                reason = "CENTER_EXISTS";
                break;
            }
            Map<String, Object> step = autoReplenishCenterFromBackOnce(matchId, userId);
            iterations.add(step);
            if (toBoolean(step.get("applied"))) {
                appliedAny = true;
            }
            if (!toBoolean(step.get("applied"))) {
                reason = asString(step.get("reason"));
                settled = false;
                break;
            }
            reason = asString(step.get("reason"));
        }
        if (hasCenterHolomem(matchId, userId)) {
            settled = true;
            if (!appliedAny) {
                reason = "CENTER_EXISTS";
            }
        }
        summary.put("applied", appliedAny);
        summary.put("reason", reason);
        summary.put("settled", settled);
        summary.put("iterationCount", iterations.size());
        summary.put("iterations", iterations);
        return summary;
    }

    private Map<String, Object> autoReplenishCenterFromBackOnce(Long matchId, Long userId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("applied", false);
        if (matchId == null || userId == null) {
            summary.put("reason", "INVALID_ARGUMENTS");
            return summary;
        }
        Integer centerCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            """,
            Integer.class,
            matchId,
            userId
        );
        if (centerCount != null && centerCount > 0) {
            summary.put("reason", "CENTER_EXISTS");
            return summary;
        }
        Map<String, Object> preferredBack = jdbcTemplate.query(
            """
            SELECT id, match_card_id, card_id, is_rested
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
            ORDER BY CASE WHEN is_rested = FALSE THEN 0 ELSE 1 END, id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("is_rested", rs.getObject("is_rested"));
                return row;
            },
            matchId,
            userId
        );
        if (preferredBack == null) {
            summary.put("reason", "NO_BACK_HOLOMEM");
            return summary;
        }
        Long holomemId = asLong(preferredBack.get("id"));
        if (holomemId == null) {
            summary.put("reason", "BACK_HOLOMEM_INVALID");
            return summary;
        }
        int moved = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'CENTER',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
            """,
            holomemId,
            matchId,
            userId
        );
        if (moved != 1) {
            summary.put("reason", "MOVE_FAILED");
            return summary;
        }
        summary.put("applied", true);
        summary.put("reason", "CENTER_REPLENISHED");
        summary.put("targetHolomemId", holomemId);
        summary.put("targetHolomemCardInstanceId", asLong(preferredBack.get("match_card_id")));
        summary.put("targetCardId", asString(preferredBack.get("card_id")));
        summary.put("fromRested", toBoolean(preferredBack.get("is_rested")));
        return summary;
    }

    private boolean hasCenterHolomem(Long matchId, Long userId) {
        Integer centerCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            """,
            Integer.class,
            matchId,
            userId
        );
        return centerCount != null && centerCount > 0;
    }

    private boolean hasTurnCheerAction(Long matchId, Long userId, int turnNumber) {
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
            ACTION_TYPE_TURN_CHEER
        );
        return count != null && count > 0;
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

    private boolean hasUsedCollabThisTurn(Long matchId, Long userId, int turnNumber) {
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
              AND action_type = 'COLLAB'
            """,
            Integer.class,
            matchId,
            userId,
            turnNumber
        );
        return count != null && count > 0;
    }

    private boolean hasUsedBatonTouchThisTurn(Long matchId, Long userId, int turnNumber) {
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
            ACTION_TYPE_BATON_TOUCH
        );
        return count != null && count > 0;
    }

    private Long findExtraBloomAllowanceId(Long matchId, Long userId, int turnNumber, Long targetHolomemId) {
        if (matchId == null || userId == null || turnNumber <= 0 || targetHolomemId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ALLOW_EXTRA_BLOOM'
              AND expires_turn >= ?
              AND (payload ->> 'targetHolomemId') = ?
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            turnNumber,
            targetHolomemId.toString()
        );
    }

    private void consumeExtraBloomAllowance(Long allowanceId, Long matchId, Long userId) {
        if (allowanceId == null || matchId == null || userId == null) {
            return;
        }
        jdbcTemplate.update(
            """
            DELETE FROM match_turn_effects
            WHERE id = ?
              AND match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ALLOW_EXTRA_BLOOM'
            """,
            allowanceId,
            matchId,
            userId
        );
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

    private Long moveTopDeckCardToHolopower(Long matchId, Long userId) {
        Long deckCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (deckCardInstanceId == null) {
            return null;
        }
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HOLOPOWER'
            """,
            Integer.class,
            matchId,
            userId
        );
        int moved = jdbcTemplate.update(
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
            nextOrder == null ? 1 : nextOrder,
            deckCardInstanceId,
            matchId,
            userId
        );
        return moved == 1 ? deckCardInstanceId : null;
    }

    private Map<String, Object> loadOwnedOshiSkill(Long matchId, Long userId, String requestedSkillType) {
        return jdbcTemplate.query(
            """
            SELECT os.skill_type,
                   os.skill_name,
                   os.holopower_cost,
                   os.effect_json::text AS effect_json_text,
                   mp.oshi_card_id,
                   mc.id AS oshi_card_instance_id
            FROM match_players mp
            JOIN oshi_skills os
              ON os.oshi_card_id = mp.oshi_card_id
            LEFT JOIN match_cards mc
              ON mc.match_id = mp.match_id
             AND mc.owner_user_id = mp.user_id
             AND mc.zone = 'OSHI'
             AND mc.card_id = mp.oshi_card_id
            WHERE mp.match_id = ?
              AND mp.user_id = ?
              AND UPPER(os.skill_type) = ?
            ORDER BY os.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("skill_type", normalizeZone(rs.getString("skill_type")));
                row.put("skill_name", rs.getString("skill_name"));
                row.put("holopower_cost", rs.getObject("holopower_cost"));
                row.put("effect_json_text", rs.getString("effect_json_text"));
                row.put("oshi_card_id", rs.getString("oshi_card_id"));
                row.put("oshi_card_instance_id", rs.getObject("oshi_card_instance_id"));
                return row;
            },
            matchId,
            userId,
            requestedSkillType
        );
    }

    private String resolvePrimaryEffectType(String effectJson) {
        JsonNode node = parseJson(effectJson);
        if (node != null && node.isObject()) {
            JsonNode type = node.get("type");
            if (type != null && type.isTextual() && StringUtils.hasText(type.asText())) {
                return normalizeZone(type.asText());
            }
        }
        return "UNIMPLEMENTED";
    }

    private String resolveEffectTargetType(String effectJson) {
        JsonNode node = parseJson(effectJson);
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode targetType = node.get("targetType");
        if (targetType != null && targetType.isTextual() && StringUtils.hasText(targetType.asText())) {
            return normalizeZone(targetType.asText());
        }
        JsonNode targetTypeSnake = node.get("target_type");
        if (targetTypeSnake != null && targetTypeSnake.isTextual() && StringUtils.hasText(targetTypeSnake.asText())) {
            return normalizeZone(targetTypeSnake.asText());
        }
        return "";
    }

    private Map<String, Object> consumeHolopowerCostToArchive(Long matchId, Long userId, int holopowerCost) {
        int required = Math.max(holopowerCost, 0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("required", required);
        if (required <= 0) {
            summary.put("paid", 0);
            summary.put("archivedCardInstanceIds", List.of());
            summary.put("archivedCardIds", List.of());
            return summary;
        }
        List<Map<String, Object>> holopowerCards = jdbcTemplate.queryForList(
            """
            SELECT id, card_id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HOLOPOWER'
            ORDER BY order_index NULLS LAST, id
            LIMIT ?
            """,
            matchId,
            userId,
            required
        );
        if (holopowerCards.size() < required) {
            throw new GameRuleException(
                GameErrorCode.OSHI_SKILL_HOLOPOWER_INSUFFICIENT,
                "Holopower 不足，無法發動 OSHI 技能",
                Map.of("required", required, "available", holopowerCards.size())
            );
        }
        Integer nextArchiveOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            Integer.class,
            matchId,
            userId
        );
        int archiveOrder = nextArchiveOrder == null ? 1 : nextArchiveOrder;
        List<Long> archivedCardInstanceIds = new ArrayList<>();
        List<String> archivedCardIds = new ArrayList<>();
        for (Map<String, Object> row : holopowerCards) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asString(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            int moved = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'ARCHIVE',
                    order_index = ?,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'HOLOPOWER'
                """,
                archiveOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (moved != 1) {
                throw new IllegalStateException("Holopower 支付失敗，請重新整理後重試");
            }
            archivedCardInstanceIds.add(cardInstanceId);
            archivedCardIds.add(cardId);
        }
        summary.put("paid", archivedCardInstanceIds.size());
        summary.put("archivedCardInstanceIds", archivedCardInstanceIds);
        summary.put("archivedCardIds", archivedCardIds);
        return summary;
    }

    private void returnCollabToBackAsRested(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return;
        }
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'BACK',
                is_rested = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'COLLAB'
            """,
            matchId,
            userId
        );
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

    private Long resolveNextMulliganUser(MatchEntity match, List<MatchPlayerEntity> players) {
        if (match == null || players == null || players.isEmpty()) {
            return null;
        }
        Long first = match.getPlayerAId();
        Long second = match.getPlayerBId();
        if (first != null && !isMulliganDone(players, first)) {
            return first;
        }
        if (second != null && !isMulliganDone(players, second)) {
            return second;
        }
        return null;
    }

    private boolean isMulliganDone(List<MatchPlayerEntity> players, Long userId) {
        if (players == null || userId == null) {
            return true;
        }
        for (MatchPlayerEntity player : players) {
            if (userId.equals(player.getUserId())) {
                return player.isMulliganDone();
            }
        }
        return true;
    }

    private void redrawOpeningHand(Long matchId, Long userId, int drawCount) {
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'DECK',
                order_index = NULL,
                is_face_down = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            matchId,
            userId
        );

        List<Long> deckCardIds = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            ORDER BY id
            """,
            (rs, rowNum) -> rs.getLong("id"),
            matchId,
            userId
        );
        Collections.shuffle(deckCardIds, random);
        for (int i = 0; i < deckCardIds.size(); i++) {
            jdbcTemplate.update(
                """
                UPDATE match_cards
                SET order_index = ?,
                    is_face_down = TRUE,
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

        int finalDrawCount = Math.max(drawCount, 0);
        List<Long> toHandCardIds = jdbcTemplate.query(
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
            finalDrawCount
        );
        for (int i = 0; i < toHandCardIds.size(); i++) {
            jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'HAND',
                    order_index = ?,
                    is_face_down = FALSE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'DECK'
                """,
                i + 1,
                toHandCardIds.get(i),
                matchId,
                userId
            );
        }
    }

    private Long drawTopDeckCardToHand(Long matchId, Long userId) {
        Long deckCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (deckCardInstanceId == null) {
            return null;
        }
        Integer nextHandOrder = jdbcTemplate.queryForObject(
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
        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'HAND',
                order_index = ?,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            nextHandOrder == null ? 1 : nextHandOrder,
            deckCardInstanceId,
            matchId,
            userId
        );
        return updated == 1 ? deckCardInstanceId : null;
    }

    private int countCardsInZone(Long matchId, Long userId, String zone) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            zone
        );
        return count == null ? 0 : count;
    }

    private MulliganResolution enforceOpeningDebutRule(Long matchId, Long userId) {
        int forcedRedrawCount = 0;
        List<Integer> forcedDrawSequence = new ArrayList<>();
        boolean hasDebut = hasDebutMemberInHand(matchId, userId);
        int handCount = countCardsInZone(matchId, userId, "HAND");
        while (!hasDebut) {
            if (handCount <= 1) {
                return new MulliganResolution(forcedRedrawCount, forcedDrawSequence, false, handCount);
            }
            int nextDrawCount = handCount - 1;
            redrawOpeningHand(matchId, userId, nextDrawCount);
            forcedRedrawCount++;
            forcedDrawSequence.add(nextDrawCount);
            handCount = countCardsInZone(matchId, userId, "HAND");
            hasDebut = hasDebutMemberInHand(matchId, userId);
        }
        return new MulliganResolution(forcedRedrawCount, forcedDrawSequence, true, handCount);
    }

    private boolean hasDebutMemberInHand(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_cards mc
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'HAND'
              AND UPPER(COALESCE(m.level_type, '')) = 'DEBUT'
            """,
            Integer.class,
            matchId,
            userId
        );
        return count != null && count > 0;
    }

    private void finishMatchByDefeat(MatchEntity match, Long loserUserId, String reason, int turnNumber) {
        Long winnerUserId = resolveOpponent(match, loserUserId);
        match.setStatus("finished");
        match.setWinnerUserId(winnerUserId);
        match.setFinishedAt(LocalDateTime.now());
        match.setCurrentTurnPlayerId(null);
        match.setCurrentPhase(MatchPhase.END.name());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("loserUserId", loserUserId);
        payload.put("winnerUserId", winnerUserId);
        appendAction(
            match,
            loserUserId,
            "MATCH_FINISHED",
            toJson(payload),
            turnNumber
        );
    }

    private void finishMatchAsDraw(MatchEntity match, Long actorUserId, String reason, int turnNumber) {
        match.setStatus("finished");
        match.setWinnerUserId(null);
        match.setFinishedAt(LocalDateTime.now());
        match.setCurrentTurnPlayerId(null);
        match.setCurrentPhase(MatchPhase.END.name());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("draw", true);
        payload.put("playerAId", match.getPlayerAId());
        payload.put("playerBId", match.getPlayerBId());
        appendAction(
            match,
            actorUserId,
            "MATCH_FINISHED",
            toJson(payload),
            turnNumber
        );
    }

    private boolean evaluateNoHolomemDefeat(MatchEntity match, Long actorUserId, int turnNumber) {
        if (match == null || !"active".equalsIgnoreCase(asString(match.getStatus()))) {
            return false;
        }
        Long playerAId = match.getPlayerAId();
        Long playerBId = match.getPlayerBId();
        if (playerAId == null || playerBId == null) {
            return false;
        }
        int playerAHolomemCount = countStageHolomems(match.getId(), playerAId);
        int playerBHolomemCount = countStageHolomems(match.getId(), playerBId);
        if (playerAHolomemCount > 0 && playerBHolomemCount > 0) {
            return false;
        }
        if (playerAHolomemCount <= 0 && playerBHolomemCount <= 0) {
            finishMatchAsDraw(match, actorUserId, "STAGE_NO_HOLOMEM_BOTH", turnNumber);
            return true;
        }
        Long loserUserId = playerAHolomemCount <= 0 ? playerAId : playerBId;
        finishMatchByDefeat(match, loserUserId, "STAGE_NO_HOLOMEM", turnNumber);
        return true;
    }

    private boolean evaluateLifeDefeat(MatchEntity match, Long actorUserId, int turnNumber) {
        if (match == null || !"active".equalsIgnoreCase(asString(match.getStatus()))) {
            return false;
        }
        Long playerAId = match.getPlayerAId();
        Long playerBId = match.getPlayerBId();
        if (playerAId == null || playerBId == null) {
            return false;
        }
        int playerALifeCount = countCardsInZone(match.getId(), playerAId, "LIFE");
        int playerBLifeCount = countCardsInZone(match.getId(), playerBId, "LIFE");
        if (playerALifeCount > 0 && playerBLifeCount > 0) {
            return false;
        }
        if (playerALifeCount <= 0 && playerBLifeCount <= 0) {
            finishMatchAsDraw(match, actorUserId, "LIFE_ZERO_BOTH", turnNumber);
            return true;
        }
        Long loserUserId = playerALifeCount <= 0 ? playerAId : playerBId;
        finishMatchByDefeat(match, loserUserId, "LIFE_ZERO", turnNumber);
        return true;
    }

    private int countStageHolomems(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
            """,
            Integer.class,
            matchId,
            userId
        );
        return count == null ? 0 : count;
    }

    private boolean hasHolomemDowned(Object summaryObject) {
        if (summaryObject == null) {
            return false;
        }
        if (summaryObject instanceof Map<?, ?> map) {
            Object downed = map.get("downed");
            if (toBoolean(downed)) {
                return true;
            }
            Object executedEffects = map.get("executedEffects");
            if (executedEffects instanceof List<?> effects) {
                for (Object effect : effects) {
                    if (hasHolomemDowned(effect)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasLifeReduced(Object summaryObject) {
        if (summaryObject == null) {
            return false;
        }
        if (summaryObject instanceof Map<?, ?> map) {
            if (toBoolean(map.get("lifeReduced"))) {
                return true;
            }
            if (map.get("lostLifeCardInstanceId") != null) {
                return true;
            }
            Object executedEffects = map.get("executedEffects");
            if (executedEffects instanceof List<?> effects) {
                for (Object effect : effects) {
                    if (hasLifeReduced(effect)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void enqueueLifeLossSendCheerInteractions(
        MatchEntity match,
        Long matchId,
        Object effectSummary,
        int turnNumber
    ) {
        if (!isMatchActive(match) || matchId == null || matchId <= 0) {
            return;
        }
        List<Long> lostLifeCardInstanceIds = collectLostLifeCardInstanceIds(effectSummary);
        if (lostLifeCardInstanceIds.isEmpty()) {
            return;
        }
        for (Long lostLifeCardInstanceId : lostLifeCardInstanceIds) {
            if (lostLifeCardInstanceId == null || lostLifeCardInstanceId <= 0) {
                continue;
            }
            Long lifeOwnerUserId = jdbcTemplate.query(
                """
                SELECT owner_user_id
                FROM match_cards
                WHERE match_id = ?
                  AND id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("owner_user_id") : null,
                matchId,
                lostLifeCardInstanceId
            );
            if (lifeOwnerUserId == null || lifeOwnerUserId <= 0) {
                continue;
            }
            Long sendCheerInteractionId = createSendCheerPendingInteraction(
                matchId,
                lifeOwnerUserId,
                lostLifeCardInstanceId,
                "LIFE_LOSS",
                "生命減少：發送吶喊",
                "請將本次減少生命產生的吶喊發送到 1 位我方 Holomem。"
            );
            if (sendCheerInteractionId == null) {
                continue;
            }
            Map<String, Object> interactionPayload = new LinkedHashMap<>();
            interactionPayload.put("interactionId", sendCheerInteractionId);
            interactionPayload.put("interactionType", INTERACTION_TYPE_SEND_CHEER);
            interactionPayload.put("sourceActionType", "LIFE_LOSS");
            interactionPayload.put("sourceCardInstanceId", lostLifeCardInstanceId);
            appendAction(match, lifeOwnerUserId, "INTERACTION_PENDING", toJson(interactionPayload), turnNumber);
        }
    }

    private List<Long> collectLostLifeCardInstanceIds(Object summaryObject) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        collectLostLifeCardInstanceIdsRecursive(summaryObject, ids);
        return new ArrayList<>(ids);
    }

    private void collectLostLifeCardInstanceIdsRecursive(Object summaryObject, Set<Long> sink) {
        if (summaryObject == null || sink == null) {
            return;
        }
        if (summaryObject instanceof Map<?, ?> map) {
            Long singleId = asLong(map.get("lostLifeCardInstanceId"));
            if (singleId != null && singleId > 0) {
                sink.add(singleId);
            }
            Object idList = map.get("lostLifeCardInstanceIds");
            if (idList instanceof List<?> list) {
                for (Object item : list) {
                    Long id = asLong(item);
                    if (id != null && id > 0) {
                        sink.add(id);
                    }
                }
            }
            Object executedEffects = map.get("executedEffects");
            if (executedEffects instanceof List<?> effects) {
                for (Object effect : effects) {
                    collectLostLifeCardInstanceIdsRecursive(effect, sink);
                }
            }
            return;
        }
        if (summaryObject instanceof List<?> list) {
            for (Object item : list) {
                collectLostLifeCardInstanceIdsRecursive(item, sink);
            }
        }
    }

    private boolean evaluateCardEffectMatchFinish(
        MatchEntity match,
        Long actorUserId,
        int turnNumber,
        Object summaryObject
    ) {
        if (match == null || !"active".equalsIgnoreCase(asString(match.getStatus()))) {
            return false;
        }
        Map<String, Object> matchResult = extractMatchResult(summaryObject);
        if (matchResult == null || matchResult.isEmpty()) {
            return false;
        }
        String reason = asString(matchResult.get("reason"));
        if (!StringUtils.hasText(reason)) {
            reason = "CARD_EFFECT_MATCH_RESULT";
        }
        if (toBoolean(matchResult.get("draw"))) {
            finishMatchAsDraw(match, actorUserId, reason, turnNumber);
            return true;
        }

        Long loserUserId = asLong(matchResult.get("loserUserId"));
        Long winnerUserId = asLong(matchResult.get("winnerUserId"));
        if (loserUserId == null && winnerUserId == null) {
            return false;
        }
        if (loserUserId == null && winnerUserId != null) {
            loserUserId = winnerUserId.equals(match.getPlayerAId()) ? match.getPlayerBId() : match.getPlayerAId();
        }
        if (loserUserId == null) {
            return false;
        }
        finishMatchByDefeat(match, loserUserId, reason, turnNumber);
        return true;
    }

    private Map<String, Object> extractMatchResult(Object summaryObject) {
        if (!(summaryObject instanceof Map<?, ?> map)) {
            return null;
        }
        Object direct = map.get("matchResult");
        if (direct instanceof Map<?, ?> directMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : directMap.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                result.put(entry.getKey().toString(), entry.getValue());
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        Object executedEffects = map.get("executedEffects");
        if (executedEffects instanceof List<?> effects) {
            for (Object effect : effects) {
                Map<String, Object> nested = extractMatchResult(effect);
                if (nested != null && !nested.isEmpty()) {
                    return nested;
                }
            }
        }
        return null;
    }

    private Map<String, Object> loadMemberCardSpec(String cardId) {
        return jdbcTemplate.query(
            """
            SELECT c.name, m.level_type, m.hp, m.bloom_level
            FROM member_cards m
            JOIN cards c ON c.card_id = m.card_id
            WHERE m.card_id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rs.getString("name"));
                row.put("level_type", rs.getString("level_type"));
                row.put("hp", rs.getObject("hp"));
                row.put("bloom_level", rs.getObject("bloom_level"));
                return row;
            },
            cardId
        );
    }

    private BloomTarget loadOwnedBloomTarget(Long matchId, Long userId, Long targetHolomemCardInstanceId) {
        return jdbcTemplate.query(
            """
            SELECT
                h.id AS holomem_id,
                h.zone,
                h.card_id AS top_card_id,
                c.name AS top_card_name,
                m.level_type AS top_level_type,
                h.damage_taken,
                h.entered_turn_number,
                h.last_bloom_turn
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next()
                ? new BloomTarget(
                    rs.getLong("holomem_id"),
                    normalizeZone(rs.getString("zone")),
                    rs.getString("top_card_id"),
                    rs.getString("top_card_name"),
                    normalizeLevel(rs.getString("top_level_type")),
                    rs.getInt("damage_taken"),
                    rs.getInt("entered_turn_number"),
                    asLong(rs.getObject("last_bloom_turn"))
                )
                : null,
            matchId,
            userId,
            targetHolomemCardInstanceId
        );
    }

    private boolean isSpecialOrUnbloomableLevel(String levelType) {
        String normalized = normalizeLevel(levelType);
        return "SPOT".equals(normalized);
    }

    private boolean isBloomLevelNextStep(String targetLevel, String bloomLevel) {
        int targetRank = resolveBloomLevelRank(targetLevel);
        int bloomRank = resolveBloomLevelRank(bloomLevel);
        if (targetRank < 0 || bloomRank < 0) {
            return false;
        }
        return bloomRank == targetRank + 1;
    }

    private int resolveBloomLevelRank(String levelType) {
        String normalized = normalizeLevel(levelType);
        return switch (normalized) {
            case "DEBUT" -> 0;
            case "FIRST" -> 1;
            case "SECOND" -> 2;
            case "BUZZ" -> 3;
            default -> -1;
        };
    }

    private void recordHolomemStackCard(Long matchHolomemId, Long matchCardId) {
        if (matchHolomemId == null || matchCardId == null) {
            return;
        }
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(stack_order), 0) + 1
            FROM match_holomem_stack_cards
            WHERE match_holomem_id = ?
            """,
            Integer.class,
            matchHolomemId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_stack_cards (match_holomem_id, match_card_id, stack_order)
            VALUES (?, ?, ?)
            ON CONFLICT (match_card_id) DO NOTHING
            """,
            matchHolomemId,
            matchCardId,
            nextOrder == null ? 1 : nextOrder
        );
    }

    private int countHolomemStackDepth(Long matchHolomemId) {
        if (matchHolomemId == null) {
            return 0;
        }
        Integer depth = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_stack_cards WHERE match_holomem_id = ?",
            Integer.class,
            matchHolomemId
        );
        return depth == null || depth <= 0 ? 1 : depth;
    }

    private Long resolveOpponent(MatchEntity match, Long userId) {
        if (match.getPlayerAId() != null && !match.getPlayerAId().equals(userId)) {
            return match.getPlayerAId();
        }
        if (match.getPlayerBId() != null && !match.getPlayerBId().equals(userId)) {
            return match.getPlayerBId();
        }
        throw new IllegalStateException("找不到對手玩家");
    }

    private MatchPhase parsePhase(String text) {
        if (!StringUtils.hasText(text)) {
            return MatchPhase.RESET;
        }
        try {
            return MatchPhase.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MatchPhase.RESET;
        }
    }

    private Long requirePositiveId(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " 不可為空");
        }
        return value;
    }

    private String normalizeZone(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeLevel(String levelType) {
        String normalized = normalizeZone(levelType);
        if (
            "DEBUT".equals(normalized) ||
            "FIRST".equals(normalized) ||
            "SECOND".equals(normalized) ||
            "SPOT".equals(normalized) ||
            "BUZZ".equals(normalized)
        ) {
            return normalized;
        }
        return "DEBUT";
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private boolean hasBlockingPendingDecision(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = ?
            """,
            Integer.class,
            matchId,
            userId,
            PENDING_STATUS
        );
        return count != null && count > 0;
    }

    private boolean hasAnyPendingDecision(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = ?
            """,
            Integer.class,
            matchId,
            userId,
            PENDING_STATUS
        );
        return count != null && count > 0;
    }

    private Long createTurnStartPendingInteraction(Long matchId, Long userId, int turnNumber) {
        if (matchId == null || userId == null || userId <= 0) {
            return null;
        }
        if (hasAnyPendingDecision(matchId, userId)) {
            return null;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_TURN_START);
        context.put("title", "回合開始");
        context.put("message", "現在是你的回合。請先確認，再由你手動執行抽牌與吶喊操作。");
        context.put("turnNumber", turnNumber);

        return jdbcTemplate.query(
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
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            INTERACTION_TYPE_TURN_START,
            INTERACTION_TYPE_TURN_START,
            INTERACTION_TYPE_TURN_START,
            PENDING_STATUS,
            toJson(context)
        );
    }

    private Long createDrawRevealPendingInteraction(Long matchId, Long userId, Long drawnCardInstanceId) {
        if (drawnCardInstanceId == null || drawnCardInstanceId <= 0) {
            return null;
        }
        if (hasAnyPendingDecision(matchId, userId)) {
            return null;
        }

        Map<String, Object> drawnCard = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   c.name,
                   c.card_type,
                   c.image_url
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cardInstanceId", rs.getLong("card_instance_id"));
                row.put("cardId", rs.getString("card_id"));
                row.put("name", rs.getString("name"));
                row.put("cardType", rs.getString("card_type"));
                row.put("zone", "HAND");
                row.put("imageUrl", rs.getString("image_url"));
                return row;
            },
            matchId,
            userId,
            drawnCardInstanceId
        );
        if (drawnCard == null) {
            return null;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_DRAW_REVEAL);
        context.put("title", "回合抽牌");
        context.put("message", "你抽到 1 張牌，確認後可繼續操作。");
        context.put("cards", List.of(drawnCard));

        return jdbcTemplate.query(
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
            ) VALUES (?, ?, ?, 'DRAW_TURN', ?, ?, ?, 1, 1, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            INTERACTION_TYPE_DRAW_REVEAL,
            drawnCardInstanceId,
            asString(drawnCard.get("cardId")),
            INTERACTION_TYPE_DRAW_REVEAL,
            PENDING_STATUS,
            toJson(context)
        );
    }

    private Long createTurnSendCheerPendingInteraction(Long matchId, Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        Long cheerCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CHEER_DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (cheerCardInstanceId == null) {
            return null;
        }
        return createSendCheerPendingInteraction(
            matchId,
            userId,
            cheerCardInstanceId,
            "TURN_CHEER",
            "回合吶喊",
            "請從エール牌庫發送 1 張吶喊到我方 Holomem。"
        );
    }

    private Long createSendCheerPendingInteraction(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceActionType,
        String title,
        String message
    ) {
        if (sourceCardInstanceId == null || sourceCardInstanceId <= 0 || userId == null || userId <= 0) {
            return null;
        }
        Map<String, Object> sourceCard = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   mc.zone,
                   c.name,
                   c.card_type,
                   c.image_url
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cardInstanceId", rs.getLong("card_instance_id"));
                row.put("cardId", rs.getString("card_id"));
                row.put("zone", rs.getString("zone"));
                row.put("name", rs.getString("name"));
                row.put("cardType", rs.getString("card_type"));
                row.put("imageUrl", rs.getString("image_url"));
                return row;
            },
            matchId,
            userId,
            sourceCardInstanceId
        );
        if (sourceCard == null) {
            return null;
        }
        String sourceCardId = asString(sourceCard.get("cardId"));
        Integer cheerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM cheer_cards WHERE card_id = ?",
            Integer.class,
            sourceCardId
        );
        if (cheerCount == null || cheerCount <= 0) {
            return null;
        }
        List<Map<String, Object>> candidateRows = jdbcTemplate.queryForList(
            """
            SELECT h.match_card_id AS card_instance_id,
                   h.card_id,
                   h.zone,
                   c.name,
                   c.card_type,
                   m.level_type,
                   c.image_url
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            LEFT JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER','COLLAB','BACK')
            ORDER BY CASE h.zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, h.id
            """,
            matchId,
            userId
        );
        if (candidateRows.isEmpty()) {
            return null;
        }
        List<Long> candidateCardInstanceIds = new ArrayList<>();
        List<Map<String, Object>> candidateCards = new ArrayList<>();
        for (Map<String, Object> row : candidateRows) {
            Long candidateCardInstanceId = asLong(row.get("card_instance_id"));
            if (candidateCardInstanceId == null || candidateCardInstanceId <= 0) {
                continue;
            }
            candidateCardInstanceIds.add(candidateCardInstanceId);
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("cardInstanceId", candidateCardInstanceId);
            candidate.put("cardId", asString(row.get("card_id")));
            candidate.put("name", asString(row.get("name")));
            candidate.put("cardType", asString(row.get("card_type")));
            candidate.put("levelType", asString(row.get("level_type")));
            candidate.put("zone", asString(row.get("zone")));
            candidate.put("imageUrl", asString(row.get("image_url")));
            candidateCards.add(candidate);
        }
        if (candidateCards.isEmpty()) {
            return null;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_SEND_CHEER);
        context.put("title", title);
        context.put("message", message);
        context.put("sourceZone", normalizeZone(sourceCard.get("zone")));
        context.put("cards", candidateCards);
        context.put("candidateCardInstanceIds", candidateCardInstanceIds);

        return jdbcTemplate.query(
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
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            INTERACTION_TYPE_SEND_CHEER,
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            INTERACTION_TYPE_SEND_CHEER,
            PENDING_STATUS,
            toJson(context)
        );
    }

    private Long createCardSelectionPendingDecision(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        String effectJson,
        String targetType,
        Long targetHolomemCardInstanceId,
        MatchEffectService.SupportDecisionPlan decisionPlan,
        boolean limited
    ) {
        if (hasBlockingPendingDecision(matchId, userId)) {
            throw new IllegalStateException("你有待處理的效果選擇，請先完成決策");
        }
        List<Long> candidateCardInstanceIds = new ArrayList<>();
        List<Map<String, Object>> candidateCards = new ArrayList<>();
        for (MatchEffectService.DecisionCandidate candidate : decisionPlan.candidates()) {
            if (candidate == null || candidate.cardInstanceId() == null) {
                continue;
            }
            candidateCardInstanceIds.add(candidate.cardInstanceId());
            Map<String, Object> candidatePayload = new LinkedHashMap<>();
            candidatePayload.put("cardInstanceId", candidate.cardInstanceId());
            candidatePayload.put("cardId", candidate.cardId());
            candidatePayload.put("name", candidate.name());
            candidatePayload.put("cardType", candidate.cardType());
            candidatePayload.put("levelType", candidate.levelType());
            candidatePayload.put("zone", candidate.zone());
            candidateCards.add(candidatePayload);
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("effectType", effectType);
        context.put("effectJson", effectJson);
        context.put("targetType", targetType);
        context.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        context.put("candidateCardInstanceIds", candidateCardInstanceIds);
        context.put("candidateCards", candidateCards);
        context.put("limited", limited);

        return jdbcTemplate.query(
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
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            SUPPORT_DECISION_TYPE_CARD_SELECTION,
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            decisionPlan.effectType(),
            decisionPlan.minSelect(),
            decisionPlan.maxSelect(),
            PENDING_STATUS,
            toJson(context)
        );
    }

    private Long createLookTopDeckPendingDecisionIfNeeded(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        Map<String, Object> effectSummary
    ) {
        LookTopDeckDecisionContext lookTopDeck = extractLookTopDeckDecisionContext(effectSummary);
        if (lookTopDeck == null || lookTopDeck.cardInstanceId() == null || !StringUtils.hasText(lookTopDeck.cardId())) {
            return null;
        }
        if (hasBlockingPendingDecision(matchId, userId)) {
            throw new IllegalStateException("你有待處理的互動，請先完成確認");
        }

        Map<String, Object> candidate = loadCardCandidateForDecision(
            matchId,
            userId,
            lookTopDeck.cardInstanceId(),
            "DECK",
            lookTopDeck.cardId()
        );
        List<Long> candidateCardInstanceIds = List.of(lookTopDeck.cardInstanceId());
        List<Map<String, Object>> candidateCards = List.of(candidate);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", DECISION_TYPE_LOOK_TOP_DECK);
        context.put("title", "查看牌庫頂");
        context.put("message", "選擇保留在牌庫頂的卡片；若不選擇則放到底部。");
        context.put("cards", candidateCards);
        context.put("placementOptions", List.of("TOP", "BOTTOM"));
        context.put("effectType", effectType);
        context.put("candidateCardInstanceIds", candidateCardInstanceIds);
        context.put("candidateCards", candidateCards);
        context.put("lookedCardInstanceId", lookTopDeck.cardInstanceId());
        context.put("lookedCardId", lookTopDeck.cardId());

        return jdbcTemplate.query(
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
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            DECISION_TYPE_LOOK_TOP_DECK,
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            effectType,
            PENDING_STATUS,
            toJson(context)
        );
    }

    private Map<String, Object> loadCardCandidateForDecision(
        Long matchId,
        Long userId,
        Long cardInstanceId,
        String fallbackZone,
        String fallbackCardId
    ) {
        Map<String, Object> row = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   mc.zone,
                   c.name,
                   c.card_type,
                   c.image_url,
                   m.level_type
            FROM match_cards mc
            LEFT JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("cardInstanceId", rs.getLong("card_instance_id"));
                value.put("cardId", rs.getString("card_id"));
                value.put("zone", normalizeZone(rs.getString("zone")));
                value.put("name", rs.getString("name"));
                value.put("cardType", rs.getString("card_type"));
                value.put("imageUrl", rs.getString("image_url"));
                value.put("levelType", rs.getString("level_type"));
                return value;
            },
            matchId,
            userId,
            cardInstanceId
        );
        if (row != null) {
            return row;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("cardInstanceId", cardInstanceId);
        fallback.put("cardId", fallbackCardId);
        fallback.put("zone", normalizeZone(fallbackZone));
        fallback.put("name", null);
        fallback.put("cardType", null);
        fallback.put("imageUrl", null);
        fallback.put("levelType", null);
        return fallback;
    }

    private String normalizeDecisionPlacement(String placement) {
        if (!StringUtils.hasText(placement)) {
            return null;
        }
        return placement.trim().toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private LookTopDeckDecisionContext extractLookTopDeckDecisionContext(Map<String, Object> effectSummary) {
        if (effectSummary == null || effectSummary.isEmpty()) {
            return null;
        }
        Object executedEffects = effectSummary.get("executedEffects");
        if (!(executedEffects instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> effectRow)) {
                continue;
            }
            String resolvedType = normalizeZone(effectRow.get("effectType"));
            if (!DECISION_TYPE_LOOK_TOP_DECK.equals(resolvedType)) {
                continue;
            }
            if (!toBoolean(effectRow.get("applied"))) {
                continue;
            }
            Long lookedCardInstanceId = asLong(effectRow.get("lookedCardInstanceId"));
            String lookedCardId = asString(effectRow.get("lookedCardId"));
            if (lookedCardInstanceId != null && StringUtils.hasText(lookedCardId)) {
                return new LookTopDeckDecisionContext(lookedCardInstanceId, lookedCardId);
            }
        }
        return null;
    }

    private PendingDecision loadPendingDecisionForUpdate(Long matchId, Long userId, Long decisionId) {
        return jdbcTemplate.query(
            """
            SELECT id,
                   decision_type,
                   source_action_type,
                   source_card_instance_id,
                   source_card_id,
                   effect_type,
                   min_select,
                   max_select,
                   context_json::text AS context_text
            FROM match_pending_decisions
            WHERE id = ?
              AND match_id = ?
              AND user_id = ?
              AND status = ?
            FOR UPDATE
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                String contextText = rs.getString("context_text");
                JsonNode contextNode = parseJson(contextText);
                int minSelect = Math.max(rs.getInt("min_select"), 0);
                int maxSelect = Math.max(rs.getInt("max_select"), minSelect);
                return new PendingDecision(
                    rs.getLong("id"),
                    normalizeZone(rs.getString("decision_type")),
                    normalizeZone(rs.getString("source_action_type")),
                    rs.getLong("source_card_instance_id"),
                    rs.getString("source_card_id"),
                    normalizeZone(rs.getString("effect_type")),
                    minSelect,
                    maxSelect,
                    extractJsonLong(contextNode, "targetHolomemCardInstanceId"),
                    extractJsonText(contextNode, "targetType"),
                    extractJsonText(contextNode, "effectJson"),
                    extractJsonLongList(contextNode, "candidateCardInstanceIds"),
                    extractJsonBoolean(contextNode, "limited")
                );
            },
            decisionId,
            matchId,
            userId,
            PENDING_STATUS
        );
    }

    private void markDecisionResolved(Long decisionId) {
        int updated = jdbcTemplate.update(
            """
            UPDATE match_pending_decisions
            SET status = 'RESOLVED',
                resolved_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND status = ?
            """,
            decisionId,
            PENDING_STATUS
        );
        if (updated != 1) {
            throw new IllegalStateException("決策已失效，請重新整理對戰狀態");
        }
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

    private JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.nullNode();
        }
    }

    private Long extractJsonLong(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !StringUtils.hasText(fieldName)) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractJsonText(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !StringUtils.hasText(fieldName)) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private boolean extractJsonBoolean(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !StringUtils.hasText(fieldName)) {
            return false;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            return Boolean.parseBoolean(value.asText());
        }
        return false;
    }

    private List<Long> extractJsonLongList(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !StringUtils.hasText(fieldName)) {
            return List.of();
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || !value.isArray()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (JsonNode item : value) {
            Long id = null;
            if (item != null && item.isNumber()) {
                id = item.longValue();
            } else if (item != null && item.isTextual()) {
                try {
                    id = Long.parseLong(item.asText().trim());
                } catch (NumberFormatException ignored) {
                    id = null;
                }
            }
            if (id == null || id <= 0 || result.contains(id)) {
                continue;
            }
            result.add(id);
        }
        return result;
    }

    private boolean hasSupportDefinitionLimitedFlag(String cardId) {
        if (!StringUtils.hasText(cardId)) {
            return false;
        }
        Boolean limited = jdbcTemplate.query(
            """
            SELECT is_limited
            FROM support_cards
            WHERE card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getBoolean("is_limited") : null,
            cardId
        );
        return limited != null && limited;
    }

    private String resolveSupportAttachmentType(String effectJsonText) {
        if (!StringUtils.hasText(effectJsonText)) {
            return SUPPORT_TYPE_OTHER;
        }
        String merged = effectJsonText;
        try {
            JsonNode root = objectMapper.readTree(effectJsonText);
            if (root != null && !root.isNull()) {
                String rawHeader = root.path("rawHeader").asText("");
                String rawEffect = root.path("rawEffect").asText("");
                String rawText = root.path("rawText").asText("");
                merged = rawHeader + "\n" + rawEffect + "\n" + rawText + "\n" + effectJsonText;
            }
        } catch (Exception ignored) {
            // fallback: keep raw json text
        }
        if (merged.contains("サポート・マスコット")) {
            return SUPPORT_TYPE_MASCOT;
        }
        if (merged.contains("サポート・ツール")) {
            return SUPPORT_TYPE_TOOL;
        }
        if (merged.contains("サポート・ファン")) {
            return SUPPORT_TYPE_FAN;
        }
        return SUPPORT_TYPE_OTHER;
    }

    private boolean isAttachableSupportType(String supportType) {
        String normalized = normalizeZone(supportType);
        return SUPPORT_TYPE_MASCOT.equals(normalized)
            || SUPPORT_TYPE_TOOL.equals(normalized)
            || SUPPORT_TYPE_FAN.equals(normalized);
    }

    private Long resolveOwnedHolomemIdByCardInstance(Long matchId, Long userId, Long holomemCardInstanceId) {
        if (matchId == null || userId == null || holomemCardInstanceId == null || holomemCardInstanceId <= 0) {
            return null;
        }
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
            userId,
            holomemCardInstanceId
        );
    }

    private void validateAttachableSupportLimit(Long matchHolomemId, String supportType) {
        String normalized = normalizeZone(supportType);
        if (!SUPPORT_TYPE_MASCOT.equals(normalized) && !SUPPORT_TYPE_TOOL.equals(normalized)) {
            return;
        }
        Integer alreadyAttached = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomem_supports
            WHERE match_holomem_id = ?
              AND support_type = ?
            """,
            Integer.class,
            matchHolomemId,
            normalized
        );
        if (alreadyAttached != null && alreadyAttached > 0) {
            String supportLabel = SUPPORT_TYPE_MASCOT.equals(normalized) ? "マスコット" : "ツール";
            throw new IllegalStateException("同一 Holomem 只能附加 1 張" + supportLabel);
        }
    }

    private boolean isMatchActive(MatchEntity match) {
        if (match == null) {
            return false;
        }
        return "active".equalsIgnoreCase(asString(match.getStatus()));
    }

    private boolean hasUsedLimitedSupportThisTurn(Long matchId, Long userId, int turnNumber) {
        Integer usedCount = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_actions ma
            JOIN support_cards sc
              ON sc.card_id = ma.payload ->> 'cardId'
            WHERE ma.match_id = ?
              AND ma.user_id = ?
              AND ma.turn_number = ?
              AND ma.action_type = 'PLAY_SUPPORT'
              AND sc.is_limited = TRUE
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            turnNumber
        );
        return usedCount != null && usedCount > 0;
    }

    private int countArtUsedByZoneThisTurn(Long matchId, Long userId, int turnNumber, String zone) {
        String normalizedZone = normalizeZone(zone);
        Integer used = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_actions ma
            WHERE ma.match_id = ?
              AND ma.user_id = ?
              AND ma.turn_number = ?
              AND ma.action_type = 'ATTACK_ART'
              AND UPPER(COALESCE(ma.payload ->> 'attackerZone', '')) = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            turnNumber,
            normalizedZone
        );
        return used == null ? 0 : used;
    }

    private boolean hasAvailableArtAttacker(Long matchId, Long userId, int turnNumber) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT zone, is_rested
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB')
            """,
            matchId,
            userId
        );
        for (Map<String, Object> row : rows) {
            String zone = normalizeZone(row.get("zone"));
            boolean rested = toBoolean(row.get("is_rested"));
            if (!rested && countArtUsedByZoneThisTurn(matchId, userId, turnNumber, zone) == 0) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> loadPrimaryArt(String cardId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT name,
                   effect_json::text AS effect_json_text,
                   cost_cheer_json::text AS cost_cheer_json_text,
                   order_index
            FROM member_arts
            WHERE member_card_id = ?
            ORDER BY order_index ASC, id ASC
            """,
            cardId
        );
        if (rows.isEmpty()) {
            return null;
        }
        for (Map<String, Object> row : rows) {
            if (resolveArtDamage(asString(row.get("effect_json_text"))) > 0) {
                return row;
            }
        }
        return rows.get(0);
    }

    private int resolveArtDamage(String effectJsonText) {
        if (!StringUtils.hasText(effectJsonText)) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(effectJsonText);
            int parsed = resolveArtDamageFromEffectJson(root);
            if (parsed > 0) {
                return parsed;
            }
        } catch (Exception ignored) {
            // JSON 解析失敗時走文字 fallback
        }
        return extractFirstNumber(effectJsonText);
    }

    private int resolveArtDamageFromEffectJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.has("value") && node.path("value").canConvertToInt()) {
            int value = node.path("value").asInt(0);
            if (value > 0) {
                return value;
            }
        }
        if (node.has("damage") && node.path("damage").canConvertToInt()) {
            int value = node.path("damage").asInt(0);
            if (value > 0) {
                return value;
            }
        }
        if (node.has("amount") && node.path("amount").canConvertToInt()) {
            int value = node.path("amount").asInt(0);
            if (value > 0) {
                return value;
            }
        }
        int fallback = extractFirstNumber(node.path("rawHeader").asText(""));
        if (fallback > 0) {
            return fallback;
        }
        fallback = extractFirstNumber(node.path("rawEffect").asText(""));
        if (fallback > 0) {
            return fallback;
        }
        return extractFirstNumber(node.path("rawText").asText(""));
    }

    private Map<String, Integer> resolveArtCheerCost(String costCheerJsonText) {
        Map<String, Integer> cost = new LinkedHashMap<>();
        if (!StringUtils.hasText(costCheerJsonText)) {
            return cost;
        }
        try {
            JsonNode root = objectMapper.readTree(costCheerJsonText);
            if (root == null || !root.isObject()) {
                return cost;
            }
            root.fields().forEachRemaining(entry -> {
                String color = normalizeZone(entry.getKey());
                int required = entry.getValue() == null ? 0 : entry.getValue().asInt(0);
                if (StringUtils.hasText(color) && required > 0) {
                    cost.put(color, required);
                }
            });
        } catch (Exception ignored) {
            // 解析失敗時視為無費用，避免對戰流程中斷
        }
        return cost;
    }

    private Map<String, Object> payArtCost(
        Long matchId,
        Long ownerUserId,
        Long attackerHolomemId,
        Map<String, Integer> requiredCost
    ) {
        Map<String, Integer> normalizedRequired = new LinkedHashMap<>();
        int totalRequired = 0;
        if (requiredCost != null) {
            for (Map.Entry<String, Integer> entry : requiredCost.entrySet()) {
                String color = normalizeZone(entry.getKey());
                int count = entry.getValue() == null ? 0 : entry.getValue();
                if (!StringUtils.hasText(color) || count <= 0) {
                    continue;
                }
                normalizedRequired.put(color, count);
                totalRequired += count;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("required", normalizedRequired);
        summary.put("requiredTotal", totalRequired);
        if (totalRequired <= 0) {
            summary.put("paid", Map.of());
            summary.put("paidTotal", 0);
            summary.put("paidCheerCardIds", List.of());
            summary.put("paidCheerCardInstanceIds", List.of());
            summary.put("paidColors", List.of());
            return summary;
        }

        List<Map<String, Object>> attachedRows = jdbcTemplate.queryForList(
            """
            SELECT mhc.id AS cheer_row_id,
                   mhc.cheer_card_id,
                   cc.color
            FROM match_holomem_cheers mhc
            JOIN cheer_cards cc ON cc.card_id = mhc.cheer_card_id
            WHERE mhc.match_holomem_id = ?
            ORDER BY mhc.id
            """,
            attackerHolomemId
        );
        if (attachedRows.isEmpty()) {
            throw new IllegalStateException("藝能費用不足：未附加任何 Cheer");
        }

        List<Map<String, Object>> remaining = new ArrayList<>(attachedRows);
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : normalizedRequired.entrySet()) {
            String color = entry.getKey();
            if ("COLORLESS".equals(color)) {
                continue;
            }
            int required = entry.getValue();
            for (int i = 0; i < required; i++) {
                int idx = findFirstCheerIndexByColor(remaining, color);
                if (idx < 0) {
                    throw new IllegalStateException("藝能費用不足：需要 " + color + " Cheer x" + required);
                }
                selected.add(remaining.remove(idx));
            }
        }
        int colorlessRequired = normalizedRequired.getOrDefault("COLORLESS", 0);
        for (int i = 0; i < colorlessRequired; i++) {
            if (remaining.isEmpty()) {
                throw new IllegalStateException("藝能費用不足：需要無色 Cheer x" + colorlessRequired);
            }
            selected.add(remaining.remove(0));
        }

        Map<String, Integer> paid = new LinkedHashMap<>();
        List<String> paidCheerCardIds = new ArrayList<>();
        List<Long> paidCheerCardInstanceIds = new ArrayList<>();
        List<String> paidColors = new ArrayList<>();
        for (Map<String, Object> row : selected) {
            String cheerCardId = asString(row.get("cheer_card_id"));
            String color = normalizeZone(row.get("color"));
            if (!StringUtils.hasText(cheerCardId) || !StringUtils.hasText(color)) {
                continue;
            }
            paid.put(color, paid.getOrDefault(color, 0) + 1);
            paidCheerCardIds.add(cheerCardId);
            paidColors.add(color);
        }

        summary.put("paid", paid);
        summary.put("paidTotal", selected.size());
        summary.put("paidCheerCardIds", paidCheerCardIds);
        summary.put("paidCheerCardInstanceIds", paidCheerCardInstanceIds);
        summary.put("paidColors", paidColors);
        summary.put("consumed", false);
        return summary;
    }

    private int resolveBatonTouchColorlessModifier(Long matchId, Long ownerUserId, Long sourceHolomemId, int currentTurn) {
        if (matchId == null || ownerUserId == null || sourceHolomemId == null || currentTurn <= 0) {
            return 0;
        }
        Integer modifier = jdbcTemplate.query(
            """
            SELECT COALESCE(SUM(modifier_value), 0) AS total
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'BATON_TOUCH_COLORLESS_MODIFIER'
              AND expires_turn >= ?
              AND payload ->> 'targetHolomemId' = ?
            """,
            rs -> rs.next() ? rs.getInt("total") : 0,
            matchId,
            ownerUserId,
            currentTurn,
            sourceHolomemId.toString()
        );
        return modifier == null ? 0 : modifier;
    }

    private boolean isStageActionLocked(
        Long matchId,
        Long userId,
        int currentTurn,
        String actionKey,
        String zone,
        Long holomemId
    ) {
        if (matchId == null || userId == null || currentTurn <= 0 || !StringUtils.hasText(actionKey)) {
            return false;
        }
        List<String> payloads = jdbcTemplate.query(
            """
            SELECT payload::text AS payload_text
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ACTION_LOCK'
              AND expires_turn >= ?
            ORDER BY id DESC
            """,
            (rs, rowNum) -> rs.getString("payload_text"),
            matchId,
            userId,
            currentTurn
        );
        if (payloads.isEmpty()) {
            return false;
        }
        String normalizedAction = normalizeZone(actionKey);
        String normalizedZone = normalizeZone(zone);
        for (String payloadText : payloads) {
            JsonNode payload = parseJson(payloadText);
            if (payload == null || payload.isNull()) {
                continue;
            }
            if (!matchesLockAction(payload, normalizedAction)) {
                continue;
            }
            if (!matchesLockZone(payload, normalizedZone)) {
                continue;
            }
            if (!matchesLockTargetHolomem(payload, holomemId)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean matchesLockAction(JsonNode payload, String actionKey) {
        JsonNode actions = payload.get("actions");
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            return true;
        }
        for (JsonNode actionNode : actions) {
            if (normalizeZone(actionNode.asText()).equals(actionKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesLockZone(JsonNode payload, String zone) {
        JsonNode zones = payload.get("zones");
        if (zones == null || !zones.isArray() || zones.isEmpty()) {
            return true;
        }
        for (JsonNode zoneNode : zones) {
            if (normalizeZone(zoneNode.asText()).equals(zone)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesLockTargetHolomem(JsonNode payload, Long holomemId) {
        JsonNode targetHolomemId = payload.get("targetHolomemId");
        if (targetHolomemId == null || targetHolomemId.isNull()) {
            return true;
        }
        Long expected = asLong(targetHolomemId.asText());
        if (expected == null || expected <= 0) {
            return true;
        }
        return holomemId != null && holomemId.equals(expected);
    }

    private Map<String, Object> payBatonTouchCost(
        Long matchId,
        Long ownerUserId,
        Long sourceHolomemId,
        int requiredColorless
    ) {
        int required = Math.max(requiredColorless, 0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requiredColorless", required);
        if (required <= 0) {
            summary.put("paidTotal", 0);
            summary.put("paidCheerCardIds", List.of());
            summary.put("paidCheerCardInstanceIds", List.of());
            summary.put("paidColors", List.of());
            summary.put("consumed", true);
            return summary;
        }

        List<Map<String, Object>> attachedRows = jdbcTemplate.queryForList(
            """
            SELECT mhc.id AS cheer_row_id,
                   mhc.cheer_card_id,
                   cc.color
            FROM match_holomem_cheers mhc
            JOIN cheer_cards cc ON cc.card_id = mhc.cheer_card_id
            WHERE mhc.match_holomem_id = ?
            ORDER BY mhc.id
            """,
            sourceHolomemId
        );
        if (attachedRows.size() < required) {
            throw new IllegalStateException("バトンタッチ費用不足：需要無色 Cheer x" + required);
        }

        List<String> paidCheerCardIds = new ArrayList<>();
        List<Long> paidCheerCardInstanceIds = new ArrayList<>();
        List<String> paidColors = new ArrayList<>();
        for (int i = 0; i < required; i++) {
            Map<String, Object> row = attachedRows.get(i);
            Long cheerRowId = asLong(row.get("cheer_row_id"));
            String cheerCardId = asString(row.get("cheer_card_id"));
            String color = normalizeZone(row.get("color"));
            if (cheerRowId == null || !StringUtils.hasText(cheerCardId)) {
                continue;
            }
            int deleted = jdbcTemplate.update(
                "DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?",
                cheerRowId,
                sourceHolomemId
            );
            if (deleted != 1) {
                continue;
            }
            Long archivedCardInstanceId = archiveStageCheerCard(matchId, ownerUserId, cheerCardId);
            paidCheerCardIds.add(cheerCardId);
            if (archivedCardInstanceId != null) {
                paidCheerCardInstanceIds.add(archivedCardInstanceId);
            }
            if (StringUtils.hasText(color)) {
                paidColors.add(color);
            }
        }

        if (paidCheerCardIds.size() < required) {
            throw new IllegalStateException("バトンタッチ費用結算失敗：實際支付不足");
        }
        summary.put("paidTotal", paidCheerCardIds.size());
        summary.put("paidCheerCardIds", paidCheerCardIds);
        summary.put("paidCheerCardInstanceIds", paidCheerCardInstanceIds);
        summary.put("paidColors", paidColors);
        summary.put("consumed", true);
        return summary;
    }

    private int findFirstCheerIndexByColor(List<Map<String, Object>> rows, String color) {
        for (int i = 0; i < rows.size(); i++) {
            if (color.equals(normalizeZone(rows.get(i).get("color")))) {
                return i;
            }
        }
        return -1;
    }

    private Long archiveStageCheerCard(Long matchId, Long ownerUserId, String cheerCardId) {
        Long cheerCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'STAGE'
              AND card_id = ?
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            cheerCardId
        );
        if (cheerCardInstanceId == null) {
            return null;
        }
        Integer nextArchiveOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            Integer.class,
            matchId,
            ownerUserId
        );
        int updated = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = ?,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'STAGE'
            """,
            nextArchiveOrder == null ? 1 : nextArchiveOrder,
            cheerCardInstanceId,
            matchId,
            ownerUserId
        );
        return updated == 1 ? cheerCardInstanceId : null;
    }

    private ArtCritical resolveArtCritical(String effectJsonText) {
        if (!StringUtils.hasText(effectJsonText)) {
            return null;
        }
        String rawHeader = "";
        String rawEffect = "";
        String rawText = "";
        try {
            JsonNode root = objectMapper.readTree(effectJsonText);
            if (root != null && !root.isNull()) {
                rawHeader = root.path("rawHeader").asText("");
                rawEffect = root.path("rawEffect").asText("");
                rawText = root.path("rawText").asText("");
            }
        } catch (Exception ignored) {
            // 解析失敗改走全文 fallback
        }
        String merged = rawHeader + " " + rawEffect + " " + rawText + " " + effectJsonText;
        Matcher matcher = ART_CRITICAL_PATTERN.matcher(merged);
        if (!matcher.find()) {
            return null;
        }
        String color = mapJapaneseColorToken(matcher.group(1));
        int bonus;
        try {
            bonus = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (!StringUtils.hasText(color) || bonus <= 0) {
            return null;
        }
        return new ArtCritical(color, bonus);
    }

    private TargetHolomem resolveOpponentTargetHolomem(
        Long matchId,
        Long opponentUserId,
        Long requestedTargetCardInstanceId
    ) {
        if (opponentUserId == null) {
            return null;
        }
        if (requestedTargetCardInstanceId != null && requestedTargetCardInstanceId > 0) {
            return jdbcTemplate.query(
                """
                SELECT h.id, h.match_card_id, h.card_id, m.main_color
                FROM match_holomems h
                JOIN member_cards m ON m.card_id = h.card_id
                WHERE h.match_id = ?
                  AND h.owner_user_id = ?
                  AND h.match_card_id = ?
                LIMIT 1
                """,
                rs -> rs.next()
                    ? new TargetHolomem(
                        rs.getLong("id"),
                        rs.getLong("match_card_id"),
                        normalizeZone(rs.getString("main_color"))
                    )
                    : null,
                matchId,
                opponentUserId,
                requestedTargetCardInstanceId
            );
        }
        return jdbcTemplate.query(
            """
            SELECT h.id, h.match_card_id, h.card_id, m.main_color
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
            ORDER BY CASE h.zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, h.id
            LIMIT 1
            """,
            rs -> rs.next()
                ? new TargetHolomem(
                    rs.getLong("id"),
                    rs.getLong("match_card_id"),
                    normalizeZone(rs.getString("main_color"))
                )
                : null,
            matchId,
            opponentUserId
        );
    }

    private String mapJapaneseColorToken(String token) {
        return switch (token) {
            case "赤" -> "RED";
            case "青" -> "BLUE";
            case "黄" -> "YELLOW";
            case "緑" -> "GREEN";
            case "紫" -> "PURPLE";
            case "白" -> "WHITE";
            default -> "";
        };
    }

    private int extractFirstNumber(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Long loseLifeOnce(Long matchId, Long ownerUserId) {
        Long lifeCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'LIFE'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId
        );
        if (lifeCardInstanceId == null) {
            return null;
        }
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
            ownerUserId
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
            ownerUserId
        );
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = GREATEST(COALESCE(current_life, 0) - 1, 0),
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            ownerUserId
        );
        return lifeCardInstanceId;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private void appendAction(
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
        matchActionRepository.save(action);
    }

    private void touchUpdatedAt(MatchEntity match) {
        match.setUpdatedAt(LocalDateTime.now());
    }

    private record ActionContext(
        MatchEntity match,
        MatchPhase phase,
        int turnNumber,
        Long opponentUserId,
        boolean blockedByPendingInteraction
    ) {
    }

    private record ArtCritical(String color, int bonus) {
    }

    private record LookTopDeckDecisionContext(
        Long cardInstanceId,
        String cardId
    ) {
    }

    private record TargetHolomem(Long holomemId, Long matchCardInstanceId, String mainColor) {
    }

    private record BloomTarget(
        Long holomemId,
        String zone,
        String topCardId,
        String topCardName,
        String topLevelType,
        int damageTaken,
        int enteredTurnNumber,
        Long lastBloomTurn
    ) {
    }

    private record PendingDecision(
        Long decisionId,
        String decisionType,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        int minSelect,
        int maxSelect,
        Long targetHolomemCardInstanceId,
        String targetType,
        String effectJson,
        List<Long> candidateCardInstanceIds,
        boolean limited
    ) {
    }

    private record MulliganResolution(
        int forcedRedrawCount,
        List<Integer> forcedDrawSequence,
        boolean hasDebut,
        int finalHandCount
    ) {
    }
}
