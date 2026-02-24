package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.dto.AttachCheerActionRequest;
import com.hololive.cardgame.dto.AttackArtActionRequest;
import com.hololive.cardgame.dto.BloomActionRequest;
import com.hololive.cardgame.dto.PlaySupportActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.entity.MatchActionEntity;
import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.LobbyMatchStatus;
import com.hololive.cardgame.model.MatchPhase;
import com.hololive.cardgame.repository.MatchActionRepository;
import com.hololive.cardgame.repository.MatchPlayerRepository;
import com.hololive.cardgame.repository.MatchRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static final String PENDING_STATUS = "PENDING";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern ART_CRITICAL_PATTERN = Pattern.compile("([赤青黄緑紫白])\\s*[+＋]\\s*(\\d+)");

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final MatchActionRepository matchActionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MatchEffectService matchEffectService;
    private final MatchEventHookService matchEventHookService;

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

        int occupiedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomems WHERE match_id = ? AND owner_user_id = ? AND zone = ?",
            Integer.class,
            matchId,
            userId,
            targetZone
        );
        if ("CENTER".equals(targetZone) && occupiedCount > 0) {
            throw new IllegalStateException("CENTER 已有 Holomen");
        }
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
            throw new IllegalArgumentException("找不到要 BLOOM 的目標 Holomem");
        }
        if (isSpecialOrUnbloomableLevel(target.topLevelType())) {
            throw new IllegalStateException("Spot Holomem 不能作為 BLOOM 目標");
        }
        if (target.enteredTurnNumber() == context.turnNumber) {
            throw new IllegalStateException("本回合剛上場的 Holomem 不能 BLOOM");
        }
        if (target.lastBloomTurn() != null && target.lastBloomTurn() == context.turnNumber) {
            throw new IllegalStateException("此 Holomem 本回合已執行過 BLOOM");
        }
        if (!StringUtils.hasText(target.topCardName()) || !target.topCardName().equals(bloomCardName)) {
            throw new IllegalStateException("BLOOM 需要與目標 Holomem 同名");
        }
        if (!isBloomLevelHigher(target.topLevelType(), bloomLevel)) {
            throw new IllegalStateException("BLOOM 等級必須高於目標目前等級");
        }
        if (bloomHp < target.damageTaken()) {
            throw new IllegalStateException("BLOOM 卡 HP 不足以承受目標目前傷害");
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

        appendAction(
            context.match,
            userId,
            "BLOOM",
            toJson(payload),
            context.turnNumber
        );
    }

    @Transactional
    public void playSupport(Long matchId, Long userId, PlaySupportActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
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
        boolean isLimited = toBoolean(supportRow.get("is_limited"));
        if (isLimited) {
            if (isPlayerFirstTurn(matchId, userId)) {
                throw new IllegalStateException("LIMITED SUPPORT 不能在玩家首回合使用");
            }
            if (hasUsedLimitedSupportThisTurn(matchId, userId, context.turnNumber)) {
                throw new IllegalStateException("本回合已使用過 LIMITED SUPPORT");
            }
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
            Long decisionId = createSupportPendingDecision(
                context.match.getId(),
                userId,
                cardInstanceId,
                cardId,
                asString(supportRow.get("effect_type")),
                asString(supportRow.get("effect_json_text")),
                asString(supportRow.get("target_type")),
                targetHolomemCardInstanceId,
                decisionPlan
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
        appendAction(
            context.match,
            userId,
            "PLAY_SUPPORT",
            toJson(payload),
            context.turnNumber
        );
    }

    @Transactional
    public void resolveDecision(Long matchId, Long userId, ResolveDecisionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN), true);
        Long decisionId = requirePositiveId(request == null ? null : request.getDecisionId(), "decisionId");
        PendingDecision pending = loadPendingDecisionForUpdate(matchId, userId, decisionId);
        if (pending == null) {
            throw new IllegalArgumentException("找不到待處理的決策");
        }
        if (!SUPPORT_DECISION_TYPE_CARD_SELECTION.equals(normalizeZone(pending.decisionType()))) {
            throw new IllegalStateException("目前只支援卡片選擇型決策");
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
        payload.put("decisionId", pending.decisionId());
        payload.put("cardInstanceId", pending.sourceCardInstanceId());
        payload.put("cardId", pending.sourceCardId());
        payload.put("limited", pending.limited());
        payload.put("targetHolomemCardInstanceId", pending.targetHolomemCardInstanceId());
        payload.put("selectedCardInstanceIds", selectedCardInstanceIds);
        payload.put("effect", effectSummary);
        appendAction(
            context.match,
            userId,
            "PLAY_SUPPORT",
            toJson(payload),
            context.turnNumber
        );
    }

    @Transactional
    public void attachCheer(Long matchId, Long userId, AttachCheerActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN));
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
        int totalDamage = Math.max(baseDamage + criticalBonus, 0);
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
    }

    @Transactional
    public void endTurn(Long matchId, Long userId) {
        ActionContext context = loadActionContext(
            matchId,
            userId,
            Set.of(MatchPhase.MAIN, MatchPhase.PERFORMANCE, MatchPhase.END)
        );
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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUserId", userId);
        payload.put("toUserId", context.opponentUserId);
        payload.put("clearedExpiredTurnEffects", clearedEffectCount);
        payload.put("resetRestedCount", resetRestedCount);

        appendAction(
            context.match,
            userId,
            "END_TURN",
            toJson(payload),
            context.turnNumber
        );

        context.match.setCurrentTurnPlayerId(context.opponentUserId);
        context.match.setTurnNumber(context.turnNumber + 1);
        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);
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

        if (!LobbyMatchStatus.STARTED.name().equals(match.getLobbyStatus())) {
            throw new IllegalStateException("對戰尚未開始");
        }
        if (!matchPlayerRepository.existsByMatchIdAndUserId(matchId, userId)) {
            throw new IllegalArgumentException("你不在此房間中");
        }
        if (match.getCurrentTurnPlayerId() == null || !match.getCurrentTurnPlayerId().equals(userId)) {
            throw new IllegalStateException("現在不是你的回合");
        }

        MatchPhase phase = parsePhase(match.getCurrentPhase());
        if (!allowedPhases.contains(phase)) {
            throw new IllegalStateException("目前 phase=" + phase + "，無法執行此操作");
        }
        if (!allowPendingDecision && hasPendingDecision(matchId, userId)) {
            throw new IllegalStateException("你有待處理的效果選擇，請先完成決策");
        }

        Long opponentUserId = resolveOpponent(match, userId);
        int turnNumber = match.getTurnNumber() == null ? 1 : match.getTurnNumber();
        return new ActionContext(match, phase, turnNumber, opponentUserId);
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

    private boolean isBloomLevelHigher(String targetLevel, String bloomLevel) {
        int targetRank = resolveBloomLevelRank(targetLevel);
        int bloomRank = resolveBloomLevelRank(bloomLevel);
        if (targetRank < 0 || bloomRank < 0) {
            return false;
        }
        return bloomRank > targetRank;
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

    private boolean hasPendingDecision(Long matchId, Long userId) {
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

    private Long createSupportPendingDecision(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        String effectJson,
        String targetType,
        Long targetHolomemCardInstanceId,
        MatchEffectService.SupportDecisionPlan decisionPlan
    ) {
        if (hasPendingDecision(matchId, userId)) {
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
        context.put("limited", hasSupportDefinitionLimitedFlag(sourceCardId));

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
            ) VALUES (?, ?, ?, 'PLAY_SUPPORT', ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            SUPPORT_DECISION_TYPE_CARD_SELECTION,
            sourceCardInstanceId,
            sourceCardId,
            decisionPlan.effectType(),
            decisionPlan.minSelect(),
            decisionPlan.maxSelect(),
            PENDING_STATUS,
            toJson(context)
        );
    }

    private PendingDecision loadPendingDecisionForUpdate(Long matchId, Long userId, Long decisionId) {
        return jdbcTemplate.query(
            """
            SELECT id,
                   decision_type,
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
                return new PendingDecision(
                    rs.getLong("id"),
                    normalizeZone(rs.getString("decision_type")),
                    rs.getLong("source_card_instance_id"),
                    rs.getString("source_card_id"),
                    normalizeZone(rs.getString("effect_type")),
                    Math.max(rs.getInt("min_select"), 1),
                    Math.max(rs.getInt("max_select"), 1),
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

    private boolean isPlayerFirstTurn(Long matchId, Long userId) {
        Integer endedTurns = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'END_TURN'
            """,
            Integer.class,
            matchId,
            userId
        );
        return endedTurns == null || endedTurns == 0;
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
            Long cheerRowId = asLong(row.get("cheer_row_id"));
            String cheerCardId = asString(row.get("cheer_card_id"));
            String color = normalizeZone(row.get("color"));
            if (cheerRowId == null || !StringUtils.hasText(cheerCardId) || !StringUtils.hasText(color)) {
                continue;
            }
            int detached = jdbcTemplate.update(
                "DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?",
                cheerRowId,
                attackerHolomemId
            );
            if (detached != 1) {
                throw new IllegalStateException("藝能費用結算失敗：無法移除已附加 Cheer");
            }
            Long archivedInstanceId = archiveStageCheerCard(matchId, ownerUserId, cheerCardId);
            if (archivedInstanceId == null) {
                throw new IllegalStateException("藝能費用結算失敗：無法歸檔 Cheer " + cheerCardId);
            }
            paid.put(color, paid.getOrDefault(color, 0) + 1);
            paidCheerCardIds.add(cheerCardId);
            paidCheerCardInstanceIds.add(archivedInstanceId);
            paidColors.add(color);
        }

        summary.put("paid", paid);
        summary.put("paidTotal", selected.size());
        summary.put("paidCheerCardIds", paidCheerCardIds);
        summary.put("paidCheerCardInstanceIds", paidCheerCardInstanceIds);
        summary.put("paidColors", paidColors);
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
        Long opponentUserId
    ) {
    }

    private record ArtCritical(String color, int bonus) {
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
}
