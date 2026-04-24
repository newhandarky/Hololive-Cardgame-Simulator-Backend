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
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.game.action.MoveZoneAction;
import com.hololive.cardgame.game.action.ReduceLifeAction;
import com.hololive.cardgame.game.action.SendCheerAction;
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
import java.util.Objects;
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
    private static final String INTERACTION_TYPE_LIVE_START = "LIVE_START";
    private static final String INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM = "TRIGGER_EFFECT_CONFIRM";
    private static final String DECISION_TYPE_LOOK_TOP_DECK = "LOOK_TOP_DECK";
    private static final String DECISION_TYPE_LOOK_OPPONENT_HAND = "LOOK_OPPONENT_HAND";
    private static final String DECISION_TYPE_LOOK_HOLOPOWER = "LOOK_HOLOPOWER";
    private static final String DECISION_TYPE_REORDER_DECK_BOTTOM = "REORDER_DECK_BOTTOM";
    private static final String ACTION_TYPE_DRAW_TURN = "DRAW_TURN";
    private static final String ACTION_TYPE_TURN_CHEER = "TURN_CHEER";
    private static final String ACTION_TYPE_USE_OSHI_SKILL = "USE_OSHI_SKILL";
    private static final String ACTION_TYPE_EFFECT_POST_TRIGGER = "EFFECT_POST_TRIGGER";
    private static final String ACTION_TYPE_BATON_TOUCH = "BATON_TOUCH";
    private static final String ACTION_TYPE_RULE_EVENT = "RULE_EVENT";
    private static final String PENDING_STATUS = "PENDING";
    private static final String SUPPORT_TYPE_MASCOT = "MASCOT";
    private static final String SUPPORT_TYPE_TOOL = "TOOL";
    private static final String SUPPORT_TYPE_FAN = "FAN";
    private static final String SUPPORT_TYPE_OTHER = "OTHER";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern ART_CRITICAL_PATTERN = Pattern.compile("([赤青黄緑紫白])\\s*[+＋]\\s*(\\d+)");
    private static final Pattern CENTER_TAG_REQUIREMENT_PATTERN = Pattern.compile("#([^\\sを]+)を持つセンターホロメンがいる間");
    private static final int OPENING_HAND_SIZE = 7;

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final MatchActionRepository matchActionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MatchEffectService matchEffectService;
    private final MatchEffectDamageService matchEffectDamageService;
    private final MatchEffectCombatModifierService matchEffectCombatModifierService;
    private final MatchTriggeredCombatEffectService matchTriggeredCombatEffectService;
    private final MatchTurnEffectMaintenanceService matchTurnEffectMaintenanceService;
    private final MatchTurnLifecycleService matchTurnLifecycleService;
    private final MatchPhaseAdvanceGiftTransitionService matchPhaseAdvanceGiftTransitionService;
    private final MatchTriggeredCardEffectService matchTriggeredCardEffectService;
    private final MatchGiftTriggerService matchGiftTriggerService;
    private final MatchTriggeredGiftResolutionService matchTriggeredGiftResolutionService;
    private final MatchTriggeredEffectResolutionService matchTriggeredEffectResolutionService;
    private final MatchEventHookService matchEventHookService;
    private final GameActionExecutor gameActionExecutor;
    private final DiceService diceService;
    private final SecureRandom random = new SecureRandom();

    /**
     * 對戰行為服務建構子。
     * 聚合所有對戰指令所需元件：交易內資料更新、效果結算、事件觸發與 action pipeline。
     */
    public MatchActionService(
        MatchRepository matchRepository,
        MatchPlayerRepository matchPlayerRepository,
        MatchActionRepository matchActionRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchEffectService matchEffectService,
        MatchEffectDamageService matchEffectDamageService,
        MatchEffectCombatModifierService matchEffectCombatModifierService,
        MatchTriggeredCombatEffectService matchTriggeredCombatEffectService,
        MatchTurnEffectMaintenanceService matchTurnEffectMaintenanceService,
        MatchTurnLifecycleService matchTurnLifecycleService,
        MatchPhaseAdvanceGiftTransitionService matchPhaseAdvanceGiftTransitionService,
        MatchTriggeredCardEffectService matchTriggeredCardEffectService,
        MatchGiftTriggerService matchGiftTriggerService,
        MatchTriggeredGiftResolutionService matchTriggeredGiftResolutionService,
        MatchTriggeredEffectResolutionService matchTriggeredEffectResolutionService,
        MatchEventHookService matchEventHookService,
        GameActionExecutor gameActionExecutor,
        DiceService diceService
    ) {
        this.matchRepository = matchRepository;
        this.matchPlayerRepository = matchPlayerRepository;
        this.matchActionRepository = matchActionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.matchEffectService = matchEffectService;
        this.matchEffectDamageService = matchEffectDamageService;
        this.matchEffectCombatModifierService = matchEffectCombatModifierService;
        this.matchTriggeredCombatEffectService = matchTriggeredCombatEffectService;
        this.matchTurnEffectMaintenanceService = matchTurnEffectMaintenanceService;
        this.matchTurnLifecycleService = matchTurnLifecycleService;
        this.matchPhaseAdvanceGiftTransitionService = matchPhaseAdvanceGiftTransitionService;
        this.matchTriggeredCardEffectService = matchTriggeredCardEffectService;
        this.matchGiftTriggerService = matchGiftTriggerService;
        this.matchTriggeredGiftResolutionService = matchTriggeredGiftResolutionService;
        this.matchTriggeredEffectResolutionService = matchTriggeredEffectResolutionService;
        this.matchEventHookService = matchEventHookService;
        this.gameActionExecutor = gameActionExecutor;
        this.diceService = diceService;
    }

    /**
     * 將手牌 Holomem 放置到場上（目前僅允許 DEBUT/SPOT 且目標為 BACK）。
     * 會建立 match_holomems 與 stack 關聯，並觸發進場 hook。
     */
    @Transactional
    public void playToStage(Long matchId, Long userId, PlayToStageActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN, MatchPhase.RESET));
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
        boolean openingReset = context.phase == MatchPhase.RESET;
        if (openingReset) {
            MatchPlayerEntity openingPlayer = matchPlayerRepository.findByMatchIdAndUserId(matchId, userId)
                .orElseThrow(() -> new IllegalArgumentException("你不在此房間中"));
            if (!openingPlayer.isMulliganDone()) {
                throw new IllegalStateException("請先完成起手調度，再設置開場舞台");
            }
            boolean hasOpeningCenter = hasOpeningCenterPlaced(matchId, userId);
            if (!hasOpeningCenter) {
                if (!"DEBUT".equals(normalizedLevelType)) {
                    throw new GameRuleException(
                        GameErrorCode.PLAY_TO_STAGE_LEVEL_NOT_ALLOWED,
                        "開場只能從手牌放置 DEBUT Holomem 到 CENTER"
                    );
                }
                if (!"CENTER".equals(targetZone)) {
                    throw new IllegalStateException("放置開場 CENTER 前，不能先設置開場 BACK");
                }
            } else {
                if (!Set.of("DEBUT", "SPOT").contains(normalizedLevelType)) {
                    throw new GameRuleException(
                        GameErrorCode.PLAY_TO_STAGE_LEVEL_NOT_ALLOWED,
                        "開場 BACK 只能放置 DEBUT 或 SPOT Holomem"
                    );
                }
                if (!"BACK".equals(targetZone)) {
                    throw new IllegalStateException("開場完成 CENTER 後，只能繼續設置 BACK");
                }
            }
        } else {
            if (!Set.of("DEBUT", "SPOT").contains(normalizedLevelType)) {
                throw new GameRuleException(
                    GameErrorCode.PLAY_TO_STAGE_LEVEL_NOT_ALLOWED,
                    "只有 DEBUT 或 SPOT Holomem 可以從手牌放置到場上；FIRST/SECOND/BUZZ 請改用 BLOOM"
                );
            }
            if (!"BACK".equals(targetZone)) {
                throw new IllegalStateException("手牌 Holomem 只能放置到 BACK");
            }
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
                is_face_down = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            openingReset,
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
            ) VALUES (?, ?, ?, ?, ?, FALSE, ?, 0, ?, ?)
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            cardInstanceId,
            cardId,
            targetZone,
            openingReset,
            normalizeLevel(levelType),
            context.turnNumber
        );
        if (matchHolomemId == null) {
            throw new IllegalStateException("建立場上 Holomen 失敗");
        }
        recordHolomemStackCard(matchHolomemId, cardInstanceId);

        if (openingReset) {
            context.match.setCurrentPhase(MatchPhase.RESET.name());
        } else {
            context.match.setCurrentPhase(MatchPhase.MAIN.name());
        }
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> triggerSummary = openingReset
            ? Map.of("deferredUntilLiveStart", true)
            : matchEventHookService.onHolomemEnter(
                matchId,
                userId,
                cardId,
                cardInstanceId,
                targetZone
            );
        List<Map<String, Object>> giftTriggeredEffects = openingReset
            ? List.of()
            : matchGiftTriggerService.previewGiftTriggeredEffectsOnStageEnter(
                matchId,
                userId,
                cardInstanceId,
                targetZone,
                context.turnNumber
            );
        Map<String, Object> giftEffectSummary = buildGiftTriggeredEffectDeferredSummary(giftTriggeredEffects);
        FollowupInteractionDecision giftTriggerConfirmDecision = null;
        if (!giftTriggeredEffects.isEmpty()) {
            giftTriggerConfirmDecision = createGiftTriggeredEffectConfirmPendingInteraction(
                matchId,
                userId,
                cardInstanceId,
                cardId,
                List.of(buildInteractionSourceCardPayload(matchId, userId, cardInstanceId, cardId, targetZone)),
                giftTriggeredEffects,
                context.turnNumber
            );
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardInstanceId", cardInstanceId);
        payload.put("cardId", cardId);
        payload.put("targetZone", targetZone);
        payload.put("enteredTurn", context.turnNumber);
        payload.put("faceDown", openingReset);
        payload.put("triggerSummary", triggerSummary);
        if (!openingReset) {
            payload.put("giftEffect", giftEffectSummary);
            payload.put(
                "triggerResolutionOrder",
                buildTriggeredResolutionOrder(
                    "GIFT_TRIGGER",
                    100,
                    giftEffectSummary,
                    "ENTER_EVENT_HOOK",
                    200,
                    triggerSummary
                )
            );
            putFollowupDecisionPayload(payload, giftTriggerConfirmDecision);
        }

        appendAction(
            context.match,
            userId,
            openingReset ? ("CENTER".equals(targetZone) ? "OPENING_SET_CENTER" : "OPENING_SET_BACK") : "PLAY_TO_STAGE",
            toJson(payload),
            context.turnNumber
        );
    }

    /**
     * 執行 Bloom（手牌 FIRST/SECOND/BUZZ 疊放到場上同名目標）。
     * 會驗證 Bloom 順序、目標合法性、傷害繼承條件，並結算 Bloom 效果/勝負檢查。
     */
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
        if (isStageActionLocked(matchId, userId, context.turnNumber, "BLOOM", target.zone(), target.holomemId())) {
            throw new GameRuleException(GameErrorCode.STAGE_ACTION_LOCKED, "目前效果限制：不可 Bloom");
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
        boolean bloomLevelOverrideApplied = false;
        if (!isBloomLevelNextStep(target.topLevelType(), bloomLevel)) {
            boolean canIgnoreBloomLevel = canIgnoreBloomLevelByPassiveGift(
                matchId,
                userId,
                target,
                bloomLevel,
                bloomCardName
            );
            if (!canIgnoreBloomLevel) {
                throw new GameRuleException(
                    GameErrorCode.BLOOM_INVALID_TARGET,
                    "BLOOM 只能依序遞進：DEBUT→FIRST、FIRST→SECOND、SECOND→BUZZ"
                );
            }
            bloomLevelOverrideApplied = true;
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
        Map<String, Object> passiveGiftSummary = matchTriggeredCardEffectService.applyPassiveGiftExtraBloomAllowanceOnBloom(
            matchId,
            userId,
            target.holomemId(),
            bloomCardInstanceId,
            bloomCardId
        );
        String sourceLevelType = target.topLevelType();
        MatchEffectService.TriggeredEffectPreview bloomPreview = matchTriggeredCardEffectService.previewBloomTriggeredEffect(
            matchId,
            userId,
            bloomCardId,
            bloomCardInstanceId,
            sourceLevelType
        );
        Map<String, Object> bloomEffectSummary = buildTriggeredEffectDeferredSummary("BLOOM", bloomPreview);
        Map<String, Object> triggerSummary = matchEventHookService.onHolomemBloom(
            matchId,
            userId,
            bloomCardId,
            bloomCardInstanceId,
            targetHolomemCardInstanceId,
            target.zone()
        );
        FollowupInteractionDecision triggerConfirmDecision = null;
        if (bloomPreview.hasEffect()) {
            Map<String, Object> additionalContext = new LinkedHashMap<>();
            additionalContext.put("sourceLevelType", sourceLevelType);
            triggerConfirmDecision = createTriggeredEffectConfirmPendingInteraction(
                matchId,
                userId,
                "BLOOM",
                bloomCardInstanceId,
                bloomCardId,
                "BLOOM_EFFECT",
                "確認 Bloom 效果",
                buildTriggeredEffectConfirmMessage("BLOOM", bloomPreview),
                List.of(buildInteractionSourceCardPayload(matchId, userId, bloomCardInstanceId, bloomCardId, "STAGE")),
                context.turnNumber,
                additionalContext
            );
        }

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
        payload.put("bloomLevelOverrideApplied", bloomLevelOverrideApplied);
        payload.put("passiveGiftSummary", passiveGiftSummary);
        payload.put("bloomEffect", bloomEffectSummary);
        payload.put("triggerSummary", triggerSummary);
        payload.put(
            "triggerResolutionOrder",
            buildTriggeredResolutionOrder(
                "BLOOM_EFFECT",
                100,
                bloomEffectSummary,
                "BLOOM_EVENT_HOOK",
                200,
                triggerSummary
            )
        );
        putFollowupDecisionPayload(payload, triggerConfirmDecision);

        appendAction(
            context.match,
            userId,
            "BLOOM",
            toJson(payload),
            context.turnNumber
        );
        if (!bloomPreview.hasEffect()) {
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
    }

    /**
     * 使用 Support 卡（一般支援或附加型支援）。
     * 會處理 LIMITED 限制、目標合法性、效果解析與後續互動決策。
     */
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
            validateAttachableSupportLimit(targetHolomemId, supportType, cardId);

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
            targetHolomemCardInstanceId,
            true
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
        FollowupInteractionDecision followupDecision = createEffectPostTriggerConfirmPendingInteractionIfNeeded(
            matchId,
            userId,
            "PLAY_SUPPORT",
            cardInstanceId,
            cardId,
            effectSummary,
            context.turnNumber
        );
        if (followupDecision == null) {
            followupDecision = createFollowupInteractionPendingDecisionIfNeeded(
                matchId,
                userId,
                "PLAY_SUPPORT",
                cardInstanceId,
                cardId,
                asString(supportRow.get("effect_type")),
                effectSummary
            );
        }
        putFollowupDecisionPayload(payload, followupDecision);
        appendAction(
            context.match,
            userId,
            "PLAY_SUPPORT",
            toJson(payload),
            context.turnNumber
        );
        finalizeResolvedEffect(context, matchId, userId, effectSummary);
    }

    /**
     * 解決 pending decision / interaction。
     * 包含 TURN_START、DRAW_REVEAL、SEND_CHEER 及各種效果後續選牌流程。
     */
    @Transactional
    public void resolveDecision(Long matchId, Long userId, ResolveDecisionRequest request) {
        ActionContext context = loadActionContext(
            matchId,
            userId,
            Set.of(MatchPhase.MAIN, MatchPhase.RESET, MatchPhase.DRAW, MatchPhase.CHEER, MatchPhase.PERFORMANCE, MatchPhase.END),
            true
        );
        Long decisionId = requirePositiveId(request == null ? null : request.getDecisionId(), "decisionId");
        PendingDecision pending = loadPendingDecisionForUpdate(matchId, userId, decisionId);
        if (pending == null) {
            throw new IllegalArgumentException("找不到待處理的決策");
        }
        String decisionType = normalizeZone(pending.decisionType());
        if (INTERACTION_TYPE_TURN_START.equals(decisionType)) {
            resolveTurnStartDecision(context, matchId, userId, pending);
            return;
        }
        if (INTERACTION_TYPE_LIVE_START.equals(decisionType)) {
            resolveLiveStartDecision(context, matchId, userId, pending);
            return;
        }
        if (INTERACTION_TYPE_DRAW_REVEAL.equals(decisionType)) {
            resolveDrawRevealDecision(context, matchId, userId, pending);
            return;
        }
        if (INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM.equals(decisionType)) {
            resolveTriggerEffectConfirmDecision(context, matchId, userId, pending, request);
            return;
        }
        if (INTERACTION_TYPE_SEND_CHEER.equals(decisionType)) {
            resolveSendCheerDecision(context, matchId, userId, pending, request);
            return;
        }
        if (DECISION_TYPE_LOOK_TOP_DECK.equals(decisionType)) {
            resolveLookTopDeckDecision(context, matchId, userId, pending, request);
            return;
        }
        if (DECISION_TYPE_LOOK_OPPONENT_HAND.equals(decisionType) || DECISION_TYPE_LOOK_HOLOPOWER.equals(decisionType)) {
            resolveLookZoneDecision(context, userId, pending, decisionType);
            return;
        }
        if (DECISION_TYPE_REORDER_DECK_BOTTOM.equals(decisionType)) {
            resolveReorderDeckBottomDecision(context, matchId, userId, pending, request);
            return;
        }
        if (!SUPPORT_DECISION_TYPE_CARD_SELECTION.equals(decisionType)) {
            throw new IllegalStateException("目前不支援此類型決策: " + decisionType);
        }
        resolveSupportCardSelectionDecision(context, matchId, userId, pending, request);
    }

    private void resolveTriggerEffectConfirmDecision(
        ActionContext context,
        Long matchId,
        Long userId,
        PendingDecision pending,
        ResolveDecisionRequest request
    ) {
        boolean confirmed = request == null || request.getConfirmed() == null || request.getConfirmed();
        markDecisionResolved(pending.decisionId());

        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", pending.decisionId());
        payload.put("interactionType", INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
        payload.put("sourceActionType", pending.sourceActionType());
        payload.put("confirmed", confirmed);

        if (confirmed) {
            applyConfirmedTriggerEffectResolution(context, matchId, userId, pending, request, payload);
        }

        appendAction(
            context.match,
            userId,
            confirmed ? "TRIGGER_EFFECT_EXECUTED" : "TRIGGER_EFFECT_SKIPPED",
            toJson(payload),
            context.turnNumber
        );
    }

    private void applyConfirmedTriggerEffectResolution(
        ActionContext context,
        Long matchId,
        Long userId,
        PendingDecision pending,
        ResolveDecisionRequest request,
        Map<String, Object> payload
    ) {
        List<Long> selectedCardInstanceIds = sanitizeSelectedCardInstanceIds(
            request == null ? null : request.getSelectedCardInstanceIds()
        );
        if (pending.maxSelect() > 0) {
            if (selectedCardInstanceIds.size() < pending.minSelect()) {
                throw new IllegalArgumentException("選擇卡片數量不足，至少需要 " + pending.minSelect() + " 張");
            }
            if (selectedCardInstanceIds.size() > pending.maxSelect()) {
                throw new IllegalArgumentException("選擇卡片數量超過上限，最多只能選 " + pending.maxSelect() + " 張");
            }
            validateSelectedCardsWithinCandidates(selectedCardInstanceIds, pending.candidateCardInstanceIds());
            payload.put("selectedCardInstanceIds", selectedCardInstanceIds);
        }
        Map<String, Object> effectSummary = applyTriggeredEffectAfterConfirm(
            matchId,
            userId,
            pending,
            context.turnNumber,
            selectedCardInstanceIds
        );
        appendGiftTriggerActionsIfPresent(context.match, userId, context.turnNumber, effectSummary);
        payload.put("effect", effectSummary);
        FollowupInteractionDecision followupDecision = createFollowupInteractionPendingDecisionIfNeeded(
            matchId,
            userId,
            pending.sourceActionType(),
            pending.sourceCardInstanceId(),
            pending.sourceCardId(),
            pending.effectType(),
            effectSummary
        );
        putFollowupDecisionPayload(payload, followupDecision);
        finalizeResolvedEffect(context, matchId, userId, effectSummary);
    }

    private void resolveLookTopDeckDecision(
        ActionContext context,
        Long matchId,
        Long userId,
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
    }

    private void resolveLookZoneDecision(
        ActionContext context,
        Long userId,
        PendingDecision pending,
        String decisionType
    ) {
        markDecisionResolved(pending.decisionId());

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", pending.decisionId());
        payload.put("decisionType", decisionType);
        payload.put("sourceActionType", pending.sourceActionType());
        payload.put("lookedCardCount", pending.candidateCardInstanceIds().size());
        appendAction(
            context.match,
            userId,
            "INTERACTION_CONFIRMED",
            toJson(payload),
            context.turnNumber
        );
    }

    private void resolveReorderDeckBottomDecision(
        ActionContext context,
        Long matchId,
        Long userId,
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

        markDecisionResolved(pending.decisionId());
        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", pending.decisionId());
        payload.put("decisionType", DECISION_TYPE_REORDER_DECK_BOTTOM);
        payload.put("sourceActionType", pending.sourceActionType());
        payload.put("orderedCardInstanceIds", orderedCardInstanceIds);
        appendAction(
            context.match,
            userId,
            "INTERACTION_CONFIRMED",
            toJson(payload),
            context.turnNumber
        );
    }

    private void resolveSupportCardSelectionDecision(
        ActionContext context,
        Long matchId,
        Long userId,
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

        Map<String, Object> effectSummary = matchEffectService.applySupportEffect(
            matchId,
            userId,
            pending.effectType(),
            pending.effectJson(),
            pending.targetType(),
            selectedCardInstanceIds,
            pending.targetHolomemCardInstanceId(),
            true
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
        FollowupInteractionDecision followupDecision = createEffectPostTriggerConfirmPendingInteractionIfNeeded(
            matchId,
            userId,
            sourceActionType,
            pending.sourceCardInstanceId(),
            pending.sourceCardId(),
            effectSummary,
            context.turnNumber
        );
        if (followupDecision == null) {
            followupDecision = createFollowupInteractionPendingDecisionIfNeeded(
                matchId,
                userId,
                sourceActionType,
                pending.sourceCardInstanceId(),
                pending.sourceCardId(),
                pending.effectType(),
                effectSummary
            );
        }
        putFollowupDecisionPayload(payload, followupDecision);
        appendAction(
            context.match,
            userId,
            resolvedActionType,
            toJson(payload),
            context.turnNumber
        );
        finalizeResolvedEffect(context, matchId, userId, effectSummary);
    }

    private void finalizeResolvedEffect(
        ActionContext context,
        Long matchId,
        Long userId,
        Map<String, Object> effectSummary
    ) {
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

    private void resolveTurnStartDecision(
        ActionContext context,
        Long matchId,
        Long userId,
        PendingDecision pending
    ) {
        markDecisionResolved(pending.decisionId());
        returnCollabToBackAsRested(matchId, userId);

        context.match.setCurrentPhase(MatchPhase.DRAW.name());
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
    }

    private void resolveLiveStartDecision(
        ActionContext context,
        Long matchId,
        Long userId,
        PendingDecision pending
    ) {
        markDecisionResolved(pending.decisionId());
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id IN (?, ?)
              AND zone IN ('CENTER', 'BACK')
            """,
            matchId,
            context.match.getPlayerAId(),
            context.match.getPlayerBId()
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id IN (?, ?)
              AND zone = 'STAGE'
              AND id IN (
                SELECT match_card_id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id IN (?, ?)
                  AND zone IN ('CENTER', 'BACK')
              )
            """,
            matchId,
            context.match.getPlayerAId(),
            context.match.getPlayerBId(),
            matchId,
            context.match.getPlayerAId(),
            context.match.getPlayerBId()
        );

        context.match.setCurrentTurnPlayerId(context.match.getPlayerAId());
        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", pending.decisionId());
        payload.put("interactionType", INTERACTION_TYPE_LIVE_START);
        payload.put("sourceActionType", INTERACTION_TYPE_LIVE_START);
        appendAction(
            context.match,
            userId,
            "INTERACTION_CONFIRMED",
            toJson(payload),
            context.turnNumber
        );

        Long turnStartInteractionId = createTurnStartPendingInteraction(matchId, context.match.getPlayerAId(), context.turnNumber);
        if (turnStartInteractionId == null) {
            return;
        }
        Map<String, Object> interactionPayload = new LinkedHashMap<>();
        interactionPayload.put("interactionId", turnStartInteractionId);
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

    private void resolveDrawRevealDecision(
        ActionContext context,
        Long matchId,
        Long userId,
        PendingDecision pending
    ) {
        markDecisionResolved(pending.decisionId());
        boolean requiresTurnCheer = canPerformTurnCheerAction(matchId, userId);
        context.match.setCurrentPhase(requiresTurnCheer ? MatchPhase.CHEER.name() : MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", pending.decisionId());
        payload.put("interactionType", INTERACTION_TYPE_DRAW_REVEAL);
        payload.put("sourceActionType", "DRAW_TURN");
        payload.put("drawnCardInstanceId", pending.sourceCardInstanceId());
        payload.put("drawnCardId", pending.sourceCardId());
        if (!requiresTurnCheer) {
            List<Map<String, Object>> mainStepGiftEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnOwnMainStep(
                matchId,
                userId,
                context.turnNumber
            );
            payload.put("mainStepGiftEffects", buildGiftTriggeredEffectDeferredSummary(mainStepGiftEffects));
            if (!mainStepGiftEffects.isEmpty()) {
                FollowupInteractionDecision mainStepGiftDecision = createGiftTriggeredEffectConfirmPendingInteraction(
                    matchId,
                    userId,
                    null,
                    null,
                    buildGiftTriggerInteractionCards(matchId, userId, null, null, mainStepGiftEffects),
                    mainStepGiftEffects,
                    context.turnNumber
                );
                putFollowupDecisionPayload(payload, mainStepGiftDecision);
            }
        }
        appendAction(
            context.match,
            userId,
            "INTERACTION_CONFIRMED",
            toJson(payload),
            context.turnNumber
        );
    }

    private void resolveSendCheerDecision(
        ActionContext context,
        Long matchId,
        Long userId,
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
        EffectContext effectContext = new EffectContext(
            matchId,
            userId,
            context.turnNumber,
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
            String reason = actionResults.isEmpty() ? "UNKNOWN" : asString(actionResults.get(0).details().get("reason"));
            throw new IllegalStateException("發送吶喊失敗：" + reason);
        }
        markDecisionResolved(pending.decisionId());

        context.match.setCurrentPhase(resolvePhaseAfterSendCheer(context.phase, pending.sourceActionType()).name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", pending.decisionId());
        payload.put("interactionType", INTERACTION_TYPE_SEND_CHEER);
        payload.put("sourceActionType", pending.sourceActionType());
        payload.put("sourceCardInstanceId", sourceCardInstanceId);
        payload.put("sourceCardId", cheerCardId);
        payload.put("targetHolomemCardInstanceId", targetHolomemCardInstanceId);
        if (ACTION_TYPE_TURN_CHEER.equals(pending.sourceActionType())) {
            List<Map<String, Object>> mainStepGiftEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnOwnMainStep(
                matchId,
                userId,
                context.turnNumber
            );
            payload.put("mainStepGiftEffects", buildGiftTriggeredEffectDeferredSummary(mainStepGiftEffects));
            if (!mainStepGiftEffects.isEmpty()) {
                FollowupInteractionDecision mainStepGiftDecision = createGiftTriggeredEffectConfirmPendingInteraction(
                    matchId,
                    userId,
                    null,
                    null,
                    buildGiftTriggerInteractionCards(matchId, userId, null, null, mainStepGiftEffects),
                    mainStepGiftEffects,
                    context.turnNumber
                );
                putFollowupDecisionPayload(payload, mainStepGiftDecision);
            }
        }
        appendAction(
            context.match,
            userId,
            "INTERACTION_CONFIRMED",
            toJson(payload),
            context.turnNumber
        );
        if (!ACTION_TYPE_TURN_CHEER.equals(pending.sourceActionType())) {
            return;
        }
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

    /**
     * 執行起手調度（Mulligan），並套用「無 Debut 強制遞減重抽」規則。
     * 當雙方完成後，會把回合交回先攻並建立 TURN_START 互動。
     */
    @Transactional
    public void mulligan(Long matchId, Long userId, MulliganActionRequest request) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.RESET));
        MatchPlayerEntity player = matchPlayerRepository.findByMatchIdAndUserId(matchId, userId)
            .orElseThrow(() -> new IllegalArgumentException("你不在此房間中"));
        if (player.isMulliganDone()) {
            throw new IllegalStateException("你已完成起手調度");
        }

        boolean useMulligan = request != null && request.isUseMulligan();
        int handCountBefore = countCardsInZone(matchId, userId, "HAND");
        if (useMulligan) {
            redrawOpeningHand(matchId, userId, Math.max(handCountBefore - 1, 0));
        }
        MulliganResolution resolution = enforceOpeningDebutRule(matchId, userId);
        boolean defeatedByNoDebut = !resolution.hasDebut();
        player.setMulliganUsed(player.isMulliganUsed() || useMulligan);
        if (!useMulligan) {
            player.setMulliganDone(true);
        }
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
        mulliganPayload.put("handCountBefore", handCountBefore);
        mulliganPayload.put("continueMulligan", useMulligan && !defeatedByNoDebut);
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
        if (useMulligan) {
            context.match.setCurrentTurnPlayerId(userId);
            context.match.setCurrentPhase(MatchPhase.RESET.name());
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
            return;
        }

        List<MatchPlayerEntity> players = matchPlayerRepository.findByMatchIdOrderByIdAsc(matchId);
        boolean allDone = players.stream().allMatch(MatchPlayerEntity::isMulliganDone);
        if (allDone) {
            Long nextCenterUser = resolveNextOpeningCenterUser(context.match);
            context.match.setCurrentTurnPlayerId(nextCenterUser == null ? context.match.getPlayerAId() : nextCenterUser);
            context.match.setCurrentPhase(MatchPhase.RESET.name());
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
    }

    /**
     * 執行回合抽牌（每回合一次）。
     * 抽牌後會建立 DRAW_REVEAL 互動，供前端以 modal 呈現確認。
     */
    @Transactional
    public void drawTurn(Long matchId, Long userId) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN, MatchPhase.DRAW));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        validateDrawTurnAvailable(matchId, userId, context.turnNumber);

        DrawTurnResult result = executeDrawTurn(matchId, userId, context);
        if (result.deckOut()) {
            return;
        }

        appendDrawTurnAction(context.match, userId, context.turnNumber, result.drawnCardInstanceId());
        matchTurnLifecycleService.beginDrawTurn(
            context.match,
            userId,
            context.turnNumber,
            result.drawnCardInstanceId(),
            result.drawInteractionId()
        );
    }

    /**
     * 發送回合 Cheer（每回合一次）。
     * 實際附加目標透過 SEND_CHEER pending interaction 讓玩家選擇。
     */
    @Transactional
    public void sendTurnCheer(Long matchId, Long userId) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.MAIN, MatchPhase.CHEER));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        validateTurnCheerAvailable(matchId, userId, context.turnNumber);

        Long interactionId = prepareTurnCheerInteraction(matchId, userId);
        matchTurnLifecycleService.beginTurnCheer(context.match, userId, context.turnNumber, interactionId);
    }

    private void validateDrawTurnAvailable(Long matchId, Long userId, int turnNumber) {
        if (hasDrawTurnAction(matchId, userId, turnNumber)) {
            throw new GameRuleException(GameErrorCode.TURN_DRAW_ALREADY_USED, "這回合你已經抽過卡了");
        }
    }

    private DrawTurnResult executeDrawTurn(Long matchId, Long userId, ActionContext context) {
        Long drawnCardInstanceId = drawTopDeckCardToHand(matchId, userId);
        if (drawnCardInstanceId == null) {
            finishMatchByDefeat(context.match, userId, "DRAW_DECK_OUT", context.turnNumber);
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
            return DrawTurnResult.deckedOut();
        }

        Long drawInteractionId = createDrawRevealPendingInteraction(matchId, userId, drawnCardInstanceId);
        return DrawTurnResult.drawn(drawnCardInstanceId, drawInteractionId);
    }

    private void appendDrawTurnAction(MatchEntity match, Long userId, int turnNumber, Long drawnCardInstanceId) {
        Map<String, Object> drawPayload = new LinkedHashMap<>();
        drawPayload.put("drawCount", 1);
        drawPayload.put("drawnCardInstanceIds", List.of(drawnCardInstanceId));
        appendAction(
            match,
            userId,
            ACTION_TYPE_DRAW_TURN,
            toJson(drawPayload),
            turnNumber
        );
    }

    private void validateTurnCheerAvailable(Long matchId, Long userId, int turnNumber) {
        if (hasTurnCheerAction(matchId, userId, turnNumber)) {
            throw new GameRuleException(GameErrorCode.TURN_CHEER_ALREADY_USED, "這回合你已經發送過吶喊了");
        }
    }

    private Long prepareTurnCheerInteraction(Long matchId, Long userId) {
        Long interactionId = createTurnSendCheerPendingInteraction(matchId, userId);
        if (interactionId == null) {
            throw new IllegalStateException("目前無法發送吶喊：請確認你有可用吶喊卡且場上有 Holomem");
        }
        return interactionId;
    }

    /**
     * 推進目前回合 phase。
     * MAIN -> PERFORMANCE；若為先攻玩家第一回合則直接跳到 END。
     * PERFORMANCE -> END。
     */
    @Transactional
    public void advancePhase(Long matchId, Long userId) {
        ActionContext context = loadActionContext(matchId, userId, Set.of(MatchPhase.RESET, MatchPhase.MAIN, MatchPhase.PERFORMANCE));
        if (context.blockedByPendingInteraction()) {
            return;
        }
        if (context.phase == MatchPhase.RESET) {
            advanceOpeningSetup(context, userId);
            return;
        }
        if (context.phase == MatchPhase.MAIN) {
            validateRequiredTurnActionsCompleted(matchId, userId, context.turnNumber, "請先完成後再進入下一階段");
        }

        MatchPhase nextPhase = resolveNextAdvancePhase(context, userId);

        MatchPhaseAdvanceGiftTransitionService.AdvancePhaseGiftTransition transition =
            matchPhaseAdvanceGiftTransitionService.resolveAdvancePhaseTransition(context.phase, nextPhase);
        AdvancePhaseFollowup followup = prepareAdvancePhaseFollowup(matchId, userId, context, transition);
        Map<String, Object> payload = buildAdvancePhasePayload(context.phase, nextPhase, followup, transition);
        matchTurnLifecycleService.advancePhase(
            context.match,
            userId,
            context.turnNumber,
            nextPhase,
            payload
        );
    }

    private void validateRequiredTurnActionsCompleted(Long matchId, Long userId, int turnNumber, String suffixMessage) {
        List<String> missingActions = new ArrayList<>();
        if (!hasDrawTurnAction(matchId, userId, turnNumber)) {
            missingActions.add("抽卡");
        }
        if (canPerformTurnCheerAction(matchId, userId) && !hasTurnCheerAction(matchId, userId, turnNumber)) {
            missingActions.add("發送吶喊");
        }
        if (!missingActions.isEmpty()) {
            throw new GameRuleException(
                GameErrorCode.TURN_ACTIONS_INCOMPLETE,
                "回合尚未完成：" + String.join("、", missingActions) + "。" + suffixMessage
            );
        }
    }

    private MatchPhase resolveNextAdvancePhase(ActionContext context, Long userId) {
        return switch (context.phase) {
            case MAIN -> isFirstPlayerFirstTurn(context.match, userId, context.turnNumber) ? MatchPhase.END : MatchPhase.PERFORMANCE;
            case PERFORMANCE -> MatchPhase.END;
            default -> throw new IllegalStateException("目前 phase 不支援推進：" + context.phase);
        };
    }

    private AdvancePhaseFollowup prepareAdvancePhaseFollowup(
        Long matchId,
        Long userId,
        ActionContext context,
        MatchPhaseAdvanceGiftTransitionService.AdvancePhaseGiftTransition transition
    ) {
        if (transition == null) {
            return AdvancePhaseFollowup.empty();
        }
        return createAdvancePhaseFollowup(
            matchId,
            userId,
            context.opponentUserId,
            context.turnNumber,
            matchPhaseAdvanceGiftTransitionService.prepareAdvancePhaseTransition(
                transition,
                matchId,
                userId,
                context.opponentUserId,
                context.turnNumber
            )
        );
    }

    private AdvancePhaseFollowup createAdvancePhaseFollowup(
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber,
        MatchPhaseAdvanceGiftTransitionService.GiftTransitionPreview transitionPreview
    ) {
        if (transitionPreview == null) {
            return AdvancePhaseFollowup.empty();
        }
        List<Map<String, Object>> ownGiftEffects = transitionPreview.ownGiftEffects();
        FollowupInteractionDecision ownDecision = createDeferredGiftTriggerDecision(
            matchId,
            userId,
            turnNumber,
            ownGiftEffects
        );

        List<Map<String, Object>> opponentGiftEffects = transitionPreview.opponentGiftEffects();
        FollowupInteractionDecision opponentDecision = null;
        if (opponentUserId != null) {
            opponentDecision = createDeferredGiftTriggerDecision(
                matchId,
                opponentUserId,
                turnNumber,
                opponentGiftEffects
            );
        }
        return new AdvancePhaseFollowup(
            ownGiftEffects,
            opponentGiftEffects,
            ownDecision,
            opponentDecision
        );
    }

    private FollowupInteractionDecision createDeferredGiftTriggerDecision(
        Long matchId,
        Long userId,
        int turnNumber,
        List<Map<String, Object>> giftEffects
    ) {
        if (giftEffects == null || giftEffects.isEmpty()) {
            return null;
        }
        return createGiftTriggeredEffectConfirmPendingInteraction(
            matchId,
            userId,
            null,
            null,
            buildGiftTriggerInteractionCards(matchId, userId, null, null, giftEffects),
            giftEffects,
            turnNumber
        );
    }

    private Map<String, Object> buildAdvancePhasePayload(
        MatchPhase currentPhase,
        MatchPhase nextPhase,
        AdvancePhaseFollowup followup,
        MatchPhaseAdvanceGiftTransitionService.AdvancePhaseGiftTransition transition
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromPhase", currentPhase.name());
        payload.put("toPhase", nextPhase.name());
        payload.put("firstPlayerFirstTurnSkip", currentPhase == MatchPhase.MAIN && nextPhase == MatchPhase.END);
        if (followup != null && transition != null) {
            matchPhaseAdvanceGiftTransitionService.putAdvancePhaseGiftEffectPayload(
                payload,
                transition,
                buildGiftTriggeredEffectDeferredSummary(followup.ownGiftEffects()),
                buildGiftTriggeredEffectDeferredSummary(followup.opponentGiftEffects())
            );
            putFollowupDecisionPayload(payload, followup.ownDecision());
            putOpponentFollowupDecisionPayload(payload, followup.opponentDecision());
        }
        return payload;
    }

    private void putOpponentFollowupDecisionPayload(
        Map<String, Object> payload,
        FollowupInteractionDecision followupDecision
    ) {
        if (payload == null || followupDecision == null) {
            return;
        }
        payload.put("opponentPendingInteractionDecisionId", followupDecision.decisionId());
        payload.put("opponentPendingInteractionDecisionType", followupDecision.decisionType());
    }

    /**
     * 推進開場設置流程。
     */
    private void advanceOpeningSetup(ActionContext context, Long userId) {
        Long matchId = context.match.getId();
        MatchPlayerEntity openingPlayer = matchPlayerRepository.findByMatchIdAndUserId(matchId, userId)
            .orElseThrow(() -> new IllegalArgumentException("你不在此房間中"));
        validateOpeningSetupAvailable(openingPlayer);

        matchTurnLifecycleService.completeOpeningSetup(context.match, userId, context.turnNumber);
    }

    private void validateOpeningSetupAvailable(MatchPlayerEntity openingPlayer) {
        if (openingPlayer == null) {
            throw new IllegalArgumentException("找不到開場設置玩家");
        }
        if (!openingPlayer.isMulliganDone()) {
            throw new IllegalStateException("請先完成起手調度");
        }
    }

    /**
     * 移動場上 Holomem（目前支援 BACK -> CENTER/COLLAB）。
     * 移動到 COLLAB 時會連帶處理 holopower 與 collab 觸發效果。
     */
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
        List<Map<String, Object>> collabGiftTriggeredEffects = List.of();
        Map<String, Object> collabGiftEffectSummary = null;
        Map<String, Object> collabTriggerSummary = null;
        MatchEffectService.TriggeredEffectPreview collabPreview = null;
        FollowupInteractionDecision collabTriggerConfirmDecision = null;
        if ("COLLAB".equals(targetZone)) {
            holopowerCardInstanceId = moveTopDeckCardToHolopower(matchId, userId);
            collabPreview = matchTriggeredCardEffectService.previewCollabTriggeredEffect(
                matchId,
                userId,
                asString(currentHolomem.get("card_id")),
                cardInstanceId
            );
            collabEffectSummary = buildTriggeredEffectDeferredSummary("COLLAB", collabPreview);
            collabGiftTriggeredEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnCollab(
                matchId,
                userId,
                cardInstanceId,
                context.turnNumber
            );
            if (!collabGiftTriggeredEffects.isEmpty()) {
                collabGiftEffectSummary = buildGiftTriggeredEffectDeferredSummary(collabGiftTriggeredEffects);
            }
            collabTriggerSummary = matchEventHookService.onHolomemCollab(
                matchId,
                userId,
                asString(currentHolomem.get("card_id")),
                cardInstanceId
            );
            if (collabPreview.hasEffect() || !collabGiftTriggeredEffects.isEmpty()) {
                collabTriggerConfirmDecision = createCollabTriggeredEffectConfirmPendingInteraction(
                    matchId,
                    userId,
                    cardInstanceId,
                    asString(currentHolomem.get("card_id")),
                    collabPreview,
                    collabGiftTriggeredEffects,
                    context.turnNumber
                );
            }
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
        }
        if (collabGiftEffectSummary != null && toBoolean(collabGiftEffectSummary.get("deferred"))) {
            payload.put("collabGiftEffect", collabGiftEffectSummary);
        }
        if (collabTriggerConfirmDecision != null) {
            putFollowupDecisionPayload(payload, collabTriggerConfirmDecision);
        }
        if (collabTriggerSummary != null) {
            payload.put("triggerSummary", collabTriggerSummary);
        }
        payload.put(
            "triggerResolutionOrder",
            buildTriggeredResolutionOrder(
                "COLLAB_TRIGGER",
                100,
                mergeEffectSummaryForChecks(
                    collabEffectSummary,
                    collabGiftEffectSummary == null ? List.of() : List.of(collabGiftEffectSummary)
                ),
                "COLLAB_EVENT_HOOK",
                200,
                collabTriggerSummary
            )
        );
        appendAction(
            context.match,
            userId,
            "COLLAB".equals(targetZone) ? "COLLAB" : "MOVE_STAGE_HOLOMEM",
            toJson(payload),
            context.turnNumber
        );
        if (collabEffectSummary != null && (collabPreview == null || !collabPreview.hasEffect())) {
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

    /**
     * 使用 Oshi 技能（NORMAL/SP）。
     * 會檢查回合使用次數、SP 一場一次限制，並結算 holopower 成本與技能效果。
     */
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
            targetHolomemCardInstanceId,
            true
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
        FollowupInteractionDecision followupDecision = createEffectPostTriggerConfirmPendingInteractionIfNeeded(
            matchId,
            userId,
            ACTION_TYPE_USE_OSHI_SKILL,
            oshiCardInstanceId,
            oshiCardId,
            effectSummary,
            context.turnNumber
        );
        if (followupDecision == null) {
            followupDecision = createFollowupInteractionPendingDecisionIfNeeded(
                matchId,
                userId,
                ACTION_TYPE_USE_OSHI_SKILL,
                oshiCardInstanceId,
                oshiCardId,
                effectType,
                effectSummary
            );
        }
        putFollowupDecisionPayload(payload, followupDecision);
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

    /**
     * 執行 バトンタッチ。
     * 來源限非休息 BACK，成本由該 BACK Holomem 支付，並與 CENTER 交換。
     */
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
        Long targetCenterHolomemCardInstanceId = requirePositiveId(
            request == null ? null : request.getTargetCenterHolomemCardInstanceId(),
            "targetCenterHolomemCardInstanceId"
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
        if (!"BACK".equals(sourceZone)) {
            throw new IllegalStateException("バトンタッチ 來源必須是 BACK Holomem");
        }
        if (toBoolean(source.get("is_rested"))) {
            throw new IllegalStateException("バトンタッチ 來源必須是非休息狀態的 BACK Holomem");
        }
        Long sourceHolomemId = asLong(source.get("id"));
        if (sourceHolomemId == null) {
            throw new IllegalStateException("來源 Holomem 資料異常");
        }
        if (sourceHolomemCardInstanceId.equals(targetCenterHolomemCardInstanceId)) {
            throw new IllegalStateException("バトンタッチ 來源與目標不可相同");
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
            targetCenterHolomemCardInstanceId
        );
        if (target == null) {
            throw new IllegalArgumentException("找不到要交換的 CENTER Holomem");
        }
        String targetZone = normalizeZone(target.get("zone"));
        if (!"CENTER".equals(targetZone)) {
            throw new IllegalStateException("バトンタッチ 目標必須是 CENTER Holomem");
        }
        Long targetHolomemId = asLong(target.get("id"));
        if (targetHolomemId == null) {
            throw new IllegalStateException("目標 Holomem 資料異常");
        }

        if (isStageActionLocked(matchId, userId, context.turnNumber, "BATON_TOUCH", sourceZone, sourceHolomemId)
            || isStageActionLocked(matchId, userId, context.turnNumber, "BATON_TOUCH", targetZone, targetHolomemId)) {
            throw new GameRuleException(GameErrorCode.STAGE_ACTION_LOCKED, "目前效果限制：不可バトンタッチ");
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
            SET zone = 'CENTER',
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
            SET zone = 'BACK',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            targetHolomemId,
            matchId,
            userId
        );
        if (moveSource != 1 || moveTarget != 1) {
            throw new IllegalStateException("バトンタッチ 移動失敗，請重新整理後重試");
        }

        List<Map<String, Object>> batonTouchGiftTriggeredEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnBatonTouchBack(
            matchId,
            userId,
            targetCenterHolomemCardInstanceId,
            context.turnNumber
        );
        Map<String, Object> batonTouchGiftEffectSummary = null;
        FollowupInteractionDecision batonTouchGiftDecision = null;
        if (!batonTouchGiftTriggeredEffects.isEmpty()) {
            batonTouchGiftEffectSummary = buildGiftTriggeredEffectDeferredSummary(batonTouchGiftTriggeredEffects);
            batonTouchGiftDecision = createGiftTriggeredEffectConfirmPendingInteraction(
                matchId,
                userId,
                targetCenterHolomemCardInstanceId,
                asString(target.get("card_id")),
                buildGiftTriggerInteractionCards(
                    matchId,
                    userId,
                    targetCenterHolomemCardInstanceId,
                    asString(target.get("card_id")),
                    batonTouchGiftTriggeredEffects
                ),
                batonTouchGiftTriggeredEffects,
                context.turnNumber
            );
        }

        context.match.setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceHolomemCardInstanceId", sourceHolomemCardInstanceId);
        payload.put("sourceCardId", asString(source.get("card_id")));
        payload.put("sourceFromZone", sourceZone);
        payload.put("sourceToZone", "CENTER");
        payload.put("targetHolomemCardInstanceId", targetCenterHolomemCardInstanceId);
        payload.put("targetCardId", asString(target.get("card_id")));
        payload.put("targetFromZone", targetZone);
        payload.put("targetToZone", "BACK");
        payload.put("requiredColorless", requiredColorless);
        payload.put("modifierColorless", batonTouchModifier);
        payload.put("cost", costSummary);
        if (batonTouchGiftEffectSummary != null) {
            payload.put("batonTouchGiftEffect", batonTouchGiftEffectSummary);
            putFollowupDecisionPayload(payload, batonTouchGiftDecision);
        }

        appendAction(
            context.match,
            userId,
            ACTION_TYPE_BATON_TOUCH,
            toJson(payload),
            context.turnNumber
        );
    }

    /**
     * 手動附加 Cheer 到指定我方 Holomem。
     * Cheer 來源允許 HAND/CHEER_DECK，附加後寫入 match_holomem_cheers。
     */
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
            INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
            VALUES (?, ?, ?, FALSE)
            """,
            matchHolomemId,
            cheerCardInstanceId,
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

    /**
     * 發動藝能攻擊（CENTER/COLLAB 各回合各一次）。
     * 會做費用驗證、傷害與關鍵字加成計算、gift 觸發、以及 down/life/勝負結算。
     */
    @Transactional
    public void attackArt(Long matchId, Long userId, AttackArtActionRequest request) {
        ActionContext context = loadActionContext(
            matchId,
            userId,
            Set.of(MatchPhase.MAIN, MatchPhase.DRAW, MatchPhase.CHEER, MatchPhase.PERFORMANCE)
        );
        if (context.blockedByPendingInteraction()) {
            return;
        }
        if (!hasDrawTurnAction(matchId, userId, context.turnNumber)) {
            throw new GameRuleException(
                GameErrorCode.TURN_ACTIONS_INCOMPLETE,
                "回合尚未完成：抽卡。請先完成後再使用藝能"
            );
        }
        if (canPerformTurnCheerAction(matchId, userId) && !hasTurnCheerAction(matchId, userId, context.turnNumber)) {
            throw new GameRuleException(
                GameErrorCode.TURN_ACTIONS_INCOMPLETE,
                "回合尚未完成：發送吶喊。請先完成後再使用藝能"
            );
        }
        if (context.phase != MatchPhase.PERFORMANCE) {
            throw new IllegalStateException("藝能只能在表演階段使用");
        }
        if (isFirstPlayerFirstTurn(context.match, userId, context.turnNumber)) {
            throw new IllegalStateException("先攻玩家第一回合不可使用藝能");
        }
        Long attackerCardInstanceId = requirePositiveId(
            request == null ? null : request.getAttackerCardInstanceId(),
            "attackerCardInstanceId"
        );
        Long targetCardInstanceId = request == null ? null : request.getTargetCardInstanceId();

        Map<String, Object> attacker = jdbcTemplate.query(
            """
            SELECT h.id, h.zone, h.is_rested, h.card_id, h.current_level, m.main_color
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
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
                row.put("current_level", rs.getString("current_level"));
                row.put("main_color", rs.getString("main_color"));
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
        int attachedSupportArtBonus = matchEffectCombatModifierService.resolveAttachedSupportArtBonus(
            matchId,
            asLong(attacker.get("id"))
        );
        // 藝能本身也可能帶有「依附著 Cheer 數量放大本次傷害」的文案。
        // 這類效果不是 stage 上另一張卡提供的 aura，而是本次藝能自己的條件，因此獨立計算。
        int artTextDamageBonus = matchEffectCombatModifierService.resolveArtTextDamageBonus(
            matchId,
            userId,
            context.turnNumber,
            asLong(attacker.get("id")),
            asString(art.get("effect_json_text"))
        );
        HoloxSlotRevealSummary holoxSlotRevealSummary = resolveHoloxSlotRevealSummary(
            matchId,
            userId,
            asString(art.get("name")),
            asString(art.get("effect_json_text"))
        );
        Map<String, Object> hbp02039SupportRecovery = applyHbp02039HoloxSupportRecovery(
            matchId,
            userId,
            attackerCardId,
            asString(art.get("name")),
            holoxSlotRevealSummary
        );
        Map<String, Object> hbp02040LifeLoss = applyHbp02040HoloxLifeLoss(
            matchId,
            userId,
            context.opponentUserId,
            context.turnNumber,
            asLong(attacker.get("id")),
            attackerCardId,
            asString(art.get("name")),
            holoxSlotRevealSummary
        );
        int holoxRevealArtBonus = holoxSlotRevealSummary.artBonus();
        // 常駐 Gift 的藝能加成可能依目標站位變化，先預設 0，待目標確定後再計算。
        int passiveGiftArtBonus = 0;
        Map<String, Integer> baseRequiredCheerCost = resolveArtCheerCost(asString(art.get("cost_cheer_json_text")));
        Map<String, Integer> passiveGiftArtCostReduction =
            matchEffectCombatModifierService.resolvePassiveGiftArtCheerCostReduction(
            matchId,
            userId,
            asLong(attacker.get("id")),
            asString(art.get("name"))
        );
        int turnArtDamageModifier = resolveTurnArtDamageModifier(
            matchId,
            userId,
            context.turnNumber,
            asLong(attacker.get("id"))
        );
        Map<String, Integer> requiredCheerCost = applyArtCheerCostReduction(
            baseRequiredCheerCost,
            passiveGiftArtCostReduction
        );
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
        Map<String, Object> defenderSelfDownedHolderSnapshot = null;
        List<Map<String, Object>> defenderSelfDownedFanSupportSnapshots = List.of();
        boolean passiveGiftTargetRestrictionToCollab = false;
        boolean passiveGiftTargetRestrictionApplied = false;
        if (hasOpponentHolomem) {
            targetHolomem = resolveOpponentTargetHolomem(matchId, context.opponentUserId, targetCardInstanceId);
            if (targetHolomem == null) {
                throw new IllegalStateException("DAMAGE 找不到可攻擊的對手 Holomen");
            }
            passiveGiftTargetRestrictionToCollab = hasPassiveGiftTargetRestrictionToCollab(matchId, context.opponentUserId);
            if (passiveGiftTargetRestrictionToCollab) {
                if (!"COLLAB".equals(targetHolomem.zone())) {
                    if (targetCardInstanceId != null && targetCardInstanceId > 0) {
                        throw new IllegalStateException("對手有用心棒效果，藝能只能以對手 COLLAB Holomen 為目標");
                    }
                    TargetHolomem collabTarget = loadOpponentCollabTargetHolomem(matchId, context.opponentUserId);
                    if (collabTarget == null) {
                        throw new IllegalStateException("對手有用心棒效果，目前沒有可被指定的 COLLAB Holomen");
                    }
                    targetHolomem = collabTarget;
                }
                passiveGiftTargetRestrictionApplied = true;
            }
            defenderSelfDownedHolderSnapshot = matchGiftTriggerService.loadGiftHolderSnapshot(
                matchId,
                context.opponentUserId,
                targetHolomem.holomemId()
            );
            defenderSelfDownedFanSupportSnapshots = loadSelfDownedFanSupportSnapshots(
                matchId,
                context.opponentUserId,
                targetHolomem.holomemId()
            );
            DamageRedirectTarget redirectTarget = resolveDamageRedirectTarget(
                matchId,
                context.opponentUserId,
                context.turnNumber
            );
            if (redirectTarget != null) {
                targetHolomem = redirectTarget.target();
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
        int turnIncomingDamageReduction = hasOpponentHolomem
            ? resolveIncomingDamageReduction(matchId, context.opponentUserId, context.turnNumber)
            : 0;
        // 受擊方自己的常駐 Gift 也可能直接改寫這次實際傷害。
        // 例如 `HSD07-009` 這種「這張卡自己受傷 -10」不是 turn effect，
        // 因此要在這裡和 turn-based modifier 分開計算，避免兩種來源混成同一個 state 模型。
        int passiveGiftIncomingDamageReduction = hasOpponentHolomem && targetHolomem != null
            ? matchEffectCombatModifierService.resolvePassiveGiftIncomingDamageReduction(
                matchId,
                context.opponentUserId,
                targetHolomem.holomemId(),
                normalizeLevel(asString(attacker.get("current_level")))
            )
            : 0;
        int attachedSupportIncomingDamageReduction = hasOpponentHolomem && targetHolomem != null
            ? matchEffectCombatModifierService.resolveAttachedSupportIncomingDamageReduction(
                matchId,
                targetHolomem.holomemId(),
                targetHolomem.zone()
            )
            : 0;
        if (targetHolomem != null) {
            passiveGiftArtBonus = matchEffectCombatModifierService.resolvePassiveGiftArtBonus(
                matchId,
                userId,
                asLong(attacker.get("id")),
                targetHolomem.zone()
            );
        }
        int incomingDamageReduction = turnIncomingDamageReduction
            + passiveGiftIncomingDamageReduction
            + attachedSupportIncomingDamageReduction;
        int totalDamage = Math.max(
            baseDamage + attachedSupportArtBonus + artTextDamageBonus + holoxRevealArtBonus + passiveGiftArtBonus
                + turnArtDamageModifier + criticalBonus - incomingDamageReduction,
            0
        );
        if (totalDamage <= 0) {
            throw new IllegalStateException("此藝能目前未解析出可造成的傷害");
        }
        Map<String, Object> defenderDamageReceivedGiftSummary = null;
        if (hasOpponentHolomem && targetHolomem != null) {
            defenderDamageReceivedGiftSummary = matchTriggeredCombatEffectService.resolveTriggeredGiftDamagePrevention(
                matchId,
                context.opponentUserId,
                userId,
                attackerCardInstanceId,
                effectiveTargetCardInstanceId,
                context.turnNumber,
                totalDamage
            );
            if (defenderDamageReceivedGiftSummary != null && !defenderDamageReceivedGiftSummary.isEmpty()) {
                appendAction(
                    context.match,
                    context.opponentUserId,
                    "GIFT_TRIGGER",
                    toJson(defenderDamageReceivedGiftSummary),
                    context.turnNumber
                );
                Integer damageAfterGift = asInt(defenderDamageReceivedGiftSummary.get("damageAfter"));
                if (damageAfterGift != null) {
                    totalDamage = Math.max(damageAfterGift, 0);
                }
            }
        }
        Map<String, Object> artSummary;
        Long lostLifeCardInstanceId = null;
        if (hasOpponentHolomem) {
            if (totalDamage > 0) {
                artSummary = matchEffectDamageService.applyArtDamage(
                    matchId,
                    userId,
                    totalDamage,
                    effectiveTargetCardInstanceId,
                    true
                );
                lostLifeCardInstanceId = asLong(artSummary.get("lostLifeCardInstanceId"));
            } else {
                artSummary = new LinkedHashMap<>();
                artSummary.put("effectType", "ART_DAMAGE_PREVENTED");
                artSummary.put("damageRequested", 0);
                artSummary.put("damageApplied", 0);
                artSummary.put("reason", "傷害已由受傷 Gift 抵銷");
                artSummary.put("lifeReduced", false);
            }
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
        Map<String, Object> officialCardArtExtraSummary = applyOfficialCardArtExtraEffects(
            matchId,
            userId,
            context.opponentUserId,
            asLong(attacker.get("id")),
            attackerCardId,
            asString(art.get("name"))
        );
        List<Map<String, Object>> officialCardArtExtraEffects = extractExecutedEffectSummaries(officialCardArtExtraSummary);
        Map<String, Object> officialOshiArtReactiveSummary = applyOfficialOshiArtReactiveEffects(
            matchId,
            userId,
            context.opponentUserId,
            context.turnNumber,
            asLong(attacker.get("id")),
            effectiveTargetCardInstanceId,
            asString(attacker.get("main_color")),
            targetHolomem,
            artSummary,
            officialCardArtExtraSummary
        );
        List<Map<String, Object>> officialOshiArtReactiveEffects = extractExecutedEffectSummaries(officialOshiArtReactiveSummary);
        Map<String, Object> attackSummaryForTriggeredChecks = mergeEffectSummaryForChecks(
            artSummary,
            mergeEffectLists(officialCardArtExtraEffects, officialOshiArtReactiveEffects)
        );
        Map<String, Object> officialOshiSelfDownedSummary = Map.of();
        List<Map<String, Object>> giftTriggeredEffects = new ArrayList<>();
        giftTriggeredEffects.addAll(
            matchGiftTriggerService.previewGiftTriggeredEffectsOnArt(
                matchId,
                userId,
                attackerCardInstanceId,
                effectiveTargetCardInstanceId,
                context.turnNumber,
                asString(art.get("name"))
            )
        );
        if (hasOpponentHolomem && hasHolomemDowned(attackSummaryForTriggeredChecks)) {
            giftTriggeredEffects.addAll(
                matchGiftTriggerService.previewGiftTriggeredEffectsOnDownedOpponent(
                    matchId,
                    userId,
                    attackerCardInstanceId,
                    effectiveTargetCardInstanceId,
                    context.turnNumber
                )
            );
        }
        Map<String, Object> artDownTriggeredEffectSummary = hasOpponentHolomem && hasHolomemDowned(attackSummaryForTriggeredChecks)
            ? matchTriggeredCombatEffectService.applyArtDownTriggeredEffects(
                matchId,
                userId,
                attackerCardInstanceId,
                asString(art.get("effect_json_text"))
            )
            : Map.of(
                "triggerType", "ART_DOWNED_OPPONENT",
                "requestedEffects", List.of(),
                "executedEffects", List.of(),
                "unsupportedEffects", List.of(),
                "skippedEffects", List.of(),
                "applied", false
            );
        List<Map<String, Object>> defenderGiftTriggeredEffects = new ArrayList<>();
        String downedTargetCardId = targetHolomem == null ? null : targetHolomem.cardId();
        String downedTargetZone = targetHolomem == null ? null : targetHolomem.zone();
        if (hasOpponentHolomem && hasHolomemDowned(attackSummaryForTriggeredChecks)) {
            officialOshiSelfDownedSummary = applyOfficialOshiSelfDownedEffects(
                matchId,
                context.opponentUserId,
                userId,
                context.turnNumber,
                targetHolomem,
                defenderSelfDownedHolderSnapshot,
                artSummary
            );
            defenderGiftTriggeredEffects.addAll(
                matchGiftTriggerService.previewGiftTriggeredEffectsOnSelfDowned(
                    matchId,
                    context.opponentUserId,
                    effectiveTargetCardInstanceId,
                    downedTargetZone,
                    context.turnNumber,
                    defenderSelfDownedHolderSnapshot
                )
            );
            defenderGiftTriggeredEffects.addAll(
                matchGiftTriggerService.previewGiftTriggeredEffectsOnAllyDowned(
                    matchId,
                    context.opponentUserId,
                    effectiveTargetCardInstanceId,
                    downedTargetZone,
                    context.turnNumber
                )
            );
            defenderGiftTriggeredEffects.addAll(
                previewHbp01124FanTriggeredEffectsOnSelfDowned(
                    effectiveTargetCardInstanceId,
                    downedTargetZone,
                    defenderSelfDownedHolderSnapshot,
                    defenderSelfDownedFanSupportSnapshots
                )
            );
        }
        FollowupInteractionDecision postTriggerConfirmDecision = null;
        FollowupInteractionDecision defenderGiftConfirmDecision = null;
        Map<String, Object> downEventPreview = extractDownEventPreview(artSummary);
        Map<String, Object> postTriggerEffectSummary = buildAttackArtPostTriggerDeferredSummary(
            giftTriggeredEffects,
            downEventPreview
        );
        if (!giftTriggeredEffects.isEmpty() || downEventPreview != null) {
            List<Map<String, Object>> sourceCards = buildGiftTriggerInteractionCards(
                matchId,
                userId,
                attackerCardInstanceId,
                attackerCardId,
                giftTriggeredEffects
            );
            postTriggerConfirmDecision = createAttackArtPostTriggerConfirmPendingInteraction(
                matchId,
                userId,
                attackerCardInstanceId,
                attackerCardId,
                sourceCards,
                giftTriggeredEffects,
                downEventPreview,
                context.turnNumber
            );
        }
        Map<String, Object> defenderGiftEffectSummary = buildGiftTriggeredEffectDeferredSummary(defenderGiftTriggeredEffects);
        if (!defenderGiftTriggeredEffects.isEmpty()) {
            List<Map<String, Object>> defenderSourceCards = buildGiftTriggerInteractionCards(
                matchId,
                context.opponentUserId,
                effectiveTargetCardInstanceId,
                downedTargetCardId,
                defenderGiftTriggeredEffects
            );
            defenderGiftConfirmDecision = createGiftTriggeredEffectConfirmPendingInteraction(
                matchId,
                context.opponentUserId,
                effectiveTargetCardInstanceId,
                downedTargetCardId,
                defenderSourceCards,
                defenderGiftTriggeredEffects,
                context.turnNumber
            );
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
        context.match.setCurrentPhase(MatchPhase.PERFORMANCE.name());
        touchUpdatedAt(context.match);
        matchRepository.saveAndFlush(context.match);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attackerCardInstanceId", attackerCardInstanceId);
        payload.put("attackerCardId", attackerCardId);
        payload.put("attackerZone", attackerZone);
        payload.put("targetCardInstanceId", effectiveTargetCardInstanceId);
        payload.put("passiveGiftTargetRestrictionToCollab", passiveGiftTargetRestrictionToCollab);
        payload.put("passiveGiftTargetRestrictionApplied", passiveGiftTargetRestrictionApplied);
        payload.put("damageRedirectApplied", hasOpponentHolomem && targetCardInstanceId != null
            && !targetCardInstanceId.equals(effectiveTargetCardInstanceId));
        payload.put("targetMainColor", targetHolomem == null ? null : targetHolomem.mainColor());
        payload.put("artName", asString(art.get("name")));
        payload.put("artOrderIndex", art.get("order_index"));
        payload.put("artBaseCost", baseRequiredCheerCost);
        payload.put("artCost", requiredCheerCost);
        payload.put("passiveGiftArtCostReduction", passiveGiftArtCostReduction);
        payload.put("costPayment", costSummary);
        payload.put("artBaseDamage", baseDamage);
        payload.put("attachedSupportArtBonus", attachedSupportArtBonus);
        payload.put("artTextDamageBonus", artTextDamageBonus);
        payload.put("holoxRevealArtBonus", holoxRevealArtBonus);
        if (holoxSlotRevealSummary.revealApplied()) {
            payload.put("holoxReveal", holoxSlotRevealSummary.toPayload());
        }
        if (!hbp02039SupportRecovery.isEmpty()) {
            payload.put("hbp02039SupportRecovery", hbp02039SupportRecovery);
        }
        if (!hbp02040LifeLoss.isEmpty()) {
            payload.put("hbp02040LifeLoss", hbp02040LifeLoss);
        }
        payload.put("passiveGiftArtBonus", passiveGiftArtBonus);
        payload.put("turnArtDamageModifier", turnArtDamageModifier);
        payload.put("criticalColor", artCritical == null ? null : artCritical.color());
        payload.put("criticalBonus", criticalBonus);
        payload.put("criticalApplied", criticalApplied);
        payload.put("turnIncomingDamageReduction", turnIncomingDamageReduction);
        payload.put("passiveGiftIncomingDamageReduction", passiveGiftIncomingDamageReduction);
        payload.put("attachedSupportIncomingDamageReduction", attachedSupportIncomingDamageReduction);
        payload.put("incomingDamageReduction", incomingDamageReduction);
        payload.put("defenderDamageReceivedGift", defenderDamageReceivedGiftSummary);
        payload.put("artTotalDamage", totalDamage);
        payload.put("effect", artSummary);
        if (!officialCardArtExtraSummary.isEmpty()) {
            payload.put("officialCardArtExtra", officialCardArtExtraSummary);
        }
        if (!officialOshiArtReactiveSummary.isEmpty()) {
            payload.put("officialOshiArtReactive", officialOshiArtReactiveSummary);
        }
        if (!officialOshiSelfDownedSummary.isEmpty()) {
            payload.put("officialOshiSelfDowned", officialOshiSelfDownedSummary);
        }
        payload.put("artDownTriggeredEffects", artDownTriggeredEffectSummary);
        payload.put("postTriggerEffects", postTriggerEffectSummary);
        payload.put("defenderGiftEffects", defenderGiftEffectSummary);
        payload.put("hasNextPerformanceAction", hasNextPerformanceAction);
        payload.put("lostLifeCardInstanceId", lostLifeCardInstanceId);
        putFollowupDecisionPayload(payload, postTriggerConfirmDecision);
        if (defenderGiftConfirmDecision != null) {
            payload.put("defenderPendingInteractionDecisionId", defenderGiftConfirmDecision.decisionId());
            payload.put("defenderPendingInteractionDecisionType", defenderGiftConfirmDecision.decisionType());
        }

        appendAction(
            context.match,
            userId,
            "ATTACK_ART",
            toJson(payload),
            context.turnNumber
        );
        List<Map<String, Object>> additionalEffectSummaries = new ArrayList<>();
        additionalEffectSummaries.addAll(officialCardArtExtraEffects);
        additionalEffectSummaries.addAll(officialOshiArtReactiveEffects);
        additionalEffectSummaries.addAll(extractExecutedEffectSummaries(officialOshiSelfDownedSummary));
        additionalEffectSummaries.add(artDownTriggeredEffectSummary);
        if (!hbp02040LifeLoss.isEmpty()) {
            additionalEffectSummaries.add(hbp02040LifeLoss);
        }
        Map<String, Object> effectSummaryForChecks = mergeEffectSummaryForChecks(
            artSummary,
            additionalEffectSummaries
        );
        if (evaluateCardEffectMatchFinish(context.match, userId, context.turnNumber, effectSummaryForChecks)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (hasLifeReduced(effectSummaryForChecks) && evaluateLifeDefeat(context.match, userId, context.turnNumber)) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        } else if (
            hasHolomemDowned(effectSummaryForChecks) && evaluateNoHolomemDefeat(context.match, userId, context.turnNumber)
        ) {
            touchUpdatedAt(context.match);
            matchRepository.saveAndFlush(context.match);
        }
        enqueueLifeLossSendCheerInteractions(context.match, matchId, effectSummaryForChecks, context.turnNumber);
    }

    private Map<String, Object> applyOfficialCardArtExtraEffects(
        Long matchId,
        Long userId,
        Long opponentUserId,
        Long attackerHolomemId,
        String attackerCardId,
        String artName
    ) {
        if (matchId == null || userId == null || opponentUserId == null || attackerHolomemId == null || !StringUtils.hasText(attackerCardId)) {
            return Map.of();
        }
        if ("HBP01-087".equals(attackerCardId) && StringUtils.hasText(artName) && artName.contains("雨のマントラ")) {
            return applyHbp01087ArtRainMantra(matchId, userId, opponentUserId, attackerHolomemId);
        }
        if ("HBP01-088".equals(attackerCardId) && StringUtils.hasText(artName) && artName.contains("ムーン ムーン ムーナだよ")) {
            return applyHbp01088ArtMoonMoonMoona(matchId, userId, opponentUserId);
        }
        return Map.of();
    }

    private Map<String, Object> applyHbp01087ArtRainMantra(
        Long matchId,
        Long userId,
        Long opponentUserId,
        Long attackerHolomemId
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cardId", "HBP01-087");
        summary.put("effectType", "ART_EXTRA_EFFECT");
        summary.put("artName", "雨のマントラ");
        Map<String, Object> archivedCheerSummary = archiveOneAttachedCheerForArtExtra(matchId, userId, attackerHolomemId);
        summary.put("archiveAttachedCheer", archivedCheerSummary);
        if (!toBoolean(archivedCheerSummary.get("applied"))) {
            summary.put("applied", false);
            summary.put("reason", "NO_ATTACHED_CHEER_TO_ARCHIVE");
            summary.put("executedEffects", List.of());
            return summary;
        }
        List<Long> opponentBackTargets = loadOpponentBackHolomemCardInstanceIds(matchId, opponentUserId);
        summary.put("targetCount", opponentBackTargets.size());
        if (opponentBackTargets.isEmpty()) {
            summary.put("applied", false);
            summary.put("reason", "NO_OPPONENT_BACK_HOLOMEM");
            summary.put("executedEffects", List.of());
            return summary;
        }
        List<Map<String, Object>> executed = new ArrayList<>();
        String effectJson = toJson(Map.of(
            "type", "DAMAGE",
            "value", 20,
            "rawText", "相手のバックホロメン全員に特殊ダメージ20を与える（ダウンしても相手のライフは減らない）。",
            "downDoesNotReduceLife", true
        ));
        for (Long targetCardInstanceId : opponentBackTargets) {
            Map<String, Object> effectSummary = matchEffectService.applySupportEffect(
                matchId,
                userId,
                "DAMAGE",
                effectJson,
                "ENEMY",
                List.of(),
                targetCardInstanceId
            );
            executed.add(effectSummary);
        }
        summary.put("applied", !executed.isEmpty());
        summary.put("executedEffects", executed);
        return summary;
    }

    private Map<String, Object> applyHbp01088ArtMoonMoonMoona(
        Long matchId,
        Long userId,
        Long opponentUserId
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cardId", "HBP01-088");
        summary.put("effectType", "ART_EXTRA_EFFECT");
        summary.put("artName", "ムーン ムーン ムーナだよ！");
        int diceRoll = diceService.rollD6();
        boolean even = diceRoll % 2 == 0;
        summary.put("diceRoll", diceRoll);
        summary.put("diceEven", even);
        if (!even) {
            summary.put("applied", false);
            summary.put("reason", "DICE_ODD");
            summary.put("executedEffects", List.of());
            return summary;
        }
        List<Long> opponentBackTargets = loadOpponentBackHolomemCardInstanceIds(matchId, opponentUserId);
        summary.put("candidateTargetCount", opponentBackTargets.size());
        if (opponentBackTargets.isEmpty()) {
            summary.put("applied", false);
            summary.put("reason", "NO_OPPONENT_BACK_HOLOMEM");
            summary.put("executedEffects", List.of());
            return summary;
        }
        Long targetCardInstanceId = opponentBackTargets.get(0);
        String effectJson = toJson(Map.of(
            "type", "DAMAGE",
            "value", 20,
            "rawText", "偶数の時、相手のバックホロメン1人に特殊ダメージ20を与える（ダウンしても相手のライフは減らない）。",
            "downDoesNotReduceLife", true
        ));
        Map<String, Object> effectSummary = matchEffectService.applySupportEffect(
            matchId,
            userId,
            "DAMAGE",
            effectJson,
            "ENEMY",
            List.of(),
            targetCardInstanceId
        );
        summary.put("selectedTargetCardInstanceId", targetCardInstanceId);
        summary.put("applied", true);
        summary.put("executedEffects", List.of(effectSummary));
        return summary;
    }

    private List<Map<String, Object>> loadSelfDownedFanSupportSnapshots(
        Long matchId,
        Long ownerUserId,
        Long holderHolomemId
    ) {
        if (matchId == null || ownerUserId == null || holderHolomemId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
            SELECT hs.match_card_id AS support_card_instance_id,
                   hs.support_card_id,
                   COALESCE(sc.effect_json ->> 'rawText', '') AS raw_text
            FROM match_holomem_supports hs
            JOIN support_cards sc ON sc.card_id = hs.support_card_id
            JOIN match_cards mc ON mc.id = hs.match_card_id
            WHERE hs.match_holomem_id = ?
              AND hs.support_type = 'FAN'
              AND hs.support_card_id = 'HBP01-124'
              AND mc.match_id = ?
              AND mc.owner_user_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("supportCardInstanceId", rs.getLong("support_card_instance_id"));
                row.put("supportCardId", rs.getString("support_card_id"));
                row.put("rawText", rs.getString("raw_text"));
                return row;
            },
            holderHolomemId,
            matchId,
            ownerUserId
        );
    }

    private List<Map<String, Object>> previewHbp01124FanTriggeredEffectsOnSelfDowned(
        Long downedCardInstanceId,
        String downedStageZone,
        Map<String, Object> holderSnapshot,
        List<Map<String, Object>> fanSupportSnapshots
    ) {
        if (holderSnapshot == null || holderSnapshot.isEmpty() || fanSupportSnapshots == null || fanSupportSnapshots.isEmpty()) {
            return List.of();
        }
        Long holderHolomemId = asLong(holderSnapshot.get("holomem_id"));
        List<Long> attachedCheerCardInstanceIds = toLongList(holderSnapshot.get("attached_cheer_card_instance_ids"));
        List<String> attachedCheerCardIds = toStringList(holderSnapshot.get("attached_cheer_card_ids"));
        List<Long> stackCardInstanceIds = toLongList(holderSnapshot.get("stack_card_instance_ids"));
        List<String> stackCardIds = toStringList(holderSnapshot.get("stack_card_ids"));
        List<Map<String, Object>> previews = new ArrayList<>();
        for (Map<String, Object> supportSnapshot : fanSupportSnapshots) {
            Long supportCardInstanceId = asLong(supportSnapshot.get("supportCardInstanceId"));
            String supportCardId = asString(supportSnapshot.get("supportCardId"));
            String rawText = asString(supportSnapshot.get("rawText"));
            if ("HBP01-124".equals(supportCardId)) {
                rawText = "相手のターンで、このファンが付いているホロメンがダウンした時、このファンが付いているホロメンのエール1枚を、自分の他のホロメンに付け替える。";
            }
            if (supportCardInstanceId == null || supportCardInstanceId <= 0 || !StringUtils.hasText(rawText)) {
                continue;
            }
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("triggerType", "SELF_DOWNED");
            preview.put("giftHolderHolomemId", holderHolomemId);
            preview.put("giftHolderCardInstanceId", supportCardInstanceId);
            preview.put("giftHolderCardId", supportCardId);
            preview.put("giftHolderZone", normalizeZone(downedStageZone));
            preview.put("sourceCardInstanceId", downedCardInstanceId);
            preview.put("triggerTargetCardInstanceId", downedCardInstanceId);
            preview.put("rawText", rawText);
            preview.put("requestedEffects", List.of("REATTACH"));
            preview.put("executedEffects", List.of());
            preview.put("unsupportedEffects", List.of());
            preview.put("skippedEffects", List.of());
            preview.put("giftHolderAttachedCheerCardInstanceIds", attachedCheerCardInstanceIds);
            preview.put("giftHolderAttachedCheerCardIds", attachedCheerCardIds);
            preview.put("giftHolderStackCardInstanceIds", stackCardInstanceIds);
            preview.put("giftHolderStackCardIds", stackCardIds);
            previews.add(preview);
        }
        return previews;
    }

    private Map<String, Object> archiveOneAttachedCheerForArtExtra(
        Long matchId,
        Long userId,
        Long attackerHolomemId
    ) {
        Map<String, Object> attachedCheer = jdbcTemplate.query(
            """
            SELECT mhc.id AS cheer_row_id,
                   mhc.match_card_id,
                   mhc.cheer_card_id
            FROM match_holomem_cheers mhc
            WHERE mhc.match_holomem_id = ?
            ORDER BY mhc.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cheer_row_id", rs.getLong("cheer_row_id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("cheer_card_id", rs.getString("cheer_card_id"));
                return row;
            },
            attackerHolomemId
        );
        Map<String, Object> summary = new LinkedHashMap<>();
        if (attachedCheer == null) {
            summary.put("applied", false);
            summary.put("reason", "NO_ATTACHED_CHEER");
            return summary;
        }
        Long cheerRowId = asLong(attachedCheer.get("cheer_row_id"));
        Long cheerCardInstanceId = asLong(attachedCheer.get("match_card_id"));
        String cheerCardId = asString(attachedCheer.get("cheer_card_id"));
        if (cheerRowId == null || !StringUtils.hasText(cheerCardId)) {
            summary.put("applied", false);
            summary.put("reason", "INVALID_ATTACHED_CHEER");
            return summary;
        }
        int deleted = jdbcTemplate.update(
            "DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?",
            cheerRowId,
            attackerHolomemId
        );
        if (deleted != 1) {
            summary.put("applied", false);
            summary.put("reason", "DELETE_ATTACHED_CHEER_FAILED");
            return summary;
        }
        Long archivedCardInstanceId = archiveStageCheerCard(matchId, userId, cheerCardInstanceId, cheerCardId);
        summary.put("applied", archivedCardInstanceId != null);
        summary.put("cheerCardId", cheerCardId);
        summary.put("archivedCardInstanceId", archivedCardInstanceId);
        if (archivedCardInstanceId == null) {
            summary.put("reason", "ARCHIVE_ATTACHED_CHEER_FAILED");
        }
        return summary;
    }

    private List<Long> loadOpponentBackHolomemCardInstanceIds(Long matchId, Long opponentUserId) {
        if (matchId == null || opponentUserId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
            ORDER BY id
            """,
            (rs, rowNum) -> rs.getLong("match_card_id"),
            matchId,
            opponentUserId
        );
    }

    private List<Map<String, Object>> extractExecutedEffectSummaries(Map<String, Object> effectSummary) {
        if (effectSummary == null || effectSummary.isEmpty()) {
            return List.of();
        }
        Object executed = effectSummary.get("executedEffects");
        if (!(executed instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Object effect : list) {
            if (effect instanceof Map<?, ?> effectMap) {
                Map<String, Object> casted = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : effectMap.entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                    }
                    casted.put(entry.getKey().toString(), entry.getValue());
                }
                summaries.add(casted);
            }
        }
        return summaries;
    }

    private List<Map<String, Object>> mergeEffectLists(
        List<Map<String, Object>> first,
        List<Map<String, Object>> second
    ) {
        if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
            return List.of();
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged;
    }

    private Map<String, Object> applyOfficialOshiArtReactiveEffects(
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber,
        Long attackerHolomemId,
        Long targetCardInstanceId,
        String attackerMainColor,
        TargetHolomem targetHolomem,
        Map<String, Object> artSummary,
        Map<String, Object> officialCardArtExtraSummary
    ) {
        String oshiCardId = loadPlayerOshiCardId(matchId, userId);
        if (!StringUtils.hasText(oshiCardId)) {
            return Map.of();
        }
        List<Map<String, Object>> executed = new ArrayList<>();
        if ("HBP01-007".equals(oshiCardId)) {
            Map<String, Object> hbp01007 = applyHbp01007OshiBackDamageTrigger(
                matchId,
                userId,
                opponentUserId,
                turnNumber,
                targetCardInstanceId,
                attackerMainColor,
                targetHolomem,
                artSummary
            );
            if (!hbp01007.isEmpty()) {
                executed.add(hbp01007);
            }
        }
        if ("HBP01-008".equals(oshiCardId)) {
            Map<String, Object> hbp01008 = applyHbp01008OshiArchiveCheerTrigger(
                matchId,
                userId,
                opponentUserId,
                turnNumber,
                attackerMainColor,
                officialCardArtExtraSummary
            );
            if (!hbp01008.isEmpty()) {
                executed.add(hbp01008);
            }
        }
        if (executed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "OSHI_REACTIVE_ART_EFFECTS");
        summary.put("oshiCardId", oshiCardId);
        summary.put("executedEffects", executed);
        summary.put("applied", true);
        return summary;
    }

    private Map<String, Object> applyHbp01007OshiBackDamageTrigger(
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber,
        Long targetCardInstanceId,
        String attackerMainColor,
        TargetHolomem targetHolomem,
        Map<String, Object> artSummary
    ) {
        if (!"BLUE".equals(normalizeZone(attackerMainColor)) || targetHolomem == null || !"BACK".equals(targetHolomem.zone())) {
            return Map.of();
        }
        if (asInt(artSummary == null ? null : artSummary.get("damageApplied")) <= 0) {
            return Map.of();
        }
        if (toBoolean(artSummary == null ? null : artSummary.get("downed"))) {
            return Map.of();
        }
        if (!canUseOshiSkill(matchId, userId, "NORMAL", 2)) {
            return Map.of();
        }
        Map<String, Object> holopowerPayment = consumeHolopowerCostToArchive(matchId, userId, 2);
        markOshiSkillUsed(matchId, userId, "NORMAL");
        String effectJson = toJson(Map.of(
            "type", "DAMAGE",
            "value", 50,
            "rawText", "この推しホロメンか自分の青ホロメンが相手のバックホロメンにダメージを与えた時、その相手のバックホロメン1人に特殊ダメージ50を与える（ダウンしても相手のライフは減らない）。",
            "downDoesNotReduceLife", true
        ));
        Map<String, Object> damage = matchEffectService.applySupportEffect(
            matchId,
            userId,
            "DAMAGE",
            effectJson,
            "ENEMY",
            List.of(),
            targetCardInstanceId
        );
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "OSHI_SKILL_TRIGGER");
        summary.put("oshiCardId", "HBP01-007");
        summary.put("skillType", "NORMAL");
        summary.put("skillName", "ほうき星");
        summary.put("triggerType", "DEAL_BACK_DAMAGE");
        summary.put("holopowerCost", 2);
        summary.put("holopowerPayment", holopowerPayment);
        summary.put("targetCardInstanceId", targetCardInstanceId);
        summary.put("executedEffects", List.of(damage));
        summary.put("applied", true);
        return summary;
    }

    private Map<String, Object> applyHbp01008OshiArchiveCheerTrigger(
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber,
        String attackerMainColor,
        Map<String, Object> officialCardArtExtraSummary
    ) {
        if (!"BLUE".equals(normalizeZone(attackerMainColor))) {
            return Map.of();
        }
        Object archiveAttachedCheer = officialCardArtExtraSummary == null ? null : officialCardArtExtraSummary.get("archiveAttachedCheer");
        if (!(archiveAttachedCheer instanceof Map<?, ?> archiveMap) || !toBoolean(archiveMap.get("applied"))) {
            return Map.of();
        }
        if (!canUseOshiSkill(matchId, userId, "NORMAL", 1)) {
            return Map.of();
        }
        Long targetCardInstanceId = loadFirstOpponentHolomemCardInstanceId(matchId, opponentUserId);
        if (targetCardInstanceId == null) {
            return Map.of();
        }
        Map<String, Object> holopowerPayment = consumeHolopowerCostToArchive(matchId, userId, 1);
        markOshiSkillUsed(matchId, userId, "NORMAL");
        String effectJson = toJson(Map.of(
            "type", "DAMAGE",
            "value", 20,
            "rawText", "自分の青ホロメンの能力でエールをアーカイブした時、相手のホロメン1人に特殊ダメージ20を与える（ダウンしても相手のライフは減らない）。",
            "downDoesNotReduceLife", true
        ));
        Map<String, Object> damage = matchEffectService.applySupportEffect(
            matchId,
            userId,
            "DAMAGE",
            effectJson,
            "ENEMY",
            List.of(),
            targetCardInstanceId
        );
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "OSHI_SKILL_TRIGGER");
        summary.put("oshiCardId", "HBP01-008");
        summary.put("skillType", "NORMAL");
        summary.put("skillName", "レイン・シャーマニズム");
        summary.put("triggerType", "ARCHIVE_CHEER_BY_BLUE_ABILITY");
        summary.put("holopowerCost", 1);
        summary.put("holopowerPayment", holopowerPayment);
        summary.put("targetCardInstanceId", targetCardInstanceId);
        summary.put("executedEffects", List.of(damage));
        summary.put("applied", true);
        return summary;
    }

    private Map<String, Object> applyOfficialOshiSelfDownedEffects(
        Long matchId,
        Long defenderUserId,
        Long attackerUserId,
        int turnNumber,
        TargetHolomem downedTarget,
        Map<String, Object> holderSnapshot,
        Map<String, Object> artSummary
    ) {
        String oshiCardId = loadPlayerOshiCardId(matchId, defenderUserId);
        if (!StringUtils.hasText(oshiCardId) || downedTarget == null || holderSnapshot == null || holderSnapshot.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> executed = new ArrayList<>();
        if ("HBP01-004".equals(oshiCardId)) {
            Map<String, Object> hbp01004 = applyHbp01004OshiSelfDownedReattach(
                matchId,
                defenderUserId,
                attackerUserId,
                holderSnapshot
            );
            if (!hbp01004.isEmpty()) {
                executed.add(hbp01004);
            }
        }
        if ("HBP01-006".equals(oshiCardId)) {
            Map<String, Object> hbp01006 = applyHbp01006OshiSelfDownedReturnStack(
                matchId,
                defenderUserId,
                attackerUserId,
                downedTarget,
                holderSnapshot,
                artSummary
            );
            if (!hbp01006.isEmpty()) {
                executed.add(hbp01006);
            }
        }
        if (executed.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "OSHI_REACTIVE_SELF_DOWNED_EFFECTS");
        summary.put("oshiCardId", oshiCardId);
        summary.put("executedEffects", executed);
        summary.put("applied", true);
        return summary;
    }

    private Map<String, Object> applyHbp01004OshiSelfDownedReattach(
        Long matchId,
        Long defenderUserId,
        Long attackerUserId,
        Map<String, Object> holderSnapshot
    ) {
        if (Objects.equals(defenderUserId, attackerUserId) || !canUseOshiSkill(matchId, defenderUserId, "NORMAL", 2)) {
            return Map.of();
        }
        Long downedHolomemId = asLong(holderSnapshot.get("holomem_id"));
        Long targetHolomemId = loadFirstOwnOtherHolomemId(matchId, defenderUserId, downedHolomemId);
        if (targetHolomemId == null) {
            return Map.of();
        }
        List<Long> movableGreenCheerIds = filterCheerCardInstanceIdsByColor(
            matchId,
            defenderUserId,
            toLongList(holderSnapshot.get("attached_cheer_card_instance_ids")),
            "GREEN"
        );
        if (movableGreenCheerIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> holopowerPayment = consumeHolopowerCostToArchive(matchId, defenderUserId, 2);
        markOshiSkillUsed(matchId, defenderUserId, "NORMAL");
        List<String> movedCheerCardIds = new ArrayList<>();
        List<Long> movedCheerRowIds = new ArrayList<>();
        for (Long cheerCardInstanceId : movableGreenCheerIds) {
            String cheerCardId = moveCheerCardInstanceToHolomem(matchId, defenderUserId, cheerCardInstanceId, targetHolomemId);
            if (StringUtils.hasText(cheerCardId)) {
                movedCheerCardIds.add(cheerCardId);
                Long rowId = loadAttachedCheerRowId(targetHolomemId, cheerCardInstanceId);
                if (rowId != null) {
                    movedCheerRowIds.add(rowId);
                }
            }
        }
        if (movedCheerCardIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> reattach = new LinkedHashMap<>();
        reattach.put("effectType", "REATTACH");
        reattach.put("moveRequested", movableGreenCheerIds.size());
        reattach.put("moveApplied", movedCheerCardIds.size());
        reattach.put("targetHolomemId", targetHolomemId);
        reattach.put("movedCheerCardIds", movedCheerCardIds);
        reattach.put("movedCheerRowIds", movedCheerRowIds);
        reattach.put("sourceMode", "DOWNED_GREEN_CHEER");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "OSHI_SKILL_TRIGGER");
        summary.put("oshiCardId", "HBP01-004");
        summary.put("skillType", "NORMAL");
        summary.put("skillName", "野兎たち～");
        summary.put("triggerType", "SELF_DOWNED");
        summary.put("holopowerCost", 2);
        summary.put("holopowerPayment", holopowerPayment);
        summary.put("executedEffects", List.of(reattach));
        summary.put("applied", true);
        return summary;
    }

    private Map<String, Object> applyHbp01006OshiSelfDownedReturnStack(
        Long matchId,
        Long defenderUserId,
        Long attackerUserId,
        TargetHolomem downedTarget,
        Map<String, Object> holderSnapshot,
        Map<String, Object> artSummary
    ) {
        if (
            Objects.equals(defenderUserId, attackerUserId)
                || downedTarget == null
                || !"RED".equals(normalizeZone(downedTarget.mainColor()))
                || !canUseOshiSkill(matchId, defenderUserId, "SP", 2)
        ) {
            return Map.of();
        }
        List<Long> stackCardInstanceIds = toLongList(holderSnapshot.get("stack_card_instance_ids"));
        if (stackCardInstanceIds.isEmpty()) {
            Long matchCardInstanceId = asLong(holderSnapshot.get("match_card_id"));
            if (matchCardInstanceId != null && matchCardInstanceId > 0) {
                stackCardInstanceIds = List.of(matchCardInstanceId);
            }
        }
        if (stackCardInstanceIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> holopowerPayment = consumeHolopowerCostToArchive(matchId, defenderUserId, 2);
        markOshiSkillUsed(matchId, defenderUserId, "SP");
        Long restoredLifeCardInstanceId = restoreLostLifeFromArtSummary(matchId, defenderUserId, artSummary);
        List<Long> returnedStackCardInstanceIds = moveArchivedCardsToHand(matchId, defenderUserId, stackCardInstanceIds);

        Map<String, Object> returnToHand = new LinkedHashMap<>();
        returnToHand.put("effectType", "RETURN_TO_HAND");
        returnToHand.put("moveRequested", stackCardInstanceIds.size());
        returnToHand.put("moveApplied", returnedStackCardInstanceIds.size());
        returnToHand.put("movedCardInstanceIds", returnedStackCardInstanceIds);
        returnToHand.put("sourceMode", "DOWNED_HOLOMEM_STACK");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "OSHI_SKILL_TRIGGER");
        summary.put("oshiCardId", "HBP01-006");
        summary.put("skillType", "SP");
        summary.put("skillName", "Rise from the ashes");
        summary.put("triggerType", "SELF_DOWNED");
        summary.put("holopowerCost", 2);
        summary.put("holopowerPayment", holopowerPayment);
        summary.put("restoredLifeCardInstanceId", restoredLifeCardInstanceId);
        summary.put("lifeLossModifier", restoredLifeCardInstanceId == null ? 0 : -1);
        summary.put("executedEffects", List.of(returnToHand));
        summary.put("applied", true);
        return summary;
    }

    private String loadPlayerOshiCardId(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT oshi_card_id
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("oshi_card_id") : null,
            matchId,
            userId
        );
    }

    private boolean canUseOshiSkill(Long matchId, Long userId, String skillType, int holopowerCost) {
        if (matchId == null || userId == null || !StringUtils.hasText(skillType)) {
            return false;
        }
        Map<String, Object> row = jdbcTemplate.query(
            """
            SELECT skill_used_this_turn,
                   sp_skill_used
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("skill_used_this_turn", rs.getBoolean("skill_used_this_turn"));
                result.put("sp_skill_used", rs.getBoolean("sp_skill_used"));
                return result;
            },
            matchId,
            userId
        );
        if (row == null) {
            return false;
        }
        if (toBoolean(row.get("skill_used_this_turn"))) {
            return false;
        }
        if ("SP".equals(normalizeZone(skillType)) && toBoolean(row.get("sp_skill_used"))) {
            return false;
        }
        Integer holopowerCount = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HOLOPOWER'
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId
        );
        return holopowerCount != null && holopowerCount >= Math.max(holopowerCost, 0);
    }

    private void markOshiSkillUsed(Long matchId, Long userId, String skillType) {
        if (matchId == null || userId == null) {
            return;
        }
        boolean sp = "SP".equals(normalizeZone(skillType));
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET skill_used_this_turn = TRUE,
                sp_skill_used = CASE WHEN ? THEN TRUE ELSE sp_skill_used END,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            sp,
            matchId,
            userId
        );
    }

    private Long loadFirstOpponentHolomemCardInstanceId(Long matchId, Long opponentUserId) {
        if (matchId == null || opponentUserId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB', 'BACK')
            ORDER BY CASE zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            opponentUserId
        );
    }

    private Long loadFirstOwnOtherHolomemId(Long matchId, Long ownerUserId, Long excludedHolomemId) {
        if (matchId == null || ownerUserId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB', 'BACK')
              AND (? IS NULL OR id <> ?)
            ORDER BY CASE zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            excludedHolomemId,
            excludedHolomemId
        );
    }

    private List<Long> filterCheerCardInstanceIdsByColor(
        Long matchId,
        Long ownerUserId,
        List<Long> cheerCardInstanceIds,
        String color
    ) {
        if (matchId == null || ownerUserId == null || cheerCardInstanceIds == null || cheerCardInstanceIds.isEmpty()) {
            return List.of();
        }
        String normalizedColor = normalizeZone(color);
        List<Long> matched = new ArrayList<>();
        for (Long cheerCardInstanceId : cheerCardInstanceIds) {
            if (cheerCardInstanceId == null || cheerCardInstanceId <= 0) {
                continue;
            }
            String cardColor = jdbcTemplate.query(
                """
                SELECT cc.color
                FROM match_cards mc
                JOIN cheer_cards cc ON cc.card_id = mc.card_id
                WHERE mc.id = ?
                  AND mc.match_id = ?
                  AND mc.owner_user_id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("color") : null,
                cheerCardInstanceId,
                matchId,
                ownerUserId
            );
            if (normalizedColor.equals(normalizeZone(cardColor))) {
                matched.add(cheerCardInstanceId);
            }
        }
        return matched;
    }

    private String moveCheerCardInstanceToHolomem(
        Long matchId,
        Long ownerUserId,
        Long cheerCardInstanceId,
        Long targetHolomemId
    ) {
        if (matchId == null || ownerUserId == null || cheerCardInstanceId == null || targetHolomemId == null) {
            return null;
        }
        String cheerCardId = jdbcTemplate.query(
            """
            SELECT card_id
            FROM match_cards
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone IN ('ARCHIVE', 'STAGE')
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("card_id") : null,
            cheerCardInstanceId,
            matchId,
            ownerUserId
        );
        if (!StringUtils.hasText(cheerCardId)) {
            return null;
        }
        jdbcTemplate.update(
            """
            DELETE FROM match_holomem_cheers c
            USING match_holomems h
            WHERE c.match_holomem_id = h.id
              AND c.match_card_id = ?
              AND h.match_id = ?
              AND h.owner_user_id = ?
            """,
            cheerCardInstanceId,
            matchId,
            ownerUserId
        );
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
              AND zone IN ('ARCHIVE', 'STAGE')
            """,
            cheerCardInstanceId,
            matchId,
            ownerUserId
        );
        if (moved != 1) {
            return null;
        }
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
            VALUES (?, ?, ?, FALSE)
            """,
            targetHolomemId,
            cheerCardInstanceId,
            cheerCardId
        );
        return cheerCardId;
    }

    private Long loadAttachedCheerRowId(Long targetHolomemId, Long cheerCardInstanceId) {
        if (targetHolomemId == null || cheerCardInstanceId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomem_cheers
            WHERE match_holomem_id = ?
              AND match_card_id = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            targetHolomemId,
            cheerCardInstanceId
        );
    }

    private Long restoreLostLifeFromArtSummary(Long matchId, Long ownerUserId, Map<String, Object> artSummary) {
        if (matchId == null || ownerUserId == null || artSummary == null || artSummary.isEmpty()) {
            return null;
        }
        Long lostLifeCardInstanceId = asLong(artSummary.get("lostLifeCardInstanceId"));
        if (lostLifeCardInstanceId == null || lostLifeCardInstanceId <= 0) {
            List<Long> ids = toLongList(artSummary.get("lostLifeCardInstanceIds"));
            if (!ids.isEmpty()) {
                lostLifeCardInstanceId = ids.get(0);
            }
        }
        if (lostLifeCardInstanceId == null || lostLifeCardInstanceId <= 0) {
            return null;
        }
        Integer nextLifeOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MIN(order_index), 1) - 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'LIFE'
            """,
            Integer.class,
            matchId,
            ownerUserId
        );
        int restored = jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'LIFE',
                order_index = ?,
                is_face_down = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'ARCHIVE'
            """,
            nextLifeOrder == null ? 0 : nextLifeOrder,
            lostLifeCardInstanceId,
            matchId,
            ownerUserId
        );
        if (restored != 1) {
            return null;
        }
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = COALESCE(current_life, 0) + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            ownerUserId
        );
        artSummary.put("lifeReduced", false);
        artSummary.put("lostLifeCardInstanceId", null);
        artSummary.put("lostLifeCardInstanceIds", List.of());
        return lostLifeCardInstanceId;
    }

    private List<Long> moveArchivedCardsToHand(Long matchId, Long ownerUserId, List<Long> cardInstanceIds) {
        if (matchId == null || ownerUserId == null || cardInstanceIds == null || cardInstanceIds.isEmpty()) {
            return List.of();
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
            ownerUserId
        );
        int order = nextHandOrder == null ? 1 : nextHandOrder;
        List<Long> moved = new ArrayList<>();
        for (Long cardInstanceId : cardInstanceIds) {
            if (cardInstanceId == null || cardInstanceId <= 0) {
                continue;
            }
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
                  AND zone = 'ARCHIVE'
                """,
                order++,
                cardInstanceId,
                matchId,
                ownerUserId
            );
            if (updated == 1) {
                moved.add(cardInstanceId);
            }
        }
        return moved;
    }

    /**
     * 解析並執行 `ホロックスロット` 的公開流程。
     *
     * <p>官方文案是「公開したホロメン1枚につき、このアーツ+20。そして公開したカードをアーカイブする」。
     * 這裡採最小可行實作：固定公開牌庫頂 3 張、逐張送 archive，並回傳本次可用於後續 Gift 的公開結果。
     */
    private HoloxSlotRevealSummary resolveHoloxSlotRevealSummary(
        Long matchId,
        Long userId,
        String artName,
        String artEffectJsonText
    ) {
        if (matchId == null || userId == null || !StringUtils.hasText(artName)) {
            return HoloxSlotRevealSummary.empty();
        }
        if (!artName.contains("ホロックスロット")) {
            return HoloxSlotRevealSummary.empty();
        }
        String normalizedEffect = normalizeZone(artEffectJsonText);
        if (!normalizedEffect.contains("デッキの上から3枚を公開") || !normalizedEffect.contains("公開したカードをアーカイブ")) {
            return HoloxSlotRevealSummary.empty();
        }

        List<Map<String, Object>> revealRows = jdbcTemplate.queryForList(
            """
            SELECT mc.id,
                   mc.card_id,
                   c.card_type,
                   m.bloom_level
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'DECK'
            ORDER BY mc.order_index NULLS LAST, mc.id
            LIMIT 3
            """,
            matchId,
            userId
        );
        if (revealRows.isEmpty()) {
            return HoloxSlotRevealSummary.empty();
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

        List<Long> revealedCardInstanceIds = new ArrayList<>();
        List<String> revealedCardIds = new ArrayList<>();
        List<Long> archivedCardInstanceIds = new ArrayList<>();
        List<String> archivedCardIds = new ArrayList<>();
        List<Long> archivedSupportCardInstanceIds = new ArrayList<>();
        List<String> archivedSupportCardIds = new ArrayList<>();
        int revealedHolomemCount = 0;
        List<Integer> revealedHolomemBloomLevels = new ArrayList<>();

        for (Map<String, Object> row : revealRows) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asString(row.get("card_id"));
            String cardType = normalizeZone(row.get("card_type"));
            Integer bloomLevel = asInt(row.get("bloom_level"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            revealedCardInstanceIds.add(cardInstanceId);
            revealedCardIds.add(cardId);
            if ("MEMBER".equals(cardType)) {
                revealedHolomemCount++;
                if (bloomLevel != null) {
                    revealedHolomemBloomLevels.add(bloomLevel);
                }
            }
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
                  AND zone = 'DECK'
                """,
                nextArchiveOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                continue;
            }
            archivedCardInstanceIds.add(cardInstanceId);
            archivedCardIds.add(cardId);
            if ("SUPPORT".equals(cardType)) {
                archivedSupportCardInstanceIds.add(cardInstanceId);
                archivedSupportCardIds.add(cardId);
            }
        }

        int artBonus = revealedHolomemCount * 20;
        boolean revealedAllMembersSameBloomLevel = revealedCardInstanceIds.size() == 3
            && revealedHolomemCount == 3
            && revealedHolomemBloomLevels.size() == 3
            && revealedHolomemBloomLevels.stream().distinct().count() == 1;
        Integer sharedBloomLevel = revealedAllMembersSameBloomLevel ? revealedHolomemBloomLevels.get(0) : null;
        return new HoloxSlotRevealSummary(
            !archivedCardInstanceIds.isEmpty(),
            revealedCardInstanceIds,
            revealedCardIds,
            revealedHolomemCount,
            artBonus,
            archivedCardInstanceIds,
            archivedCardIds,
            archivedSupportCardInstanceIds,
            archivedSupportCardIds,
            revealedAllMembersSameBloomLevel,
            sharedBloomLevel
        );
    }

    private Map<String, Object> applyHbp02039HoloxSupportRecovery(
        Long matchId,
        Long userId,
        String attackerCardId,
        String artName,
        HoloxSlotRevealSummary holoxSlotRevealSummary
    ) {
        if (matchId == null || userId == null || !StringUtils.hasText(attackerCardId) || !StringUtils.hasText(artName)) {
            return Map.of();
        }
        if (!"HBP02-039".equals(attackerCardId) || !artName.contains("ホロックスロット") || holoxSlotRevealSummary == null) {
            return Map.of();
        }
        List<Long> candidates = holoxSlotRevealSummary.archivedSupportCardInstanceIds();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "HBP02039_SUPPORT_RECOVERY");
        summary.put("candidateCardInstanceIds", candidates);
        if (candidates == null || candidates.isEmpty()) {
            summary.put("applied", false);
            summary.put("reason", "本次公開沒有支援卡可回手");
            return summary;
        }
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
            userId
        );
        for (Long cardInstanceId : candidates) {
            if (cardInstanceId == null || cardInstanceId <= 0) {
                continue;
            }
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
                  AND zone = 'ARCHIVE'
                """,
                nextHandOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                continue;
            }
            summary.put("applied", true);
            summary.put("movedCardInstanceId", cardInstanceId);
            summary.put(
                "movedCardId",
                jdbcTemplate.query("SELECT card_id FROM match_cards WHERE id = ?", rs -> rs.next() ? rs.getString("card_id") : null, cardInstanceId)
            );
            return summary;
        }
        summary.put("applied", false);
        summary.put("reason", "找不到可從 Archive 回手的支援卡");
        return summary;
    }

    private Map<String, Object> applyHbp02040HoloxLifeLoss(
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber,
        Long attackerHolomemId,
        String attackerCardId,
        String artName,
        HoloxSlotRevealSummary holoxSlotRevealSummary
    ) {
        if (matchId == null
            || userId == null
            || opponentUserId == null
            || turnNumber <= 0
            || attackerHolomemId == null
            || attackerHolomemId <= 0
            || !StringUtils.hasText(attackerCardId)
            || !StringUtils.hasText(artName)) {
            return Map.of();
        }
        if (!"HBP02-040".equals(attackerCardId) || !artName.contains("ホロックスロット")) {
            return Map.of();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", "HBP02040_LIFE_LOSS");
        summary.put("holderHolomemId", attackerHolomemId);
        summary.put("turnNumber", turnNumber);
        summary.put("turnOnce", true);
        if (isHbp02040LifeLossAlreadyUsedThisTurn(matchId, userId, turnNumber, attackerHolomemId)) {
            summary.put("applied", false);
            summary.put("reason", "TURN_ONCE_ALREADY_USED");
            return summary;
        }
        if (holoxSlotRevealSummary == null || !holoxSlotRevealSummary.revealApplied()) {
            summary.put("applied", false);
            summary.put("reason", "NO_HOLOX_REVEAL");
            return summary;
        }
        if (!holoxSlotRevealSummary.revealedAllMembersSameBloomLevel()) {
            summary.put("applied", false);
            summary.put("reason", "REVEALED_CARDS_NOT_SAME_BLOOM_LEVEL_HOLOMEM");
            return summary;
        }

        Long lostLifeCardInstanceId = loseLifeOnce(matchId, opponentUserId);
        if (lostLifeCardInstanceId == null) {
            summary.put("applied", false);
            summary.put("reason", "NO_OPPONENT_LIFE_AVAILABLE");
            return summary;
        }
        summary.put("applied", true);
        summary.put("lifeReduced", true);
        summary.put("lostLifeCardInstanceId", lostLifeCardInstanceId);
        summary.put("lostLifeCardInstanceIds", List.of(lostLifeCardInstanceId));
        summary.put("requestedLifeLoss", 1);
        summary.put("appliedLifeLoss", 1);
        return summary;
    }

    private boolean isHbp02040LifeLossAlreadyUsedThisTurn(
        Long matchId,
        Long userId,
        int turnNumber,
        Long holderHolomemId
    ) {
        if (matchId == null || userId == null || turnNumber <= 0 || holderHolomemId == null || holderHolomemId <= 0) {
            return false;
        }
        Integer usedCount = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'ATTACK_ART'
              AND payload -> 'hbp02040LifeLoss' ->> 'holderHolomemId' = ?
              AND payload -> 'hbp02040LifeLoss' ->> 'applied' = 'true'
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            turnNumber,
            holderHolomemId.toString()
        );
        return usedCount != null && usedCount > 0;
    }

    /**
     * 解析 DAMAGE_REDIRECT 類行動鎖效果，必要時回傳改向目標並消耗該效果。
     */
    private DamageRedirectTarget resolveDamageRedirectTarget(Long matchId, Long affectedUserId, int currentTurn) {
        if (matchId == null || affectedUserId == null || currentTurn <= 0) {
            return null;
        }
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList(
            """
            SELECT id, payload::text AS payload_text
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ACTION_LOCK'
              AND expires_turn >= ?
            ORDER BY id DESC
            """,
            matchId,
            affectedUserId,
            currentTurn
        );
        for (Map<String, Object> row : candidates) {
            Long effectId = asLong(row.get("id"));
            String payloadText = asString(row.get("payload_text"));
            JsonNode payload = parseJson(payloadText);
            if (!matchesLockAction(payload, "DAMAGE_REDIRECT")) {
                continue;
            }
            Long targetHolomemId = extractJsonLong(payload, "targetHolomemId");
            if (targetHolomemId == null || targetHolomemId <= 0) {
                continue;
            }
            TargetHolomem redirectTarget = loadTargetHolomemById(matchId, affectedUserId, targetHolomemId);
            if (redirectTarget == null) {
                continue;
            }
            if (effectId != null && effectId > 0) {
                jdbcTemplate.update(
                    "DELETE FROM match_turn_effects WHERE id = ? AND match_id = ?",
                    effectId,
                    matchId
                );
            }
            return new DamageRedirectTarget(effectId, redirectTarget);
        }
        return null;
    }

    /**
     * 依 holomemId 載入可用目標資訊（match_card_id 與主色）。
     */
    private TargetHolomem loadTargetHolomemById(Long matchId, Long ownerUserId, Long holomemId) {
        if (matchId == null || ownerUserId == null || holomemId == null || holomemId <= 0) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT h.id, h.match_card_id, h.card_id, h.zone, m.main_color
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> rs.next()
                ? new TargetHolomem(
                    rs.getLong("id"),
                    rs.getLong("match_card_id"),
                    rs.getString("card_id"),
                    normalizeZone(rs.getString("zone")),
                    normalizeZone(rs.getString("main_color"))
                )
                : null,
            matchId,
            ownerUserId,
            holomemId
        );
    }

    /**
     * 計算本回合對攻擊方生效的傷害加成（非受傷減免類）。
     *
     * <p>這裡要同時支援兩種回合效果：
     *
     * <p>- 玩家層級 buff：例如「這回合自己所有 Holomem 的藝能 +20」
     * <p>- 單體 buff：例如 `HBP06-084` 的「〈博衣こより〉1人のアーツ+20」
     *
     * <p>因此 SQL 不能只看 `affected_user_id`。若 payload 已記錄 `targetHolomemId`，就必須只在
     * 「本次出招的攻擊者就是那張 Holomem」時才套用。否則會把單體加成誤放大成整個玩家都吃到。
     */
    private int resolveTurnArtDamageModifier(Long matchId, Long userId, int currentTurn, Long attackerHolomemId) {
        if (matchId == null || userId == null || currentTurn <= 0) {
            return 0;
        }
        String attackerHolomemIdText = attackerHolomemId == null ? "" : attackerHolomemId.toString();
        Integer modifier = jdbcTemplate.query(
            """
            SELECT COALESCE(SUM(modifier_value), 0) AS total
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'DAMAGE_MODIFIER'
              AND expires_turn >= ?
              AND COALESCE(payload ->> 'rawText', '') NOT LIKE '%受けるダメージ%'
              AND COALESCE(payload ->> 'rawText', '') NOT LIKE '%ダメージを受ける%'
              AND (
                COALESCE(payload ->> 'targetHolomemId', '') = ''
                OR (payload ->> 'targetHolomemId') = ?
              )
            """,
            rs -> rs.next() ? rs.getInt("total") : 0,
            matchId,
            userId,
            currentTurn,
            attackerHolomemIdText
        );
        return modifier == null ? 0 : modifier;
    }

    /**
     * 計算本回合對受擊方生效的受傷減免總和。
     */
    private int resolveIncomingDamageReduction(Long matchId, Long targetUserId, int currentTurn) {
        if (matchId == null || targetUserId == null || currentTurn <= 0) {
            return 0;
        }
        Integer reduction = jdbcTemplate.query(
            """
            SELECT COALESCE(SUM(ABS(modifier_value)), 0) AS total
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'DAMAGE_MODIFIER'
              AND expires_turn >= ?
              AND (
                COALESCE(payload ->> 'rawText', '') LIKE '%受けるダメージ%'
                OR COALESCE(payload ->> 'rawText', '') LIKE '%ダメージを受ける%'
              )
            """,
            rs -> rs.next() ? rs.getInt("total") : 0,
            matchId,
            targetUserId,
            currentTurn
        );
        return reduction == null ? 0 : reduction;
    }

    /**
     * 結束目前回合並交棒給對手。
     * 會先驗證必要回合動作（抽牌、吶喊）是否完成，再重置狀態與建立對手 TURN_START 互動。
     */
    @Transactional
    public void endTurn(Long matchId, Long userId) {
        ActionContext context = loadActionContext(
            matchId,
            userId,
            Set.of(MatchPhase.END)
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
        int clearedEffectCount = matchTurnEffectMaintenanceService.clearExpiredTurnEffects(matchId, context.turnNumber);
        int resetRestedCount = matchTurnLifecycleService.resetRestedHolomemsForTurnStart(
            matchId,
            context.opponentUserId,
            context.turnNumber
        );
        Map<String, Object> centerReplenishSummary = matchTurnLifecycleService.resolveEndTurnCenterReplenishCycle(
            matchId,
            userId
        );
        matchTurnLifecycleService.completeEndTurn(
            context.match,
            userId,
            context.opponentUserId,
            context.turnNumber,
            clearedEffectCount,
            resetRestedCount,
            centerReplenishSummary
        );
    }

    /**
     * 投降並立即結束對戰。
     * 以當前玩家為敗北方，reason 為 CONCEDE。
     */
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

    /**
     * 載入行為執行上下文（預設不允許有 pending decision 阻擋）。
     */
    private ActionContext loadActionContext(Long matchId, Long userId, Set<MatchPhase> allowedPhases) {
        return loadActionContext(matchId, userId, allowedPhases, false);
    }

    /**
     * 載入並驗證操作上下文：對戰狀態、回合歸屬、phase 合法性、pending 阻擋。
     */
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
        boolean isCurrentTurnPlayer = match.getCurrentTurnPlayerId() != null && match.getCurrentTurnPlayerId().equals(userId);
        if (!isCurrentTurnPlayer && (!allowPendingDecision || !hasBlockingPendingDecision(matchId, userId))) {
            throw new GameRuleException(GameErrorCode.NOT_YOUR_TURN, "現在不是你的回合");
        }

        MatchPhase phase = parsePhase(match.getCurrentPhase());
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
            throw new GameRuleException(GameErrorCode.PENDING_INTERACTION_BLOCKED, "你有待處理的互動，請先完成確認");
        }
        if (!allowPendingDecision && hasAnyPendingDecision(matchId)) {
            throw new GameRuleException(GameErrorCode.PENDING_INTERACTION_BLOCKED, "對戰中有待處理的互動，請先完成確認");
        }

        return new ActionContext(match, phase, turnNumber, opponentUserId, false);
    }

    /**
     * 推導下一位需放置開場 CENTER 的玩家（A 優先、再 B）。
     */
    private Long resolveNextOpeningCenterUser(MatchEntity match) {
        if (match == null) {
            return null;
        }
        Long a = match.getPlayerAId();
        Long b = match.getPlayerBId();
        if (a != null && !hasOpeningCenterPlaced(match.getId(), a)) {
            return a;
        }
        if (b != null && !hasOpeningCenterPlaced(match.getId(), b)) {
            return b;
        }
        return null;
    }

    /**
     * 判斷玩家是否已放置開場 CENTER Holomem。
     */
    private boolean hasOpeningCenterPlaced(Long matchId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
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
        return count != null && count > 0;
    }

    /**
     * 判斷本回合是否已執行過抽牌動作。
     */
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

    /**
     * 判斷本回合是否已執行 TURN_CHEER。
     */
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

    /**
     * SEND_CHEER 決策結束後的 phase 推導。
     * 回合 Cheer 完成後回到 MAIN；其餘來源維持原 phase。
     */
    private MatchPhase resolvePhaseAfterSendCheer(MatchPhase currentPhase, String sourceActionType) {
        if (ACTION_TYPE_TURN_CHEER.equals(normalizeZone(sourceActionType))) {
            return MatchPhase.MAIN;
        }
        return currentPhase == null ? MatchPhase.MAIN : currentPhase;
    }

    /**
     * 判斷是否為先攻玩家的第一回合。
     */
    private boolean isFirstPlayerFirstTurn(MatchEntity match, Long userId, int turnNumber) {
        if (match == null || userId == null) {
            return false;
        }
        return turnNumber == 1 && userId.equals(match.getPlayerAId());
    }

    /**
     * 判斷是否具備執行回合 Cheer 的必要條件（牌庫有 Cheer 且場上有 Holomem）。
     */
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

    /**
     * 判斷本回合是否已使用過 COLLAB。
     */
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

    /**
     * 判斷本回合是否已使用過バトンタッチ。
     */
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

    /**
     * 查詢額外 Bloom 許可效果 id（同回合二次 Bloom 例外）。
     */
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

    /**
     * 消耗一筆額外 Bloom 許可效果。
     */
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

    /**
     * 將指定牌庫卡移到牌庫底部。
     */
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

    /**
     * 驗證牌庫底排序提交內容（數量、唯一性、候選一致性）。
     */
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

    /**
     * 將牌庫頂卡移到 HOLOPOWER。
     */
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

    /**
     * 載入玩家可用的 Oshi 技能資料（NORMAL / SP）。
     */
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

    /**
     * 從 effect json 解析主 effectType。
     */
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

    /**
     * 從 effect json 解析 targetType。
     */
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

    /**
     * 支付並歸檔 Holopower 成本。
     */
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

    /**
     * 將 COLLAB Holomem 退回 BACK 並設為休息。
     */
    private void returnCollabToBackAsRested(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return;
        }
        List<Map<String, Object>> collabRows = jdbcTemplate.queryForList(
            """
            SELECT id, card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'COLLAB'
            """,
            matchId,
            userId
        );
        if (collabRows.isEmpty()) {
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
        boolean shouldKeepHbp03039Unrested = isOwnCenterHolomemNameContains(matchId, userId, "フワワ・アビスガード");
        if (!shouldKeepHbp03039Unrested) {
            return;
        }
        List<Long> movedCollabIds = collabRows.stream()
            .map(row -> asLong(row.get("id")))
            .filter(Objects::nonNull)
            .toList();
        if (movedCollabIds.isEmpty()) {
            return;
        }
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
              AND card_id = 'HBP03-039'
              AND id = ANY (?::bigint[])
            """,
            ps -> {
                ps.setLong(1, matchId);
                ps.setLong(2, userId);
                ps.setArray(3, ps.getConnection().createArrayOf("bigint", movedCollabIds.toArray()));
            }
        );
    }

    private boolean isOwnCenterHolomemNameContains(Long matchId, Long userId, String requiredNamePart) {
        if (matchId == null || userId == null || !StringUtils.hasText(requiredNamePart)) {
            return false;
        }
        String centerName = jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'CENTER'
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("name") : null,
            matchId,
            userId
        );
        return StringUtils.hasText(centerName) && centerName.contains(requiredNamePart);
    }

    /**
     * 載入指定卡片實例（需為該玩家持有）。
     */
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

    /**
     * 推導下一位應執行 mulligan 的玩家。
     */
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

    /**
     * 判斷指定玩家是否已完成 mulligan。
     */
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

    /**
     * 將手牌洗回牌庫並重抽指定張數。
     */
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

    /**
     * 從牌庫頂抽 1 張到手牌。
     */
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
        EffectContext effectContext = EffectContext.system(matchId, userId, ACTION_TYPE_DRAW_TURN);
        MoveZoneAction moveZoneAction = new MoveZoneAction(
            deckCardInstanceId,
            userId,
            "DECK",
            "HAND",
            nextHandOrder == null ? 1 : nextHandOrder,
            false
        );
        List<ActionResult> results = gameActionExecutor.execute(effectContext, List.of(moveZoneAction));
        if (results.isEmpty() || !results.get(0).success()) {
            return null;
        }
        return deckCardInstanceId;
    }

    /**
     * 計算指定 zone 卡片數量。
     */
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

    /**
     * 強制開場 Debut 規則：
     * 若手牌無 Debut，則遞減重抽直到有 Debut 或手牌降到 1。
     */
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

    /**
     * 判斷手牌是否含有 Debut Holomem。
     */
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

    /**
     * 以敗北方式結束對戰，並統一寫入 MATCH_FINISHED 與 RULE_EVENT。
     */
    private void finishMatchByDefeat(MatchEntity match, Long loserUserId, String reason, int turnNumber) {
        Long winnerUserId = resolveOpponent(match, loserUserId);
        String reasonCode = standardizeReasonCode(reason);
        match.setStatus("finished");
        match.setWinnerUserId(winnerUserId);
        match.setFinishedAt(LocalDateTime.now());
        match.setCurrentTurnPlayerId(null);
        match.setCurrentPhase(MatchPhase.END.name());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reasonCode);
        payload.put("reasonCode", reasonCode);
        payload.put("loserUserId", loserUserId);
        payload.put("winnerUserId", winnerUserId);
        appendAction(
            match,
            loserUserId,
            "MATCH_FINISHED",
            toJson(payload),
            turnNumber
        );
        appendRuleEvent(
            match,
            loserUserId,
            turnNumber,
            "MATCH_FINISHED",
            reasonCode,
            Map.of(
                "winnerUserId", winnerUserId,
                "loserUserId", loserUserId,
                "draw", false
            )
        );
    }

    /**
     * 以平手方式結束對戰，並統一寫入 MATCH_FINISHED 與 RULE_EVENT。
     */
    private void finishMatchAsDraw(MatchEntity match, Long actorUserId, String reason, int turnNumber) {
        String reasonCode = standardizeReasonCode(reason);
        match.setStatus("finished");
        match.setWinnerUserId(null);
        match.setFinishedAt(LocalDateTime.now());
        match.setCurrentTurnPlayerId(null);
        match.setCurrentPhase(MatchPhase.END.name());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reasonCode);
        payload.put("reasonCode", reasonCode);
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
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("winnerUserId", null);
        details.put("loserUserId", null);
        details.put("draw", true);
        appendRuleEvent(
            match,
            actorUserId,
            turnNumber,
            "MATCH_FINISHED",
            reasonCode,
            details
        );
    }

    /**
     * 依「場上無 Holomem」規則判斷是否終局。
     */
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

    /**
     * 依「Life 歸零」規則判斷是否終局。
     */
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

    /**
     * 計算場上 Holomem 數量（CENTER/COLLAB/BACK）。
     */
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

    /**
     * 從效果摘要遞迴判斷是否存在 down 事件。
     */
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

    /**
     * 從效果摘要遞迴判斷是否存在 life 減少事件。
     */
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

    /**
     * 合併主效果與附加效果摘要，供後續勝負檢查共用。
     */
    private Map<String, Object> mergeEffectSummaryForChecks(
        Map<String, Object> primary,
        List<Map<String, Object>> additionalEffects
    ) {
        if ((additionalEffects == null || additionalEffects.isEmpty()) && primary != null) {
            return primary;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        List<Object> executed = new ArrayList<>();
        if (primary != null) {
            executed.add(primary);
        }
        if (additionalEffects != null) {
            executed.addAll(additionalEffects);
        }
        merged.put("executedEffects", executed);
        return merged;
    }

    /**
     * 根據 life 減少結果建立補發 Cheer 互動（例如被擊倒失去 life 後）。
     */
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

    /**
     * 從摘要中收集所有 lost life 的卡片實例 id。
     */
    private List<Long> collectLostLifeCardInstanceIds(Object summaryObject) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        collectLostLifeCardInstanceIdsRecursive(summaryObject, ids);
        return new ArrayList<>(ids);
    }

    /**
     * 遞迴版本：支援巢狀 executedEffects / list 結構。
     */
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

    /**
     * 若卡片效果直接定義勝負，於此統一結算入口處理。
     */
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

    /**
     * 從效果摘要中抽出可用的 matchResult 結構（含巢狀搜尋）。
     */
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

    /**
     * 載入 MEMBER 卡規格資訊。
     */
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

    /**
     * 載入 Bloom 目標資料（目前堆疊頂卡與受傷狀態）。
     */
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

    /**
     * 判斷是否為不可 Bloom 的特殊等級。
     */
    private boolean isSpecialOrUnbloomableLevel(String levelType) {
        String normalized = normalizeLevel(levelType);
        return "SPOT".equals(normalized);
    }

    /**
     * 判斷 Bloom 階級是否為合法遞進。
     */
    private boolean isBloomLevelNextStep(String targetLevel, String bloomLevel) {
        int targetRank = resolveBloomLevelRank(targetLevel);
        int bloomRank = resolveBloomLevelRank(bloomLevel);
        if (targetRank < 0 || bloomRank < 0) {
            return false;
        }
        return bloomRank == targetRank + 1;
    }

    /**
     * 檢查目標 Holomem 的被動 Gift 是否允許本次忽略 Bloom 等級限制。
     *
     * <p>目前先支援官方 HBP01-045：
     * `自分のライフが3以下の間、このホロメンは、自分の手札の2nd〈AZKi〉に、Bloomレベルを無視してBloomできる。`
     */
    private boolean canIgnoreBloomLevelByPassiveGift(
        Long matchId,
        Long userId,
        BloomTarget target,
        String bloomLevel,
        String bloomCardName
    ) {
        if (matchId == null || userId == null || target == null) {
            return false;
        }
        String passiveText = jdbcTemplate.query(
            """
            SELECT passive_effect_json::text
            FROM member_cards
            WHERE card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString(1) : null,
            target.topCardId()
        );
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("Bloomレベルを無視してBloomできる")) {
            return false;
        }

        String normalizedText = normalizeDigits(passiveText).toUpperCase(Locale.ROOT);
        if (!normalizedText.contains("自分のライフが3以下")) {
            return false;
        }
        if (!normalizedText.contains("このホロメン")) {
            return false;
        }

        Integer currentLife = jdbcTemplate.query(
            """
            SELECT current_life
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getInt(1) : null,
            matchId,
            userId
        );
        if (currentLife == null || currentLife > 3) {
            return false;
        }

        if (normalizedText.contains("手札の2ND") && !"SECOND".equals(normalizeLevel(bloomLevel))) {
            return false;
        }

        Matcher nameMatcher = Pattern.compile("〈([^〉]+)〉").matcher(normalizedText);
        if (nameMatcher.find()) {
            String requiredName = nameMatcher.group(1);
            if (!StringUtils.hasText(bloomCardName) || !bloomCardName.toUpperCase(Locale.ROOT).contains(requiredName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 將等級文字映射成可比較序位。
     */
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

    /**
     * 記錄 Holomem 疊牌關聯（match_holomem_stack_cards）。
     */
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

    /**
     * 計算 Holomem 堆疊深度。
     */
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

    /**
     * 解析對手 userId。
     */
    private Long resolveOpponent(MatchEntity match, Long userId) {
        if (match.getPlayerAId() != null && !match.getPlayerAId().equals(userId)) {
            return match.getPlayerAId();
        }
        if (match.getPlayerBId() != null && !match.getPlayerBId().equals(userId)) {
            return match.getPlayerBId();
        }
        throw new IllegalStateException("找不到對手玩家");
    }

    /**
     * 解析 phase 字串成 enum。
     */
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

    /**
     * 驗證 id 必須為正數。
     */
    private Long requirePositiveId(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " 不可為空");
        }
        return value;
    }

    /**
     * 字串正規化（trim + uppercase）。
     */
    private String normalizeZone(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 等級文字正規化。
     */
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

    /**
     * 安全轉字串。
     */
    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 安全轉 Long。
     */
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

    /**
     * 安全轉 int。
     */
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

    /**
     * 安全轉 boolean。
     */
    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * 判斷是否存在會阻擋操作的 pending 決策。
     */
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

    /**
     * 判斷此對戰是否存在任何 pending 決策。
     */
    private boolean hasAnyPendingDecision(Long matchId) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_pending_decisions
            WHERE match_id = ?
              AND status = ?
            """,
            Integer.class,
            matchId,
            PENDING_STATUS
        );
        return count != null && count > 0;
    }

    /**
     * 判斷是否存在任何 pending 決策。
     */
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

    /**
     * 建立 TURN_START 互動，要求玩家確認回合開始。
     */
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
        context.put("message", "現在是你的回合。請先確認，接著依序進入抽牌與吶喊階段。");
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

    /**
     * 建立 DRAW_REVEAL 互動，用於顯示本回合抽到的牌。
     */
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

    /**
     * 建立回合 Cheer 互動（來源為 Cheer 牌庫頂牌）。
     */
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

    /**
     * 通用 SEND_CHEER 互動建立器，可指定來源 action 與提示文案。
     */
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

    /**
     * 建立通用 CARD_SELECTION 決策，供搜尋/選牌等效果共用。
     */
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

    private Map<String, Object> applyTriggeredEffectAfterConfirm(
        Long matchId,
        Long userId,
        PendingDecision pending,
        int turnNumber,
        List<Long> selectedCardInstanceIds
    ) {
        String normalizedSourceActionType = normalizeZone(pending.sourceActionType());
        if ("BLOOM".equals(normalizedSourceActionType)) {
            return matchTriggeredCardEffectService.applyBloomTriggeredEffects(
                matchId,
                userId,
                pending.sourceCardId(),
                pending.sourceCardInstanceId(),
                extractJsonText(pending.contextNode(), "sourceLevelType")
            );
        }
        if ("COLLAB".equals(normalizedSourceActionType)) {
            return applyCollabPostTriggeredEffectsAfterConfirm(matchId, userId, pending, turnNumber, selectedCardInstanceIds);
        }
        if ("ATTACK_ART_POST_TRIGGER".equals(normalizedSourceActionType)) {
            return applyAttackArtPostTriggeredEffectsAfterConfirm(
                matchId,
                userId,
                pending,
                turnNumber,
                selectedCardInstanceIds
            );
        }
        if (ACTION_TYPE_EFFECT_POST_TRIGGER.equals(normalizedSourceActionType)) {
            return applyEffectPostTriggeredEffectsAfterConfirm(matchId, userId, pending, turnNumber);
        }
        if ("GIFT".equals(normalizedSourceActionType)) {
            return applyGiftTriggeredEffectsAfterConfirm(matchId, userId, pending, turnNumber, selectedCardInstanceIds);
        }
        Map<String, Object> skipped = new LinkedHashMap<>();
        skipped.put("applied", false);
        skipped.put("reason", "UNSUPPORTED_TRIGGER_SOURCE");
        skipped.put("sourceActionType", normalizedSourceActionType);
        return skipped;
    }

    private FollowupInteractionDecision createTriggeredEffectConfirmPendingInteraction(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        String title,
        String message,
        List<Map<String, Object>> cards,
        int turnNumber
    ) {
        return createTriggeredEffectConfirmPendingInteraction(
            matchId,
            userId,
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            effectType,
            title,
            message,
            cards,
            turnNumber,
            null
        );
    }

    private FollowupInteractionDecision createTriggeredEffectConfirmPendingInteraction(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        String title,
        String message,
        List<Map<String, Object>> cards,
        int turnNumber,
        Map<String, Object> additionalContext
    ) {
        if (hasBlockingPendingDecision(matchId, userId)) {
            throw new IllegalStateException("你有待處理的互動，請先完成確認");
        }
        int minSelect = 0;
        int maxSelect = 0;
        if (additionalContext != null && !additionalContext.isEmpty()) {
            minSelect = Math.max(asInt(additionalContext.get("minSelect")), 0);
            maxSelect = Math.max(asInt(additionalContext.get("maxSelect")), minSelect);
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
        context.put("sourceActionType", normalizeZone(sourceActionType));
        context.put("title", title);
        context.put("message", message);
        context.put("cards", cards == null ? List.of() : cards);
        context.put("turnNumber", turnNumber);
        if (additionalContext != null && !additionalContext.isEmpty()) {
            context.putAll(additionalContext);
        }

        Long decisionId = jdbcTemplate.query(
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
            INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM,
            normalizeZone(sourceActionType),
            sourceCardInstanceId,
            sourceCardId,
            effectType,
            minSelect,
            maxSelect,
            PENDING_STATUS,
            toJson(context)
        );
        if (decisionId == null) {
            return null;
        }
        return new FollowupInteractionDecision(decisionId, INTERACTION_TYPE_TRIGGER_EFFECT_CONFIRM);
    }

    /**
     * GIFT 互動確認後執行：依 pending context 逐筆觸發並彙整摘要。
     */
    private Map<String, Object> applyGiftTriggeredEffectsAfterConfirm(
        Long matchId,
        Long userId,
        PendingDecision pending,
        int turnNumber,
        List<Long> selectedCardInstanceIds
    ) {
        List<Map<String, Object>> giftTriggers = extractGiftTriggerContexts(pending.contextNode());
        if (giftTriggers.isEmpty()) {
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("applied", false);
            skipped.put("reason", "NO_GIFT_TRIGGER_CONTEXT");
            skipped.put("sourceActionType", "GIFT");
            return skipped;
        }
        return matchTriggeredGiftResolutionService.applyGiftTriggeredEffectsFromContext(
            matchId,
            userId,
            pending.sourceCardInstanceId(),
            turnNumber,
            giftTriggers,
            "GIFT",
            selectedCardInstanceIds,
            extractJsonLong(pending.contextNode(), "selectionGiftHolderCardInstanceId")
        );
    }

    /**
     * COLLAB 確認後執行：整合 collab effect 與同時觸發的 Gift。
     */
    private Map<String, Object> applyCollabPostTriggeredEffectsAfterConfirm(
        Long matchId,
        Long userId,
        PendingDecision pending,
        int turnNumber,
        List<Long> selectedCardInstanceIds
    ) {
        return matchTriggeredEffectResolutionService.applyCollabPostTriggeredEffectsAfterConfirm(
            matchId,
            userId,
            pending.sourceCardId(),
            pending.sourceCardInstanceId(),
            turnNumber,
            pending.contextNode() != null && pending.contextNode().path("hasCollabEffect").asBoolean(false),
            extractGiftTriggerContexts(pending.contextNode()),
            selectedCardInstanceIds,
            extractJsonLong(pending.contextNode(), "selectionGiftHolderCardInstanceId")
        );
    }

    /**
     * ATTACK_ART 的後續觸發（Gift + Down Event）確認後執行。
     */
    private Map<String, Object> applyAttackArtPostTriggeredEffectsAfterConfirm(
        Long matchId,
        Long userId,
        PendingDecision pending,
        int turnNumber,
        List<Long> selectedCardInstanceIds
    ) {
        return matchTriggeredEffectResolutionService.applyAttackArtPostTriggeredEffectsAfterConfirm(
            matchId,
            userId,
            pending.sourceCardInstanceId(),
            turnNumber,
            extractGiftTriggerContexts(pending.contextNode()),
            selectedCardInstanceIds,
            extractJsonLong(pending.contextNode(), "selectionGiftHolderCardInstanceId"),
            extractDownEventContext(pending.contextNode())
        );
    }

    /**
     * 非攻擊來源（Support / Oshi 等）的後續 down event 確認後執行。
     */
    private Map<String, Object> applyEffectPostTriggeredEffectsAfterConfirm(
        Long matchId,
        Long userId,
        PendingDecision pending,
        int turnNumber
    ) {
        return matchTriggeredEffectResolutionService.applyEffectPostTriggeredEffectsAfterConfirm(
            matchId,
            userId,
            turnNumber,
            extractJsonText(pending.contextNode(), "originSourceActionType"),
            extractDownEventContext(pending.contextNode())
        );
    }

    /**
     * 若本次確認來源為 GIFT，補寫每筆 `GIFT_TRIGGER` action（供 turn once / 追蹤）。
     */
    private void appendGiftTriggerActionsIfPresent(
        MatchEntity match,
        Long userId,
        int turnNumber,
        Map<String, Object> effectSummary
    ) {
        if (effectSummary == null) {
            return;
        }
        String sourceActionType = normalizeZone(effectSummary.get("sourceActionType"));
        Object triggeredGifts = effectSummary.get("triggeredGifts");
        if ("ATTACK_ART_POST_TRIGGER".equals(sourceActionType)) {
            Object nestedGift = effectSummary.get("gift");
            if (nestedGift instanceof Map<?, ?> map) {
                triggeredGifts = castToMap(map).get("triggeredGifts");
            }
        } else if ("COLLAB".equals(sourceActionType)) {
            Object nestedGift = effectSummary.get("gift");
            if (nestedGift instanceof Map<?, ?> map) {
                triggeredGifts = castToMap(map).get("triggeredGifts");
            }
        } else if (!"GIFT".equals(sourceActionType)) {
            return;
        }
        if (!(triggeredGifts instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
                continue;
            }
            appendAction(match, userId, "GIFT_TRIGGER", toJson(castToMap(map)), turnNumber);
        }
    }

    /**
     * 從 pending context 解析 Gift trigger list。
     */
    private List<Map<String, Object>> extractGiftTriggerContexts(JsonNode contextNode) {
        if (contextNode == null || contextNode.isNull()) {
            return List.of();
        }
        JsonNode triggersNode = contextNode.get("giftTriggers");
        if (triggersNode == null || !triggersNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> triggers = new ArrayList<>();
        for (JsonNode node : triggersNode) {
            if (node == null || !node.isObject()) {
                continue;
            }
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("triggerType", extractJsonText(node, "triggerType"));
            trigger.put("sourceCardInstanceId", extractJsonLong(node, "sourceCardInstanceId"));
            trigger.put("triggerTargetCardInstanceId", extractJsonLong(node, "triggerTargetCardInstanceId"));
            trigger.put("giftHolderHolomemId", extractJsonLong(node, "giftHolderHolomemId"));
            trigger.put("giftHolderCardInstanceId", extractJsonLong(node, "giftHolderCardInstanceId"));
            trigger.put("giftHolderCardId", extractJsonText(node, "giftHolderCardId"));
            trigger.put("giftHolderZone", extractJsonText(node, "giftHolderZone"));
            trigger.put(
                "giftHolderAttachedCheerCardInstanceIds",
                extractJsonLongList(node, "giftHolderAttachedCheerCardInstanceIds")
            );
            trigger.put(
                "giftHolderAttachedCheerCardIds",
                extractJsonTextList(node, "giftHolderAttachedCheerCardIds")
            );
            trigger.put(
                "giftHolderStackCardInstanceIds",
                extractJsonLongList(node, "giftHolderStackCardInstanceIds")
            );
            trigger.put(
                "giftHolderStackCardIds",
                extractJsonTextList(node, "giftHolderStackCardIds")
            );
            trigger.put("selectionRequired", extractJsonBoolean(node, "selectionRequired"));
            trigger.put("selectionEffectType", extractJsonText(node, "selectionEffectType"));
            trigger.put("selectionMinSelect", extractJsonLong(node, "selectionMinSelect"));
            trigger.put("selectionMaxSelect", extractJsonLong(node, "selectionMaxSelect"));
            trigger.put(
                "selectionCandidateCardInstanceIds",
                extractJsonLongList(node, "selectionCandidateCardInstanceIds")
            );
            trigger.put("rawText", extractJsonText(node, "rawText"));
            triggers.add(trigger);
        }
        return triggers;
    }

    /**
     * 從 pending context 解析 down event context。
     */
    private Map<String, Object> extractDownEventContext(JsonNode contextNode) {
        if (contextNode == null || contextNode.isNull()) {
            return null;
        }
        JsonNode downEventNode = contextNode.get("downEvent");
        if (downEventNode == null || downEventNode.isNull() || !downEventNode.isObject()) {
            return null;
        }
        Map<String, Object> downEvent = new LinkedHashMap<>();
        downEvent.put("downedOwnerUserId", extractJsonLong(downEventNode, "downedOwnerUserId"));
        downEvent.put("downedCardId", extractJsonText(downEventNode, "downedCardId"));
        downEvent.put("downedStageZone", extractJsonText(downEventNode, "downedStageZone"));
        downEvent.put("turnNumber", asInt(extractJsonLong(downEventNode, "turnNumber")));
        downEvent.put("rawText", extractJsonText(downEventNode, "rawText"));
        downEvent.put("requestedLifeLoss", asInt(extractJsonLong(downEventNode, "requestedLifeLoss")));
        return downEvent;
    }

    private Map<String, Object> buildInteractionSourceCardPayload(
        Long matchId,
        Long userId,
        Long cardInstanceId,
        String fallbackCardId,
        String fallbackZone
    ) {
        Map<String, Object> card = loadCardCandidateForDecision(
            matchId,
            userId,
            userId,
            cardInstanceId,
            fallbackZone,
            fallbackCardId
        );
        if (!card.containsKey("cardInstanceId")) {
            card.put("cardInstanceId", cardInstanceId);
        }
        if (!card.containsKey("cardId")) {
            card.put("cardId", fallbackCardId);
        }
        return card;
    }

    private String buildTriggeredEffectConfirmMessage(
        String sourceActionType,
        MatchEffectService.TriggeredEffectPreview preview
    ) {
        String actionName = "BLOOM".equals(normalizeZone(sourceActionType)) ? "BLOOM" : "連動";
        String rawText = preview == null ? null : preview.rawText();
        List<String> effectTypes = preview == null ? List.of() : preview.effectTypes();
        String effectSummary = effectTypes == null || effectTypes.isEmpty()
            ? "無可解析效果類型"
            : String.join("、", effectTypes);
        if (!StringUtils.hasText(rawText)) {
            return "是否要執行此 " + actionName + " 特殊效果？\n效果類型：" + effectSummary;
        }
        return "是否要執行此 " + actionName + " 特殊效果？\n能力文本：" + rawText + "\n效果類型：" + effectSummary;
    }

    private Map<String, Object> buildTriggeredEffectDeferredSummary(
        String sourceActionType,
        MatchEffectService.TriggeredEffectPreview preview
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        String normalizedSourceActionType = normalizeZone(sourceActionType);
        boolean hasEffect = preview != null && preview.hasEffect();
        if ("COLLAB".equals(normalizedSourceActionType)) {
            summary.put("hasCollabEffect", hasEffect);
        } else {
            summary.put("hasBloomEffect", hasEffect);
        }
        summary.put("deferred", hasEffect);
        summary.put("requestedEffects", preview == null || preview.effectTypes() == null ? List.of() : preview.effectTypes());
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        summary.put("rawText", preview == null ? null : preview.rawText());
        if (preview != null && preview.diceRoll() != null) {
            summary.put("diceRoll", preview.diceRoll());
        }
        return summary;
    }

    /**
     * 從效果摘要萃取可延後確認的 down event 預覽（支援 top-level 與巢狀 executedEffects）。
     */
    private Map<String, Object> extractDownEventPreview(Map<String, Object> artSummary) {
        if (artSummary == null || artSummary.isEmpty()) {
            return null;
        }
        Object downEvent = artSummary.get("downEvent");
        if (downEvent instanceof Map<?, ?> map) {
            Map<String, Object> preview = castToMap(map);
            if (toBoolean(preview.get("triggered")) && toBoolean(preview.get("deferred"))) {
                return preview;
            }
        }
        Object executedEffects = artSummary.get("executedEffects");
        if (!(executedEffects instanceof List<?> list)) {
            return null;
        }
        for (Object effect : list) {
            if (!(effect instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> nested = extractDownEventPreview(castToMap(map));
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /**
     * 建立 ATTACK_ART 後續觸發的 deferred 摘要（Gift + Down Event）。
     */
    private Map<String, Object> buildAttackArtPostTriggerDeferredSummary(
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> giftTriggers = giftTriggeredEffects == null ? List.of() : giftTriggeredEffects;
        List<String> requestedEffects = new ArrayList<>();
        for (Map<String, Object> trigger : giftTriggers) {
            requestedEffects.addAll(toStringList(trigger == null ? null : trigger.get("requestedEffects")));
        }
        if (downEventPreview != null) {
            if (!requestedEffects.contains("DOWN_EVENT")) {
                requestedEffects.add("DOWN_EVENT");
            }
        }
        summary.put("sourceActionType", "ATTACK_ART_POST_TRIGGER");
        summary.put("deferred", !giftTriggers.isEmpty() || downEventPreview != null);
        summary.put("triggeredGifts", giftTriggers);
        summary.put("downEvent", downEventPreview);
        summary.put("triggerSections", buildAttackArtPostTriggerSections(giftTriggeredEffects, downEventPreview));
        summary.put("requestedEffects", requestedEffects);
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        return summary;
    }

    /**
     * 建立 ATTACK_ART 後續觸發確認互動（Gift + Down Event）。
     */
    private FollowupInteractionDecision createAttackArtPostTriggerConfirmPendingInteraction(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> cards,
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview,
        int turnNumber
    ) {
        List<Map<String, Object>> giftTriggers = buildGiftTriggerPayloads(giftTriggeredEffects);

        Map<String, Object> additionalContext = new LinkedHashMap<>();
        additionalContext.put("giftTriggers", giftTriggers);
        additionalContext.put("giftCount", giftTriggers.size());
        appendGiftSelectionPendingContext(additionalContext, giftTriggeredEffects);
        if (downEventPreview != null && !downEventPreview.isEmpty()) {
            Map<String, Object> downEvent = new LinkedHashMap<>();
            downEvent.put("downedOwnerUserId", asLong(downEventPreview.get("downedOwnerUserId")));
            downEvent.put("downedCardId", asString(downEventPreview.get("downedCardId")));
            downEvent.put("downedStageZone", asString(downEventPreview.get("downedStageZone")));
            downEvent.put("turnNumber", asInt(downEventPreview.get("turnNumber")));
            downEvent.put("rawText", asString(downEventPreview.get("rawText")));
            downEvent.put("requestedLifeLoss", asInt(downEventPreview.get("requestedLifeLoss")));
            additionalContext.put("downEvent", downEvent);
        }
        additionalContext.put("triggerSections", buildAttackArtPostTriggerSections(giftTriggeredEffects, downEventPreview));

        return createTriggeredEffectConfirmPendingInteraction(
            matchId,
            userId,
            "ATTACK_ART_POST_TRIGGER",
            sourceCardInstanceId,
            sourceCardId,
            "ATTACK_ART_POST_TRIGGER",
            "確認攻擊後觸發效果",
            buildAttackArtPostTriggerConfirmMessage(giftTriggeredEffects, downEventPreview),
            cards,
            turnNumber,
            additionalContext
        );
    }

    /**
     * 組裝 ATTACK_ART 後續觸發確認訊息。
     */
    private String buildAttackArtPostTriggerConfirmMessage(
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview
    ) {
        List<String> lines = new ArrayList<>();
        if (downEventPreview != null && !downEventPreview.isEmpty()) {
            Integer requestedLifeLoss = asInt(downEventPreview.get("requestedLifeLoss"));
            String downedCardId = asString(downEventPreview.get("downedCardId"));
            String rawText = asString(downEventPreview.get("rawText"));
            StringBuilder line = new StringBuilder("[Down Event]\n");
            line.append("DOWN_EVENT");
            if (StringUtils.hasText(downedCardId)) {
                line.append(" (").append(downedCardId).append(")");
            }
            if (requestedLifeLoss != null && requestedLifeLoss > 0) {
                line.append("：額外失去生命 ").append(requestedLifeLoss);
            }
            if (StringUtils.hasText(rawText)) {
                line.append("\n").append(rawText);
            }
            lines.add(line.toString());
        }
        if (giftTriggeredEffects != null && !giftTriggeredEffects.isEmpty()) {
            lines.add("[Gift]\n" + buildGiftTriggeredEffectDetails(giftTriggeredEffects));
        }
        if (lines.isEmpty()) {
            return "是否要執行攻擊後觸發效果？";
        }
        return "是否要執行攻擊後觸發效果？\n" + String.join("\n\n", lines);
    }

    /**
     * 建立 ATTACK_ART_POST_TRIGGER 互動分區資料（供前端 modal 分塊渲染）。
     */
    private List<Map<String, Object>> buildAttackArtPostTriggerSections(
        List<Map<String, Object>> giftTriggeredEffects,
        Map<String, Object> downEventPreview
    ) {
        List<Map<String, Object>> sections = new ArrayList<>();
        if (downEventPreview != null && !downEventPreview.isEmpty()) {
            Map<String, Object> downSection = new LinkedHashMap<>();
            downSection.put("sectionType", "DOWN_EVENT");
            downSection.put("title", "Down Event");
            downSection.put("requestedLifeLoss", asInt(downEventPreview.get("requestedLifeLoss")));
            downSection.put("downedCardId", asString(downEventPreview.get("downedCardId")));
            downSection.put("rawText", asString(downEventPreview.get("rawText")));
            sections.add(downSection);
        }
        if (giftTriggeredEffects != null && !giftTriggeredEffects.isEmpty()) {
            List<Map<String, Object>> giftItems = new ArrayList<>();
            for (Map<String, Object> trigger : giftTriggeredEffects) {
                if (trigger == null || trigger.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("triggerType", normalizeZone(trigger.get("triggerType")));
                item.put("giftHolderCardId", asString(trigger.get("giftHolderCardId")));
                item.put("rawText", asString(trigger.get("rawText")));
                item.put("requestedEffects", toStringList(trigger.get("requestedEffects")));
                giftItems.add(item);
            }
            if (!giftItems.isEmpty()) {
                Map<String, Object> giftSection = new LinkedHashMap<>();
                giftSection.put("sectionType", "GIFT");
                giftSection.put("title", "Gift");
                giftSection.put("count", giftItems.size());
                giftSection.put("items", giftItems);
                sections.add(giftSection);
            }
        }
        return sections;
    }

    /**
     * 建立 Gift 觸發待確認摘要（不立即執行效果）。
     */
    private Map<String, Object> buildGiftTriggeredEffectDeferredSummary(List<Map<String, Object>> giftTriggeredEffects) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> triggers = giftTriggeredEffects == null ? List.of() : giftTriggeredEffects;
        List<String> requestedEffects = new ArrayList<>();
        for (Map<String, Object> trigger : triggers) {
            Object requested = trigger.get("requestedEffects");
            if (!(requested instanceof List<?> list)) {
                continue;
            }
            for (Object effectType : list) {
                String normalized = normalizeZone(effectType);
                if (StringUtils.hasText(normalized) && !requestedEffects.contains(normalized)) {
                    requestedEffects.add(normalized);
                }
            }
        }
        summary.put("sourceActionType", "GIFT");
        summary.put("deferred", !triggers.isEmpty());
        summary.put("triggeredGifts", triggers);
        summary.put("requestedEffects", requestedEffects);
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        return summary;
    }

    private List<Map<String, Object>> buildGiftTriggerPayloads(List<Map<String, Object>> giftTriggeredEffects) {
        List<Map<String, Object>> giftTriggers = new ArrayList<>();
        if (giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return giftTriggers;
        }
        for (Map<String, Object> trigger : giftTriggeredEffects) {
            if (trigger == null || trigger.isEmpty()) {
                continue;
            }
            Map<String, Object> triggerPayload = new LinkedHashMap<>();
            triggerPayload.put("triggerType", normalizeZone(trigger.get("triggerType")));
            triggerPayload.put("sourceCardInstanceId", asLong(trigger.get("sourceCardInstanceId")));
            triggerPayload.put("triggerTargetCardInstanceId", asLong(trigger.get("triggerTargetCardInstanceId")));
            triggerPayload.put("giftHolderHolomemId", asLong(trigger.get("giftHolderHolomemId")));
            triggerPayload.put("giftHolderCardInstanceId", asLong(trigger.get("giftHolderCardInstanceId")));
            triggerPayload.put("giftHolderCardId", asString(trigger.get("giftHolderCardId")));
            triggerPayload.put("giftHolderZone", asString(trigger.get("giftHolderZone")));
            triggerPayload.put(
                "giftHolderAttachedCheerCardInstanceIds",
                toLongList(trigger.get("giftHolderAttachedCheerCardInstanceIds"))
            );
            triggerPayload.put("giftHolderAttachedCheerCardIds", toStringList(trigger.get("giftHolderAttachedCheerCardIds")));
            triggerPayload.put("giftHolderStackCardInstanceIds", toLongList(trigger.get("giftHolderStackCardInstanceIds")));
            triggerPayload.put("giftHolderStackCardIds", toStringList(trigger.get("giftHolderStackCardIds")));
            triggerPayload.put("selectionRequired", toBoolean(trigger.get("selectionRequired")));
            triggerPayload.put("selectionEffectType", asString(trigger.get("selectionEffectType")));
            triggerPayload.put("selectionMinSelect", asInt(trigger.get("selectionMinSelect")));
            triggerPayload.put("selectionMaxSelect", asInt(trigger.get("selectionMaxSelect")));
            triggerPayload.put(
                "selectionCandidateCardInstanceIds",
                toLongList(trigger.get("selectionCandidateCardInstanceIds"))
            );
            triggerPayload.put("rawText", asString(trigger.get("rawText")));
            giftTriggers.add(triggerPayload);
        }
        return giftTriggers;
    }

    private void appendGiftSelectionPendingContext(
        Map<String, Object> additionalContext,
        List<Map<String, Object>> giftTriggeredEffects
    ) {
        if (additionalContext == null || giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return;
        }
        List<Map<String, Object>> selectableTriggers = giftTriggeredEffects.stream()
            .filter(Objects::nonNull)
            .filter(trigger -> toBoolean(trigger.get("selectionRequired")))
            .toList();
        if (selectableTriggers.size() != 1) {
            return;
        }
        Map<String, Object> selectionTrigger = selectableTriggers.get(0);
        List<Long> candidateCardInstanceIds = toLongList(selectionTrigger.get("selectionCandidateCardInstanceIds"));
        if (candidateCardInstanceIds.isEmpty()) {
            return;
        }
        additionalContext.put("candidateCardInstanceIds", candidateCardInstanceIds);
        additionalContext.put("selectionGiftHolderCardInstanceId", asLong(selectionTrigger.get("giftHolderCardInstanceId")));
        additionalContext.put("minSelect", Math.max(asInt(selectionTrigger.get("selectionMinSelect")), 1));
        additionalContext.put(
            "maxSelect",
            Math.max(
                asInt(selectionTrigger.get("selectionMaxSelect")),
                Math.max(asInt(selectionTrigger.get("selectionMinSelect")), 1)
            )
        );
    }

    /**
     * 建立 Gift 觸發確認互動。
     */
    private FollowupInteractionDecision createGiftTriggeredEffectConfirmPendingInteraction(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> cards,
        List<Map<String, Object>> giftTriggeredEffects,
        int turnNumber
    ) {
        List<Map<String, Object>> giftTriggers = buildGiftTriggerPayloads(giftTriggeredEffects);
        Map<String, Object> additionalContext = new LinkedHashMap<>();
        additionalContext.put("giftTriggers", giftTriggers);
        additionalContext.put("giftCount", giftTriggers.size());
        appendGiftSelectionPendingContext(additionalContext, giftTriggeredEffects);

        return createTriggeredEffectConfirmPendingInteraction(
            matchId,
            userId,
            "GIFT",
            sourceCardInstanceId,
            sourceCardId,
            "GIFT_TRIGGER",
            "確認 Gift 效果",
            buildGiftTriggeredEffectConfirmMessage(giftTriggeredEffects),
            cards,
            turnNumber,
            additionalContext
        );
    }

    private FollowupInteractionDecision createCollabTriggeredEffectConfirmPendingInteraction(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        MatchEffectService.TriggeredEffectPreview collabPreview,
        List<Map<String, Object>> giftTriggeredEffects,
        int turnNumber
    ) {
        List<Map<String, Object>> cards = buildGiftTriggerInteractionCards(
            matchId,
            userId,
            sourceCardInstanceId,
            sourceCardId,
            giftTriggeredEffects
        );
        if (cards.isEmpty() && sourceCardInstanceId != null && sourceCardInstanceId > 0) {
            cards = List.of(buildInteractionSourceCardPayload(matchId, userId, sourceCardInstanceId, sourceCardId, "COLLAB"));
        }

        Map<String, Object> additionalContext = new LinkedHashMap<>();
        additionalContext.put("hasCollabEffect", collabPreview != null && collabPreview.hasEffect());

        List<Map<String, Object>> giftTriggers = buildGiftTriggerPayloads(giftTriggeredEffects);
        additionalContext.put("giftTriggers", giftTriggers);
        additionalContext.put("giftCount", giftTriggers.size());
        appendGiftSelectionPendingContext(additionalContext, giftTriggeredEffects);
        additionalContext.put("triggerSections", buildCollabTriggerSections(collabPreview, giftTriggeredEffects));

        return createTriggeredEffectConfirmPendingInteraction(
            matchId,
            userId,
            "COLLAB",
            sourceCardInstanceId,
            sourceCardId,
            "COLLAB_TRIGGER",
            "確認連動觸發效果",
            buildCollabTriggeredEffectConfirmMessage(collabPreview, giftTriggeredEffects),
            cards,
            turnNumber,
            additionalContext
        );
    }

    private String buildCollabTriggeredEffectConfirmMessage(
        MatchEffectService.TriggeredEffectPreview collabPreview,
        List<Map<String, Object>> giftTriggeredEffects
    ) {
        List<String> lines = new ArrayList<>();
        if (collabPreview != null && collabPreview.hasEffect()) {
            lines.add("[Collab]\n" + buildTriggeredEffectConfirmMessage("COLLAB", collabPreview).replaceFirst("^是否要執行本次觸發效果？\\n?", ""));
        }
        if (giftTriggeredEffects != null && !giftTriggeredEffects.isEmpty()) {
            lines.add("[Gift]\n" + buildGiftTriggeredEffectDetails(giftTriggeredEffects));
        }
        if (lines.isEmpty()) {
            return "是否要執行本次連動觸發效果？";
        }
        return "是否要執行本次連動觸發效果？\n" + String.join("\n\n", lines);
    }

    private List<Map<String, Object>> buildCollabTriggerSections(
        MatchEffectService.TriggeredEffectPreview collabPreview,
        List<Map<String, Object>> giftTriggeredEffects
    ) {
        List<Map<String, Object>> sections = new ArrayList<>();
        if (collabPreview != null && collabPreview.hasEffect()) {
            Map<String, Object> collabSection = new LinkedHashMap<>();
            collabSection.put("sectionType", "COLLAB_EFFECT");
            collabSection.put("title", "Collab");
            collabSection.put("effectTypes", collabPreview.effectTypes() == null ? List.of() : collabPreview.effectTypes());
            collabSection.put("rawText", collabPreview.rawText());
            sections.add(collabSection);
        }
        if (giftTriggeredEffects != null && !giftTriggeredEffects.isEmpty()) {
            List<Map<String, Object>> giftItems = new ArrayList<>();
            for (Map<String, Object> trigger : giftTriggeredEffects) {
                if (trigger == null || trigger.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("triggerType", normalizeZone(trigger.get("triggerType")));
                item.put("giftHolderCardId", asString(trigger.get("giftHolderCardId")));
                item.put("rawText", asString(trigger.get("rawText")));
                item.put("requestedEffects", toStringList(trigger.get("requestedEffects")));
                giftItems.add(item);
            }
            if (!giftItems.isEmpty()) {
                Map<String, Object> giftSection = new LinkedHashMap<>();
                giftSection.put("sectionType", "GIFT");
                giftSection.put("title", "Gift");
                giftSection.put("count", giftItems.size());
                giftSection.put("items", giftItems);
                sections.add(giftSection);
            }
        }
        return sections;
    }

    /**
     * 組裝 Gift 觸發確認訊息（含卡文與 effectType 摘要）。
     */
    private String buildGiftTriggeredEffectConfirmMessage(List<Map<String, Object>> giftTriggeredEffects) {
        if (giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return "是否要執行本次 Gift 觸發效果？";
        }
        return "是否要執行本次 Gift 觸發效果？\n" + buildGiftTriggeredEffectDetails(giftTriggeredEffects);
    }

    /**
     * 組裝 Gift 觸發明細文字（不含最上層提問句）。
     */
    private String buildGiftTriggeredEffectDetails(List<Map<String, Object>> giftTriggeredEffects) {
        int count = 0;
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> trigger : giftTriggeredEffects) {
            count++;
            String cardId = asString(trigger.get("giftHolderCardId"));
            String triggerType = normalizeZone(trigger.get("triggerType"));
            String rawText = asString(trigger.get("rawText"));
            List<String> effectTypes = toStringList(trigger.get("requestedEffects"));
            String effectSummary = effectTypes.isEmpty() ? "無可解析效果類型" : String.join("、", effectTypes);
            StringBuilder line = new StringBuilder();
            line.append("#").append(count).append(" ");
            if (StringUtils.hasText(cardId)) {
                line.append(cardId).append(" ");
            }
            line.append("[").append(StringUtils.hasText(triggerType) ? triggerType : "GIFT").append("]");
            line.append(" 效果類型：").append(effectSummary);
            if (StringUtils.hasText(rawText)) {
                line.append("\n").append(rawText);
            }
            lines.add(line.toString());
        }
        return String.join("\n\n", lines);
    }

    /**
     * 組裝 Gift 互動用卡片清單（攻擊者 + 觸發 Gift 的持有者）。
     */
    private List<Map<String, Object>> buildGiftTriggerInteractionCards(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> giftTriggeredEffects
    ) {
        List<Map<String, Object>> cards = new ArrayList<>();
        if (sourceCardInstanceId != null && sourceCardInstanceId > 0) {
            cards.add(buildInteractionSourceCardPayload(matchId, userId, sourceCardInstanceId, sourceCardId, "STAGE"));
        }
        if (giftTriggeredEffects == null || giftTriggeredEffects.isEmpty()) {
            return cards;
        }
        for (Map<String, Object> trigger : giftTriggeredEffects) {
            Long holderCardInstanceId = asLong(trigger.get("giftHolderCardInstanceId"));
            String holderCardId = asString(trigger.get("giftHolderCardId"));
            String holderZone = asString(trigger.get("giftHolderZone"));
            if (holderCardInstanceId == null || holderCardInstanceId <= 0) {
                continue;
            }
            boolean exists = cards.stream()
                .anyMatch(card -> holderCardInstanceId.equals(asLong(card.get("cardInstanceId"))));
            if (exists) {
                continue;
            }
            cards.add(
                buildInteractionSourceCardPayload(
                    matchId,
                    userId,
                    holderCardInstanceId,
                    holderCardId,
                    StringUtils.hasText(holderZone) ? holderZone : "STAGE"
                )
            );
        }
        return cards;
    }

    /**
     * 非攻擊來源若有 deferred down event，建立統一的確認互動。
     */
    private FollowupInteractionDecision createEffectPostTriggerConfirmPendingInteractionIfNeeded(
        Long matchId,
        Long userId,
        String originSourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        Map<String, Object> effectSummary,
        int turnNumber
    ) {
        Map<String, Object> downEventPreview = extractDownEventPreview(effectSummary);
        if (downEventPreview == null || downEventPreview.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        if (sourceCardInstanceId != null && sourceCardInstanceId > 0) {
            String fallbackZone = ACTION_TYPE_USE_OSHI_SKILL.equals(normalizeZone(originSourceActionType))
                ? "OSHI"
                : "ARCHIVE";
            cards.add(
                buildInteractionSourceCardPayload(
                    matchId,
                    userId,
                    sourceCardInstanceId,
                    sourceCardId,
                    fallbackZone
                )
            );
        }

        Map<String, Object> downEventContext = new LinkedHashMap<>();
        downEventContext.put("downedOwnerUserId", asLong(downEventPreview.get("downedOwnerUserId")));
        downEventContext.put("downedCardId", asString(downEventPreview.get("downedCardId")));
        downEventContext.put("downedStageZone", asString(downEventPreview.get("downedStageZone")));
        downEventContext.put("turnNumber", asInt(downEventPreview.get("turnNumber")));
        downEventContext.put("rawText", asString(downEventPreview.get("rawText")));
        downEventContext.put("requestedLifeLoss", asInt(downEventPreview.get("requestedLifeLoss")));

        Map<String, Object> additionalContext = new LinkedHashMap<>();
        additionalContext.put("downEvent", downEventContext);
        additionalContext.put("originSourceActionType", normalizeZone(originSourceActionType));

        return createTriggeredEffectConfirmPendingInteraction(
            matchId,
            userId,
            ACTION_TYPE_EFFECT_POST_TRIGGER,
            sourceCardInstanceId,
            sourceCardId,
            "DOWN_EVENT",
            "確認觸發效果",
            buildEffectPostTriggerConfirmMessage(originSourceActionType, downEventPreview),
            cards,
            turnNumber,
            additionalContext
        );
    }

    /**
     * 組裝非攻擊來源 down event 的確認訊息。
     */
    private String buildEffectPostTriggerConfirmMessage(
        String originSourceActionType,
        Map<String, Object> downEventPreview
    ) {
        String source = normalizeZone(originSourceActionType);
        String sourceLabel = ACTION_TYPE_USE_OSHI_SKILL.equals(source) ? "Oshi 技能" : "卡片效果";
        Integer requestedLifeLoss = asInt(downEventPreview.get("requestedLifeLoss"));
        String downedCardId = asString(downEventPreview.get("downedCardId"));
        String rawText = asString(downEventPreview.get("rawText"));

        StringBuilder line = new StringBuilder("DOWN_EVENT");
        if (StringUtils.hasText(downedCardId)) {
            line.append(" (").append(downedCardId).append(")");
        }
        if (requestedLifeLoss != null && requestedLifeLoss > 0) {
            line.append("：額外失去生命 ").append(requestedLifeLoss);
        }
        if (StringUtils.hasText(rawText)) {
            line.append("\n").append(rawText);
        }
        return "是否要執行此 " + sourceLabel + " 的後續觸發效果？\n" + line;
    }

    /**
     * 根據效果摘要判斷是否要產生 follow-up 互動（LOOK_TOP_DECK/REORDER 等）。
     */
    private FollowupInteractionDecision createFollowupInteractionPendingDecisionIfNeeded(
        Long matchId,
        Long userId,
        String sourceActionType,
        Long sourceCardInstanceId,
        String sourceCardId,
        String effectType,
        Map<String, Object> effectSummary
    ) {
        FollowupInteractionContext interaction = extractFollowupInteractionDecisionContext(matchId, userId, effectSummary);
        if (interaction == null) {
            return null;
        }
        if (hasBlockingPendingDecision(matchId, userId)) {
            throw new IllegalStateException("你有待處理的互動，請先完成確認");
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("interactionType", interaction.decisionType());
        context.put("title", interaction.title());
        context.put("message", interaction.message());
        context.put("cards", interaction.cards());
        if (interaction.placementOptions() != null && !interaction.placementOptions().isEmpty()) {
            context.put("placementOptions", interaction.placementOptions());
        }
        context.put("effectType", effectType);
        context.put("candidateCardInstanceIds", interaction.candidateCardInstanceIds());
        context.put("candidateCards", interaction.cards());
        if (interaction.lookedCardInstanceId() != null) {
            context.put("lookedCardInstanceId", interaction.lookedCardInstanceId());
        }
        if (StringUtils.hasText(interaction.lookedCardId())) {
            context.put("lookedCardId", interaction.lookedCardId());
        }

        Long decisionId = jdbcTemplate.query(
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
            interaction.decisionType(),
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            effectType,
            interaction.minSelect(),
            interaction.maxSelect(),
            PENDING_STATUS,
            toJson(context)
        );
        if (decisionId == null) {
            return null;
        }
        return new FollowupInteractionDecision(decisionId, interaction.decisionType());
    }

    /**
     * 載入決策候選卡片資料，若查無完整資料則回傳 fallback 結構。
     */
    private Map<String, Object> loadCardCandidateForDecision(
        Long matchId,
        Long viewerUserId,
        Long ownerUserId,
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
            ownerUserId,
            cardInstanceId
        );
        if (row != null) {
            if (!viewerUserId.equals(ownerUserId)) {
                row.put("zone", null);
            }
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

    /**
     * 正規化決策中的 placement 欄位（例如 TOP/BOTTOM）。
     */
    private String normalizeDecisionPlacement(String placement) {
        if (!StringUtils.hasText(placement)) {
            return null;
        }
        return placement.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 從效果摘要萃取 follow-up 互動上下文（含候選卡、提示訊息、可選數量）。
     */
    private FollowupInteractionContext extractFollowupInteractionDecisionContext(
        Long matchId,
        Long userId,
        Map<String, Object> effectSummary
    ) {
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
            if (!toBoolean(effectRow.get("applied"))) {
                continue;
            }
            if (DECISION_TYPE_LOOK_TOP_DECK.equals(resolvedType)) {
                Long lookedCardInstanceId = asLong(effectRow.get("lookedCardInstanceId"));
                String lookedCardId = asString(effectRow.get("lookedCardId"));
                if (lookedCardInstanceId == null || !StringUtils.hasText(lookedCardId)) {
                    continue;
                }
                Map<String, Object> candidate = loadCardCandidateForDecision(
                    matchId,
                    userId,
                    userId,
                    lookedCardInstanceId,
                    "DECK",
                    lookedCardId
                );
                return new FollowupInteractionContext(
                    DECISION_TYPE_LOOK_TOP_DECK,
                    "查看牌庫頂",
                    "選擇保留在牌庫頂的卡片；若不選擇則放到底部。",
                    0,
                    1,
                    List.of(candidate),
                    List.of(lookedCardInstanceId),
                    List.of("TOP", "BOTTOM"),
                    lookedCardInstanceId,
                    lookedCardId
                );
            }
            if (DECISION_TYPE_LOOK_OPPONENT_HAND.equals(resolvedType) || DECISION_TYPE_LOOK_HOLOPOWER.equals(resolvedType)) {
                Long lookedUserId = asLong(effectRow.get("lookedUserId"));
                String lookedZone = DECISION_TYPE_LOOK_OPPONENT_HAND.equals(resolvedType) ? "HAND" : "HOLOPOWER";
                List<Map<String, Object>> cards = buildLookZoneCandidateCards(
                    matchId,
                    userId,
                    lookedUserId == null ? userId : lookedUserId,
                    effectRow.get("lookedCards"),
                    lookedZone
                );
                List<Long> candidateCardInstanceIds = cards.stream()
                    .map(card -> asLong(card.get("cardInstanceId")))
                    .filter(id -> id != null && id > 0)
                    .toList();
                String title = DECISION_TYPE_LOOK_OPPONENT_HAND.equals(resolvedType) ? "查看對手手牌" : "查看 Holopower";
                String message = DECISION_TYPE_LOOK_OPPONENT_HAND.equals(resolvedType)
                    ? "以下為本次效果可查看的對手手牌。"
                    : "以下為本次效果可查看的 Holopower。";
                return new FollowupInteractionContext(
                    resolvedType,
                    title,
                    message,
                    0,
                    0,
                    cards,
                    candidateCardInstanceIds,
                    List.of(),
                    null,
                    null
                );
            }
            if (DECISION_TYPE_REORDER_DECK_BOTTOM.equals(resolvedType) || "SEARCH".equals(resolvedType)) {
                if (!toBoolean(effectRow.get("requiresDeckBottomReorder"))) {
                    continue;
                }
                Long lookedUserId = userId;
                List<Map<String, Object>> cards = buildLookZoneCandidateCards(
                    matchId,
                    userId,
                    lookedUserId,
                    effectRow.get("deckBottomReorderCandidates"),
                    "DECK"
                );
                List<Long> candidateCardInstanceIds = cards.stream()
                    .map(card -> asLong(card.get("cardInstanceId")))
                    .filter(id -> id != null && id > 0)
                    .toList();
                if (candidateCardInstanceIds.size() <= 1) {
                    continue;
                }
                return new FollowupInteractionContext(
                    DECISION_TYPE_REORDER_DECK_BOTTOM,
                    "排序牌庫底",
                    "請依你要的順序確認，將剩餘卡片放到牌庫底。",
                    candidateCardInstanceIds.size(),
                    candidateCardInstanceIds.size(),
                    cards,
                    candidateCardInstanceIds,
                    List.of(),
                    null,
                    null
                );
            }
        }
        return null;
    }

    /**
     * 建立 LOOK_* 類互動的候選卡清單（唯讀展示用途）。
     */
    @SuppressWarnings("unchecked")
    /**
     * 建立 LOOK 類決策的候選卡片顯示資料。
     */
    private List<Map<String, Object>> buildLookZoneCandidateCards(
        Long matchId,
        Long viewerUserId,
        Long ownerUserId,
        Object lookedCardsObject,
        String fallbackZone
    ) {
        if (!(lookedCardsObject instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawCard)) {
                continue;
            }
            Long cardInstanceId = asLong(rawCard.get("cardInstanceId"));
            String cardId = asString(rawCard.get("cardId"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            Map<String, Object> card = loadCardCandidateForDecision(
                matchId,
                viewerUserId,
                ownerUserId,
                cardInstanceId,
                fallbackZone,
                cardId
            );
            cards.add(card);
        }
        return cards;
    }

    /**
     * 將 follow-up decision id/type 寫回 action payload，便於前端串接 modal。
     */
    private void putFollowupDecisionPayload(Map<String, Object> payload, FollowupInteractionDecision followupDecision) {
        if (payload == null || followupDecision == null || followupDecision.decisionId() == null) {
            return;
        }
        payload.put("pendingInteractionDecisionId", followupDecision.decisionId());
        payload.put("pendingInteractionDecisionType", followupDecision.decisionType());
        if (DECISION_TYPE_LOOK_TOP_DECK.equals(followupDecision.decisionType())) {
            payload.put("pendingLookTopDeckDecisionId", followupDecision.decisionId());
        }
    }

    /**
     * 載入並鎖定 pending decision，避免同一決策被重複處理。
     */
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
                    extractJsonBoolean(contextNode, "limited"),
                    contextNode
                );
            },
            decisionId,
            matchId,
            userId,
            PENDING_STATUS
        );
    }

    /**
     * 將 pending decision 標記為 RESOLVED。
     */
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

    /**
     * 清洗選牌輸入：去重、過濾無效 id（null/<=0）。
     */
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

    /**
     * 驗證選牌結果是否完全落在候選集合中。
     */
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

    /**
     * 解析 JSON 字串，失敗回傳 null node。
     */
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

    /**
     * 從 JsonNode 取 Long 欄位。
     */
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

    /**
     * 從 JsonNode 取文字欄位。
     */
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

    /**
     * 從 JsonNode 取布林欄位。
     */
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

    /**
     * 從 JsonNode 取 Long 列表欄位。
     */
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

    /**
     * 將任意清單值正規化為字串清單（去空白與重複）。
     */
    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = normalizeZone(item);
            if (!StringUtils.hasText(text) || result.contains(text)) {
                continue;
            }
            result.add(text);
        }
        return result;
    }

    private List<Long> toLongList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : list) {
            Long id = asLong(item);
            if (id == null || id <= 0 || result.contains(id)) {
                continue;
            }
            result.add(id);
        }
        return result;
    }

    private List<String> extractJsonTextList(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !StringUtils.hasText(fieldName)) {
            return List.of();
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || !value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item == null || item.isNull()) {
                continue;
            }
            String text = normalizeZone(item.asText());
            if (!StringUtils.hasText(text) || result.contains(text)) {
                continue;
            }
            result.add(text);
        }
        return result;
    }

    /**
     * 將 Map<?,?> 安全轉成 Map<String,Object>。
     */
    private Map<String, Object> castToMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    /**
     * 檢查 support 定義是否標記 LIMITED。
     */
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

    /**
     * 解析支援牌附加類型（MASCOT/TOOL/FAN/OTHER）。
     */
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

    /**
     * 判斷是否為可附加型支援。
     */
    private boolean isAttachableSupportType(String supportType) {
        String normalized = normalizeZone(supportType);
        return SUPPORT_TYPE_MASCOT.equals(normalized)
            || SUPPORT_TYPE_TOOL.equals(normalized)
            || SUPPORT_TYPE_FAN.equals(normalized);
    }

    /**
     * 透過卡片實例解析我方 Holomem id。
     */
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

    /**
     * 驗證同一 Holomem 的可附加支援上限。
     */
    private void validateAttachableSupportLimit(Long matchHolomemId, String supportType, String supportCardId) {
        String normalized = normalizeZone(supportType);
        if (!SUPPORT_TYPE_MASCOT.equals(normalized) && !SUPPORT_TYPE_TOOL.equals(normalized)) {
            return;
        }
        List<String> attachedSupportNames = jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_holomem_supports hs
            JOIN cards c ON c.card_id = hs.support_card_id
            WHERE hs.match_holomem_id = ?
              AND hs.support_type = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> rs.getString("name"),
            matchHolomemId,
            normalized
        );
        if (attachedSupportNames == null) {
            attachedSupportNames = List.of();
        }

        if (SUPPORT_TYPE_MASCOT.equals(normalized) && "HBP02-013".equals(loadHolomemTopCardId(matchHolomemId))) {
            if (attachedSupportNames.size() >= 2) {
                throw new IllegalStateException("HBP02-013 最多只能附加 2 張マスコット");
            }
            String newSupportName = loadCardName(supportCardId);
            if (StringUtils.hasText(newSupportName)) {
                for (String attachedSupportName : attachedSupportNames) {
                    if (StringUtils.hasText(attachedSupportName) && attachedSupportName.equals(newSupportName)) {
                        throw new IllegalStateException("HBP02-013 的 2 張マスコット必須是不同卡名");
                    }
                }
            }
            return;
        }

        if (!attachedSupportNames.isEmpty()) {
            String supportLabel = SUPPORT_TYPE_MASCOT.equals(normalized) ? "マスコット" : "ツール";
            throw new IllegalStateException("同一 Holomem 只能附加 1 張" + supportLabel);
        }
    }

    private String loadHolomemTopCardId(Long matchHolomemId) {
        if (matchHolomemId == null) {
            return "";
        }
        return jdbcTemplate.query(
            """
            SELECT card_id
            FROM match_holomems
            WHERE id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? asString(rs.getString("card_id")) : "",
            matchHolomemId
        );
    }

    private String loadCardName(String cardId) {
        if (!StringUtils.hasText(cardId)) {
            return "";
        }
        return jdbcTemplate.query(
            """
            SELECT name
            FROM cards
            WHERE card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? asString(rs.getString("name")) : "",
            cardId
        );
    }

    /**
     * 判斷對戰是否處於 active + STARTED。
     */
    private boolean isMatchActive(MatchEntity match) {
        if (match == null) {
            return false;
        }
        return "active".equalsIgnoreCase(asString(match.getStatus()));
    }

    /**
     * 判斷本回合是否已使用過 LIMITED support。
     */
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

    /**
     * 統計本回合指定區位的 ATTACK_ART 使用次數。
     */
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

    /**
     * 判斷是否仍有可發動藝能的攻擊者（決定是否維持 PERFORMANCE phase）。
     */
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

    /**
     * 載入主要藝能資料；優先回傳可解析出傷害的藝能。
     */
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

    /**
     * 解析藝能傷害值（JSON 解析優先，失敗時文字 fallback）。
     */
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

    /**
     * 從藝能 effect JSON 節點解析傷害數值。
     */
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

    /**
     * 解析藝能 Cheer 成本（有色與 COLORLESS）。
     */
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

    /**
     * 套用常駐減費後的藝能 Cheer 成本。
     */
    private Map<String, Integer> applyArtCheerCostReduction(
        Map<String, Integer> baseCost,
        Map<String, Integer> reduction
    ) {
        Map<String, Integer> effectiveCost = new LinkedHashMap<>();
        if (baseCost == null || baseCost.isEmpty()) {
            return effectiveCost;
        }
        for (Map.Entry<String, Integer> entry : baseCost.entrySet()) {
            String color = normalizeZone(entry.getKey());
            int required = entry.getValue() == null ? 0 : entry.getValue();
            if (!StringUtils.hasText(color) || required <= 0) {
                continue;
            }
            int reducedBy = reduction == null ? 0 : Math.max(0, reduction.getOrDefault(color, 0));
            int effectiveRequired = Math.max(required - reducedBy, 0);
            if (effectiveRequired > 0) {
                effectiveCost.put(color, effectiveRequired);
            }
        }
        return effectiveCost;
    }

    /**
     * 驗證藝能費用是否足夠支付（目前僅驗證，不扣除附加 Cheer）。
     */
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
                   mhc.match_card_id,
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

    /**
     * 取得バトンタッチ無色成本修正值（來自 match_turn_effects）。
     */
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

    /**
     * 判斷指定場上行為是否被 ACTION_LOCK 封鎖。
     */
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

    /**
     * ACTION_LOCK 的 action 條件比對。
     */
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

    /**
     * ACTION_LOCK 的 zone 條件比對。
     */
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

    /**
     * ACTION_LOCK 的特定 Holomem 目標條件比對。
     */
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

    /**
     * 支付バトンタッチ成本：實際扣除 Cheer 並移送 ARCHIVE。
     */
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
            Long cheerCardInstanceId = asLong(row.get("match_card_id"));
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
            Long archivedCardInstanceId = archiveStageCheerCard(matchId, ownerUserId, cheerCardInstanceId, cheerCardId);
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

    /**
     * 在 Cheer 列表中找第一張指定顏色的位置索引。
     */
    private int findFirstCheerIndexByColor(List<Map<String, Object>> rows, String color) {
        for (int i = 0; i < rows.size(); i++) {
            if (color.equals(normalizeZone(rows.get(i).get("color")))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 將場上的指定 Cheer 卡歸檔到 ARCHIVE。
     */
    private Long archiveStageCheerCard(Long matchId, Long ownerUserId, String cheerCardId) {
        return archiveStageCheerCard(matchId, ownerUserId, null, cheerCardId);
    }

    /**
     * 將場上的指定 Cheer 卡歸檔到 ARCHIVE，優先使用已綁定的實卡 instance。
     */
    private Long archiveStageCheerCard(Long matchId, Long ownerUserId, Long cheerCardInstanceId, String cheerCardId) {
        Long resolvedCardInstanceId = cheerCardInstanceId;
        if (resolvedCardInstanceId == null || resolvedCardInstanceId <= 0) {
            resolvedCardInstanceId = jdbcTemplate.query(
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
        }
        if (resolvedCardInstanceId == null) {
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
            resolvedCardInstanceId,
            matchId,
            ownerUserId
        );
        return updated == 1 ? resolvedCardInstanceId : null;
    }

    /**
     * 解析藝能文本中的屬性色剋加成（例如紅+50）。
     */
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

    /**
     * 解析攻擊目標 Holomem：
     * 有指定目標則優先驗證，否則依站位預設優先順序挑選。
     */
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
                SELECT h.id, h.match_card_id, h.card_id, h.zone, m.main_color
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
                        rs.getString("card_id"),
                        normalizeZone(rs.getString("zone")),
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
            SELECT h.id, h.match_card_id, h.card_id, h.zone, m.main_color
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
                    rs.getString("card_id"),
                    normalizeZone(rs.getString("zone")),
                    normalizeZone(rs.getString("main_color"))
                )
                : null,
            matchId,
            opponentUserId
        );
    }

    private TargetHolomem loadOpponentCollabTargetHolomem(Long matchId, Long opponentUserId) {
        if (opponentUserId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT h.id, h.match_card_id, h.card_id, h.zone, m.main_color
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'COLLAB'
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> rs.next()
                ? new TargetHolomem(
                    rs.getLong("id"),
                    rs.getLong("match_card_id"),
                    rs.getString("card_id"),
                    normalizeZone(rs.getString("zone")),
                    normalizeZone(rs.getString("main_color"))
                )
                : null,
            matchId,
            opponentUserId
        );
    }

    private boolean hasPassiveGiftTargetRestrictionToCollab(Long matchId, Long ownerUserId) {
        if (matchId == null || ownerUserId == null) {
            return false;
        }
        List<String> passiveTexts = jdbcTemplate.query(
            """
            SELECT mc.passive_effect_json::text AS passive_text
            FROM match_holomems h
            JOIN member_cards mc ON mc.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'COLLAB'
              AND mc.passive_effect_json IS NOT NULL
              AND mc.passive_effect_json::text LIKE '%相手のホロメンのアーツは%'
              AND mc.passive_effect_json::text LIKE '%自分のコラボホロメンしか対象にできない%'
            """,
            (rs, rowNum) -> rs.getString("passive_text"),
            matchId,
            ownerUserId
        );
        for (String passiveText : passiveTexts) {
            if (!StringUtils.hasText(passiveText)) {
                continue;
            }
            String requiredCenterTag = extractRequiredCenterTagForPassiveTargetRestriction(passiveText);
            if (StringUtils.hasText(requiredCenterTag)
                && !hasCenterHolomemWithTag(matchId, ownerUserId, requiredCenterTag)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private String extractRequiredCenterTagForPassiveTargetRestriction(String passiveText) {
        if (!StringUtils.hasText(passiveText)) {
            return "";
        }
        Matcher matcher = CENTER_TAG_REQUIREMENT_PATTERN.matcher(passiveText);
        if (!matcher.find()) {
            return "";
        }
        String tagToken = matcher.group(1);
        if (!StringUtils.hasText(tagToken)) {
            return "";
        }
        return "#" + tagToken.trim();
    }

    private boolean hasCenterHolomemWithTag(Long matchId, Long ownerUserId, String requiredTag) {
        if (matchId == null || ownerUserId == null || !StringUtils.hasText(requiredTag)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'CENTER'
              AND jsonb_exists(COALESCE(c.tags_json, '[]'::jsonb), ?)
            """,
            Integer.class,
            matchId,
            ownerUserId,
            requiredTag
        );
        return count != null && count > 0;
    }

    /**
     * 將日文顏色 token 轉為系統色碼。
     */
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

    /**
     * 抽取字串中的第一個整數；找不到或解析失敗回傳 0。
     */
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

    private String normalizeDigits(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text
            .replace('０', '0')
            .replace('１', '1')
            .replace('２', '2')
            .replace('３', '3')
            .replace('４', '4')
            .replace('５', '5')
            .replace('６', '6')
            .replace('７', '7')
            .replace('８', '8')
            .replace('９', '9');
    }

    /**
     * 讓指定玩家失去 1 點 life，回傳失去的 life 卡片實例 id。
     */
    private Long loseLifeOnce(Long matchId, Long ownerUserId) {
        EffectContext effectContext = EffectContext.system(matchId, ownerUserId, ACTION_TYPE_RULE_EVENT);
        ReduceLifeAction reduceLifeAction = new ReduceLifeAction(ownerUserId, 1, "LOSE_LIFE_ONCE");
        List<ActionResult> actionResults = gameActionExecutor.execute(effectContext, List.of(reduceLifeAction));
        if (actionResults.isEmpty() || !actionResults.get(0).success()) {
            return null;
        }
        Object movedCards = actionResults.get(0).details().get("lifeCardInstanceIds");
        if (!(movedCards instanceof List<?> movedList) || movedList.isEmpty()) {
            return null;
        }
        Object first = movedList.get(0);
        if (first instanceof Number n) {
            return n.longValue();
        }
        if (first instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 將 payload map 序列化為 JSON 字串，失敗時回傳空物件字串。
     */
    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /**
     * 將 reason 正規化為可追蹤 reason code（大寫、底線、去除非法字元）。
     */
    private String standardizeReasonCode(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "UNKNOWN";
        }
        String normalized = reason.trim().toUpperCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^A-Z0-9_]", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        return StringUtils.hasText(normalized) ? normalized : "UNKNOWN";
    }

    /**
     * 統一寫入 RULE_EVENT action（含 eventType/reasonCode/details）。
     */
    private void appendRuleEvent(
        MatchEntity match,
        Long userId,
        int turnNumber,
        String eventType,
        String reasonCode,
        Map<String, Object> details
    ) {
        if (match == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", StringUtils.hasText(eventType) ? eventType : "UNKNOWN_EVENT");
        payload.put("reasonCode", standardizeReasonCode(reasonCode));
        payload.put("matchStatus", asString(match.getStatus()));
        payload.put("currentPhase", asString(match.getCurrentPhase()));
        payload.put("turnNumber", turnNumber);
        if (details != null && !details.isEmpty()) {
            payload.put("details", details);
        }
        appendAction(
            match,
            userId,
            ACTION_TYPE_RULE_EVENT,
            toJson(payload),
            turnNumber
        );
    }

    /**
     * 建立觸發結算順序資訊，供前端顯示與除錯追蹤。
     */
    private List<Map<String, Object>> buildTriggeredResolutionOrder(
        String firstStep,
        int firstPriority,
        Map<String, Object> firstSummary,
        String secondStep,
        int secondPriority,
        Map<String, Object> secondSummary
    ) {
        List<Map<String, Object>> order = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("step", firstStep);
        first.put("priority", firstPriority);
        first.put("applied", firstSummary != null);
        order.add(first);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("step", secondStep);
        second.put("priority", secondPriority);
        second.put("applied", secondSummary != null);
        order.add(second);
        return order;
    }

    /**
     * 寫入一筆 match_actions 紀錄並自動計算 action_order。
     */
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

    /**
     * 更新 match.updatedAt 時戳。
     */
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

    private record DrawTurnResult(
        Long drawnCardInstanceId,
        Long drawInteractionId,
        boolean deckOut
    ) {
        private static DrawTurnResult drawn(Long drawnCardInstanceId, Long drawInteractionId) {
            return new DrawTurnResult(drawnCardInstanceId, drawInteractionId, false);
        }

        private static DrawTurnResult deckedOut() {
            return new DrawTurnResult(null, null, true);
        }
    }

    private record ArtCritical(String color, int bonus) {
    }

    private record HoloxSlotRevealSummary(
        boolean revealApplied,
        List<Long> revealedCardInstanceIds,
        List<String> revealedCardIds,
        int revealedHolomemCount,
        int artBonus,
        List<Long> archivedCardInstanceIds,
        List<String> archivedCardIds,
        List<Long> archivedSupportCardInstanceIds,
        List<String> archivedSupportCardIds,
        boolean revealedAllMembersSameBloomLevel,
        Integer sharedBloomLevel
    ) {
        private static HoloxSlotRevealSummary empty() {
            return new HoloxSlotRevealSummary(
                false,
                List.of(),
                List.of(),
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                null
            );
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("revealApplied", revealApplied);
            payload.put("revealedCardInstanceIds", revealedCardInstanceIds);
            payload.put("revealedCardIds", revealedCardIds);
            payload.put("revealedHolomemCount", revealedHolomemCount);
            payload.put("artBonus", artBonus);
            payload.put("archivedCardInstanceIds", archivedCardInstanceIds);
            payload.put("archivedCardIds", archivedCardIds);
            payload.put("archivedSupportCardInstanceIds", archivedSupportCardInstanceIds);
            payload.put("archivedSupportCardIds", archivedSupportCardIds);
            payload.put("revealedAllMembersSameBloomLevel", revealedAllMembersSameBloomLevel);
            payload.put("sharedBloomLevel", sharedBloomLevel);
            return payload;
        }
    }

    private record FollowupInteractionDecision(
        Long decisionId,
        String decisionType
    ) {
    }

    private record AdvancePhaseFollowup(
        List<Map<String, Object>> ownGiftEffects,
        List<Map<String, Object>> opponentGiftEffects,
        FollowupInteractionDecision ownDecision,
        FollowupInteractionDecision opponentDecision
    ) {
        private static AdvancePhaseFollowup empty() {
            return new AdvancePhaseFollowup(List.of(), List.of(), null, null);
        }
    }

    private record FollowupInteractionContext(
        String decisionType,
        String title,
        String message,
        int minSelect,
        int maxSelect,
        List<Map<String, Object>> cards,
        List<Long> candidateCardInstanceIds,
        List<String> placementOptions,
        Long lookedCardInstanceId,
        String lookedCardId
    ) {
    }

    private record TargetHolomem(Long holomemId, Long matchCardInstanceId, String cardId, String zone, String mainColor) {
    }

    private record DamageRedirectTarget(Long effectId, TargetHolomem target) {
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
        boolean limited,
        JsonNode contextNode
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
