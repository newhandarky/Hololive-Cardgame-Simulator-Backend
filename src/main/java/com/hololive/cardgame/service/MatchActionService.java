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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MatchActionService {

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
    private static final int OPENING_HAND_SIZE = 7;

    private final MatchRepository matchRepository;
    private final MatchPlayerRepository matchPlayerRepository;
    private final MatchActionRepository matchActionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MatchPayloadJsonService matchPayloadJsonService;
    private final GiftTriggerActionPayloadExtractor giftTriggerActionPayloadExtractor;
    private final GiftTriggerActionWriter giftTriggerActionWriter;
    private final PendingGiftTriggerContextExtractor pendingGiftTriggerContextExtractor;
    private final PendingDownEventContextExtractor pendingDownEventContextExtractor;
    private final FollowupTriggerConfirmPendingDecisionWriter followupTriggerConfirmPendingDecisionWriter;
    private final FollowupTriggerConfirmPendingDecisionCreator followupTriggerConfirmPendingDecisionCreator;
    private final GiftTriggeredEffectDeferredSummaryBuilder giftTriggeredEffectDeferredSummaryBuilder;
    private final GiftTriggeredEffectConfirmPendingInputBuilder giftTriggeredEffectConfirmPendingInputBuilder;
    private final GiftTriggerInteractionCardsBuilder giftTriggerInteractionCardsBuilder;
    private final GiftPendingDecisionCreator giftPendingDecisionCreator;
    private final AttackArtPostTriggerConfirmPendingInputBuilder attackArtPostTriggerConfirmPendingInputBuilder;
    private final EffectPostTriggerPendingService effectPostTriggerPendingService;
    private final FollowupInteractionContextBuilder followupInteractionContextBuilder;
    private final FollowupCardCandidateLoader followupCardCandidateLoader;
    private final FollowupPendingDecisionContextBuilder followupPendingDecisionContextBuilder;
    private final FollowupInteractionPendingDecisionWriter followupInteractionPendingDecisionWriter;
    private final MatchEffectService matchEffectService;
    private final MatchEffectCombatModifierService matchEffectCombatModifierService;
    private final MatchTriggeredCombatEffectService matchTriggeredCombatEffectService;
    private final MatchTurnEffectMaintenanceService matchTurnEffectMaintenanceService;
    private final MatchTurnLifecycleService matchTurnLifecycleService;
    private final EndTurnApplicationService endTurnApplicationService;
    private final BloomApplicationService bloomApplicationService;
    private final CollabApplicationService collabApplicationService;
    private final AttachCheerApplicationService attachCheerApplicationService;
    private final PlayCardApplicationService playCardApplicationService;
    private final CollabEffectResolutionService collabEffectResolutionService;
    private final BloomEffectResolutionService bloomEffectResolutionService;
    private final PlayCardEffectResolutionService playCardEffectResolutionService;
    private final AttackCostService attackCostService;
    private final AttackTargetService attackTargetService;
    private final AttackDamageService attackDamageService;
    private final AttackDamageApplicationService attackDamageApplicationService;
    private final AttackDownService attackDownService;
    private final AttackDefenderGiftFollowupService attackDefenderGiftFollowupService;
    private final AttackPostTriggerPendingService attackPostTriggerPendingService;
    private final AttackRestAndPayloadService attackRestAndPayloadService;
    private final AttackActionLogService attackActionLogService;
    private final AttackPayloadJsonService attackPayloadJsonService;
    private final AttackPendingDecisionConversionService attackPendingDecisionConversionService;
    private final AttackEffectSummaryExtractor attackEffectSummaryExtractor;
    private final MatchTimestampService matchTimestampService;
    private final AttackFinishCheckService attackFinishCheckService;
    private final AttackEffectFollowupService attackEffectFollowupService;
    private final AttackArtApplicationService attackArtApplicationService;
    private final AttackPerformanceAvailabilityService attackPerformanceAvailabilityService;
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
        MatchEffectCombatModifierService matchEffectCombatModifierService,
        MatchTriggeredCombatEffectService matchTriggeredCombatEffectService,
        MatchTurnEffectMaintenanceService matchTurnEffectMaintenanceService,
        MatchTurnLifecycleService matchTurnLifecycleService,
        EndTurnApplicationService endTurnApplicationService,
        BloomApplicationService bloomApplicationService,
        CollabApplicationService collabApplicationService,
        AttachCheerApplicationService attachCheerApplicationService,
        PlayCardApplicationService playCardApplicationService,
        CollabEffectResolutionService collabEffectResolutionService,
        BloomEffectResolutionService bloomEffectResolutionService,
        PlayCardEffectResolutionService playCardEffectResolutionService,
        AttackCostService attackCostService,
        AttackTargetService attackTargetService,
        AttackDamageService attackDamageService,
        AttackDamageApplicationService attackDamageApplicationService,
        AttackDownService attackDownService,
        AttackDefenderGiftFollowupService attackDefenderGiftFollowupService,
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
        this.matchPayloadJsonService = new MatchPayloadJsonService(objectMapper);
        this.giftTriggerActionPayloadExtractor = new GiftTriggerActionPayloadExtractor();
        this.giftTriggerActionWriter = new GiftTriggerActionWriter(matchActionRepository, matchPayloadJsonService);
        this.pendingGiftTriggerContextExtractor = new PendingGiftTriggerContextExtractor();
        this.pendingDownEventContextExtractor = new PendingDownEventContextExtractor();
        this.followupTriggerConfirmPendingDecisionWriter = new FollowupTriggerConfirmPendingDecisionWriter(jdbcTemplate, objectMapper);
        this.followupTriggerConfirmPendingDecisionCreator = new FollowupTriggerConfirmPendingDecisionCreator(
            followupTriggerConfirmPendingDecisionWriter
        );
        this.giftTriggeredEffectDeferredSummaryBuilder = new GiftTriggeredEffectDeferredSummaryBuilder();
        this.giftTriggeredEffectConfirmPendingInputBuilder = new GiftTriggeredEffectConfirmPendingInputBuilder();
        this.giftTriggerInteractionCardsBuilder = new GiftTriggerInteractionCardsBuilder(jdbcTemplate);
        this.giftPendingDecisionCreator = new GiftPendingDecisionCreator(
            giftTriggerInteractionCardsBuilder,
            giftTriggeredEffectConfirmPendingInputBuilder,
            followupTriggerConfirmPendingDecisionWriter
        );
        this.attackArtPostTriggerConfirmPendingInputBuilder = new AttackArtPostTriggerConfirmPendingInputBuilder();
        this.effectPostTriggerPendingService = new EffectPostTriggerPendingService(
            jdbcTemplate,
            new EffectPostTriggerConfirmMessageBuilder(),
            followupTriggerConfirmPendingDecisionCreator
        );
        this.followupInteractionContextBuilder = new FollowupInteractionContextBuilder();
        this.followupCardCandidateLoader = new FollowupCardCandidateLoader(jdbcTemplate);
        this.followupPendingDecisionContextBuilder = new FollowupPendingDecisionContextBuilder();
        this.followupInteractionPendingDecisionWriter = new FollowupInteractionPendingDecisionWriter(
            jdbcTemplate,
            matchPayloadJsonService,
            followupPendingDecisionContextBuilder
        );
        this.matchEffectService = matchEffectService;
        this.matchEffectCombatModifierService = matchEffectCombatModifierService;
        this.matchTriggeredCombatEffectService = matchTriggeredCombatEffectService;
        this.matchTurnEffectMaintenanceService = matchTurnEffectMaintenanceService;
        this.matchTurnLifecycleService = matchTurnLifecycleService;
        this.endTurnApplicationService = endTurnApplicationService;
        this.bloomApplicationService = bloomApplicationService;
        this.collabApplicationService = collabApplicationService;
        this.attachCheerApplicationService = attachCheerApplicationService;
        this.playCardApplicationService = playCardApplicationService;
        this.collabEffectResolutionService = collabEffectResolutionService;
        this.bloomEffectResolutionService = bloomEffectResolutionService;
        this.playCardEffectResolutionService = playCardEffectResolutionService;
        this.attackCostService = attackCostService;
        this.attackTargetService = attackTargetService;
        this.attackDamageService = attackDamageService;
        this.attackDamageApplicationService = attackDamageApplicationService;
        this.attackDownService = attackDownService;
        this.attackDefenderGiftFollowupService = attackDefenderGiftFollowupService;
        this.attackPendingDecisionConversionService = new AttackPendingDecisionConversionService();
        this.attackPostTriggerPendingService = new AttackPostTriggerPendingService(new AttackArtPendingDecisionCreator(
            this.giftTriggerInteractionCardsBuilder,
            this.attackArtPostTriggerConfirmPendingInputBuilder,
            this.giftTriggeredEffectConfirmPendingInputBuilder,
            this.followupTriggerConfirmPendingDecisionWriter,
            this.attackPendingDecisionConversionService
        ));
        this.attackRestAndPayloadService = new AttackRestAndPayloadService();
        this.attackActionLogService = new AttackActionLogService(new AttackActionWriterAdapter(matchActionRepository));
        this.attackPayloadJsonService = new AttackPayloadJsonService(objectMapper);
        this.attackEffectSummaryExtractor = new AttackEffectSummaryExtractor();
        this.matchTimestampService = new MatchTimestampService();
        this.attackPerformanceAvailabilityService = new AttackPerformanceAvailabilityService(jdbcTemplate);
        this.attackFinishCheckService = new AttackFinishCheckService(
            this::evaluateCardEffectMatchFinish,
            (match, actorUserId, turnNumber, effectSummary) -> evaluateLifeDefeat(match, actorUserId, turnNumber),
            (match, actorUserId, turnNumber, effectSummary) -> evaluateNoHolomemDefeat(match, actorUserId, turnNumber),
            this::hasLifeReduced,
            this::hasHolomemDowned,
            this::saveFinishedMatch
        );
        this.attackEffectFollowupService = new AttackEffectFollowupService(
            new AttackHoloxRevealResolver(),
            new AttackHbp02039SupportRecoveryResolver(),
            new AttackHbp02040LifeLossResolver(),
            new AttackDefenderDamagePreventionResolver(),
            new AttackOfficialCardArtExtraResolver(),
            new AttackOfficialOshiArtReactiveResolver()
        );
        this.attackArtApplicationService = new AttackArtApplicationAdapterFactory(
            this.attackCostService,
            this.attackTargetService,
            this.attackDamageService,
            this.attackDamageApplicationService,
            this.attackDownService,
            this.attackDefenderGiftFollowupService,
            this.attackPostTriggerPendingService,
            this.attackRestAndPayloadService,
            this.attackActionLogService,
            this.attackPayloadJsonService,
            this.attackPendingDecisionConversionService,
            this.attackEffectSummaryExtractor,
            this.attackFinishCheckService,
            this.attackEffectFollowupService,
            this.attackPerformanceAvailabilityService,
            this.matchTimestampService,
            this.matchEffectCombatModifierService,
            matchGiftTriggerService,
            jdbcTemplate,
            matchRepository
        ).create();
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
        boolean openingReset = context.phase == MatchPhase.RESET;

        PlayCardAction action = PlayCardAction.fromApi(
            matchId,
            userId,
            cardInstanceId,
            targetZone,
            context.turnNumber,
            openingReset,
            null
        );
        PlayCardValidationContext validationContext = playCardApplicationService.validate(action);
        PlayCardResolutionResult resolutionResult = playCardApplicationService.resolveState(action, validationContext);

        resolutionResult.match().setCurrentPhase(openingReset ? MatchPhase.RESET.name() : MatchPhase.MAIN.name());
        touchUpdatedAt(resolutionResult.match());
        matchRepository.saveAndFlush(resolutionResult.match());

        PlayCardEffectResolution effectResolution = playCardEffectResolutionService.resolve(action, resolutionResult);
        playCardApplicationService.dispatchResolvedEvents(action, resolutionResult, effectResolution);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardInstanceId", resolutionResult.cardInstanceId());
        payload.put("cardId", resolutionResult.cardId());
        payload.put("targetZone", resolutionResult.targetZone());
        payload.put("enteredTurn", resolutionResult.enteredTurnNumber());
        payload.put("faceDown", resolutionResult.faceDown());
        payload.put("idempotencyKey", action.idempotencyKey());
        payload.put("triggerSummary", effectResolution.triggerSummary());
        if (!openingReset) {
            payload.put("giftEffect", effectResolution.giftEffectSummary());
            payload.put("triggerResolutionOrder", effectResolution.triggerResolutionOrder());
            if (effectResolution.hasPendingInteraction()) {
                payload.put("pendingInteractionDecisionId", effectResolution.pendingInteractionDecisionId());
                payload.put("pendingInteractionDecisionType", effectResolution.pendingInteractionDecisionType());
            }
        }

        appendAction(
            resolutionResult.match(),
            userId,
            openingReset
                ? ("CENTER".equals(resolutionResult.targetZone()) ? "OPENING_SET_CENTER" : "OPENING_SET_BACK")
                : "PLAY_TO_STAGE",
            toJson(payload),
            resolutionResult.turnNumber()
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
        BloomAction action = BloomAction.fromApi(
            matchId,
            userId,
            bloomCardInstanceId,
            targetHolomemCardInstanceId,
            context.turnNumber,
            null
        );
        BloomValidationContext validationContext = bloomApplicationService.validate(action);
        BloomResolutionResult stateResolution = bloomApplicationService.resolveState(action, validationContext);
        BloomTargetSnapshot target = validationContext.target();
        String bloomCardId = stateResolution.sourceCardId();
        String bloomLevel = stateResolution.sourceLevelType();
        boolean bloomLevelOverrideApplied = stateResolution.bloomLevelOverrideApplied();
        int stackDepth = stateResolution.stackDepth();
        BloomEffectResolution effectResolution = bloomEffectResolutionService.resolveAfterBloom(
            matchId,
            userId,
            context.turnNumber,
            bloomCardInstanceId,
            bloomCardId,
            target
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
        payload.put("bloomLevelOverrideApplied", bloomLevelOverrideApplied);
        payload.put("passiveGiftSummary", effectResolution.passiveGiftSummary());
        payload.put("bloomEffect", effectResolution.bloomEffectSummary());
        payload.put("triggerSummary", effectResolution.triggerSummary());
        payload.put("triggerResolutionOrder", effectResolution.triggerResolutionOrder());
        effectResolution.appendFollowupPayload(payload);

        appendAction(
            context.match,
            userId,
            "BLOOM",
            toJson(payload),
            context.turnNumber
        );
        BloomResolutionResult resolutionResult = new BloomResolutionResult(
            context.match,
            userId,
            context.turnNumber,
            bloomCardInstanceId,
            bloomCardId,
            bloomLevel,
            target.holomemId(),
            targetHolomemCardInstanceId,
            target.topCardId(),
            target.topLevelType(),
            target.zone(),
            target.damageTaken(),
            stackDepth,
            bloomLevelOverrideApplied,
            target.extraBloomAllowanceId(),
            effectResolution.passiveGiftSummary(),
            effectResolution.bloomEffectSummary(),
            effectResolution.triggerSummary(),
            effectResolution.pendingInteractionDecisionId()
        );
        bloomApplicationService.dispatchResolvedEvents(action, resolutionResult);
        if (!effectResolution.deferredEffect()) {
            if (evaluateCardEffectMatchFinish(context.match, userId, context.turnNumber, effectResolution.bloomEffectSummary())) {
                touchUpdatedAt(context.match);
                matchRepository.saveAndFlush(context.match);
            } else if (
                hasLifeReduced(effectResolution.bloomEffectSummary()) &&
                    evaluateLifeDefeat(context.match, userId, context.turnNumber)
            ) {
                touchUpdatedAt(context.match);
                matchRepository.saveAndFlush(context.match);
            } else if (
                hasHolomemDowned(effectResolution.bloomEffectSummary()) &&
                evaluateNoHolomemDefeat(context.match, userId, context.turnNumber)
            ) {
                touchUpdatedAt(context.match);
                matchRepository.saveAndFlush(context.match);
            }
            enqueueLifeLossSendCheerInteractions(
                context.match,
                matchId,
                effectResolution.bloomEffectSummary(),
                context.turnNumber
            );
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
        matchTurnLifecycleService.confirmTurnStartDecision(
            context.match,
            userId,
            context.turnNumber,
            pending.decisionId()
        );
    }

    private void resolveLiveStartDecision(
        ActionContext context,
        Long matchId,
        Long userId,
        PendingDecision pending
    ) {
        markDecisionResolved(pending.decisionId());
        matchTurnLifecycleService.confirmLiveStartDecision(
            context.match,
            userId,
            context.turnNumber,
            pending.decisionId()
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
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!requiresTurnCheer) {
            List<Map<String, Object>> mainStepGiftEffects = matchGiftTriggerService.previewGiftTriggeredEffectsOnOwnMainStep(
                matchId,
                userId,
                context.turnNumber
            );
            payload.put("mainStepGiftEffects", buildGiftTriggeredEffectDeferredSummary(mainStepGiftEffects));
            if (!mainStepGiftEffects.isEmpty()) {
                FollowupInteractionDecision mainStepGiftDecision = createGiftTriggerDecisionWithoutSourceCard(
                    matchId,
                    userId,
                    context.turnNumber,
                    mainStepGiftEffects
                );
                putFollowupDecisionPayload(payload, mainStepGiftDecision);
            }
        }
        matchTurnLifecycleService.confirmDrawRevealDecision(
            context.match,
            userId,
            context.turnNumber,
            pending.decisionId(),
            requiresTurnCheer ? MatchPhase.CHEER : MatchPhase.MAIN,
            pending.sourceCardInstanceId(),
            pending.sourceCardId(),
            payload
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
                FollowupInteractionDecision mainStepGiftDecision = createGiftTriggerDecisionWithoutSourceCard(
                    matchId,
                    userId,
                    context.turnNumber,
                    mainStepGiftEffects
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
        FollowupInteractionDecision ownDecision = createGiftTriggerDecisionWithoutSourceCard(
            matchId,
            userId,
            turnNumber,
            ownGiftEffects
        );

        List<Map<String, Object>> opponentGiftEffects = transitionPreview.opponentGiftEffects();
        FollowupInteractionDecision opponentDecision = null;
        if (opponentUserId != null) {
            opponentDecision = createGiftTriggerDecisionWithoutSourceCard(
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

    private FollowupInteractionDecision createGiftTriggerDecisionWithoutSourceCard(
        Long matchId,
        Long userId,
        int turnNumber,
        List<Map<String, Object>> giftEffects
    ) {
        return giftPendingDecisionCreator.createWithGiftTriggerInteractionCards(
            matchId,
            userId,
            null,
            null,
            giftEffects,
            turnNumber
        );
    }

    private FollowupInteractionDecision createBatonTouchGiftTriggerDecision(
        Long matchId,
        Long userId,
        Long sourceCardInstanceId,
        String sourceCardId,
        List<Map<String, Object>> giftEffects,
        int turnNumber
    ) {
        return giftPendingDecisionCreator.createWithGiftTriggerInteractionCards(
            matchId,
            userId,
            sourceCardInstanceId,
            sourceCardId,
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
        if ("COLLAB".equals(targetZone)) {
            executeCollabAction(matchId, userId, context, cardInstanceId);
            return;
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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardInstanceId", cardInstanceId);
        payload.put("cardId", asString(currentHolomem.get("card_id")));
        payload.put("sourceZone", sourceZone);
        payload.put("targetZone", targetZone);
        payload.put(
            "triggerResolutionOrder",
            buildTriggeredResolutionOrder(
                "COLLAB_TRIGGER",
                100,
                mergeEffectSummaryForChecks(null, List.of()),
                "COLLAB_EVENT_HOOK",
                200,
                null
            )
        );
        appendAction(context.match, userId, "MOVE_STAGE_HOLOMEM", toJson(payload), context.turnNumber);
    }

    private void executeCollabAction(Long matchId, Long userId, ActionContext context, Long cardInstanceId) {
        CollabAction action = CollabAction.fromApi(matchId, userId, cardInstanceId, context.turnNumber, null);
        CollabValidationContext validationContext = collabApplicationService.validate(action);
        CollabResolutionResult resolutionResult = collabApplicationService.resolveState(action, validationContext);
        CollabSourceHolomemSnapshot currentHolomem = validationContext.sourceHolomem();
        CollabEffectResolution effectResolution = collabEffectResolutionService.resolve(action, resolutionResult);
        collabApplicationService.dispatchResolvedEvents(action, resolutionResult, effectResolution);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardInstanceId", cardInstanceId);
        payload.put("cardId", currentHolomem.cardId());
        payload.put("sourceZone", resolutionResult.sourceZone());
        payload.put("targetZone", resolutionResult.targetZone());
        payload.put("idempotencyKey", action.idempotencyKey());
        if (resolutionResult.holopowerCardInstanceId() != null) {
            payload.put("holopowerCardInstanceId", resolutionResult.holopowerCardInstanceId());
        }
        if (!effectResolution.collabEffectSummary().isEmpty()) {
            payload.put("collabEffect", effectResolution.collabEffectSummary());
        }
        if (!effectResolution.giftEffectSummary().isEmpty() && toBoolean(effectResolution.giftEffectSummary().get("deferred"))) {
            payload.put("collabGiftEffect", effectResolution.giftEffectSummary());
        }
        if (effectResolution.hasPendingInteraction()) {
            payload.put("pendingInteractionDecisionId", effectResolution.pendingInteractionDecisionId());
            payload.put("pendingInteractionDecisionType", effectResolution.pendingInteractionDecisionType());
        }
        if (!effectResolution.triggerSummary().isEmpty()) {
            payload.put("triggerSummary", effectResolution.triggerSummary());
        }
        payload.put("triggerResolutionOrder", effectResolution.triggerResolutionOrder());
        appendAction(resolutionResult.match(), userId, "COLLAB", toJson(payload), context.turnNumber);
        if (effectResolution.hasImmediateEffectSummary() && !effectResolution.hasDeferredCollabEffect()) {
            if (evaluateCardEffectMatchFinish(resolutionResult.match(), userId, context.turnNumber, effectResolution.collabEffectSummary())) {
                touchUpdatedAt(resolutionResult.match());
                matchRepository.saveAndFlush(resolutionResult.match());
            } else if (
                hasLifeReduced(effectResolution.collabEffectSummary()) &&
                    evaluateLifeDefeat(resolutionResult.match(), userId, context.turnNumber)
            ) {
                touchUpdatedAt(resolutionResult.match());
                matchRepository.saveAndFlush(resolutionResult.match());
            } else if (
                hasHolomemDowned(effectResolution.collabEffectSummary()) &&
                    evaluateNoHolomemDefeat(resolutionResult.match(), userId, context.turnNumber)
            ) {
                touchUpdatedAt(resolutionResult.match());
                matchRepository.saveAndFlush(resolutionResult.match());
            }
            enqueueLifeLossSendCheerInteractions(
                resolutionResult.match(),
                matchId,
                effectResolution.collabEffectSummary(),
                context.turnNumber
            );
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
            batonTouchGiftDecision = createBatonTouchGiftTriggerDecision(
                matchId,
                userId,
                targetCenterHolomemCardInstanceId,
                asString(target.get("card_id")),
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
        Long cheerCardInstanceId = requirePositiveId(
            request == null ? null : request.getCheerCardInstanceId(),
            "cheerCardInstanceId"
        );
        Long targetHolomemCardInstanceId = requirePositiveId(
            request == null ? null : request.getTargetHolomemCardInstanceId(),
            "targetHolomemCardInstanceId"
        );
        int requestedTurnNumber = loadRequestedTurnNumberSnapshot(matchId);
        AttachCheerAction action = AttachCheerAction.fromApi(
            matchId,
            userId,
            cheerCardInstanceId,
            targetHolomemCardInstanceId,
            requestedTurnNumber,
            null
        );
        AttachCheerValidationContext validationContext = attachCheerApplicationService.validate(action);
        AttachCheerResolutionResult result = attachCheerApplicationService.resolveState(action, validationContext);
        attachCheerApplicationService.dispatchResolvedEvents(action, result);

        result.match().setCurrentPhase(MatchPhase.MAIN.name());
        touchUpdatedAt(result.match());
        matchRepository.saveAndFlush(result.match());

        appendAction(
            result.match(),
            userId,
            "ATTACH_CHEER",
            toJson(
                Map.of(
                    "cheerCardInstanceId", result.cheerCardInstanceId(),
                    "cheerCardId", result.cheerCardId(),
                    "sourceFromZone", result.sourceZone(),
                    "targetHolomemId", result.targetHolomemId(),
                    "targetHolomemCardInstanceId", result.targetHolomemCardInstanceId(),
                    "attachmentId", result.attachmentId(),
                    "idempotencyKey", action.idempotencyKey()
                )
            ),
            result.turnNumber()
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
        if (attackPerformanceAvailabilityService.countArtUsedByZoneThisTurn(matchId, userId, context.turnNumber, attackerZone) > 0) {
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
        AttackArtApplicationResult applicationResult = attackArtApplicationService.execute(
            AttackArtApplicationContext.attackArt(
                context.match,
                matchId,
                userId,
                context.opponentUserId,
                context.turnNumber,
                attackerCardInstanceId,
                targetCardInstanceId,
                asLong(attacker.get("id")),
                attackerZone,
                attackerCardId,
                asString(attacker.get("current_level")),
                asString(attacker.get("main_color")),
                asString(art.get("name")),
                art.get("order_index"),
                asString(art.get("cost_cheer_json_text")),
                asString(art.get("effect_json_text")),
                null,
                null,
                List.of()
            )
        );
        AttackArtApplicationAdapterFactory.AttackApplicationRestPayloadStage restPayloadStage = requireAttackStage(
            applicationResult.stageResult(AttackArtApplicationService.STAGE_REST_AND_PAYLOAD),
            AttackArtApplicationAdapterFactory.AttackApplicationRestPayloadStage.class,
            "restAndPayload"
        );
        Map<String, Object> effectSummaryForChecks = restPayloadStage.result().effectSummaryForChecks();
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

    private Map<String, Object> applyOfficialOshiArtReactiveEffects(
        Long matchId,
        Long userId,
        Long opponentUserId,
        int turnNumber,
        Long attackerHolomemId,
        Long targetCardInstanceId,
        String attackerMainColor,
        AttackTargetHolomem targetHolomem,
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
        AttackTargetHolomem targetHolomem,
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
     * 結束目前回合並交棒給對手。
     * 會先驗證必要回合動作（抽牌、吶喊）是否完成，再重置狀態與建立對手 TURN_START 互動。
     */
    @Transactional
    public void endTurn(Long matchId, Long userId) {
        endTurn(matchId, userId, null, null);
    }

    @Transactional
    public void endTurn(Long matchId, Long userId, Integer requestedTurnNumber, String idempotencyKey) {
        EndTurnAction action = EndTurnAction.fromApi(
            matchId,
            userId,
            resolveRequestedTurnNumber(matchId, requestedTurnNumber),
            idempotencyKey
        );
        endTurnApplicationService.handle(action);
    }

    private int resolveRequestedTurnNumber(Long matchId, Integer requestedTurnNumber) {
        if (requestedTurnNumber != null && requestedTurnNumber > 0) {
            return requestedTurnNumber;
        }
        return loadRequestedTurnNumberSnapshot(matchId);
    }

    private int loadRequestedTurnNumberSnapshot(Long matchId) {
        if (matchId == null) {
            return 0;
        }
        Integer turnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        return turnNumber == null ? 0 : turnNumber;
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

    private void saveFinishedMatch(MatchEntity match) {
        touchUpdatedAt(match);
        matchRepository.saveAndFlush(match);
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
        List<Map<String, Object>> payloads = giftTriggerActionPayloadExtractor.extractTriggeredGiftPayloads(effectSummary);
        giftTriggerActionWriter.appendGiftTriggerActions(match.getId(), userId, turnNumber, payloads);
    }

    /**
     * 從 pending context 解析 Gift trigger list。
     */
    private List<Map<String, Object>> extractGiftTriggerContexts(JsonNode contextNode) {
        return pendingGiftTriggerContextExtractor.extractGiftTriggerContexts(contextNode);
    }

    /**
     * 從 pending context 解析 down event context。
     */
    private Map<String, Object> extractDownEventContext(JsonNode contextNode) {
        return pendingDownEventContextExtractor.extractDownEventContext(contextNode);
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

    private <T> T requireAttackStage(Object stageResult, Class<T> type, String stageName) {
        if (type.isInstance(stageResult)) {
            return type.cast(stageResult);
        }
        throw new IllegalStateException("attack application stage result 型別錯誤：" + stageName);
    }

    private class AttackHoloxRevealResolver implements AttackEffectFollowupService.HoloxRevealResolver {

        @Override
        public AttackEffectFollowupService.HoloxRevealResult resolve(AttackEffectFollowupContext context) {
            HoloxSlotRevealSummary summary = resolveHoloxSlotRevealSummary(
                context.matchId(),
                context.attackerUserId(),
                context.artName(),
                context.artEffectJsonText()
            );
            return new AttackEffectFollowupService.HoloxRevealResult(summary, summary.artBonus());
        }
    }

    private class AttackHbp02039SupportRecoveryResolver implements AttackEffectFollowupService.Hbp02039SupportRecoveryResolver {

        @Override
        public Map<String, Object> resolve(AttackEffectFollowupContext context, HoloxSlotRevealSummary holoxSlotRevealSummary) {
            return applyHbp02039HoloxSupportRecovery(
                context.matchId(),
                context.attackerUserId(),
                context.attackerCardId(),
                context.artName(),
                holoxSlotRevealSummary == null ? HoloxSlotRevealSummary.empty() : holoxSlotRevealSummary
            );
        }
    }

    private class AttackHbp02040LifeLossResolver implements AttackEffectFollowupService.Hbp02040LifeLossResolver {

        @Override
        public Map<String, Object> resolve(AttackEffectFollowupContext context, HoloxSlotRevealSummary holoxSlotRevealSummary) {
            return applyHbp02040HoloxLifeLoss(
                context.matchId(),
                context.attackerUserId(),
                context.defenderUserId(),
                context.turnNumber(),
                context.attackerHolomemId(),
                context.attackerCardId(),
                context.artName(),
                holoxSlotRevealSummary == null ? HoloxSlotRevealSummary.empty() : holoxSlotRevealSummary
            );
        }
    }

    private class AttackDefenderDamagePreventionResolver implements AttackEffectFollowupService.DamagePreventionResolver {

        @Override
        public Map<String, Object> resolve(AttackEffectDamagePreventionContext context) {
            return matchTriggeredCombatEffectService.resolveTriggeredGiftDamagePrevention(
                context.matchId(),
                context.defenderUserId(),
                context.attackerUserId(),
                context.attackerCardInstanceId(),
                context.effectiveTargetCardInstanceId(),
                context.turnNumber(),
                context.totalDamage()
            );
        }
    }

    private class AttackOfficialCardArtExtraResolver implements AttackEffectFollowupService.OfficialCardArtExtraResolver {

        @Override
        public Map<String, Object> resolve(AttackEffectPostDamageContext context) {
            return applyOfficialCardArtExtraEffects(
                context.matchId(),
                context.attackerUserId(),
                context.defenderUserId(),
                context.attackerHolomemId(),
                context.attackerCardId(),
                context.artName()
            );
        }
    }

    private class AttackOfficialOshiArtReactiveResolver implements AttackEffectFollowupService.OfficialOshiArtReactiveResolver {

        @Override
        public Map<String, Object> resolve(
            AttackEffectPostDamageContext context,
            Map<String, Object> officialCardArtExtraSummary
        ) {
            return applyOfficialOshiArtReactiveEffects(
                context.matchId(),
                context.attackerUserId(),
                context.defenderUserId(),
                context.turnNumber(),
                context.attackerHolomemId(),
                context.effectiveTargetCardInstanceId(),
                context.attackerMainColor(),
                context.targetHolomem(),
                context.artSummary(),
                officialCardArtExtraSummary
            );
        }
    }

    /**
     * 建立 Gift 觸發待確認摘要（不立即執行效果）。
     */
    private Map<String, Object> buildGiftTriggeredEffectDeferredSummary(List<Map<String, Object>> giftTriggeredEffects) {
        return giftTriggeredEffectDeferredSummaryBuilder.buildGiftTriggeredEffectDeferredSummary(giftTriggeredEffects);
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
        return effectPostTriggerPendingService.createEffectPostTriggerConfirmPendingInteractionIfNeeded(
            matchId,
            userId,
            originSourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            effectSummary,
            turnNumber
        );
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
        return followupInteractionPendingDecisionWriter.create(
            matchId,
            userId,
            sourceActionType,
            sourceCardInstanceId,
            sourceCardId,
            effectType,
            interaction
        );
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
        return followupInteractionContextBuilder.buildFollowupInteractionContext(
            userId,
            effectSummary,
            (viewerUserId, ownerUserId, cardInstanceId, fallbackZone, fallbackCardId) -> followupCardCandidateLoader.loadCardCandidateForDecision(
                matchId,
                viewerUserId,
                ownerUserId,
                cardInstanceId,
                fallbackZone,
                fallbackCardId
            )
        );
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
            if (attackDamageService.resolveArtDamage(asString(row.get("effect_json_text"))) > 0) {
                return row;
            }
        }
        return rows.get(0);
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
        return matchPayloadJsonService.toJson(payload);
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
    private MatchActionEntity appendAction(
        MatchEntity match,
        Long userId,
        String actionType,
        String payload,
        int turnNumber
    ) {
        return appendAction(match.getId(), userId, actionType, payload, turnNumber);
    }

    private MatchActionEntity appendAction(
        Long matchId,
        Long userId,
        String actionType,
        String payload,
        int turnNumber
    ) {
        MatchActionEntity action = new MatchActionEntity();
        action.setMatchId(matchId);
        action.setUserId(userId);
        action.setActionType(actionType);
        action.setPayload(payload);
        action.setTurnNumber(turnNumber);
        action.setActionOrder(matchActionRepository.findMaxActionOrderByTurn(matchId, turnNumber) + 1);
        action.setExecutedAt(LocalDateTime.now());
        return matchActionRepository.save(action);
    }

    /**
     * 更新 match.updatedAt 時戳。
     */
    private void touchUpdatedAt(MatchEntity match) {
        matchTimestampService.touchUpdatedAt(match);
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
