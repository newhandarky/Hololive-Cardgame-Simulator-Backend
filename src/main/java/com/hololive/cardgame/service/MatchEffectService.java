package com.hololive.cardgame.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.EffectResolver;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.game.action.ReduceLifeAction;
import com.hololive.cardgame.game.action.SendCheerAction;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftExecutionSummary;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MatchEffectService {

    private static final Pattern CHEER_COUNT_PATTERN = Pattern.compile("エール\\s*(\\d+)\\s*枚");
    private static final Pattern TAG_PATTERN = Pattern.compile(
        "#([\\p{L}\\p{N}_'\\-]+?)(?=(?:を|が|に|で|と|へ|や|も|、|。|\\s|$))"
    );
    private static final Pattern ARTS_MODIFIER_PATTERN = Pattern.compile("アーツ\\s*([+＋\\-−]\\s*\\d+)");
    private static final Pattern DICE_AT_LEAST_PATTERN = Pattern.compile("(\\d+)\\s*以上の時");
    private static final Pattern DICE_AT_MOST_PATTERN = Pattern.compile("(\\d+)\\s*以下の時");
    private static final Pattern DICE_ROLL_COUNT_PATTERN = Pattern.compile("サイコロ\\D*(\\d+)\\s*回");
    static final Pattern ATTACHED_SUPPORT_HP_PATTERN = Pattern.compile(
        "この(?:マスコット|ツール|ファン)が付いているホロメンのHP\\s*([+＋−-]\\s*\\d+)"
    );
    static final Pattern ATTACHED_SUPPORT_ARTS_PATTERN = Pattern.compile(
        "この(?:マスコット|ツール|ファン)が付いているホロメンのアーツ\\s*([+＋−-]\\s*\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_ART_COST_REDUCTION_PATTERN = Pattern.compile(
        "アーツ(?:[「『][^」』]+[」』])?に必要な\\s*(赤|青|緑|白|紫|黄|無色)\\s*[ー\\-−]\\s*(\\d+)"
    );
    private static final Pattern OPPONENT_STAGE_TAG_PRESENCE_PATTERN = Pattern.compile(
        "相手のステージに\\[([^\\]]+)]を持つホロメンがいる"
    );
    private static final Pattern INLINE_TAG_TOKEN_PATTERN = Pattern.compile(
        "#([\\p{L}\\p{N}_'\\-]+?)(?=(?:#|か|を|が|に|で|と|へ|や|も|、|。|\\]|\\s|$))"
    );
    private static final Pattern PASSIVE_GIFT_REFERENCED_OSHI_SKILL_PATTERN = Pattern.compile(
        "(SP)?推しスキル[「『]([^」』]+)[」』]を使っていた"
    );
    private static final Pattern PASSIVE_GIFT_REFERENCED_ART_NAME_PATTERN = Pattern.compile(
        "アーツ[「『]([^」』]+)[」』]"
    );
    private static final Pattern DOWN_EXTRA_LIFE_PATTERN = Pattern.compile("ライフを\\s*(\\d+)\\s*つ?減ら");
    private static final Pattern DOWN_EXTRA_LIFE_MINUS_PATTERN = Pattern.compile("ライフ\\s*[ー\\-−]\\s*(\\d+)");
    private static final Pattern PASSIVE_GIFT_SPECIAL_DAMAGE_BONUS_PATTERN = Pattern.compile("特殊ダメージ\\s*[+＋]\\s*(\\d+)");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DiceService diceService;
    private final GameActionExecutor gameActionExecutor;
    private final EffectTextParser effectTextParser;
    private final GiftTriggerMatcher giftTriggerMatcher;
    private final SearchCriteriaParser searchCriteriaParser;
    private final MatchEffectSearchService searchService;
    private final MatchGiftTriggerConditionService giftTriggerConditionService;
    private final MatchGiftTriggerContextService giftTriggerContextService;
    private final GiftTurnUsageReader giftTurnUsageReader;
    private final MatchCardSelectionRequestResolver cardSelectionRequestResolver;
    private final MatchCardSelectionProbeBuilder cardSelectionProbeBuilder;
    private final MatchCardSelectionExecutionService cardSelectionExecutionService;
    private final MatchDamageEffectiveHpResolverService damageEffectiveHpResolverService;
    private final MatchSpecialDamagePreventionResolverService specialDamagePreventionResolverService;
    private final MatchPassiveGiftHpChangePreventionResolverService hpChangePreventionResolverService;
    private final MatchLookEffectExecutionService lookEffectExecutionService;
    private final MatchDrawEffectExecutionService drawEffectExecutionService;
    private final MatchHolopowerMoveEffectExecutionService holopowerMoveEffectExecutionService;
    private final MatchRestEffectExecutionService restEffectExecutionService;
    private final MatchSwapCenterBackEffectExecutionService swapCenterBackEffectExecutionService;
    private final MatchCollabSwapEffectExecutionService collabSwapEffectExecutionService;
    private final MatchActionLockEffectExecutionService actionLockEffectExecutionService;
    private final MatchExtraBloomAllowanceEffectExecutionService extraBloomAllowanceEffectExecutionService;
    private final MatchBatonTouchCostModifierEffectExecutionService batonTouchCostModifierEffectExecutionService;
    private final MatchResultEffectExecutionService matchResultEffectExecutionService;
    private final MatchDiscardHandEffectExecutionService discardHandEffectExecutionService;
    private final MatchRevealToArchiveEffectExecutionService revealToArchiveEffectExecutionService;
    private final MatchSummonToStageEffectExecutionService summonToStageEffectExecutionService;
    private final MatchArchiveBloomEffectExecutionService archiveBloomEffectExecutionService;
    private final MatchCheerDeckReturnEffectExecutionService cheerDeckReturnEffectExecutionService;
    private final MatchDownEffectExecutionService downEffectExecutionService;
    private final MatchHealEffectExecutionService healEffectExecutionService;
    private final MatchMoveZoneEffectExecutionService moveZoneEffectExecutionService;
    private final MatchCheerRemovalEffectExecutionService cheerRemovalEffectExecutionService;
    private final MatchDamageEffectExecutionService damageEffectExecutionService;
    private final MatchBloomEffectDispatcher bloomEffectDispatcher;
    private final MatchCollabEffectDispatcher collabEffectDispatcher;

    /**
     * 效果結算服務建構子。
     */
    public MatchEffectService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        DiceService diceService,
        EffectResolver effectResolver,
        GameActionExecutor gameActionExecutor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.diceService = diceService;
        this.gameActionExecutor = gameActionExecutor;
        this.effectTextParser = new EffectTextParser(objectMapper);
        this.giftTriggerMatcher = new GiftTriggerMatcher();
        this.searchCriteriaParser = new SearchCriteriaParser(jdbcTemplate, effectTextParser);
        this.searchService = new MatchEffectSearchService(jdbcTemplate, effectTextParser);
        this.giftTriggerConditionService = new MatchGiftTriggerConditionService(
            jdbcTemplate,
            effectTextParser,
            giftTriggerMatcher,
            searchCriteriaParser
        );
        this.giftTriggerContextService = new MatchGiftTriggerContextService(
            jdbcTemplate,
            objectMapper,
            effectTextParser
        );
        this.giftTurnUsageReader = new GiftTurnUsageReader(jdbcTemplate);
        this.cardSelectionRequestResolver = new MatchCardSelectionRequestResolver(effectTextParser);
        MatchCardSelectionSearchCandidateProvider cardSelectionCandidateProvider =
            new MatchCardSelectionSearchCandidateProvider(searchService);
        this.cardSelectionProbeBuilder = new MatchCardSelectionProbeBuilder(
            effectTextParser,
            searchCriteriaParser,
            cardSelectionRequestResolver,
            cardSelectionCandidateProvider,
            this::shouldApplyByDice
        );
        MatchCardSelectionSummaryBuilder cardSelectionSummaryBuilder = new MatchCardSelectionSummaryBuilder();
        this.cardSelectionExecutionService = new MatchCardSelectionExecutionService(
            jdbcTemplate,
            effectTextParser,
            searchCriteriaParser,
            cardSelectionRequestResolver,
            cardSelectionProbeBuilder,
            cardSelectionSummaryBuilder,
            cardSelectionCandidateProvider,
            this::shouldApplyByDice
        );
        this.damageEffectiveHpResolverService = new MatchDamageEffectiveHpResolverService(
            jdbcTemplate,
            objectMapper,
            effectTextParser
        );
        this.specialDamagePreventionResolverService = new MatchSpecialDamagePreventionResolverService(
            jdbcTemplate,
            effectTextParser,
            giftTriggerConditionService
        );
        this.hpChangePreventionResolverService = new MatchPassiveGiftHpChangePreventionResolverService(
            jdbcTemplate,
            objectMapper,
            effectTextParser,
            giftTriggerMatcher,
            searchCriteriaParser
        );
        this.lookEffectExecutionService = new MatchLookEffectExecutionService(jdbcTemplate, effectTextParser);
        this.drawEffectExecutionService = new MatchDrawEffectExecutionService(
            jdbcTemplate,
            objectMapper,
            effectResolver,
            gameActionExecutor,
            effectTextParser,
            this::shouldApplyByDice
        );
        this.holopowerMoveEffectExecutionService = new MatchHolopowerMoveEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            cardSelectionRequestResolver,
            this::shouldApplyByDice
        );
        this.restEffectExecutionService = new MatchRestEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            this::shouldApplyByDice,
            this::resolveEffectTargetHolomemId,
            this::resolveOpponentUserId,
            this::resolveHolomemCardInstanceId
        );
        this.swapCenterBackEffectExecutionService = new MatchSwapCenterBackEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            this::shouldApplyByDice,
            this::resolveOpponentUserId,
            this::resolveCurrentTurnNumber,
            this::isActionLockActive,
            this::resolveHolomemCardInstanceId
        );
        this.collabSwapEffectExecutionService = new MatchCollabSwapEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            this::resolveTargetHolomemId,
            this::resolveHolomemCardInstanceId
        );
        this.actionLockEffectExecutionService = new MatchActionLockEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            this::resolveOpponentUserId,
            this::resolveCurrentTurnNumber,
            this::resolveEffectTargetHolomemId,
            this::resolveHolomemCardInstanceId
        );
        this.extraBloomAllowanceEffectExecutionService = new MatchExtraBloomAllowanceEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            giftTriggerMatcher,
            this::resolveCurrentTurnNumber,
            this::resolveRequiredOshiName,
            this::hasOpponentStageHolomemWithLevel,
            this::containsAnyName
        );
        this.batonTouchCostModifierEffectExecutionService = new MatchBatonTouchCostModifierEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            this::resolveEffectTargetHolomemId,
            this::resolveOpponentUserId,
            this::resolveCurrentTurnNumber,
            this::resolveHolomemOwner,
            this::resolveHolomemCardInstanceId
        );
        this.matchResultEffectExecutionService = new MatchResultEffectExecutionService(
            effectTextParser,
            this::resolveOpponentUserId
        );
        this.discardHandEffectExecutionService = new MatchDiscardHandEffectExecutionService(
            jdbcTemplate,
            objectMapper,
            effectTextParser,
            searchCriteriaParser,
            cardSelectionRequestResolver,
            searchService
        );
        this.revealToArchiveEffectExecutionService = new MatchRevealToArchiveEffectExecutionService(
            jdbcTemplate,
            searchCriteriaParser,
            cardSelectionRequestResolver,
            searchService
        );
        this.summonToStageEffectExecutionService = new MatchSummonToStageEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            searchCriteriaParser,
            cardSelectionRequestResolver,
            searchService
        );
        this.archiveBloomEffectExecutionService = new MatchArchiveBloomEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            searchCriteriaParser,
            searchService
        );
        this.cheerDeckReturnEffectExecutionService = new MatchCheerDeckReturnEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            cardSelectionRequestResolver
        );
        this.downEffectExecutionService = new MatchDownEffectExecutionService(
            jdbcTemplate,
            gameActionExecutor,
            effectTextParser,
            this::shouldApplyByDice,
            this::resolveOpponentUserId,
            this::resolveCurrentTurnNumber,
            this::archiveAttachedCheerCards,
            this::archiveAttachedSupportCards,
            this::archiveHolomemStackCards,
            this::executeDownEvent,
            this::loseLifeOnce
        );
        this.healEffectExecutionService = new MatchHealEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            this::resolveEffectTargetHolomemId,
            this::resolveHolomemOwner,
            this::resolveHolomemCardInstanceId,
            hpChangePreventionResolverService::isHpChangeBlockedByOpponentAbility
        );
        this.moveZoneEffectExecutionService = new MatchMoveZoneEffectExecutionService(
            jdbcTemplate,
            gameActionExecutor,
            effectTextParser,
            this::shouldApplyByDice,
            this::resolveEffectTargetHolomemId,
            this::resolveCurrentTurnNumber,
            this::isActionLockActive,
            this::resolveHolomemCardInstanceId
        );
        this.cheerRemovalEffectExecutionService = new MatchCheerRemovalEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            this::resolveEffectTargetHolomemId,
            this::resolveHolomemOwner,
            this::resolveHolomemCardInstanceId
        );
        this.damageEffectExecutionService = new MatchDamageEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            this::resolveEffectTargetHolomemId,
            this::resolveHolomemOwner,
            this::resolveCurrentTurnNumber,
            this::resolveActiveDamageModifier,
            hpChangePreventionResolverService::isHpChangeBlockedByOpponentAbility,
            this::resolveHolomemZone,
            specialDamagePreventionResolverService::isSpecialDamageImmunityActive,
            specialDamagePreventionResolverService::tryActivateHsd13012SpecialDamageImmunity,
            damageEffectiveHpResolverService::resolve,
            this::archiveAttachedCheerCards,
            this::archiveAttachedSupportCards,
            this::archiveHolomemStackCards,
            this::isDownWithoutLifeLoss,
            this::loseLifeOnce,
            this::executeDownEvent,
            this::extractLostLifeCardInstanceIds
        );
        this.bloomEffectDispatcher = new MatchBloomEffectDispatcher(
            cardSelectionExecutionService,
            lookEffectExecutionService,
            drawEffectExecutionService,
            holopowerMoveEffectExecutionService,
            restEffectExecutionService,
            swapCenterBackEffectExecutionService,
            collabSwapEffectExecutionService,
            actionLockEffectExecutionService,
            extraBloomAllowanceEffectExecutionService,
            batonTouchCostModifierEffectExecutionService,
            matchResultEffectExecutionService,
            discardHandEffectExecutionService,
            revealToArchiveEffectExecutionService,
            summonToStageEffectExecutionService,
            archiveBloomEffectExecutionService,
            cheerDeckReturnEffectExecutionService,
            downEffectExecutionService,
            healEffectExecutionService,
            moveZoneEffectExecutionService,
            cheerRemovalEffectExecutionService,
            this
        );
        this.collabEffectDispatcher = new MatchCollabEffectDispatcher(
            cardSelectionExecutionService,
            lookEffectExecutionService,
            drawEffectExecutionService,
            holopowerMoveEffectExecutionService,
            restEffectExecutionService,
            swapCenterBackEffectExecutionService,
            collabSwapEffectExecutionService,
            actionLockEffectExecutionService,
            extraBloomAllowanceEffectExecutionService,
            batonTouchCostModifierEffectExecutionService,
            matchResultEffectExecutionService,
            discardHandEffectExecutionService,
            revealToArchiveEffectExecutionService,
            summonToStageEffectExecutionService,
            archiveBloomEffectExecutionService,
            cheerDeckReturnEffectExecutionService,
            downEffectExecutionService,
            healEffectExecutionService,
            moveZoneEffectExecutionService,
            cheerRemovalEffectExecutionService,
            this
        );
    }

    /**
     * 支援卡效果總入口：解析 effectType 並依序執行，回傳完整摘要。
     */
    public Map<String, Object> applySupportEffect(
        Long matchId,
        Long userId,
        String effectType,
        String effectJson,
        String targetType,
        List<Long> selectedCardInstanceIds,
        Long targetHolomemCardInstanceId
    ) {
        return applySupportEffect(
            matchId,
            userId,
            effectType,
            effectJson,
            targetType,
            selectedCardInstanceIds,
            targetHolomemCardInstanceId,
            false
        );
    }

    /**
     * 支援卡效果總入口（可選擇延後 down event 到互動確認後再結算）。
     */
    public Map<String, Object> applySupportEffect(
        Long matchId,
        Long userId,
        String effectType,
        String effectJson,
        String targetType,
        List<Long> selectedCardInstanceIds,
        Long targetHolomemCardInstanceId,
        boolean deferDownEvent
    ) {
        JsonNode effectNode = effectTextParser.parseEffectJson(effectJson);
        JsonNode damageEffectNode = effectTextParser.withDeferDownEventFlag(effectNode, deferDownEvent);
        List<String> effectTypes = resolveEffectTypes(effectType, effectNode);
        List<Map<String, Object>> executed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<Map<String, Object>> skippedEffects = new ArrayList<>();

        for (String type : effectTypes) {
            try {
                switch (type) {
                    case "DRAW" -> executed.add(drawEffectExecutionService.executeDrawEffect(matchId, userId, type, effectNode));
                    case "SEARCH" -> executed.add(
                        cardSelectionExecutionService.executeSearchEffect(matchId, userId, type, effectNode, selectedCardInstanceIds)
                    );
                    case "RETURN_TO_HAND" -> executed.add(
                        cardSelectionExecutionService.executeReturnToHandEffect(matchId, userId, type, effectNode, selectedCardInstanceIds)
                    );
                    case "RETURN_TO_DECK_TOP" -> executed.add(
                        cardSelectionExecutionService.executeReturnToDeckTopEffect(matchId, userId, type, effectNode, selectedCardInstanceIds)
                    );
                    case "ADD_CHEER" -> executed.add(
                        executeAddCheerEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "DAMAGE" -> executed.add(
                        executeDamageEffect(
                            matchId,
                            userId,
                            type,
                            damageEffectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "HEAL" -> executed.add(
                        healEffectExecutionService.executeHealEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "REMOVE_CHEER" -> executed.add(
                        cheerRemovalEffectExecutionService.executeRemoveCheerEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "REATTACH" -> executed.add(
                        executeReattachEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "MOVE_ZONE" -> executed.add(
                        moveZoneEffectExecutionService.executeMoveZoneEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "SUMMON_TO_STAGE" -> executed.add(
                        summonToStageEffectExecutionService.executeSummonToStageEffect(matchId, userId, type, effectNode)
                    );
                    case "REVEAL_TO_ARCHIVE" -> executed.add(
                        revealToArchiveEffectExecutionService.executeRevealToArchiveEffect(matchId, userId, type, effectNode)
                    );
                    case "BLOOM_FROM_ARCHIVE" -> executed.add(
                        archiveBloomEffectExecutionService.executeBloomFromArchiveEffect(matchId, userId, type, effectNode)
                    );
                    case "RETURN_CHEER_TO_DECK_BOTTOM" -> executed.add(
                        cheerDeckReturnEffectExecutionService.executeReturnCheerToDeckBottomEffect(matchId, userId, type, effectNode)
                    );
                    case "DISCARD_HAND" -> executed.add(
                        discardHandEffectExecutionService.executeDiscardHandEffect(matchId, userId, type, effectNode)
                    );
                    case "REST" -> executed.add(
                        restEffectExecutionService.executeRestEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "SWAP_CENTER_BACK" -> executed.add(
                        swapCenterBackEffectExecutionService.executeSwapCenterBackEffect(matchId, userId, type, effectNode)
                    );
                    case "MOVE_TO_HOLOPOWER" -> executed.add(
                        holopowerMoveEffectExecutionService.executeMoveToHolopowerEffect(matchId, userId, type, effectNode)
                    );
                    case "DOWN_NO_LIFE" -> executed.add(
                        downEffectExecutionService.executeDownNoLifeEffect(matchId, userId, type, effectNode)
                    );
                    case "DOWN_EXTRA_LIFE" -> executed.add(
                        downEffectExecutionService.executeDownExtraLifeEffect(matchId, userId, type, effectNode)
                    );
                    case "BATON_TOUCH_COST_MODIFIER" -> executed.add(
                        batonTouchCostModifierEffectExecutionService.executeBatonTouchCostModifierEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "ACTION_LOCK" -> executed.add(
                        actionLockEffectExecutionService.executeActionLockEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "ALLOW_EXTRA_BLOOM" -> executed.add(
                        extraBloomAllowanceEffectExecutionService.executeAllowExtraBloomEffect(matchId, userId, type, effectNode)
                    );
                    case "LOOK_TOP_DECK" -> executed.add(
                        lookEffectExecutionService.executeLookTopDeckEffect(matchId, userId, type, effectNode)
                    );
                    case "LOOK_OPPONENT_HAND" -> executed.add(
                        lookEffectExecutionService.executeLookOpponentHandEffect(matchId, userId, type, effectNode)
                    );
                    case "LOOK_HOLOPOWER" -> executed.add(
                        lookEffectExecutionService.executeLookHolopowerEffect(matchId, userId, type, effectNode)
                    );
                    case "SWAP_WITH_COLLAB" -> executed.add(
                        collabSwapEffectExecutionService.executeSwapWithCollabEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "BUFF", "DEBUFF" -> executed.add(
                        executeBuffDebuffEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType
                        )
                    );
                    case "MATCH_RESULT", "WIN", "LOSE" -> executed.add(
                        matchResultEffectExecutionService.executeMatchResultEffect(matchId, userId, type, effectNode)
                    );
                    case "UNIMPLEMENTED" -> executed.add(
                        executeNoOpEffect(type, effectNode, "尚未落地，先保留 action 並不中斷流程")
                    );
                    default -> {
                        unsupported.add(type);
                        Map<String, Object> skipped = buildSkippedEffect(type, "UNSUPPORTED_EFFECT");
                        executed.add(skipped);
                        skippedEffects.add(skipped);
                    }
                }
            } catch (RuntimeException ex) {
                Map<String, Object> skipped = buildSkippedEffect(type, ex.getMessage());
                executed.add(skipped);
                skippedEffects.add(skipped);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requestedEffects", effectTypes);
        summary.put("executedEffects", executed);
        summary.put("unsupportedEffects", unsupported);
        summary.put("skippedEffects", skippedEffects);
        summary.put("partiallyResolved", !skippedEffects.isEmpty() || !unsupported.isEmpty());
        return summary;
    }

    /**
     * 建立支援卡的選牌決策計畫（若效果需要互動選牌）。
     */
    public SupportDecisionPlan buildSupportDecisionPlan(
        Long matchId,
        Long userId,
        String effectType,
        String effectJson
    ) {
        JsonNode effectNode = effectTextParser.parseEffectJson(effectJson);
        List<String> effectTypes = resolveEffectTypes(effectType, effectNode);
        for (String type : effectTypes) {
            MatchCardSelectionProbeBuilder.SelectionProbe probe = cardSelectionProbeBuilder.probeSelectionCandidates(
                matchId,
                userId,
                type,
                effectNode
            );
            if (probe == null || probe.candidates().isEmpty()) {
                continue;
            }
            int maxSelect = Math.min(probe.requestedCount(), probe.candidates().size());
            if (maxSelect <= 0 || probe.candidates().size() <= maxSelect) {
                continue;
            }
            return new SupportDecisionPlan(
                effectTextParser.normalizeEffectType(type),
                1,
                maxSelect,
                probe.candidates()
            );
        }
        return null;
    }

    /**
     * 執行單一 Gift 持有者所需的 effectType 並彙整結果。
     */
    GiftExecutionSummary resolveGiftTriggerExecution(
        Long matchId,
        Long userId,
        Long holderCardInstanceId,
        Long triggerTargetCardInstanceId,
        String giftText,
        boolean executeEffects
    ) {
        if (executeEffects) {
            return executeGiftEffectsForHolder(
                matchId,
                userId,
                holderCardInstanceId,
                triggerTargetCardInstanceId,
                giftText
            );
        }
        return previewGiftEffects(giftText);
    }

    MatchGiftTriggerOrchestrationService.GiftSelectionPreview resolveGiftSelectionPreview(
        Long matchId,
        Long userId,
        String effectType,
        String giftText,
        Map<String, Object> storedTriggerContext
    ) {
        ObjectNode giftNode = objectMapper.createObjectNode();
        giftNode.put("rawText", giftText);
        giftTriggerContextService.appendStoredGiftExecutionContext(giftNode, storedTriggerContext);
        MatchCardSelectionProbeBuilder.SelectionProbe probe = cardSelectionProbeBuilder.probeSelectionCandidates(
            matchId,
            userId,
            effectType,
            giftNode
        );
        if (probe == null || probe.candidates().isEmpty()) {
            return null;
        }
        return new MatchGiftTriggerOrchestrationService.GiftSelectionPreview(
            effectType,
            probe.requestedCount(),
            probe.candidates()
        );
    }

    private boolean matchesGiftTurnOwnershipCondition(Long matchId, Long userId, String giftText) {
        return giftTriggerConditionService.matchesTurnOwnershipCondition(matchId, userId, giftText);
    }

    private boolean matchesGiftLifeComparisonCondition(Long matchId, Long userId, String giftText) {
        return giftTriggerConditionService.matchesLifeComparisonCondition(matchId, userId, giftText);
    }

    private boolean matchesGiftHandCountCondition(Long matchId, Long userId, String giftText) {
        return giftTriggerConditionService.matchesHandCountCondition(matchId, userId, giftText);
    }

    GiftExecutionSummary executeGiftEffectsForHolder(
        Long matchId,
        Long userId,
        Long holderCardInstanceId,
        Long triggerTargetCardInstanceId,
        String giftText
    ) {
        return executeGiftEffectsForHolder(
            matchId,
            userId,
            holderCardInstanceId,
            triggerTargetCardInstanceId,
            giftText,
            null
        );
    }

    GiftExecutionSummary executeGiftEffectsForHolder(
        Long matchId,
        Long userId,
        Long holderCardInstanceId,
        Long triggerTargetCardInstanceId,
        String giftText,
        Map<String, Object> storedTriggerContext
    ) {
        if (isHbp06020SelfDownedGiftText(giftText)) {
            return executeHbp06020SelfDownedGiftEffects(matchId, userId, giftText);
        }
        ObjectNode giftNode = objectMapper.createObjectNode();
        giftNode.put("rawText", giftText);
        giftTriggerContextService.appendStoredGiftExecutionContext(giftNode, storedTriggerContext);
        int clauseSeparatorIndex = findClauseSeparator(giftText);
        List<String> costEffectTypes = clauseSeparatorIndex >= 0 ? inferBloomEffectTypes(extractCostClause(giftText)) : List.of();
        List<String> resolvedEffectTypes = clauseSeparatorIndex >= 0 ? inferBloomEffectTypes(extractResolvedEffectClause(giftText)) : List.of();
        boolean hasMeaningfulSequentialCost = hasMeaningfulSequentialCost(costEffectTypes);
        List<String> effectTypes;
        if (clauseSeparatorIndex >= 0 && hasMeaningfulSequentialCost) {
            effectTypes = mergeEffectTypes(costEffectTypes, resolvedEffectTypes);
        } else if (clauseSeparatorIndex >= 0 && !resolvedEffectTypes.isEmpty()) {
            // 不是所有 `：` 都代表「成本：效果」。
            //
            // 像 `HBP05-035` 這種 Gift 文案：
            // `...ダウンした時に使える：自分のデッキから...`
            //
            // 冒號前只是觸發敘述，沒有任何可支付成本。若這裡硬把前半句當成本段，
            // `inferBloomEffectTypes(...)` 只會得到 `UNIMPLEMENTED`，接著被誤判成
            // 「前置成本未支付」，導致真正的 SEARCH 永遠不會執行。
            //
            // 因此只有在冒號前確實解析出可執行的成本 effect 時，才走 sequential cost。
            // 否則直接以冒號後的主要效果段為準。
            effectTypes = resolvedEffectTypes;
        } else {
            effectTypes = inferBloomEffectTypes(giftText);
        }
        List<Map<String, Object>> executed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<Map<String, Object>> skippedEffects = new ArrayList<>();
        List<Map<String, Object>> costExecutions = new ArrayList<>();
        if (clauseSeparatorIndex >= 0 && hasMeaningfulSequentialCost) {
            for (String effectType : costEffectTypes) {
                executeGiftEffectSafely(
                    matchId,
                    userId,
                    holderCardInstanceId,
                    triggerTargetCardInstanceId,
                    giftNode,
                    effectType,
                    executed,
                    unsupported,
                    skippedEffects,
                    costExecutions
                );
            }
            if (!costEffectTypes.isEmpty() && !areSequentialCostEffectsSatisfied(costExecutions)) {
                for (String effectType : resolvedEffectTypes) {
                    Map<String, Object> skipped = buildSkippedEffect(effectType, "前置成本未支付");
                    executed.add(skipped);
                    skippedEffects.add(skipped);
                }
            } else {
                for (String effectType : resolvedEffectTypes) {
                    executeGiftEffectSafely(
                        matchId,
                        userId,
                        holderCardInstanceId,
                        triggerTargetCardInstanceId,
                        giftNode,
                        effectType,
                        executed,
                        unsupported,
                        skippedEffects,
                        null
                    );
                }
            }
        } else {
            for (String effectType : effectTypes) {
                executeGiftEffectSafely(
                    matchId,
                    userId,
                    holderCardInstanceId,
                    triggerTargetCardInstanceId,
                    giftNode,
                    effectType,
                    executed,
                    unsupported,
                    skippedEffects,
                    null
                );
            }
        }
        return new GiftExecutionSummary(effectTypes, executed, unsupported, skippedEffects);
    }

    private boolean isHbp06020SelfDownedGiftText(String giftText) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        return giftText.contains("相手のターンで、このホロメンがダウンした時")
            && giftText.contains("自分のデッキの上から2枚をアーカイブ")
            && giftText.contains("異なるカード名の#FLOW GLOWを持つホロメン1人につき")
            && giftText.contains("自分のデッキを1枚引く");
    }

    private GiftExecutionSummary executeHbp06020SelfDownedGiftEffects(
        Long matchId,
        Long userId,
        String giftText
    ) {
        ObjectNode archiveNode = objectMapper.createObjectNode();
        archiveNode.put("rawText", "自分のデッキの上から2枚をアーカイブする");
        archiveNode.put("value", 2);
        Map<String, Object> archiveSummary = revealToArchiveEffectExecutionService.executeRevealToArchiveEffect(
            matchId,
            userId,
            "REVEAL_TO_ARCHIVE",
            archiveNode
        );

        List<Map<String, Object>> executed = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        executed.add(archiveSummary);
        int archiveApplied = asInt(archiveSummary.get("archiveApplied"));
        int drawCount = archiveApplied > 0 ? countDistinctFlowGlowStageHolomemNames(matchId, userId) : 0;
        if (drawCount > 0) {
            ObjectNode drawNode = objectMapper.createObjectNode();
            drawNode.put("rawText", "自分のデッキを" + drawCount + "枚引く");
            drawNode.put("value", drawCount);
            Map<String, Object> drawSummary = drawEffectExecutionService.executeDrawEffect(matchId, userId, "DRAW", drawNode);
            executed.add(drawSummary);
            return new GiftExecutionSummary(
                List.of("REVEAL_TO_ARCHIVE", "DRAW"),
                executed,
                List.of(),
                skipped
            );
        }

        Map<String, Object> drawSkipped = buildSkippedEffect("DRAW", "條件未成立：沒有可計算的 #FLOW GLOW 異名目標");
        executed.add(drawSkipped);
        skipped.add(drawSkipped);
        return new GiftExecutionSummary(
            List.of("REVEAL_TO_ARCHIVE", "DRAW"),
            executed,
            List.of(),
            skipped
        );
    }

    private int countDistinctFlowGlowStageHolomemNames(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return 0;
        }
        Integer count = jdbcTemplate.query(
            """
            SELECT COUNT(DISTINCT c.name)
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER', 'COLLAB', 'BACK')
              AND EXISTS (
                    SELECT 1
                    FROM jsonb_array_elements_text(COALESCE(c.tags_json, '[]'::jsonb)) AS t(tag)
                    WHERE t.tag IN ('#FLOW GLOW', '#FLOW')
              )
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId
        );
        return count == null ? 0 : Math.max(count, 0);
    }

    /**
     * 判斷冒號前是否真的存在「需要先支付」的成本效果。
     *
     * <p>目前 `inferBloomEffectTypes(...)` 在完全看不懂的句段上，會保底回傳 `UNIMPLEMENTED`。
     * 這對一般效果偵測是可接受的，但若直接拿來當 sequential cost 判斷，就會把
     * `使える：`、`次の能力を得る：` 這類純敘述前半句誤認成成本段。
     *
     * <p>因此這裡要求冒號前至少要解析出一個「真正可執行」的 effect type，才視為有成本。
     */
    private boolean hasMeaningfulSequentialCost(List<String> costEffectTypes) {
        return MatchGiftExecutionHelper.hasMeaningfulSequentialCost(costEffectTypes);
    }

    /**
     * 以一致方式執行單一 Gift effect type，並把結果回填到各摘要集合。
     *
     * <p>這一層把「單一 effect 的 switch 分派」從主流程抽出，讓 `成本段` 與 `效果段` 可以共用同一套
     * 執行邏輯。否則只要一加入 `成本：效果` 的序列規則，就會把整段 switch 複製兩次，後續維護容易分叉。
     */
    private void executeGiftEffectSafely(
        Long matchId,
        Long userId,
        Long holderCardInstanceId,
        Long triggerTargetCardInstanceId,
        JsonNode giftNode,
        String effectType,
        List<Map<String, Object>> executed,
        List<String> unsupported,
        List<Map<String, Object>> skippedEffects,
        List<Map<String, Object>> costExecutions
    ) {
        try {
            Map<String, Object> summary = executeGiftEffectByType(
                matchId,
                userId,
                holderCardInstanceId,
                triggerTargetCardInstanceId,
                giftNode,
                effectType
            );
            if (summary != null) {
                executed.add(summary);
                if (costExecutions != null) {
                    costExecutions.add(summary);
                }
            }
        } catch (UnsupportedOperationException ex) {
            unsupported.add(effectType);
            Map<String, Object> skipped = buildSkippedEffect(effectType, "UNSUPPORTED_EFFECT");
            executed.add(skipped);
            skippedEffects.add(skipped);
            if (costExecutions != null) {
                costExecutions.add(skipped);
            }
        } catch (RuntimeException ex) {
            Map<String, Object> skipped = buildSkippedEffect(effectType, ex.getMessage());
            executed.add(skipped);
            skippedEffects.add(skipped);
            if (costExecutions != null) {
                costExecutions.add(skipped);
            }
        }
    }

    /**
     * 執行單一 Gift effect type 的實際分派。
     */
    private Map<String, Object> executeGiftEffectByType(
        Long matchId,
        Long userId,
        Long holderCardInstanceId,
        Long triggerTargetCardInstanceId,
        JsonNode giftNode,
        String effectType
    ) {
        String targetType = inferBloomTargetType(effectType);
        return switch (effectType) {
            case "DRAW" -> drawEffectExecutionService.executeDrawEffect(matchId, userId, effectType, giftNode);
            case "SEARCH" -> cardSelectionExecutionService.executeSearchEffect(matchId, userId, effectType, giftNode, null);
            case "REPLACE_ARCHIVE_WITH_HAND" -> executeReplaceArchiveWithHandEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                holderCardInstanceId
            );
            case "RETURN_TO_HAND" -> cardSelectionExecutionService.executeReturnToHandEffect(matchId, userId, effectType, giftNode, null);
            case "RETURN_TO_DECK_TOP" -> cardSelectionExecutionService.executeReturnToDeckTopEffect(matchId, userId, effectType, giftNode, null);
            case "ADD_CHEER" -> executeAddCheerEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId);
            case "DAMAGE" -> executeDamageEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                triggerTargetCardInstanceId
            );
            case "REATTACH" -> executeReattachEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId);
            case "SUMMON_TO_STAGE" -> summonToStageEffectExecutionService.executeSummonToStageEffect(
                matchId,
                userId,
                effectType,
                giftNode
            );
            case "REVEAL_TO_ARCHIVE" -> revealToArchiveEffectExecutionService.executeRevealToArchiveEffect(
                matchId,
                userId,
                effectType,
                giftNode
            );
            case "BLOOM_FROM_ARCHIVE" -> archiveBloomEffectExecutionService.executeBloomFromArchiveEffect(
                matchId,
                userId,
                effectType,
                giftNode
            );
            case "RETURN_CHEER_TO_DECK_BOTTOM" -> cheerDeckReturnEffectExecutionService.executeReturnCheerToDeckBottomEffect(
                matchId,
                userId,
                effectType,
                giftNode
            );
            case "DISCARD_HAND" -> discardHandEffectExecutionService.executeDiscardHandEffect(matchId, userId, effectType, giftNode);
            case "REST" -> restEffectExecutionService.executeRestEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                holderCardInstanceId
            );
            case "SWAP_CENTER_BACK" -> swapCenterBackEffectExecutionService.executeSwapCenterBackEffect(matchId, userId, effectType, giftNode);
            case "MOVE_TO_HOLOPOWER" -> holopowerMoveEffectExecutionService.executeMoveToHolopowerEffect(matchId, userId, effectType, giftNode);
            case "DOWN_NO_LIFE" -> downEffectExecutionService.executeDownNoLifeEffect(matchId, userId, effectType, giftNode);
            case "DOWN_EXTRA_LIFE" -> downEffectExecutionService.executeDownExtraLifeEffect(matchId, userId, effectType, giftNode);
            case "BATON_TOUCH_COST_MODIFIER" -> batonTouchCostModifierEffectExecutionService.executeBatonTouchCostModifierEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                holderCardInstanceId
            );
            case "ACTION_LOCK" -> actionLockEffectExecutionService.executeActionLockEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                holderCardInstanceId
            );
            case "ALLOW_EXTRA_BLOOM" -> extraBloomAllowanceEffectExecutionService.executeAllowExtraBloomEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                null,
                holderCardInstanceId
            );
            case "LOOK_TOP_DECK" -> lookEffectExecutionService.executeLookTopDeckEffect(matchId, userId, effectType, giftNode);
            case "LOOK_OPPONENT_HAND" -> lookEffectExecutionService.executeLookOpponentHandEffect(matchId, userId, effectType, giftNode);
            case "LOOK_HOLOPOWER" -> lookEffectExecutionService.executeLookHolopowerEffect(matchId, userId, effectType, giftNode);
            case "ARCHIVE_STACK_CARD" -> executeArchiveStackCardEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                holderCardInstanceId
            );
            case "SWAP_WITH_COLLAB" -> collabSwapEffectExecutionService.executeSwapWithCollabEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                holderCardInstanceId
            );
            case "HEAL" -> healEffectExecutionService.executeHealEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                holderCardInstanceId
            );
            case "BUFF", "DEBUFF" -> executeBuffDebuffEffect(matchId, userId, effectType, giftNode, targetType);
            case "MATCH_RESULT", "WIN", "LOSE" -> matchResultEffectExecutionService.executeMatchResultEffect(matchId, userId, effectType, giftNode);
            case "UNIMPLEMENTED" -> executeNoOpEffect(effectType, giftNode, "尚未支援的 GIFT 效果");
            default -> throw new UnsupportedOperationException("UNSUPPORTED_GIFT_EFFECT");
        };
    }

    /**
     * 判斷 `成本段` 是否真的支付成功。
     *
     * <p>只要成本段中的任一效果沒有真正生效，例如：
     *
     * <p>- `discardApplied = 0`
     * <p>- `removeApplied = 0`
     * <p>- `applied = false`
     *
     * <p>就視為後段效果不能繼續執行。
     */
    private boolean areSequentialCostEffectsSatisfied(List<Map<String, Object>> costExecutions) {
        return MatchGiftExecutionHelper.areSequentialCostEffectsSatisfied(costExecutions);
    }

    /**
     * 嘗試以共通欄位判斷單一 effect summary 是否有實際生效。
     */
    private boolean isEffectSummaryApplied(Map<String, Object> summary) {
        return MatchGiftExecutionHelper.isEffectSummaryApplied(summary);
    }

    private List<String> mergeEffectTypes(List<String> first, List<String> second) {
        return MatchGiftExecutionHelper.mergeEffectTypes(first, second);
    }

    /**
     * 僅解析 Gift effectType，不執行效果。
     */
    GiftExecutionSummary previewGiftEffects(String giftText) {
        List<String> effectTypes = inferBloomEffectTypes(giftText);
        return new GiftExecutionSummary(effectTypes, List.of(), List.of(), List.of());
    }

    /**
     * 在藝能傷害套用前，處理「受傷時觸發」的 Gift（例如 HBP01-027）。
     */
    Map<String, Object> resolveTriggeredGiftDamagePrevention(
        Long matchId,
        Long defendingUserId,
        Long attackingUserId,
        Long sourceCardInstanceId,
        Long targetCardInstanceId,
        int turnNumber,
        int incomingDamage
    ) {
        if (
            matchId == null
                || defendingUserId == null
                || attackingUserId == null
                || targetCardInstanceId == null
                || targetCardInstanceId <= 0
                || turnNumber <= 0
                || incomingDamage <= 0
        ) {
            return null;
        }

        Map<String, Object> targetHolomem = jdbcTemplate.query(
            """
            SELECT id, match_card_id, zone, current_level
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
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("zone", normalize(rs.getString("zone")));
                row.put("current_level", normalizeLevelType(rs.getString("current_level")));
                return row;
            },
            matchId,
            defendingUserId,
            targetCardInstanceId
        );
        if (targetHolomem == null || targetHolomem.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> holders = jdbcTemplate.queryForList(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   h.zone,
                   h.current_level,
                   m.passive_effect_json::text AS passive_text
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
            ORDER BY h.id
            """,
            matchId,
            defendingUserId
        );
        if (holders.isEmpty()) {
            return null;
        }

        for (Map<String, Object> holder : holders) {
            Long holderHolomemId = asLong(holder.get("holomem_id"));
            Long holderCardInstanceId = asLong(holder.get("match_card_id"));
            String holderZone = normalize(asText(holder.get("zone")));
            String holderLevel = normalizeLevelType(asText(holder.get("current_level")));
            String giftText = loadGiftEffectText(asText(holder.get("passive_text")));
            if (!StringUtils.hasText(giftText)) {
                continue;
            }
            boolean alwaysPreventOpponentDamage = giftText.contains("相手からダメージを受けない")
                || giftText.contains("相手からアーツダメージを受けない");
            if (!alwaysPreventOpponentDamage && !giftTriggerMatcher.matchesGiftTriggerType(giftText, "DAMAGE_RECEIVED")) {
                continue;
            }
            String normalizedGiftText = effectTextParser.normalizeDigits(giftText);
            if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(giftText, holderZone)) {
                continue;
            }
            if (
                normalizedGiftText.contains("ターンに1回")
                    && isGiftAlreadyUsedThisTurn(matchId, defendingUserId, turnNumber, holderHolomemId)
            ) {
                continue;
            }
            if (!matchesGiftTurnOwnershipCondition(matchId, defendingUserId, giftText)) {
                continue;
            }
            if (!matchesGiftLifeComparisonCondition(matchId, defendingUserId, giftText)) {
                continue;
            }
            if (!matchesGiftHandCountCondition(matchId, defendingUserId, giftText)) {
                continue;
            }
            if (!matchesGiftDamageReceivedCollabPresenceCondition(matchId, defendingUserId, attackingUserId, giftText)) {
                continue;
            }
            if (
                !matchesGiftDamageReceivedTargetCondition(
                    giftText,
                    asText(targetHolomem.get("zone")),
                    targetCardInstanceId,
                    holderCardInstanceId
                )
            ) {
                continue;
            }
            if (giftText.contains("相手からダメージを受ける時") && Objects.equals(defendingUserId, attackingUserId)) {
                continue;
            }
            if (giftText.contains("このホロメンが") && !Objects.equals(targetCardInstanceId, holderCardInstanceId)) {
                continue;
            }
            if (normalizedGiftText.contains("1stホロメンから") && !"FIRST".equals(asText(targetHolomem.get("current_level")))) {
                continue;
            }
            if (normalizedGiftText.contains("2ndホロメンから") && !"SECOND".equals(asText(targetHolomem.get("current_level")))) {
                continue;
            }
            if (normalizedGiftText.contains("Debutホロメンから") && !"DEBUT".equals(asText(targetHolomem.get("current_level")))) {
                continue;
            }

            Integer diceRoll = null;
            if (giftText.contains("サイコロ")) {
                diceRoll = diceService.rollD6();
            }
            boolean diceMatched = matchesGiftDamageReceivedDiceCondition(giftText, diceRoll);
            boolean prevented = alwaysPreventOpponentDamage || (diceMatched && giftText.contains("そのダメージを受けない"));

            Map<String, Object> executed = new LinkedHashMap<>();
            executed.put("effectType", "PREVENT_DAMAGE");
            executed.put("applied", prevented);
            executed.put("damageBefore", incomingDamage);
            executed.put("damageAfter", prevented ? 0 : incomingDamage);
            if (diceRoll != null) {
                executed.put("diceRoll", diceRoll);
                executed.put("diceMatched", diceMatched);
            }
            if (!prevented) {
                executed.put("skipped", true);
                executed.put("reason", "條件未成立：骰子結果不符");
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("triggerType", "DAMAGE_RECEIVED");
            summary.put("giftHolderHolomemId", holderHolomemId);
            summary.put("giftHolderCardInstanceId", holderCardInstanceId);
            summary.put("giftHolderCardId", asText(holder.get("card_id")));
            summary.put("giftHolderZone", holderZone);
            summary.put("sourceCardInstanceId", sourceCardInstanceId);
            summary.put("triggerTargetCardInstanceId", targetCardInstanceId);
            summary.put("rawText", giftText);
            summary.put("requestedEffects", List.of("PREVENT_DAMAGE"));
            summary.put("executedEffects", List.of(executed));
            summary.put("unsupportedEffects", List.of());
            summary.put("skippedEffects", prevented ? List.of() : List.of(executed));
            summary.put("incomingDamage", incomingDamage);
            summary.put("damageAfter", prevented ? 0 : incomingDamage);
            summary.put("applied", true);
            summary.put("preventedDamage", prevented);
            if (diceRoll != null) {
                summary.put("diceRoll", diceRoll);
                summary.put("diceMatched", diceMatched);
            }
            return summary;
        }
        return null;
    }

    private boolean matchesGiftDamageReceivedTargetCondition(
        String giftText,
        String targetZone,
        Long targetCardInstanceId,
        Long holderCardInstanceId
    ) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        if (giftText.contains("このホロメンは相手からダメージを受けない")) {
            return Objects.equals(targetCardInstanceId, holderCardInstanceId);
        }
        String normalizedTargetZone = normalize(targetZone);
        if (giftText.contains("このホロメンがダメージを受ける時")) {
            return Objects.equals(targetCardInstanceId, holderCardInstanceId);
        }
        if (giftText.contains("自分のホロメン全員は相手からアーツダメージを受けない")) {
            return true;
        }
        if (giftText.contains("自分のセンターホロメンがダメージを受ける時")) {
            return "CENTER".equals(normalizedTargetZone);
        }
        if (giftText.contains("自分のコラボホロメンがダメージを受ける時")) {
            return "COLLAB".equals(normalizedTargetZone);
        }
        if (giftText.contains("自分のバックホロメンがダメージを受ける時")) {
            return "BACK".equals(normalizedTargetZone);
        }
        return giftText.contains("自分のホロメンが相手からダメージを受ける時")
            || giftText.contains("自分のホロメンがダメージを受ける時");
    }

    private boolean matchesGiftDamageReceivedCollabPresenceCondition(
        Long matchId,
        Long defendingUserId,
        Long attackingUserId,
        String giftText
    ) {
        if (!StringUtils.hasText(giftText)) {
            return true;
        }
        if (!giftText.contains("自分のコラボホロメンがいて、相手のコラボホロメンがいないなら")) {
            return true;
        }
        int ownCollabCount = countHolomemsInZone(matchId, defendingUserId, "COLLAB");
        int opponentCollabCount = countHolomemsInZone(matchId, attackingUserId, "COLLAB");
        return ownCollabCount > 0 && opponentCollabCount == 0;
    }

    private boolean matchesGiftDamageReceivedDiceCondition(String giftText, Integer diceRoll) {
        if (!StringUtils.hasText(giftText)) {
            return false;
        }
        if (!giftText.contains("サイコロ")) {
            return true;
        }
        if (diceRoll == null || diceRoll <= 0) {
            return false;
        }
        String text = effectTextParser.normalizeDigits(giftText);
        if (text.contains("奇数の時")) {
            return diceRoll % 2 == 1;
        }
        if (text.contains("偶数の時")) {
            return diceRoll % 2 == 0;
        }
        Matcher atLeastMatcher = DICE_AT_LEAST_PATTERN.matcher(text);
        if (atLeastMatcher.find()) {
            try {
                return diceRoll >= Integer.parseInt(atLeastMatcher.group(1));
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        Matcher atMostMatcher = DICE_AT_MOST_PATTERN.matcher(text);
        if (atMostMatcher.find()) {
            try {
                return diceRoll <= Integer.parseInt(atMostMatcher.group(1));
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    /**
     * 描述常駐藝能加成的受益者。
     *
     * <p>目前只保留常駐 Gift 判斷真正需要的欄位，避免把完整 Holomem state 傳遞到每個 helper。
     */
    record StaticArtBonusTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        Set<String> tags,
        Set<String> opponentStageTags
    ) {}

    /**
     * 描述「藝能自己文字所提供的加成」受益者。
     *
     * <p>和中心位常駐 Gift 不同，這類加成直接寫在藝能文案內，常見模式是：
     *
     * <p>- `このホロメンのエール1枚につき、このアーツ+20`
     *
     * <p>- `自分のライフが3以下の時、このアーツ+70`
     *
     * <p>因此除了基本站位/等級/tag，還必須帶出：
     *
     * <p>- 實際附著 Cheer 數量
     * <p>- 目前玩家的 LIFE
     *
     * <p>才能在攻擊時計算像 `HSD13-007`、`HSD07-009` 這類條件加傷。
     */
    record ArtSelfBonusTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        Set<String> tags,
        int attachedCheerCount,
        int currentLife,
        String oshiCardName
    ) {}

    /**
     * 描述提供常駐 Gift 的 holder。
     */
    record PassiveGiftHolderContext(
        Long holomemId,
        String stageZone,
        String passiveEffectJsonText
    ) {}

    /**
     * 描述「常駐 Gift 藝能費用減免」的受益者。
     *
     * <p>這類文案目前已知會同時依賴：
     *
     * <p>- 受益者站位（例如 `センターホロメン`）
     * <p>- 受益者卡名（例如 `〈アーニャ・メルフィッサ〉`）
     * <p>- 受益者是否附著指定名稱的 support（例如 `〈古代武器〉が付いている`）
     *
     * <p>因此需要比一般 `アーツ+N` 多帶出卡名欄位。
     */
    record PassiveGiftArtCostReductionTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        String artName,
        Set<String> tags
    ) {}

    /**
     * Bloom 觸發效果入口（含條件判斷、執行結果摘要）。
     */
    Map<String, Object> applyBloomTriggeredEffects(
        Long matchId,
        Long userId,
        String bloomCardId,
        Long selfHolomemCardInstanceId
    ) {
        return applyBloomTriggeredEffects(matchId, userId, bloomCardId, selfHolomemCardInstanceId, null);
    }

    /**
     * Bloom 觸發效果入口（含來源等級條件）。
     */
    Map<String, Object> applyBloomTriggeredEffects(
        Long matchId,
        Long userId,
        String bloomCardId,
        Long selfHolomemCardInstanceId,
        String sourceLevelType
    ) {
        BloomEffectPlan bloomPlan = resolveBloomEffectPlan(
            bloomCardId,
            new BloomRuntimeContext(
                sourceLevelType,
                loadCollabRuntimeContext(matchId, userId, selfHolomemCardInstanceId)
            )
        );
        if (!bloomPlan.hasBloomEffect()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("hasBloomEffect", false);
            summary.put("requestedEffects", List.of());
            summary.put("executedEffects", List.of());
            summary.put("unsupportedEffects", List.of());
            summary.put("rawText", null);
            return summary;
        }

        List<String> effectTypes = bloomPlan.effectTypes();
        Integer diceRoll = bloomPlan.diceRoll();
        ObjectNode bloomEffectNode = mutableEffectNode(bloomPlan.effectNode());
        if (matchId != null) {
            bloomEffectNode.put("matchId", matchId);
        }
        if (userId != null) {
            bloomEffectNode.put("sourceUserId", userId);
        }
        if (selfHolomemCardInstanceId != null) {
            bloomEffectNode.put("sourceHolomemCardInstanceId", selfHolomemCardInstanceId);
        }
        String normalizedBloomCardId = normalize(bloomCardId);
        Integer oddRollCount = null;
        if (normalizedBloomCardId.startsWith("HBP04-059")) {
            DiceResolution resolution = resolveDiceResolution(bloomEffectNode);
            bloomEffectNode.put("diceRoll", resolution.chosenRoll());
            bloomEffectNode.set("diceRolls", objectMapper.valueToTree(resolution.rolls()));
            bloomEffectNode.put("diceRollCountApplied", resolution.rolls().size());
            oddRollCount = (int) resolution.rolls().stream().filter(roll -> roll % 2 == 1).count();
            bloomEffectNode.put("value", oddRollCount);
            bloomEffectNode.put("oddRollCount", oddRollCount);
            diceRoll = resolution.chosenRoll();
        }

        MatchBloomEffectDispatcher.BloomDispatchResult dispatchResult = bloomEffectDispatcher.execute(
            matchId,
            userId,
            selfHolomemCardInstanceId,
            normalizedBloomCardId,
            effectTypes,
            bloomEffectNode
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hasBloomEffect", true);
        summary.put("requestedEffects", effectTypes);
        summary.put("executedEffects", dispatchResult.executed());
        summary.put("unsupportedEffects", dispatchResult.unsupported());
        summary.put("skippedEffects", dispatchResult.skippedEffects());
        summary.put(
            "partiallyResolved",
            !dispatchResult.skippedEffects().isEmpty() || !dispatchResult.unsupported().isEmpty()
        );
        summary.put("rawText", bloomPlan.rawText());
        if ((diceRoll == null || diceRoll <= 0) && bloomEffectNode.has("diceRoll")) {
            int fromNode = bloomEffectNode.path("diceRoll").asInt(0);
            if (fromNode >= 1 && fromNode <= 6) {
                diceRoll = fromNode;
            }
        }
        if (diceRoll != null) {
            summary.put("diceRoll", diceRoll);
        }
        if (bloomEffectNode.has("diceRolls")) {
            summary.put("diceRolls", objectMapper.convertValue(bloomEffectNode.get("diceRolls"), new TypeReference<List<Integer>>() {}));
        }
        if (oddRollCount != null) {
            summary.put("oddRollCount", oddRollCount);
        }
        return summary;
    }

    /**
     * Collab 觸發效果入口（含條件判斷、執行結果摘要）。
     */
    Map<String, Object> applyCollabTriggeredEffects(
        Long matchId,
        Long userId,
        String collabCardId,
        Long selfHolomemCardInstanceId
    ) {
        CollabRuntimeContext runtimeContext = loadCollabRuntimeContext(
            matchId,
            userId,
            selfHolomemCardInstanceId
        );
        BloomEffectPlan collabPlan = resolveCollabEffectPlan(collabCardId, runtimeContext);
        if (!collabPlan.hasBloomEffect()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("hasCollabEffect", false);
            summary.put("requestedEffects", List.of());
            summary.put("executedEffects", List.of());
            summary.put("unsupportedEffects", List.of());
            summary.put("rawText", null);
            return summary;
        }

        List<String> effectTypes = collabPlan.effectTypes();
        List<Map<String, Object>> executed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<Map<String, Object>> skippedEffects = new ArrayList<>();
        Integer diceRoll = collabPlan.diceRoll();
        ObjectNode collabEffectNode = mutableEffectNode(collabPlan.effectNode());
        if (matchId != null) {
            collabEffectNode.put("matchId", matchId);
        }
        if (userId != null) {
            collabEffectNode.put("sourceUserId", userId);
        }
        if (selfHolomemCardInstanceId != null) {
            collabEffectNode.put("sourceHolomemCardInstanceId", selfHolomemCardInstanceId);
        }
        String normalizedCollabCardId = normalize(collabCardId);
        MatchCollabEffectDispatcher.CollabDispatchResult dispatchResult = collabEffectDispatcher.execute(
            matchId,
            userId,
            selfHolomemCardInstanceId,
            normalizedCollabCardId,
            effectTypes,
            collabEffectNode
        );
        executed.addAll(dispatchResult.executed());
        unsupported.addAll(dispatchResult.unsupported());
        skippedEffects.addAll(dispatchResult.skippedEffects());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hasCollabEffect", true);
        summary.put("requestedEffects", effectTypes);
        summary.put("executedEffects", executed);
        summary.put("unsupportedEffects", unsupported);
        summary.put("skippedEffects", skippedEffects);
        summary.put("partiallyResolved", !skippedEffects.isEmpty() || !unsupported.isEmpty());
        summary.put("rawText", collabPlan.rawText());
        if ((diceRoll == null || diceRoll <= 0) && collabEffectNode.has("diceRoll")) {
            int fromNode = collabEffectNode.path("diceRoll").asInt(0);
            if (fromNode >= 1 && fromNode <= 6) {
                diceRoll = fromNode;
            }
        }
        if (diceRoll != null) {
            summary.put("diceRoll", diceRoll);
        }
        return summary;
    }

    /**
     * Collab ADD_CHEER 目標解析：優先讀效果覆寫目標，否則回退到觸發卡本身。
     */
    Long resolveCollabAddCheerTargetCardInstanceId(JsonNode collabEffectNode, Long fallbackCardInstanceId) {
        Long override = readLong(
            collabEffectNode,
            "targetHolomemCardInstanceId",
            "targetCardInstanceId",
            "targetMatchCardInstanceId"
        );
        if (override != null && override > 0) {
            return override;
        }
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(collabEffectNode, "rawText", "rawEffect"));
        if (StringUtils.hasText(rawText) && rawText.contains("バックホロメン")) {
            return null;
        }
        return fallbackCardInstanceId;
    }

    /**
     * Collab MOVE_ZONE 目標側解析：可由 effectNode 覆寫，文案含「このホロメン」時預設 SELF。
     */
    String resolveCollabMoveTargetType(JsonNode collabEffectNode, String fallbackTargetType) {
        String override = effectTextParser.normalizeEffectType(readText(collabEffectNode, "moveTargetType", "move_target_type"));
        if (StringUtils.hasText(override)) {
            return override;
        }
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(collabEffectNode, "rawText", "rawEffect"));
        if (StringUtils.hasText(rawText) && rawText.contains("このホロメン")) {
            return "SELF";
        }
        return fallbackTargetType;
    }

    /**
     * Collab MOVE_ZONE 目標卡解析：可由 effectNode 覆寫，文案含「このホロメン」時預設為觸發卡本身。
     */
    Long resolveCollabMoveTargetCardInstanceId(JsonNode collabEffectNode, Long fallbackSelfCardInstanceId) {
        Long override = readLong(
            collabEffectNode,
            "moveTargetHolomemCardInstanceId",
            "moveTargetCardInstanceId",
            "moveTargetMatchCardInstanceId"
        );
        if (override != null && override > 0) {
            return override;
        }
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(collabEffectNode, "rawText", "rawEffect"));
        if (StringUtils.hasText(rawText) && rawText.contains("このホロメン")) {
            return fallbackSelfCardInstanceId;
        }
        return null;
    }

    /**
     * 執行重新附加效果（將符合條件卡移到目標 Holomem 下方）。
     */
    Map<String, Object> executeReattachEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        if (!rawText.contains("エール")) {
            return executeNoOpEffect(effectType, effectNode, "目前僅支援 Cheer 的付け/付け替え");
        }

        String normalizedTargetType = normalize(targetType);
        boolean opponentContext =
            isOpponentTargetType(normalizedTargetType)
                || rawText.contains("相手のステージ")
                || rawText.contains("相手のアーカイブ")
                || rawText.contains("相手のエールデッキ");
        Long sourceOwnerUserId = opponentContext ? resolveOpponentUserId(matchId, userId) : userId;
        if (sourceOwnerUserId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到可操作的玩家");
        }
        String effectiveTargetType = opponentContext ? "ENEMY" : targetType;
        Long holderHolomemId = resolveGiftEffectHolderHolomemId(
            matchId,
            sourceOwnerUserId,
            targetHolomemCardInstanceId,
            effectNode
        );
        Long targetHolomemId = resolvePreferredAddCheerTargetHolomemId(
            matchId,
            sourceOwnerUserId,
            effectiveTargetType,
            targetHolomemCardInstanceId,
            rawText,
            false,
            holderHolomemId
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("REATTACH 找不到目標 Holomen");
        }
        Long targetOwnerUserId = resolveHolomemOwner(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("REATTACH 結算失敗：找不到目標擁有者");
        }
        if (!targetOwnerUserId.equals(sourceOwnerUserId)) {
            targetHolomemId = resolveTargetHolomemId(matchId, sourceOwnerUserId, null);
            if (targetHolomemId == null) {
                throw new IllegalStateException("REATTACH 結算失敗：找不到可附加的目標 Holomen");
            }
            targetOwnerUserId = sourceOwnerUserId;
        }

        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "付け", 1);
        int moveCount = Math.max(requestedCount, 1);

        List<String> movedCheerCardIds = new ArrayList<>();
        List<Long> movedCheerRowIds = new ArrayList<>();
        String sourceMode;
        if (rawText.contains("アーカイブ")) {
            sourceMode = "ARCHIVE";
            for (int i = 0; i < moveCount; i++) {
                Map<String, Object> archivedCheer = findCheerCardFromZone(matchId, sourceOwnerUserId, "ARCHIVE");
                if (archivedCheer == null) {
                    break;
                }
                Long cardInstanceId = asLong(archivedCheer.get("id"));
                String cheerCardId = asText(archivedCheer.get("card_id"));
                if (cardInstanceId == null || !StringUtils.hasText(cheerCardId)) {
                    continue;
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
                      AND zone = 'ARCHIVE'
                    """,
                    cardInstanceId,
                    matchId,
                    sourceOwnerUserId
                );
                if (moved != 1) {
                    continue;
                }
                Long cheerRowId = jdbcTemplate.query(
                    """
                    INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    targetHolomemId,
                    cardInstanceId,
                    cheerCardId
                );
                movedCheerCardIds.add(cheerCardId);
                if (cheerRowId != null) {
                    movedCheerRowIds.add(cheerRowId);
                }
            }
        } else {
            sourceMode = "STAGE";
            boolean restrictToHolderCheer = rawText.contains("このホロメンのエール");
            if (restrictToHolderCheer) {
                List<Map<String, Object>> holderCheerRows = resolvePreferredReattachSourceRows(
                    matchId,
                    sourceOwnerUserId,
                    holderHolomemId,
                    effectNode
                );
                String holderCheerSourceMode = moveSpecificCheerRowsToHolomem(
                    matchId,
                    sourceOwnerUserId,
                    targetHolomemId,
                    holderCheerRows,
                    moveCount,
                    movedCheerCardIds,
                    movedCheerRowIds
                );
                if (StringUtils.hasText(holderCheerSourceMode)) {
                    sourceMode = holderCheerSourceMode;
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("effectType", effectType);
                summary.put("moveRequested", moveCount);
                summary.put("moveApplied", movedCheerCardIds.size());
                summary.put("targetHolomemId", targetHolomemId);
                summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
                summary.put("movedCheerCardIds", movedCheerCardIds);
                summary.put("movedCheerRowIds", movedCheerRowIds);
                summary.put("sourceMode", StringUtils.hasText(sourceMode) ? sourceMode : "HOLDER_CHEER");
                return summary;
            }
            List<Map<String, Object>> attachedRows = jdbcTemplate.queryForList(
                """
                SELECT c.id AS cheer_row_id,
                       c.match_card_id,
                       c.cheer_card_id,
                       c.match_holomem_id
                FROM match_holomem_cheers c
                JOIN match_holomems h ON h.id = c.match_holomem_id
                WHERE h.match_id = ?
                  AND h.owner_user_id = ?
                ORDER BY CASE WHEN c.match_holomem_id = ? THEN 1 ELSE 0 END, c.id
                LIMIT ?
                """,
                matchId,
                sourceOwnerUserId,
                targetHolomemId,
                moveCount * 2
            );
            for (Map<String, Object> row : attachedRows) {
                if (movedCheerCardIds.size() >= moveCount) {
                    break;
                }
                Long cheerRowId = asLong(row.get("cheer_row_id"));
                Long cheerCardInstanceId = asLong(row.get("match_card_id"));
                Long fromHolomemId = asLong(row.get("match_holomem_id"));
                String cheerCardId = asText(row.get("cheer_card_id"));
                if (cheerRowId == null || !StringUtils.hasText(cheerCardId)) {
                    continue;
                }
                if (targetHolomemId.equals(fromHolomemId)) {
                    continue;
                }
                int deleted = jdbcTemplate.update(
                    "DELETE FROM match_holomem_cheers WHERE id = ?",
                    cheerRowId
                );
                if (deleted != 1) {
                    continue;
                }
                Long newCheerRowId = jdbcTemplate.query(
                    """
                    INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    targetHolomemId,
                    cheerCardInstanceId,
                    cheerCardId
                );
                movedCheerCardIds.add(cheerCardId);
                if (newCheerRowId != null) {
                    movedCheerRowIds.add(newCheerRowId);
                }
            }
            if (movedCheerCardIds.isEmpty() && rawText.contains("エールデッキ")) {
                sourceMode = "CHEER_DECK";
                for (int i = 0; i < moveCount; i++) {
                    Map<String, Object> cheerDeckTop = findCheerCardFromZone(matchId, sourceOwnerUserId, "CHEER_DECK");
                    if (cheerDeckTop == null) {
                        break;
                    }
                    Long cardInstanceId = asLong(cheerDeckTop.get("id"));
                    String cheerCardId = asText(cheerDeckTop.get("card_id"));
                    if (cardInstanceId == null || !StringUtils.hasText(cheerCardId)) {
                        continue;
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
                          AND zone = 'CHEER_DECK'
                        """,
                        cardInstanceId,
                        matchId,
                        sourceOwnerUserId
                    );
                    if (moved != 1) {
                        continue;
                    }
                    Long newCheerRowId = jdbcTemplate.query(
                        """
                        INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                        VALUES (?, ?, ?, FALSE)
                        RETURNING id
                        """,
                        rs -> rs.next() ? rs.getLong("id") : null,
                        targetHolomemId,
                        cardInstanceId,
                        cheerCardId
                    );
                    movedCheerCardIds.add(cheerCardId);
                    if (newCheerRowId != null) {
                        movedCheerRowIds.add(newCheerRowId);
                    }
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("moveRequested", moveCount);
        summary.put("moveApplied", movedCheerCardIds.size());
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("movedCheerCardIds", movedCheerCardIds);
        summary.put("movedCheerRowIds", movedCheerRowIds);
        summary.put("sourceMode", sourceMode);
        return summary;
    }

    /**
     * 執行「將已公開且本來要進 Archive 的支援卡改為回手」效果。
     *
     * <p>目前用於 `HBP02-039`。公開本體在 `ATTACK_ART` 的 `holoxReveal` payload，這裡只負責把
     * 本次公開進 Archive 的支援卡 1 張改到手牌。
     */
    private Map<String, Object> executeReplaceArchiveWithHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long holderCardInstanceId
    ) {
        List<Long> archivedSupportCardInstanceIds = loadLatestHoloxArchivedSupportCardInstanceIds(
            matchId,
            userId,
            holderCardInstanceId
        );
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("candidateCardInstanceIds", archivedSupportCardInstanceIds);
        if (archivedSupportCardInstanceIds.isEmpty()) {
            summary.put("applied", false);
            summary.put("reason", "本次公開沒有支援卡可改為回手");
            return summary;
        }

        Long movedCardInstanceId = null;
        String movedCardId = null;
        int nextHandOrder = nextZoneOrder(matchId, userId, "HAND");
        for (Long candidateCardInstanceId : archivedSupportCardInstanceIds) {
            if (candidateCardInstanceId == null || candidateCardInstanceId <= 0) {
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
                candidateCardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                continue;
            }
            movedCardInstanceId = candidateCardInstanceId;
            movedCardId = jdbcTemplate.query(
                "SELECT card_id FROM match_cards WHERE id = ?",
                rs -> rs.next() ? rs.getString("card_id") : null,
                candidateCardInstanceId
            );
            break;
        }

        if (movedCardInstanceId == null) {
            summary.put("applied", false);
            summary.put("reason", "找不到可從 Archive 改為回手的支援卡");
            return summary;
        }

        summary.put("applied", true);
        summary.put("movedCardInstanceId", movedCardInstanceId);
        summary.put("movedCardId", movedCardId);
        summary.put("movedCount", 1);
        return summary;
    }

    private List<Long> loadLatestHoloxArchivedSupportCardInstanceIds(
        Long matchId,
        Long userId,
        Long holderCardInstanceId
    ) {
        if (matchId == null || userId == null || holderCardInstanceId == null || holderCardInstanceId <= 0) {
            return List.of();
        }
        String payloadText = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'ATTACK_ART'
              AND payload ->> 'attackerCardInstanceId' = ?
              AND payload ->> 'artName' = 'ホロックスロット'
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("payload") : null,
            matchId,
            userId,
            holderCardInstanceId.toString()
        );
        JsonNode payloadNode = effectTextParser.parseEffectJson(payloadText);
        if (payloadNode == null || payloadNode.isNull()) {
            return List.of();
        }
        JsonNode holoxRevealNode = payloadNode.get("holoxReveal");
        if (holoxRevealNode == null || holoxRevealNode.isNull()) {
            return List.of();
        }
        return toLongList(holoxRevealNode.get("archivedSupportCardInstanceIds"));
    }

    /**
     * 套用バトンタッチ費用修正，寫入當回合效果表供後續行為讀取。
     */
    Map<String, Object> executeBatonTouchCostModifierEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        return batonTouchCostModifierEffectExecutionService.executeBatonTouchCostModifierEffect(
            matchId,
            userId,
            effectType,
            effectNode,
            targetType,
            targetHolomemCardInstanceId
        );
    }

    /**
     * 設定本回合額外 Bloom 許可效果。
     */
    Map<String, Object> executeAllowExtraBloomEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        return extraBloomAllowanceEffectExecutionService.executeAllowExtraBloomEffect(
            matchId,
            userId,
            effectType,
            effectNode
        );
    }

    /**
     * 設定本回合額外 Bloom 許可效果。
     */
    Map<String, Object> executeAllowExtraBloomEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long preferredTargetHolomemId,
        Long holderCardInstanceId
    ) {
        return extraBloomAllowanceEffectExecutionService.executeAllowExtraBloomEffect(
            matchId,
            userId,
            effectType,
            effectNode,
            preferredTargetHolomemId,
            holderCardInstanceId
        );
    }

    /**
     * 從額外 Bloom 文案中擷取推し名稱限制。
     */
    private String resolveRequiredOshiName(String rawText) {
        if (!StringUtils.hasText(rawText) || !rawText.contains("推しホロメン")) {
            return null;
        }
        Matcher matcher = Pattern.compile("推しホロメンが〈([^〉]+)〉").matcher(rawText);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * 判斷對手場上是否存在指定等級的 Holomem。
     */
    private boolean hasOpponentStageHolomemWithLevel(Long matchId, Long userId, String levelType) {
        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        if (opponentUserId == null || !StringUtils.hasText(levelType)) {
            return false;
        }
        Integer count = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB', 'BACK')
              AND current_level = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            opponentUserId,
            levelType
        );
        return count != null && count > 0;
    }

    /**
     * 執行附加 cheer 效果，包含目標解析與來源區挑選。
     */
    Map<String, Object> executeAddCheerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        String addCheerEffectClause = extractResolvedEffectClause(rawText);
        boolean preferSelfBackTarget =
            !isOpponentTargetType(normalize(targetType))
                && StringUtils.hasText(addCheerEffectClause)
                && addCheerEffectClause.contains("バックホロメン");

        Long targetHolomemId = resolvePreferredAddCheerTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            addCheerEffectClause,
            preferSelfBackTarget,
            null
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("ADD_CHEER 需要指定可用的我方 Holomen");
        }
        int requestedCount = resolveCheerCount(effectNode, 1);
        int attachCount = Math.max(requestedCount, 1);

        List<Long> attachedCardInstanceIds = new ArrayList<>();
        List<String> sourceZones = new ArrayList<>();
        for (int i = 0; i < attachCount; i++) {
            Map<String, Object> source = resolvePreferredAddCheerSource(matchId, userId, addCheerEffectClause);
            if (source == null) {
                break;
            }
            Long cardInstanceId = asLong(source.get("id"));
            String sourceZone = normalize(source.get("zone"));
            String cardId = asText(source.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            EffectContext actionContext = new EffectContext(
                matchId,
                userId,
                resolveCurrentTurnNumber(matchId),
                effectType,
                cardInstanceId,
                cardId
            );
            SendCheerAction sendCheerAction = new SendCheerAction(cardInstanceId, targetHolomemId, effectType);
            List<ActionResult> actionResults = gameActionExecutor.execute(actionContext, List.of(sendCheerAction));
            if (!actionResults.isEmpty() && actionResults.get(0).success()) {
                attachedCardInstanceIds.add(cardInstanceId);
                sourceZones.add(sourceZone);
                continue;
            }

            // fallback: preserve previous behavior when pipeline fails unexpectedly
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
                cardInstanceId,
                matchId,
                userId
            );
            if (moved == 1) {
                jdbcTemplate.update(
                    """
                    INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, ?, FALSE)
                    """,
                    targetHolomemId,
                    cardInstanceId,
                    cardId
                );
                attachedCardInstanceIds.add(cardInstanceId);
                sourceZones.add(sourceZone);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("attachRequested", attachCount);
        summary.put("attachApplied", attachedCardInstanceIds.size());
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("attachedCheerCardInstanceIds", attachedCardInstanceIds);
        summary.put("sourceZones", sourceZones);
        return summary;
    }

    /**
     * ADD_CHEER 會混到兩種不同需求：
     * 1. 純粹把某張可用 Cheer 貼到預設目標。
     * 2. 文案明確限制「只能從 Archive」或「只能貼到帶指定 tag 的 Holomem」。
     *
     * 這裡先把「不需要額外互動、可由文案穩定推斷」的條件集中處理，
     * 讓 Gift / Bloom / Collab 共用同一套 deterministic 選目標規則。
     */
    private Long resolvePreferredAddCheerTargetHolomemId(
        Long matchId,
        Long userId,
        String targetType,
        Long targetHolomemCardInstanceId,
        String rawText,
        boolean preferSelfBackTarget,
        Long excludedHolomemId
    ) {
        String targetClause = extractAddCheerTargetClause(rawText);
        boolean explicitAnyOwnHolomemTarget = targetClause.contains("自分のホロメン");
        boolean excludeCurrentHolder = targetClause.contains("他の") || targetClause.contains("以外");
        String requiredTag = searchCriteriaParser.resolveTagFromKnownTags(targetClause);
        String requiredZone = resolveRequiredAddCheerTargetZone(targetClause, preferSelfBackTarget);
        String requiredLevelType = resolveRequiredAddCheerTargetLevelType(targetClause);
        String requiredNameContains = resolveTargetNameContains(targetClause);

        // 只要文案沒有額外限制，就維持既有 targetType 解析邏輯，避免改動面過大。
        if (
            !excludeCurrentHolder &&
            !StringUtils.hasText(requiredTag) &&
            !StringUtils.hasText(requiredZone) &&
            !StringUtils.hasText(requiredLevelType) &&
            !StringUtils.hasText(requiredNameContains)
        ) {
            Long resolvedTarget = resolveEffectTargetHolomemId(
                matchId,
                userId,
                targetType,
                targetHolomemCardInstanceId,
                false
            );
            if (resolvedTarget != null) {
                return resolvedTarget;
            }
            if (explicitAnyOwnHolomemTarget) {
                return findPreferredOwnedStageHolomemId(matchId, userId, "", "", "", "", null);
            }
            return null;
        }

        Long restrictedTarget = findPreferredOwnedStageHolomemId(
            matchId,
            userId,
            requiredZone,
            requiredLevelType,
            requiredNameContains,
            requiredTag,
            excludeCurrentHolder ? excludedHolomemId : null
        );
        if (restrictedTarget != null) {
            return restrictedTarget;
        }

        // 有些效果文案同時帶 tag/zone 條件，找不到符合者時應視為不能執行，
        // 不能回退成隨便貼到中心，否則會把「限定目標」做成「任意目標」。
        return null;
    }

    /**
     * 依文案限制選出優先的自家場上 Holomem。
     *
     * <p>排序固定為 `CENTER -> COLLAB -> BACK`，讓沒有互動 UI 的自動結算仍保持 deterministic。
     */
    private Long findPreferredOwnedStageHolomemId(
        Long matchId,
        Long userId,
        String requiredZone,
        String requiredLevelType,
        String requiredNameContains,
        String requiredTag,
        Long excludedHolomemId
    ) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            """
            SELECT h.id
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
            """
        );
        args.add(matchId);
        args.add(userId);

        if (StringUtils.hasText(requiredZone)) {
            sql.append("\n  AND h.zone = ?");
            args.add(requiredZone);
        }
        if (StringUtils.hasText(requiredLevelType)) {
            sql.append("\n  AND m.level_type = ?");
            args.add(requiredLevelType);
        }
        if (StringUtils.hasText(requiredNameContains)) {
            sql.append("\n  AND c.name ILIKE '%' || ? || '%'");
            args.add(requiredNameContains);
        }
        if (StringUtils.hasText(requiredTag)) {
            sql.append("\n  AND c.tags_json @> to_jsonb(ARRAY[?]::text[])");
            args.add(requiredTag);
        }
        if (excludedHolomemId != null && excludedHolomemId > 0) {
            sql.append("\n  AND h.id <> ?");
            args.add(excludedHolomemId);
        }

        sql.append(
            """

            ORDER BY CASE h.zone
                        WHEN 'CENTER' THEN 1
                        WHEN 'COLLAB' THEN 2
                        WHEN 'BACK' THEN 3
                        ELSE 9
                     END,
                     h.id
            LIMIT 1
            """
        );

        return jdbcTemplate.query(sql.toString(), rs -> rs.next() ? rs.getLong("id") : null, args.toArray());
    }

    private Long resolveGiftEffectHolderHolomemId(
        Long matchId,
        Long ownerUserId,
        Long holderCardInstanceId,
        JsonNode effectNode
    ) {
        Long storedHolomemId = null;
        if (effectNode != null && !effectNode.isNull()) {
            JsonNode storedHolomemNode = effectNode.get("giftHolderHolomemId");
            if (storedHolomemNode != null && !storedHolomemNode.isNull()) {
                storedHolomemId = storedHolomemNode.isNumber()
                    ? storedHolomemNode.longValue()
                    : asLong(storedHolomemNode.asText());
            }
        }
        if (storedHolomemId != null && storedHolomemId > 0) {
            return storedHolomemId;
        }
        return resolveTargetHolomemId(matchId, ownerUserId, holderCardInstanceId);
    }

    private List<Map<String, Object>> resolvePreferredReattachSourceRows(
        Long matchId,
        Long ownerUserId,
        Long holderHolomemId,
        JsonNode effectNode
    ) {
        List<Long> storedCheerCardInstanceIds = extractEffectNodeLongList(effectNode, "giftHolderAttachedCheerCardInstanceIds");
        if (!storedCheerCardInstanceIds.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Long storedCheerCardInstanceId : storedCheerCardInstanceIds) {
                if (storedCheerCardInstanceId == null || storedCheerCardInstanceId <= 0) {
                    continue;
                }
                Map<String, Object> row = jdbcTemplate.query(
                    """
                    SELECT c.id AS cheer_row_id,
                           mc.id AS match_card_id,
                           mc.card_id AS cheer_card_id,
                           c.match_holomem_id,
                           mc.zone
                    FROM match_cards mc
                    LEFT JOIN match_holomem_cheers c ON c.match_card_id = mc.id
                    WHERE mc.match_id = ?
                      AND mc.owner_user_id = ?
                      AND mc.id = ?
                    LIMIT 1
                    """,
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("cheer_row_id", asLong(rs.getObject("cheer_row_id")));
                        result.put("match_card_id", asLong(rs.getObject("match_card_id")));
                        result.put("cheer_card_id", rs.getString("cheer_card_id"));
                        result.put("match_holomem_id", asLong(rs.getObject("match_holomem_id")));
                        result.put("zone", rs.getString("zone"));
                        return result;
                    },
                    matchId,
                    ownerUserId,
                    storedCheerCardInstanceId
                );
                if (row != null && !row.isEmpty()) {
                    rows.add(row);
                }
            }
            return rows;
        }
        if (holderHolomemId == null || holderHolomemId <= 0) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
            """
            SELECT c.id AS cheer_row_id,
                   mc.id AS match_card_id,
                   mc.card_id AS cheer_card_id,
                   c.match_holomem_id,
                   mc.zone
            FROM match_holomem_cheers c
            JOIN match_cards mc ON mc.id = c.match_card_id
            WHERE c.match_holomem_id = ?
            ORDER BY c.id
            """,
            holderHolomemId
        );
    }

    private String moveSpecificCheerRowsToHolomem(
        Long matchId,
        Long ownerUserId,
        Long targetHolomemId,
        List<Map<String, Object>> candidateRows,
        int moveCount,
        List<String> movedCheerCardIds,
        List<Long> movedCheerRowIds
    ) {
        String sourceMode = null;
        if (candidateRows == null || candidateRows.isEmpty()) {
            return sourceMode;
        }
        for (Map<String, Object> row : candidateRows) {
            if (movedCheerCardIds.size() >= moveCount) {
                break;
            }
            Long cheerCardInstanceId = asLong(row.get("match_card_id"));
            String cheerCardId = asText(row.get("cheer_card_id"));
            String currentZone = normalize(asText(row.get("zone")));
            if (cheerCardInstanceId == null || cheerCardInstanceId <= 0 || !StringUtils.hasText(cheerCardId)) {
                continue;
            }
            if ("ARCHIVE".equals(currentZone)) {
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
                      AND zone = 'ARCHIVE'
                    """,
                    cheerCardInstanceId,
                    matchId,
                    ownerUserId
                );
                if (moved != 1) {
                    continue;
                }
                Long newCheerRowId = jdbcTemplate.query(
                    """
                    INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    targetHolomemId,
                    cheerCardInstanceId,
                    cheerCardId
                );
                movedCheerCardIds.add(cheerCardId);
                if (newCheerRowId != null) {
                    movedCheerRowIds.add(newCheerRowId);
                }
                sourceMode = mergeSourceMode(sourceMode, "ARCHIVE");
                continue;
            }
            if (!"STAGE".equals(currentZone)) {
                continue;
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
            Long newCheerRowId = jdbcTemplate.query(
                """
                INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
                VALUES (?, ?, ?, FALSE)
                RETURNING id
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                targetHolomemId,
                cheerCardInstanceId,
                cheerCardId
            );
            movedCheerCardIds.add(cheerCardId);
            if (newCheerRowId != null) {
                movedCheerRowIds.add(newCheerRowId);
            }
            sourceMode = mergeSourceMode(sourceMode, "STAGE");
        }
        return sourceMode;
    }

    private String mergeSourceMode(String current, String next) {
        if (!StringUtils.hasText(next)) {
            return current;
        }
        if (!StringUtils.hasText(current)) {
            return next;
        }
        if (current.equals(next)) {
            return current;
        }
        return "MIXED";
    }

    private List<Long> extractEffectNodeLongList(JsonNode effectNode, String fieldName) {
        return MatchEffectValueHelper.extractEffectNodeLongList(effectNode, fieldName);
    }

    private List<Long> toLongList(Object value) {
        return MatchEffectValueHelper.toLongList(value);
    }

    private List<String> toTextList(Object value) {
        return MatchEffectValueHelper.toTextList(value);
    }

    /**
     * 從文案推斷 ADD_CHEER 的來源區。
     *
     * 目前先補齊專案裡最常見且已經有官方卡需求的兩種來源：
     * - アーカイブのエール
     * - エールデッキの上から
     *
     * 若文案沒有明示，仍保留原本的 CHEER_DECK > ARCHIVE > HAND fallback。
     */
    private Map<String, Object> resolvePreferredAddCheerSource(Long matchId, Long userId, String rawText) {
        String sourceClause = extractAddCheerSourceClause(rawText);
        SearchCriteria sourceCriteria = resolveSearchCriteriaFromRawText(sourceClause);

        if (StringUtils.hasText(sourceClause) && sourceClause.contains("アーカイブの")) {
            return findCheerCardFromZone(matchId, userId, "ARCHIVE", sourceCriteria);
        }
        if (StringUtils.hasText(sourceClause) && sourceClause.contains("エールデッキ")) {
            return findCheerCardFromZone(matchId, userId, "CHEER_DECK", sourceCriteria);
        }
        return findAttachableCheerCard(matchId, userId);
    }

    /**
     * 由文案判斷 ADD_CHEER 的目標區位限制。
     */
    private String resolveRequiredAddCheerTargetZone(String rawText, boolean preferSelfBackTarget) {
        if (!StringUtils.hasText(rawText)) {
            return preferSelfBackTarget ? "BACK" : "";
        }
        if (rawText.contains("バックホロメン")) {
            return "BACK";
        }
        if (rawText.contains("コラボホロメン")) {
            return "COLLAB";
        }
        if (rawText.contains("センターホロメン")) {
            return "CENTER";
        }
        return preferSelfBackTarget ? "BACK" : "";
    }

    /**
     * 由文案判斷 ADD_CHEER 的目標等級限制。
     */
    private String resolveRequiredAddCheerTargetLevelType(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        if (rawText.contains("2ndホロメン")) {
            return "SECOND";
        }
        if (rawText.contains("1stホロメン")) {
            return "FIRST";
        }
        if (rawText.contains("Debutホロメン")) {
            return "DEBUT";
        }
        if (rawText.contains("Spotホロメン")) {
            return "SPOT";
        }
        return "";
    }

    /**
     * 將目標 Holomem 疊卡中的指定等級卡片歸檔（常用於 Bloom 前置成本）。
     */
    Map<String, Object> executeArchiveStackCardEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long selfHolomemCardInstanceId
    ) {
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            "SELF",
            selfHolomemCardInstanceId,
            false
        );
        if (targetHolomemId == null || targetHolomemId <= 0) {
            return executeNoOpEffect(effectType, effectNode, "找不到可支付疊卡成本的 Holomem");
        }

        int archiveRequested = effectTextParser.extractInt(effectNode, 1, "stackArchiveCount", "archiveCount", "stackCostCount", "costCount");
        if (archiveRequested <= 0) {
            archiveRequested = 1;
        }
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        String requiredLevelType = normalizeLevelType(effectTextParser.extractText(effectNode, "stackCostLevelType", "costLevelType"));
        if (!StringUtils.hasText(requiredLevelType)) {
            requiredLevelType = resolveRequiredAddCheerTargetLevelType(rawText);
        }

        Long topCardInstanceId = selfHolomemCardInstanceId != null
            ? selfHolomemCardInstanceId
            : resolveHolomemCardInstanceId(targetHolomemId);
        List<Map<String, Object>> candidates = jdbcTemplate.query(
            """
            SELECT s.match_card_id,
                   mc.card_id,
                   UPPER(COALESCE(m.level_type, '')) AS level_type
            FROM match_holomem_stack_cards s
            JOIN match_cards mc ON mc.id = s.match_card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            WHERE s.match_holomem_id = ?
              AND mc.match_id = ?
              AND mc.owner_user_id = ?
              AND s.match_card_id <> COALESCE(?, -1)
            ORDER BY s.stack_order DESC, s.id DESC
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("level_type", rs.getString("level_type"));
                return row;
            },
            targetHolomemId,
            matchId,
            userId,
            topCardInstanceId
        );

        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String levelType = normalizeLevelType(asText(candidate.get("level_type")));
            if (StringUtils.hasText(requiredLevelType) && !requiredLevelType.equals(levelType)) {
                continue;
            }
            selected.add(candidate);
            if (selected.size() >= archiveRequested) {
                break;
            }
        }

        List<Long> archivedStackCardInstanceIds = new ArrayList<>();
        List<String> archivedStackCardIds = new ArrayList<>();
        for (Map<String, Object> candidate : selected) {
            Long stackCardInstanceId = asLong(candidate.get("match_card_id"));
            if (stackCardInstanceId == null || stackCardInstanceId <= 0) {
                continue;
            }
            int archiveOrder = nextZoneOrder(matchId, userId, "ARCHIVE");
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
                """,
                archiveOrder,
                stackCardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                continue;
            }
            jdbcTemplate.update(
                """
                DELETE FROM match_holomem_stack_cards
                WHERE match_holomem_id = ?
                  AND match_card_id = ?
                """,
                targetHolomemId,
                stackCardInstanceId
            );
            archivedStackCardInstanceIds.add(stackCardInstanceId);
            archivedStackCardIds.add(asText(candidate.get("card_id")));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("archiveRequested", archiveRequested);
        summary.put("archiveApplied", archivedStackCardInstanceIds.size());
        summary.put("requiredLevelType", requiredLevelType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("archivedStackCardInstanceIds", archivedStackCardInstanceIds);
        summary.put("archivedStackCardIds", archivedStackCardIds);
        summary.put("applied", !archivedStackCardInstanceIds.isEmpty());
        if (archivedStackCardInstanceIds.isEmpty()) {
            if (StringUtils.hasText(requiredLevelType)) {
                summary.put("reason", "沒有可歸檔的重疊 " + requiredLevelType + " 卡片");
            } else {
                summary.put("reason", "沒有可歸檔的重疊卡片");
            }
        }
        return summary;
    }

    /**
     * 執行傷害效果，含傷害修正、擊倒處理、附屬卡歸檔與生命扣減。
     */
    Map<String, Object> executeDamageEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        if (rawText.contains("かわりに受ける")) {
            return executeDamageRedirectPreparationEffect(
                matchId,
                userId,
                effectType,
                effectNode,
                targetType,
                targetHolomemCardInstanceId,
                rawText
            );
        }
        return damageEffectExecutionService.executeDamageEffect(
            matchId,
            userId,
            effectType,
            effectNode,
            targetType,
            targetHolomemCardInstanceId
        );
    }

    boolean isOpponentTurnForUser(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return false;
        }
        Long currentTurnPlayerId = jdbcTemplate.query(
            """
            SELECT current_turn_player_id
            FROM matches
            WHERE id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? asLong(rs.getObject("current_turn_player_id")) : null,
            matchId
        );
        return currentTurnPlayerId != null && !Objects.equals(currentTurnPlayerId, userId);
    }

    private String resolveHolomemZone(Long matchId, Long holomemId) {
        if (matchId == null || holomemId == null) {
            return "";
        }
        String zone = jdbcTemplate.query(
            """
            SELECT zone
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("zone") : null,
            holomemId,
            matchId
        );
        return normalize(zone);
    }

    /**
     * 設定傷害轉移預備狀態，於回合效果表登記可替代承傷的目標。
     */
    private Map<String, Object> executeDamageRedirectPreparationEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId,
        String rawText
    ) {
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "DAMAGE_REDIRECT 找不到可替代承傷的 Holomen");
        }
        Long affectedUserId = resolveHolomemOwner(matchId, targetHolomemId);
        if (affectedUserId == null || affectedUserId <= 0) {
            return executeNoOpEffect(effectType, effectNode, "DAMAGE_REDIRECT 找不到目標擁有者");
        }
        int currentTurn = resolveCurrentTurnNumber(matchId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actions", List.of("DAMAGE_REDIRECT"));
        payload.put("targetHolomemId", targetHolomemId);
        payload.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        payload.put("rawText", rawText);

        int inserted = jdbcTemplate.update(
            """
            INSERT INTO match_turn_effects (
                match_id,
                source_user_id,
                affected_user_id,
                effect_type,
                stat_type,
                modifier_value,
                expires_turn,
                payload
            ) VALUES (?, ?, ?, ?, 'ACTION_LOCK', 1, ?, CAST(? AS jsonb))
            """,
            matchId,
            userId,
            affectedUserId,
            "DEBUFF",
            currentTurn,
            effectTextParser.toJsonString(payload)
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", inserted == 1);
        summary.put("redirectPrepared", inserted == 1);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("affectedUserId", affectedUserId);
        summary.put("expiresTurn", currentTurn);
        return summary;
    }

    /**
     * 套用 BUFF/DEBUFF 效果，寫入回合效果並回傳修正摘要。
     */
    Map<String, Object> executeBuffDebuffEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType
    ) {
        int modifier = resolveBuffDebuffModifier(effectNode, effectType);
        int currentTurn = resolveCurrentTurnNumber(matchId);
        int expiresTurn = currentTurn;
        List<Long> affectedUserIds = resolveAffectedUserIds(matchId, userId, targetType);
        boolean requireSingleTarget = rawTextRequiresSingleArtTarget(effectNode);
        Map<String, Object> targetHolomem = resolveBuffDebuffTargetHolomem(matchId, userId, targetType, effectNode);
        int inserted = 0;
        if (modifier != 0 && !affectedUserIds.isEmpty() && (!requireSingleTarget || (targetHolomem != null && !targetHolomem.isEmpty()))) {
            for (Long affectedUserId : affectedUserIds) {
                if (affectedUserId == null || affectedUserId <= 0) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("rawText", effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
                if (targetHolomem != null && !targetHolomem.isEmpty()) {
                    payload.put("targetHolomemId", asLong(targetHolomem.get("holomem_id")));
                    payload.put("targetHolomemCardInstanceId", asLong(targetHolomem.get("match_card_id")));
                    payload.put("targetCardId", asText(targetHolomem.get("card_id")));
                    payload.put("targetName", asText(targetHolomem.get("name")));
                }
                inserted += jdbcTemplate.update(
                    """
                    INSERT INTO match_turn_effects (
                        match_id,
                        source_user_id,
                        affected_user_id,
                        effect_type,
                        stat_type,
                        modifier_value,
                        expires_turn,
                        payload
                    ) VALUES (?, ?, ?, ?, 'DAMAGE_MODIFIER', ?, ?, CAST(? AS jsonb))
                    """,
                    matchId,
                    userId,
                    affectedUserId,
                    effectType,
                    modifier,
                    expiresTurn,
                    effectTextParser.toJsonString(payload)
                );
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", inserted > 0);
        summary.put("statType", "DAMAGE_MODIFIER");
        summary.put("modifierValue", modifier);
        summary.put("currentTurn", currentTurn);
        summary.put("expiresTurn", expiresTurn);
        summary.put("affectedUserIds", affectedUserIds);
        summary.put("appliedCount", inserted);
        if (targetHolomem != null && !targetHolomem.isEmpty()) {
            summary.put("targetHolomemId", asLong(targetHolomem.get("holomem_id")));
            summary.put("targetHolomemCardInstanceId", asLong(targetHolomem.get("match_card_id")));
            summary.put("targetCardId", asText(targetHolomem.get("card_id")));
            summary.put("targetName", asText(targetHolomem.get("name")));
        }
        if (modifier == 0) {
            summary.put("reason", "找不到可用的傷害修正值");
        } else if (requireSingleTarget && (targetHolomem == null || targetHolomem.isEmpty())) {
            summary.put("reason", "找不到唯一可套用的單體藝能加成目標");
        }
        return summary;
    }

    /**
     * 嘗試由 BUFF/DEBUFF 文案解析出唯一 Holomem 目標。
     *
     * <p>目前先處理像 `HBP06-084` 這種：
     *
     * <p>- `このターンの間`
     * <p>- `自分のステージの〈博衣こより〉1人のアーツ+20`
     *
     * <p>若文案沒有「1人」這種單體限制，就維持原本的玩家層級 turn modifier。
     * 若文案要求單體，但盤面上找不到唯一合法目標，就回傳 `null`，交由 caller 視為 skipped，
     * 避免把「選 1 人」偷做成「全體都吃到」。
     */
    private Map<String, Object> resolveBuffDebuffTargetHolomem(
        Long matchId,
        Long userId,
        String targetType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        if (!rawTextRequiresSingleArtTarget(rawText)) {
            return null;
        }

        List<Long> affectedUserIds = resolveAffectedUserIds(matchId, userId, targetType);
        if (affectedUserIds.size() != 1) {
            return null;
        }
        Long affectedUserId = affectedUserIds.get(0);
        if (affectedUserId == null || affectedUserId <= 0) {
            return null;
        }

        SearchCriteria criteria = resolveSearchCriteriaFromRawText(rawText);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   c.name,
                   h.zone
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER','COLLAB','BACK')
            """
        );
        args.add(matchId);
        args.add(affectedUserId);

        if (rawText.contains("センターホロメン")) {
            sql.append("\n  AND h.zone = 'CENTER'");
        } else if (rawText.contains("コラボホロメン")) {
            sql.append("\n  AND h.zone = 'COLLAB'");
        } else if (rawText.contains("バックホロメン")) {
            sql.append("\n  AND h.zone = 'BACK'");
        }
        if (StringUtils.hasText(criteria.levelType())) {
            sql.append("\n  AND m.level_type = ?");
            args.add(criteria.levelType());
        }
        if (StringUtils.hasText(criteria.nameContains())) {
            sql.append("\n  AND c.name ILIKE '%' || ? || '%'");
            args.add(criteria.nameContains());
        }
        if (StringUtils.hasText(criteria.tag())) {
            sql.append("\n  AND c.tags_json @> to_jsonb(ARRAY[?]::text[])");
            args.add(criteria.tag());
        }

        sql.append(
            """

            ORDER BY CASE h.zone
                        WHEN 'CENTER' THEN 1
                        WHEN 'COLLAB' THEN 2
                        WHEN 'BACK' THEN 3
                        ELSE 9
                     END,
                     h.id
            """
        );

        List<Map<String, Object>> matches = jdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("holomem_id", rs.getLong("holomem_id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("name", rs.getString("name"));
                row.put("zone", rs.getString("zone"));
                return row;
            },
            args.toArray()
        );
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private boolean rawTextRequiresSingleArtTarget(JsonNode effectNode) {
        return rawTextRequiresSingleArtTarget(
            effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"))
        );
    }

    /**
     * 判斷文案是否要求「選 1 張 Holomem 吃本回合藝能修正」。
     */
    private boolean rawTextRequiresSingleArtTarget(String rawText) {
        return StringUtils.hasText(rawText) && rawText.contains("1人") && rawText.contains("アーツ");
    }

    /**
     * 建立不執行效果（No-Op）的標準摘要。
     */
    Map<String, Object> executeNoOpEffect(String effectType, JsonNode effectNode, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", false);
        summary.put("reason", reason);
        summary.put("rawText", effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        return summary;
    }

    /**
     * 建立被跳過效果的摘要（用於 unsupported/exception fallback）。
     */
    Map<String, Object> buildSkippedEffect(String effectType, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectTextParser.normalizeEffectType(effectType));
        summary.put("applied", false);
        summary.put("skipped", true);
        summary.put("reason", StringUtils.hasText(reason) ? reason : "EFFECT_SKIPPED");
        return summary;
    }

    /**
     * 載入指定成員卡的 passive effect JSON 文字。
     */
    String loadPassiveEffectText(String bloomCardId) {
        if (!StringUtils.hasText(bloomCardId)) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT passive_effect_json::text AS passive_text
            FROM member_cards
            WHERE card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("passive_text") : null,
            bloomCardId
        );
    }

    /**
     * 解析 Bloom 效果計畫，優先結構化 JSON，否則回退文案推斷。
     */
    BloomEffectPlan resolveBloomEffectPlan(String bloomCardId) {
        return resolveBloomEffectPlan(bloomCardId, null);
    }

    BloomEffectPlan resolveBloomEffectPlan(String bloomCardId, BloomRuntimeContext runtimeContext) {
        String passiveText = loadPassiveEffectText(bloomCardId);
        if (!StringUtils.hasText(passiveText)) {
            return emptyBloomEffectPlan(null, null);
        }
        JsonNode passiveNode = effectTextParser.parseEffectJson(passiveText);
        BloomEffectPlan structured = resolveStructuredBloomEffectPlan(passiveNode);
        if (structured != null && structured.hasBloomEffect()) {
            return structured;
        }

        String bloomText = loadBloomEffectText(passiveText);
        if (!StringUtils.hasText(bloomText)) {
            return emptyBloomEffectPlan(null, null);
        }
        List<String> effectTypes = inferBloomEffectTypes(bloomText);
        String normalizedCardId = normalize(bloomCardId);
        Integer diceRoll = normalizedCardId.startsWith("HBP04-059") ? null : resolveBloomDiceRoll(bloomText);
        Map<String, Object> bloomEffectPayload = buildFallbackEffectPayload(effectTypes, bloomText, null);
        if (normalizedCardId.startsWith("HSD02-007")) {
            effectTypes = applyHsd02007BloomFallbackPayload(bloomEffectPayload);
        }
        if (normalizedCardId.startsWith("HSD13-011")) {
            effectTypes = applyHsd13011BloomFallbackPayload(bloomEffectPayload);
        }
        if (normalizedCardId.startsWith("HSD07-007")) {
            effectTypes = applyHsd07007BloomFallbackPayload(bloomEffectPayload);
        }
        if (normalizedCardId.startsWith("HBP04-059")) {
            int ownHandCount = runtimeContext == null || runtimeContext.common() == null
                ? 0
                : runtimeContext.common().ownHandCount();
            if (ownHandCount <= 0) {
                return emptyBloomEffectPlan(bloomText, diceRoll);
            }
            effectTypes = applyHbp04059BloomFallbackPayload(bloomEffectPayload);
        }
        if (normalizedCardId.startsWith("HBP02-016")) {
            if (!"DEBUT".equals(normalizeLevelType(runtimeContext == null ? null : runtimeContext.sourceLevelType()))) {
                return emptyBloomEffectPlan(bloomText, diceRoll);
            }
            effectTypes = applyHbp02016BloomFallbackPayload(bloomEffectPayload);
        }
        if (normalizedCardId.startsWith("HBP06-081")) {
            String oshiCardName = runtimeContext == null || runtimeContext.common() == null
                ? null
                : runtimeContext.common().oshiCardName();
            int ownedStageCheerCount = runtimeContext == null || runtimeContext.common() == null
                ? 0
                : runtimeContext.common().ownedStageCheerCount();
            if (!StringUtils.hasText(oshiCardName) || !"大空スバル".equals(oshiCardName.trim()) || ownedStageCheerCount <= 0) {
                return emptyBloomEffectPlan(bloomText, diceRoll);
            }
            effectTypes = applyHbp06081BloomFallbackPayload(bloomEffectPayload);
        }
        if (diceRoll != null) {
            bloomEffectPayload.put("diceRoll", diceRoll);
        }
        return activeBloomEffectPlan(effectTypes, bloomEffectPayload, bloomText, diceRoll);
    }

    /**
     * 解析 Collab 效果計畫，優先結構化 JSON，否則回退文案推斷。
     */
    BloomEffectPlan resolveCollabEffectPlan(String collabCardId) {
        String passiveText = loadPassiveEffectText(collabCardId);
        if (!StringUtils.hasText(passiveText)) {
            return emptyBloomEffectPlan(null, null);
        }
        JsonNode passiveNode = effectTextParser.parseEffectJson(passiveText);
        BloomEffectPlan structured = resolveStructuredCollabEffectPlan(passiveNode);
        if (structured != null && structured.hasBloomEffect()) {
            return structured;
        }

        String collabText = loadCollabEffectText(passiveText);
        if (!StringUtils.hasText(collabText)) {
            return emptyBloomEffectPlan(null, null);
        }
        List<String> effectTypes = inferBloomEffectTypes(collabText);
        Integer diceRoll = resolveBloomDiceRoll(collabText);
        Map<String, Object> collabEffectPayload = buildFallbackEffectPayload(effectTypes, collabText, diceRoll);
        return activeBloomEffectPlan(effectTypes, collabEffectPayload, collabText, diceRoll);
    }

    /**
     * 解析 Collab 效果計畫（含場況條件修正）。
     */
    BloomEffectPlan resolveCollabEffectPlan(String collabCardId, CollabRuntimeContext runtimeContext) {
        BloomEffectPlan basePlan = resolveCollabEffectPlan(collabCardId);
        if (basePlan == null || !basePlan.hasBloomEffect()) {
            return basePlan;
        }
        if (!StringUtils.hasText(collabCardId) || runtimeContext == null) {
            return basePlan;
        }
        String normalizedCardId = normalize(collabCardId);
        List<String> adjustedEffects = new ArrayList<>(basePlan.effectTypes());
        ObjectNode adjustedNode = mutableEffectNode(basePlan.effectNode());

        // HSD01-015：依 CENTER 夥伴分支，只能二擇一，不可同時套用。
        if (normalizedCardId.startsWith("HSD01-015")) {
            if (isCenterAzki(runtimeContext)) {
                adjustedEffects = List.of("ADD_CHEER");
                adjustedNode.set("effects", objectMapper.valueToTree(adjustedEffects));
                if (runtimeContext.centerHolomemCardInstanceId() != null) {
                    adjustedNode.put("targetHolomemCardInstanceId", runtimeContext.centerHolomemCardInstanceId());
                }
                return new BloomEffectPlan(true, adjustedEffects, adjustedNode, basePlan.rawText(), basePlan.diceRoll());
            }
            if (isCenterTokinoSora(runtimeContext)) {
                adjustedEffects = List.of("DRAW");
                adjustedNode.set("effects", objectMapper.valueToTree(adjustedEffects));
                if (!adjustedNode.has("value") && !adjustedNode.has("cards") && !adjustedNode.has("amount")) {
                    adjustedNode.put("value", 1);
                }
                return new BloomEffectPlan(true, adjustedEffects, adjustedNode, basePlan.rawText(), basePlan.diceRoll());
            }
            return new BloomEffectPlan(false, List.of(), objectMapper.createObjectNode(), basePlan.rawText(), basePlan.diceRoll());
        }

        // HSD13-009：僅後攻玩家第一回合可觸發。
        if (normalizedCardId.startsWith("HSD13-009") && !runtimeContext.secondPlayerFirstTurn()) {
            return new BloomEffectPlan(false, List.of(), objectMapper.createObjectNode(), basePlan.rawText(), basePlan.diceRoll());
        }

        // HSD01-009：骰值 <=4 才送 cheer；骰值 =1 時才可再把本體移回 BACK。
        if (normalizedCardId.startsWith("HSD01-009")) {
            LinkedHashSet<String> reorderedEffects = new LinkedHashSet<>();
            reorderedEffects.add("ADD_CHEER");
            reorderedEffects.add("MOVE_ZONE");
            adjustedEffects = new ArrayList<>(reorderedEffects);
            adjustedNode.set("effects", objectMapper.valueToTree(adjustedEffects));
            adjustedNode.put("moveTargetType", "SELF");
            if (runtimeContext.selfHolomemCardInstanceId() != null) {
                adjustedNode.put("moveTargetHolomemCardInstanceId", runtimeContext.selfHolomemCardInstanceId());
            }
            if (runtimeContext.firstBackHolomemCardInstanceId() != null) {
                adjustedNode.put("targetHolomemCardInstanceId", runtimeContext.firstBackHolomemCardInstanceId());
            }
            ObjectNode diceConditions = objectMapper.createObjectNode();
            diceConditions.put("ADD_CHEER", "AT_MOST_4");
            diceConditions.put("MOVE_ZONE", "EXACT_1");
            adjustedNode.set("effectDiceConditions", diceConditions);
            return new BloomEffectPlan(true, adjustedEffects, adjustedNode, basePlan.rawText(), basePlan.diceRoll());
        }

        // HBP01-031：從 HOLOPOWER 選 1 入手，再把牌庫頂 1 張送去 HOLOPOWER。
        if (normalizedCardId.startsWith("HBP01-031")) {
            adjustedEffects = List.of("LOOK_HOLOPOWER", "SEARCH", "MOVE_TO_HOLOPOWER");
            adjustedNode.set("effects", objectMapper.valueToTree(adjustedEffects));
            adjustedNode.put("searchSourceZone", "HOLOPOWER");
            adjustedNode.put("holopowerSourceZone", "DECK");
            adjustedNode.put("value", 1);
            return new BloomEffectPlan(true, adjustedEffects, adjustedNode, basePlan.rawText(), basePlan.diceRoll());
        }

        // HSD04-011：看 HOLOPOWER 取 1 入手，再從手牌送 1 到 HOLOPOWER。
        if (normalizedCardId.startsWith("HSD04-011")) {
            adjustedEffects = List.of("LOOK_HOLOPOWER", "SEARCH", "MOVE_TO_HOLOPOWER");
            adjustedNode.set("effects", objectMapper.valueToTree(adjustedEffects));
            adjustedNode.put("searchSourceZone", "HOLOPOWER");
            adjustedNode.put("holopowerSourceZone", "HAND");
            adjustedNode.put("value", 1);
            return new BloomEffectPlan(true, adjustedEffects, adjustedNode, basePlan.rawText(), basePlan.diceRoll());
        }

        // HBP06-078：先支付「此卡附屬エール 1」成本，再搜尋與推し同名 Debut。
        if (normalizedCardId.startsWith("HBP06-078")) {
            if (runtimeContext == null || !StringUtils.hasText(runtimeContext.oshiCardName())) {
                return new BloomEffectPlan(false, List.of(), objectMapper.createObjectNode(), basePlan.rawText(), basePlan.diceRoll());
            }
            adjustedEffects = List.of("REMOVE_CHEER", "SEARCH");
            adjustedNode.set("effects", objectMapper.valueToTree(adjustedEffects));
            adjustedNode.put("value", 1);
            adjustedNode.put("searchSourceZone", "DECK");
            if (runtimeContext.selfHolomemCardInstanceId() != null) {
                adjustedNode.put("targetHolomemCardInstanceId", runtimeContext.selfHolomemCardInstanceId());
            }
            ObjectNode criteriaNode = adjustedNode.putObject("searchCriteria");
            criteriaNode.put("cardType", "MEMBER");
            criteriaNode.put("levelType", "DEBUT");
            criteriaNode.put("nameContains", runtimeContext.oshiCardName());
            return new BloomEffectPlan(true, adjustedEffects, adjustedNode, basePlan.rawText(), basePlan.diceRoll());
        }

        // HSD10-008：只有看到對手手牌中有 SUPPORT 時才追加 DRAW。
        if (normalizedCardId.startsWith("HSD10-008") && runtimeContext != null && !runtimeContext.opponentHandHasSupport()) {
            adjustedEffects.removeIf("DRAW"::equals);
            if (adjustedEffects.isEmpty()) {
                return new BloomEffectPlan(false, List.of(), objectMapper.createObjectNode(), basePlan.rawText(), basePlan.diceRoll());
            }
            adjustedNode.set("effects", objectMapper.valueToTree(adjustedEffects));
            return new BloomEffectPlan(true, adjustedEffects, adjustedNode, basePlan.rawText(), basePlan.diceRoll());
        }

        // HSD13-015：先退回場上エール；若場上無可退回エール則整段不觸發。
        if (normalizedCardId.startsWith("HSD13-015") && runtimeContext != null && runtimeContext.ownedStageCheerCount() <= 0) {
            return new BloomEffectPlan(false, List.of(), objectMapper.createObjectNode(), basePlan.rawText(), basePlan.diceRoll());
        }

        // HSD10-009：查看牌庫頂張數 = 對手目前手牌張數（至少 1，避免空查詢退化）。
        if (normalizedCardId.startsWith("HSD10-009") && runtimeContext != null) {
            int lookTopCount = Math.max(runtimeContext.opponentHandCount(), 1);
            adjustedNode.put("lookTopCount", lookTopCount);
            if (!adjustedEffects.contains("SEARCH")) {
                adjustedEffects.add(0, "SEARCH");
            }
            adjustedNode.set("effects", objectMapper.valueToTree(adjustedEffects));
            return new BloomEffectPlan(true, adjustedEffects, adjustedNode, basePlan.rawText(), basePlan.diceRoll());
        }

        return basePlan;
    }

    /**
     * 載入 Collab 規則判斷所需場況（回合、CENTER 搭檔、對手手牌資訊）。
     */
    CollabRuntimeContext loadCollabRuntimeContext(
        Long matchId,
        Long userId,
        Long selfHolomemCardInstanceId
    ) {
        if (matchId == null || userId == null) {
            return new CollabRuntimeContext(
                selfHolomemCardInstanceId,
                1,
                false,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                false,
                0
            );
        }

        Map<String, Object> matchRow = jdbcTemplate.query(
            """
            SELECT turn_number, player_a_id, player_b_id
            FROM matches
            WHERE id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("turn_number", rs.getObject("turn_number"));
                row.put("player_a_id", rs.getObject("player_a_id"));
                row.put("player_b_id", rs.getObject("player_b_id"));
                return row;
            },
            matchId
        );

        int turnNumber = 1;
        boolean secondPlayerFirstTurn = false;
        if (matchRow != null) {
            turnNumber = Math.max(1, asInt(matchRow.get("turn_number")));
            Long playerBId = asLong(matchRow.get("player_b_id"));
            secondPlayerFirstTurn = turnNumber == 2 && userId.equals(playerBId);
        }

        Map<String, Object> centerRow = jdbcTemplate.query(
            """
            SELECT h.match_card_id, h.card_id, c.name
            FROM match_holomems h
            LEFT JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'CENTER'
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("match_card_id", rs.getObject("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("name", rs.getString("name"));
                return row;
            },
            matchId,
            userId
        );

        String oshiCardName = jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_players mp
            JOIN cards c ON c.card_id = mp.oshi_card_id
            WHERE mp.match_id = ?
              AND mp.user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("name") : null,
            matchId,
            userId
        );

        Long firstBackHolomemCardInstanceId = jdbcTemplate.query(
            """
            SELECT h.match_card_id
            FROM match_holomems h
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'BACK'
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            userId
        );

        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        int ownHandCount = 0;
        int opponentHandCount = 0;
        boolean opponentHandHasSupport = false;
        int ownedStageCheerCount = 0;
        Integer selfHandCount = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId
        );
        ownHandCount = selfHandCount == null ? 0 : Math.max(selfHandCount, 0);
        if (opponentUserId != null && opponentUserId > 0) {
            Integer handCount = jdbcTemplate.query(
                """
                SELECT COUNT(*)
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'HAND'
                """,
                rs -> rs.next() ? rs.getInt(1) : 0,
                matchId,
                opponentUserId
            );
            Integer supportCount = jdbcTemplate.query(
                """
                SELECT COUNT(*)
                FROM match_cards mc
                JOIN cards c ON c.card_id = mc.card_id
                WHERE mc.match_id = ?
                  AND mc.owner_user_id = ?
                  AND mc.zone = 'HAND'
                  AND c.card_type = 'SUPPORT'
                """,
                rs -> rs.next() ? rs.getInt(1) : 0,
                matchId,
                opponentUserId
            );
            opponentHandCount = handCount == null ? 0 : Math.max(handCount, 0);
            opponentHandHasSupport = supportCount != null && supportCount > 0;
        }
        Integer stageCheerCount = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_holomem_cheers hc
            JOIN match_holomems h ON h.id = hc.match_holomem_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId
        );
        ownedStageCheerCount = stageCheerCount == null ? 0 : Math.max(stageCheerCount, 0);

        return new CollabRuntimeContext(
            selfHolomemCardInstanceId,
            turnNumber,
            secondPlayerFirstTurn,
            centerRow == null ? null : asLong(centerRow.get("match_card_id")),
            centerRow == null ? null : asText(centerRow.get("card_id")),
            centerRow == null ? null : asText(centerRow.get("name")),
            oshiCardName,
            firstBackHolomemCardInstanceId,
            ownHandCount,
            opponentHandCount,
            opponentHandHasSupport,
            ownedStageCheerCount
        );
    }

    /**
     * 轉成可編輯 ObjectNode，保留既有欄位內容。
     */
    private ObjectNode mutableEffectNode(JsonNode effectNode) {
        if (effectNode instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }
        ObjectNode node = objectMapper.createObjectNode();
        if (effectNode != null && !effectNode.isNull()) {
            node.set("sourceEffect", effectNode);
        }
        return node;
    }

    /**
     * 判斷 CENTER 是否為 AZKi（以卡號或名稱辨識）。
     */
    private boolean isCenterAzki(CollabRuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return false;
        }
        String centerCardId = normalize(runtimeContext.centerHolomemCardId());
        String centerName = normalize(runtimeContext.centerHolomemName());
        return centerCardId.contains("AZKI") || centerName.contains("AZKI");
    }

    /**
     * 判斷 CENTER 是否為ときのそら（以卡號或名稱辨識）。
     */
    private boolean isCenterTokinoSora(CollabRuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return false;
        }
        String centerCardId = normalize(runtimeContext.centerHolomemCardId());
        String centerNameRaw = runtimeContext.centerHolomemName() == null ? "" : runtimeContext.centerHolomemName();
        String centerName = normalize(centerNameRaw);
        return centerCardId.contains("TOKINO") && centerCardId.contains("SORA")
            || centerCardId.contains("TOKINOSORA")
            || centerName.contains("TOKINO") && centerName.contains("SORA")
            || centerNameRaw.contains("ときのそら");
    }

    /**
     * 由結構化 passive JSON 解析 Bloom 效果計畫。
     */
    private BloomEffectPlan resolveStructuredBloomEffectPlan(JsonNode passiveNode) {
        return resolveStructuredEffectPlan(passiveNode, "bloomEffect");
    }

    /**
     * 由結構化 passive JSON 解析 Collab 效果計畫。
     */
    private BloomEffectPlan resolveStructuredCollabEffectPlan(JsonNode passiveNode) {
        return resolveStructuredEffectPlan(passiveNode, "collabEffect");
    }

    private BloomEffectPlan resolveStructuredEffectPlan(JsonNode passiveNode, String effectFieldName) {
        if (passiveNode == null || passiveNode.isNull() || !passiveNode.isObject()) {
            return null;
        }
        JsonNode structuredNode = passiveNode.get(effectFieldName);
        if (structuredNode == null || structuredNode.isNull() || !structuredNode.isObject()) {
            return null;
        }

        List<String> effectTypes = resolveEffectTypes(readText(structuredNode, "type"), structuredNode);
        String rawText = readText(structuredNode, "rawText", "rawEffect", "text");
        if (effectTypes.isEmpty() && StringUtils.hasText(rawText)) {
            effectTypes = inferBloomEffectTypes(rawText);
        }
        if (effectTypes.isEmpty()) {
            effectTypes = List.of("UNIMPLEMENTED");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", effectTextParser.normalizeEffectType(readText(structuredNode, "type")));
        payload.put("effects", effectTypes);
        if (StringUtils.hasText(rawText)) {
            payload.put("rawText", rawText);
        }
        if (structuredNode.has("searchCriteria")) {
            payload.put("searchCriteria", structuredNode.get("searchCriteria"));
        }
        if (structuredNode.has("value")) {
            payload.put("value", structuredNode.get("value").asInt());
        }
        if (structuredNode.has("cards")) {
            payload.put("cards", structuredNode.get("cards").asInt());
        }
        if (structuredNode.has("amount")) {
            payload.put("amount", structuredNode.get("amount").asInt());
        }
        if (structuredNode.has("diceCondition")) {
            payload.put("diceCondition", readText(structuredNode, "diceCondition"));
        }
        if (structuredNode.has("effectDiceConditions")) {
            payload.put("effectDiceConditions", structuredNode.get("effectDiceConditions"));
        }

        Integer diceRoll = null;
        if (structuredNode.has("diceCondition") || structuredNode.has("effectDiceConditions")) {
            diceRoll = resolveDiceRoll(structuredNode);
            payload.put("diceRoll", diceRoll);
        }
        return new BloomEffectPlan(true, effectTypes, objectMapper.valueToTree(payload), rawText, diceRoll);
    }

    private BloomEffectPlan emptyBloomEffectPlan(String rawText, Integer diceRoll) {
        return new BloomEffectPlan(false, List.of(), objectMapper.createObjectNode(), rawText, diceRoll);
    }

    private BloomEffectPlan activeBloomEffectPlan(
        List<String> effectTypes,
        Map<String, Object> payload,
        String rawText,
        Integer diceRoll
    ) {
        return new BloomEffectPlan(true, effectTypes, objectMapper.valueToTree(payload), rawText, diceRoll);
    }

    private Map<String, Object> buildFallbackEffectPayload(
        List<String> effectTypes,
        String rawText,
        Integer diceRoll
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "UNIMPLEMENTED");
        payload.put("effects", effectTypes);
        payload.put("rawText", rawText);
        if (diceRoll != null) {
            payload.put("diceRoll", diceRoll);
        }
        return payload;
    }

    private List<String> applyHsd02007BloomFallbackPayload(Map<String, Object> payload) {
        List<String> effectTypes = List.of("SEARCH");
        payload.put("effects", effectTypes);
        payload.put("value", 1);
        payload.put("lookTopCount", 2);
        payload.put("searchSourceZone", "DECK");
        payload.put("archiveUnselectedTopWindow", true);
        return effectTypes;
    }

    private List<String> applyHsd13011BloomFallbackPayload(Map<String, Object> payload) {
        List<String> effectTypes = List.of("ARCHIVE_STACK_CARD", "DAMAGE");
        payload.put("effects", effectTypes);
        payload.put("stackArchiveCount", 1);
        payload.put("stackCostLevelType", "DEBUT");
        payload.put("value", 20);
        payload.put("damageTargetZone", "COLLAB");
        return effectTypes;
    }

    private List<String> applyHsd07007BloomFallbackPayload(Map<String, Object> payload) {
        List<String> effectTypes = List.of("SWAP_WITH_COLLAB");
        payload.put("effects", effectTypes);
        return effectTypes;
    }

    private List<String> applyHbp04059BloomFallbackPayload(Map<String, Object> payload) {
        List<String> effectTypes = List.of("DISCARD_HAND", "DRAW");
        payload.put("effects", effectTypes);
        payload.put("value", 0);
        payload.put("diceRollCount", 3);
        payload.put("oddRollsDrawCount", true);
        return effectTypes;
    }

    private List<String> applyHbp02016BloomFallbackPayload(Map<String, Object> payload) {
        List<String> effectTypes = List.of("SEARCH");
        payload.put("effects", effectTypes);
        payload.put("value", 1);
        payload.put("searchSourceZone", "DECK");
        ObjectNode criteriaNode = objectMapper.createObjectNode();
        criteriaNode.put("cardType", "MEMBER");
        criteriaNode.put("tag", "#3期生");
        var anyOf = criteriaNode.putArray("anyOf");
        anyOf.addObject().put("levelType", "DEBUT");
        anyOf.addObject().put("levelType", "FIRST");
        anyOf.addObject().put("levelType", "SPOT");
        payload.put("searchCriteria", criteriaNode);
        return effectTypes;
    }

    private List<String> applyHbp06081BloomFallbackPayload(Map<String, Object> payload) {
        List<String> effectTypes = List.of("REMOVE_STAGE_CHEER", "SEARCH");
        payload.put("effects", effectTypes);
        payload.put("value", 1);
        payload.put("searchSourceZone", "DECK");
        ObjectNode criteriaNode = objectMapper.createObjectNode();
        criteriaNode.put("cardType", "MEMBER");
        criteriaNode.put("nameContains", "大空スバル");
        payload.put("searchCriteria", criteriaNode);
        return effectTypes;
    }

    /**
     * 從被動文本中提取 Bloom 專用描述文字。
     */
    private String loadBloomEffectText(String passiveText) {
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("ブルームエフェクト")) {
            return null;
        }
        return normalizeBloomText(passiveText);
    }

    /**
     * 從被動文本中提取 Collab 專用描述文字。
     */
    private String loadCollabEffectText(String passiveText) {
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("コラボエフェクト")) {
            return null;
        }
        return normalizeCollabText(passiveText);
    }

    /**
     * 從被動文本中提取 Gift 專用描述文字。
     */
    String loadGiftEffectText(String passiveText) {
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("ギフト")) {
            return null;
        }
        return normalizeGiftText(passiveText);
    }

    /**
     * 正規化 Bloom 文案內容（去除不必要片段並統一格式）。
     */
    private String normalizeBloomText(String passiveText) {
        if (!StringUtils.hasText(passiveText)) {
            return "";
        }
        String normalized = passiveText
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("{", " ")
            .replace("}", " ")
            .replace("\"", " ")
            .replace(":", " ");
        int idx = normalized.indexOf("ブルームエフェクト");
        if (idx < 0) {
            return normalized.trim();
        }
        String trimmed = normalized.substring(idx).trim();
        String[] stopTokens = { "コラボエフェクト", "ギフト", "エクストラ" };
        int end = trimmed.length();
        for (String token : stopTokens) {
            int tokenIdx = trimmed.indexOf(token, "ブルームエフェクト".length());
            if (tokenIdx > 0 && tokenIdx < end) {
                end = tokenIdx;
            }
        }
        return trimmed.substring(0, end).trim();
    }

    /**
     * 正規化 Collab 文案內容。
     */
    private String normalizeCollabText(String passiveText) {
        if (!StringUtils.hasText(passiveText)) {
            return "";
        }
        String normalized = passiveText
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("{", " ")
            .replace("}", " ")
            .replace("\"", " ")
            .replace(":", " ");
        int idx = normalized.indexOf("コラボエフェクト");
        if (idx < 0) {
            return normalized.trim();
        }
        String trimmed = normalized.substring(idx).trim();
        String[] stopTokens = { "ブルームエフェクト", "ギフト", "エクストラ" };
        int end = trimmed.length();
        for (String token : stopTokens) {
            int tokenIdx = trimmed.indexOf(token, "コラボエフェクト".length());
            if (tokenIdx > 0 && tokenIdx < end) {
                end = tokenIdx;
            }
        }
        return trimmed.substring(0, end).trim();
    }

    /**
     * 正規化 Gift 文案內容。
     */
    private String normalizeGiftText(String passiveText) {
        if (!StringUtils.hasText(passiveText)) {
            return "";
        }
        String normalized = passiveText
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("{", " ")
            .replace("}", " ")
            .replace("\"", " ")
            .replace(":", " ");
        int idx = normalized.indexOf("ギフト");
        if (idx < 0) {
            return normalized.trim();
        }
        String trimmed = normalized.substring(idx).trim();
        String[] stopTokens = { "ブルームエフェクト", "コラボエフェクト", "エクストラ" };
        int end = trimmed.length();
        for (String token : stopTokens) {
            int tokenIdx = trimmed.indexOf(token, "ギフト".length());
            if (tokenIdx > 0 && tokenIdx < end) {
                end = tokenIdx;
            }
        }
        return trimmed.substring(0, end).trim();
    }

    /**
     * 從 Bloom/Collab 文案推斷效果類型列表。
     */
    private List<String> inferBloomEffectTypes(String bloomText) {
        Set<String> effectTypes = new LinkedHashSet<>();
        String text = effectTextParser.normalizeDigits(bloomText == null ? "" : bloomText);
        if (!StringUtils.hasText(text)) {
            effectTypes.add("UNIMPLEMENTED");
            return new ArrayList<>(effectTypes);
        }
        boolean archiveReplacementToHand = text.contains("アーカイブするかわりに手札に加えられる");

        if (text.contains("手札に加える")) {
            effectTypes.add("SEARCH");
        }
        if (archiveReplacementToHand) {
            effectTypes.add("REPLACE_ARCHIVE_WITH_HAND");
            effectTypes.remove("SEARCH");
        }
        if (text.contains("手札に戻")) {
            effectTypes.add("RETURN_TO_HAND");
        }
        if (text.contains("デッキの上に戻")) {
            effectTypes.add("RETURN_TO_DECK_TOP");
        }
        if (text.contains("引く")) {
            effectTypes.add("DRAW");
        }
        if (text.contains("エール") && text.contains("送")) {
            effectTypes.add("ADD_CHEER");
        }
        if (
            text.contains("付け替え")
            || text.contains("割り振って付け")
            || text.contains("付けられる")
            || text.contains("付ける")
        ) {
            effectTypes.add("REATTACH");
        }
        if (text.contains("ステージに出せ") || text.contains("ステージに出す")) {
            effectTypes.add("SUMMON_TO_STAGE");
        }
        if (text.contains("公開し、アーカイブ")) {
            effectTypes.add("REVEAL_TO_ARCHIVE");
        }
        if (text.contains("アーカイブのホロメンを使ってBloom")) {
            effectTypes.add("BLOOM_FROM_ARCHIVE");
        }
        if (text.contains("エールデッキの下に戻")) {
            effectTypes.add("RETURN_CHEER_TO_DECK_BOTTOM");
        }
        if (text.contains("エールデッキに戻")) {
            effectTypes.add("RETURN_CHEER_TO_DECK_BOTTOM");
        }
        if (text.contains("エール") && (text.contains("アーカイブできる") || text.contains("アーカイブする"))) {
            effectTypes.add("REMOVE_CHEER");
        }
        if (
            text.contains("重なっているホロメン")
                && (text.contains("アーカイブできる") || text.contains("アーカイブする"))
        ) {
            effectTypes.add("ARCHIVE_STACK_CARD");
        }
        if (text.contains("手札") && (text.contains("アーカイブする") || text.contains("アーカイブできる"))) {
            effectTypes.add("DISCARD_HAND");
        }
        if (text.contains("お休みさせる")) {
            effectTypes.add("REST");
        }
        if (text.contains("センターホロメン") && text.contains("バックホロメン") && text.contains("交代")) {
            effectTypes.add("SWAP_CENTER_BACK");
        }
        if (text.contains("ホロパワーにする")) {
            effectTypes.add("MOVE_TO_HOLOPOWER");
        }
        if (text.contains("ダウンさせる") && text.contains("ダウンしても相手のライフは減らない")) {
            effectTypes.add("DOWN_NO_LIFE");
        }
        if (
            text.contains("ダウンさせる")
                && text.contains("ライフ")
                && (text.contains("追加") || text.contains("さらに"))
        ) {
            effectTypes.add("DOWN_EXTRA_LIFE");
        }
        if (text.contains("バトンタッチに必要な無色") && (text.contains("+") || text.contains("＋"))) {
            effectTypes.add("BATON_TOUCH_COST_MODIFIER");
        }
        if (
            text.contains("できない")
                && (text.contains("バトンタッチ") || text.contains("移動") || text.contains("交代") || text.contains("Bloom") || text.contains("ブルーム"))
        ) {
            effectTypes.add("ACTION_LOCK");
        }
        if (text.contains("もう1回Bloomできる")) {
            effectTypes.add("ALLOW_EXTRA_BLOOM");
        }
        if (text.contains("デッキの上から1枚を見る")) {
            effectTypes.add("LOOK_TOP_DECK");
        }
        if (
            text.contains("相手")
                && text.contains("手札")
                && (text.contains("見る") || text.contains("見"))
        ) {
            effectTypes.add("LOOK_OPPONENT_HAND");
        }
        if (
            text.contains("ホロパワー")
                && (text.contains("見る") || text.contains("見"))
        ) {
            effectTypes.add("LOOK_HOLOPOWER");
        }
        if (text.contains("交代できる")) {
            effectTypes.add("SWAP_WITH_COLLAB");
        }
        if (text.contains("移動させる")) {
            effectTypes.add("MOVE_ZONE");
        }
        if (text.contains("回復")) {
            effectTypes.add("HEAL");
        }
        if (text.contains("ダメージ")) {
            effectTypes.add("DAMAGE");
        }
        if (text.contains("勝利") || text.contains("敗北") || text.contains("引き分け")) {
            effectTypes.add("MATCH_RESULT");
        }
        if (text.contains("アーツ")) {
            if (text.contains("-")) {
                effectTypes.add("DEBUFF");
            } else if (text.contains("+")) {
                effectTypes.add("BUFF");
            }
        }
        if (effectTypes.isEmpty()) {
            effectTypes.add("UNIMPLEMENTED");
        }
        return new ArrayList<>(effectTypes);
    }

    /**
     * 依效果類型推斷 Bloom 目標側（SELF/ENEMY）。
     */
    String inferBloomTargetType(String effectType) {
        return switch (effectType) {
            case "DAMAGE", "DEBUFF", "MOVE_ZONE", "REST", "DOWN_NO_LIFE", "DOWN_EXTRA_LIFE" -> "ENEMY";
            default -> "SELF";
        };
    }

    /**
     * 取得指定區域下一個 order_index。
     */
    private int nextZoneOrder(Long matchId, Long userId, String zone) {
        Integer next = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
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
        return next == null ? 1 : next;
    }

    /**
     * 清除已過期的回合性效果（expires_turn <= currentTurn）。
     */
    int clearExpiredTurnEffects(Long matchId, int currentTurn) {
        return jdbcTemplate.update(
            """
            DELETE FROM match_turn_effects
            WHERE match_id = ?
              AND expires_turn <= ?
            """,
            matchId,
            currentTurn
        );
    }

    /**
     * 合併 effectType 與 effectJson.effects，輸出去重後的效果類型列表。
     */
    private List<String> resolveEffectTypes(String effectType, JsonNode effectNode) {
        Set<String> effectTypes = new LinkedHashSet<>();
        boolean hasStructuredEffects = false;
        if (effectNode != null) {
            JsonNode effectsNode = effectNode.get("effects");
            if (effectsNode != null && effectsNode.isArray()) {
                for (JsonNode node : effectsNode) {
                    if (node.isTextual() && StringUtils.hasText(node.asText())) {
                        hasStructuredEffects = true;
                        effectTypes.add(effectTextParser.normalizeEffectType(node.asText()));
                    }
                }
            }
        }
        if (!hasStructuredEffects && StringUtils.hasText(effectType)) {
            effectTypes.add(effectTextParser.normalizeEffectType(effectType));
        }
        if (!hasStructuredEffects && effectNode != null && effectNode.hasNonNull("type")) {
            effectTypes.add(effectTextParser.normalizeEffectType(effectNode.path("type").asText()));
        }
        if (hasStructuredEffects && StringUtils.hasText(effectType)) {
            effectTypes.add(effectTextParser.normalizeEffectType(effectType));
        }
        if (hasStructuredEffects && effectNode != null && effectNode.hasNonNull("type")) {
            effectTypes.add(effectTextParser.normalizeEffectType(effectNode.path("type").asText()));
        }
        return new ArrayList<>(effectTypes);
    }

    /**
     * 當 Bloom 文案包含骰子條件時擲骰，否則回傳 null。
     */
    private Integer resolveBloomDiceRoll(String bloomText) {
        String text = effectTextParser.normalizeDigits(bloomText);
        if (!StringUtils.hasText(text) || !text.contains("サイコロ")) {
            return null;
        }
        return diceService.rollD6();
    }

    /**
     * 依顯式條件或文案判斷此次效果是否命中骰子條件。
     */
    private boolean shouldApplyByDice(String rawText, JsonNode effectNode, String effectType) {
        String explicitCondition = resolveExplicitDiceCondition(effectNode, effectType);
        if (StringUtils.hasText(explicitCondition)) {
            int diceRoll = resolveDiceRoll(effectNode);
            if (diceRoll <= 0) {
                return true;
            }
            return evaluateDiceCondition(explicitCondition, diceRoll);
        }
        String text = effectTextParser.normalizeDigits(rawText);
        if (!StringUtils.hasText(text) || !text.contains("サイコロ")) {
            return true;
        }
        int diceRoll = resolveDiceRoll(effectNode);
        if (diceRoll <= 0) {
            return true;
        }
        if (text.contains("奇数の時") && text.contains("偶数の時")) {
            if ("RETURN_TO_HAND".equals(effectType)) {
                return diceRoll % 2 == 1;
            }
            if ("RETURN_TO_DECK_TOP".equals(effectType)) {
                return diceRoll % 2 == 0;
            }
        }
        Matcher atLeastMatcher = DICE_AT_LEAST_PATTERN.matcher(text);
        if (atLeastMatcher.find()) {
            try {
                return diceRoll >= Integer.parseInt(atLeastMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        Matcher atMostMatcher = DICE_AT_MOST_PATTERN.matcher(text);
        if (atMostMatcher.find()) {
            try {
                return diceRoll <= Integer.parseInt(atMostMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return true;
    }

    /**
     * 解析 effectJson 內顯式的骰子條件（可按 effectType 區分）。
     */
    private String resolveExplicitDiceCondition(JsonNode effectNode, String effectType) {
        if (effectNode == null || effectNode.isNull()) {
            return null;
        }
        JsonNode perEffect = effectNode.get("effectDiceConditions");
        String normalizedEffectType = effectTextParser.normalizeEffectType(effectType);
        if (perEffect != null && perEffect.isObject()) {
            JsonNode conditionNode = perEffect.get(normalizedEffectType);
            if (conditionNode == null) {
                conditionNode = perEffect.get(effectType);
            }
            if (conditionNode != null && conditionNode.isTextual()) {
                return effectTextParser.normalizeEffectType(conditionNode.asText());
            }
        }
        return effectTextParser.normalizeEffectType(readText(effectNode, "diceCondition", "dice_condition"));
    }

    /**
     * 驗證骰值是否符合條件字串（ODD/EVEN/AT_LEAST/AT_MOST/EXACT/BETWEEN）。
     */
    private boolean evaluateDiceCondition(String condition, int diceRoll) {
        if (!StringUtils.hasText(condition)) {
            return true;
        }
        String normalized = effectTextParser.normalizeEffectType(condition);
        if ("ODD".equals(normalized)) {
            return diceRoll % 2 == 1;
        }
        if ("EVEN".equals(normalized)) {
            return diceRoll % 2 == 0;
        }
        if (normalized.startsWith("AT_LEAST_")) {
            try {
                int threshold = Integer.parseInt(normalized.substring("AT_LEAST_".length()));
                return diceRoll >= threshold;
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        if (normalized.startsWith("AT_MOST_")) {
            try {
                int threshold = Integer.parseInt(normalized.substring("AT_MOST_".length()));
                return diceRoll <= threshold;
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        if (normalized.startsWith("EXACT_")) {
            try {
                int target = Integer.parseInt(normalized.substring("EXACT_".length()));
                return diceRoll == target;
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        if (normalized.startsWith("BETWEEN_")) {
            String range = normalized.substring("BETWEEN_".length());
            String[] parts = range.split("_");
            if (parts.length == 2) {
                try {
                    int min = Integer.parseInt(parts[0]);
                    int max = Integer.parseInt(parts[1]);
                    return diceRoll >= min && diceRoll <= max;
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
        }
        return true;
    }

    /**
     * 取得本次效果要使用的骰值，並回填到 effectNode 供後續摘要使用。
     */
    private int resolveDiceRoll(JsonNode effectNode) {
        int fromNode = effectTextParser.extractInt(effectNode, 0, "diceRoll");
        if (fromNode >= 1 && fromNode <= 6) {
            if (effectNode instanceof ObjectNode objectNode) {
                Integer rerolled = applyHbp01123RerollForPresetDiceIfNeeded(objectNode, fromNode);
                if (rerolled != null && rerolled >= 1 && rerolled <= 6) {
                    return rerolled;
                }
            }
            return fromNode;
        }
        DiceResolution resolution = resolveDiceResolution(effectNode);
        if (effectNode instanceof ObjectNode objectNode && resolution.chosenRoll() >= 1 && resolution.chosenRoll() <= 6) {
            objectNode.put("diceRoll", resolution.chosenRoll());
            objectNode.set("diceRolls", objectMapper.valueToTree(resolution.rolls()));
            objectNode.put("diceRollCountApplied", resolution.rolls().size());
        }
        return resolution.chosenRoll();
    }

    private Integer applyHbp01123RerollForPresetDiceIfNeeded(ObjectNode effectNode, int presetRoll) {
        Long matchId = effectNode.has("matchId") ? effectNode.path("matchId").asLong() : null;
        Long ownerUserId = effectNode.has("sourceUserId") ? effectNode.path("sourceUserId").asLong() : null;
        Long sourceHolomemCardInstanceId = effectNode.has("sourceHolomemCardInstanceId")
            ? effectNode.path("sourceHolomemCardInstanceId").asLong()
            : null;
        Hbp01123RerollResult rerollResult = consumeHbp01123FanAndRerollIfNeeded(
            matchId,
            ownerUserId,
            sourceHolomemCardInstanceId,
            1
        );
        if (!rerollResult.applied() || rerollResult.rerolledRolls().isEmpty()) {
            return null;
        }
        int rerolled = rerollResult.rerolledRolls().get(0);
        effectNode.put("hbp01123RerollApplied", true);
        effectNode.set("hbp01123OriginalRolls", objectMapper.valueToTree(List.of(presetRoll)));
        effectNode.set("hbp01123RerolledRolls", objectMapper.valueToTree(rerollResult.rerolledRolls()));
        if (rerollResult.archivedSupportCardInstanceId() != null) {
            effectNode.put("hbp01123ArchivedSupportCardInstanceId", rerollResult.archivedSupportCardInstanceId());
        }
        effectNode.put("diceRoll", rerolled);
        effectNode.set("diceRolls", objectMapper.valueToTree(rerollResult.rerolledRolls()));
        effectNode.put("diceRollCountApplied", rerollResult.rerolledRolls().size());
        return rerolled;
    }

    /**
     * 執行多次擲骰與挑選策略（FIRST/MAX/MIN/LAST），產出最終骰值。
     */
    private DiceResolution resolveDiceResolution(JsonNode effectNode) {
        int rollCount = resolveDiceRollCount(effectNode);
        String strategy = resolveDicePickStrategy(effectNode);
        Integer fixedDiceValue = resolveFixedDiceValue(effectNode);
        List<Integer> rolls = rollDiceValues(rollCount, fixedDiceValue, true);
        if (effectNode instanceof ObjectNode objectNode) {
            Long matchId = objectNode.has("matchId") ? objectNode.path("matchId").asLong() : null;
            Long ownerUserId = objectNode.has("sourceUserId") ? objectNode.path("sourceUserId").asLong() : null;
            Long sourceHolomemCardInstanceId = objectNode.has("sourceHolomemCardInstanceId")
                ? objectNode.path("sourceHolomemCardInstanceId").asLong()
                : null;
            Hbp01123RerollResult rerollResult = consumeHbp01123FanAndRerollIfNeeded(
                matchId,
                ownerUserId,
                sourceHolomemCardInstanceId,
                rollCount
            );
            if (rerollResult.applied()) {
                objectNode.put("hbp01123RerollApplied", true);
                objectNode.set("hbp01123OriginalRolls", objectMapper.valueToTree(rolls));
                objectNode.set("hbp01123RerolledRolls", objectMapper.valueToTree(rerollResult.rerolledRolls()));
                if (rerollResult.archivedSupportCardInstanceId() != null) {
                    objectNode.put("hbp01123ArchivedSupportCardInstanceId", rerollResult.archivedSupportCardInstanceId());
                }
                rolls = rerollResult.rerolledRolls();
            }
        }
        if (rolls.isEmpty()) {
            return new DiceResolution(1, List.of(1), "FIRST", false);
        }
        int chosen = switch (strategy) {
            case "MAX", "HIGHEST" -> rolls.stream().mapToInt(Integer::intValue).max().orElse(rolls.get(0));
            case "MIN", "LOWEST" -> rolls.stream().mapToInt(Integer::intValue).min().orElse(rolls.get(0));
            case "LAST" -> rolls.get(rolls.size() - 1);
            default -> rolls.get(0);
        };
        return new DiceResolution(chosen, rolls, strategy, fixedDiceValue != null);
    }

    private List<Integer> rollDiceValues(int rollCount, Integer fixedDiceValue, boolean applyFixedFirstRoll) {
        List<Integer> rolls = new ArrayList<>();
        for (int i = 0; i < rollCount; i++) {
            int roll = diceService.rollD6();
            if (applyFixedFirstRoll && i == 0 && fixedDiceValue != null && fixedDiceValue >= 1 && fixedDiceValue <= 6) {
                roll = fixedDiceValue;
            }
            if (roll < 1 || roll > 6) {
                roll = 1;
            }
            rolls.add(roll);
        }
        return rolls;
    }

    private Hbp01123RerollResult consumeHbp01123FanAndRerollIfNeeded(
        Long matchId,
        Long ownerUserId,
        Long sourceHolomemCardInstanceId,
        int rollCount
    ) {
        if (matchId == null || ownerUserId == null || sourceHolomemCardInstanceId == null || rollCount <= 0) {
            return Hbp01123RerollResult.none();
        }
        Long sourceHolomemId = jdbcTemplate.query(
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
            ownerUserId,
            sourceHolomemCardInstanceId
        );
        if (sourceHolomemId == null) {
            return Hbp01123RerollResult.none();
        }
        Map<String, Object> attachedFan = jdbcTemplate.query(
            """
            SELECT id, match_card_id
            FROM match_holomem_supports
            WHERE match_holomem_id = ?
              AND support_type = 'FAN'
              AND support_card_id = 'HBP01-123'
            ORDER BY id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                return row;
            },
            sourceHolomemId
        );
        if (attachedFan == null) {
            return Hbp01123RerollResult.none();
        }
        Long supportRowId = asLong(attachedFan.get("id"));
        Long supportCardInstanceId = asLong(attachedFan.get("match_card_id"));
        if (supportRowId == null || supportCardInstanceId == null) {
            return Hbp01123RerollResult.none();
        }
        int deleted = jdbcTemplate.update(
            """
            DELETE FROM match_holomem_supports
            WHERE id = ?
              AND match_holomem_id = ?
            """,
            supportRowId,
            sourceHolomemId
        );
        if (deleted != 1) {
            return Hbp01123RerollResult.none();
        }
        Long archivedSupportCardInstanceId = moveSupportCardInstanceToArchive(matchId, ownerUserId, supportCardInstanceId);
        if (archivedSupportCardInstanceId == null) {
            return Hbp01123RerollResult.none();
        }
        List<Integer> rerolledRolls = rollDiceValues(rollCount, null, false);
        return new Hbp01123RerollResult(true, rerolledRolls, archivedSupportCardInstanceId);
    }

    /**
     * 解析擲骰次數，包含欄位讀取與文案推斷，並限制最大次數。
     */
    private int resolveDiceRollCount(JsonNode effectNode) {
        int fromField = effectTextParser.extractInt(effectNode, 0, "diceRollCount", "diceCount", "rollCount");
        if (fromField > 0) {
            return Math.min(fromField, 6);
        }
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        Matcher matcher = DICE_ROLL_COUNT_PATTERN.matcher(rawText);
        if (matcher.find()) {
            try {
                int parsed = Integer.parseInt(matcher.group(1));
                if (parsed > 0) {
                    return Math.min(parsed, 6);
                }
            } catch (NumberFormatException ignored) {
                // keep fallback
            }
        }
        return 1;
    }

    /**
     * 解析多骰取值策略（如取大/取小），預設使用 FIRST。
     */
    private String resolveDicePickStrategy(JsonNode effectNode) {
        String explicit = effectTextParser.normalizeEffectType(readText(effectNode, "dicePickStrategy", "dicePick", "diceSelect"));
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (rawText.contains("大きい方")) {
            return "MAX";
        }
        if (rawText.contains("小さい方")) {
            return "MIN";
        }
        return "FIRST";
    }

    /**
     * 解析固定骰值效果（如「視為 X」）。
     */
    private Integer resolveFixedDiceValue(JsonNode effectNode) {
        int fromField = effectTextParser.extractInt(effectNode, 0, "fixedDiceValue", "diceFixedValue", "forcedDiceValue");
        if (fromField >= 1 && fromField <= 6) {
            return fromField;
        }
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        Matcher matcher = Pattern.compile("(\\d+)\\s*として扱う").matcher(rawText);
        if (matcher.find()) {
            try {
                int parsed = Integer.parseInt(matcher.group(1));
                if (parsed >= 1 && parsed <= 6) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 依單一條件載入可檢索候選清單（預設來源為 DECK）。
     */
    private List<Map<String, Object>> loadSearchCandidates(
        Long matchId,
        Long userId,
        SearchCriteria criteria
    ) {
        return searchService.loadSearchCandidates(matchId, userId, criteria);
    }

    /**
     * 讀取牌庫頂 N 張窗口，供 LOOK_TOP_DECK/SEARCH_TOP_WINDOW 使用。
     */
    private List<Map<String, Object>> loadTopDeckWindow(Long matchId, Long userId, int count) {
        return searchService.loadTopDeckWindow(matchId, userId, count);
    }

    /**
     * 從指定區域載入候選卡（可帶條件過濾）。
     */
    private List<Map<String, Object>> loadCandidatesFromZone(
        Long matchId,
        Long userId,
        String zone,
        SearchCriteria criteria,
        boolean excludeLimitedSupport
    ) {
        return searchService.loadCandidatesFromZone(matchId, userId, zone, criteria, excludeLimitedSupport);
    }

    private List<Map<String, Object>> loadCandidatesByCardInstanceIds(
        Long matchId,
        Long userId,
        List<Long> cardInstanceIds,
        SearchCriteria criteria
    ) {
        return searchService.loadCandidatesByCardInstanceIds(matchId, userId, cardInstanceIds, criteria);
    }

    /**
     * 從多區域載入候選卡並保留原始排序。
     */
    private List<Map<String, Object>> loadCandidatesFromZone(
        Long matchId,
        Long userId,
        String zone,
        String cardType,
        String levelType,
        String tag,
        String nameContains,
        boolean excludeLimitedSupport
    ) {
        return searchService.loadCandidatesFromZone(
            matchId,
            userId,
            zone,
            cardType,
            levelType,
            tag,
            nameContains,
            excludeLimitedSupport
        );
    }

    /**
     * 套用 SearchCriteria 到候選清單。
     */
    private List<Map<String, Object>> filterCandidatesByCriteria(List<Map<String, Object>> rows, SearchCriteria criteria) {
        return searchService.filterCandidatesByCriteria(rows, criteria);
    }

    /**
     * 驗證單一卡片資料列是否符合完整條件（含 allOf/anyOf）。
     */
    private boolean matchesSearchCriteria(Map<String, Object> row, SearchCriteria criteria) {
        return searchService.matchesSearchCriteria(row, criteria);
    }

    /**
     * 依欄位順序讀取第一個有效文字值。
     */
    private String readText(JsonNode node, String... fields) {
        return MatchEffectValueHelper.readText(node, fields);
    }

    /**
     * 依欄位順序讀取 Long，支援數字與數字字串。
     */
    private Long readLong(JsonNode node, String... fields) {
        return MatchEffectValueHelper.readLong(node, fields);
    }

    /**
     * 將查詢列欄位值轉為 Boolean。
     */
    private Boolean readRowBoolean(Object value) {
        return MatchEffectValueHelper.readRowBoolean(value);
    }

    /**
     * 將 SearchCriteria 轉成可回傳前端的摘要結構。
     */
    private Map<String, Object> buildCriteriaSummary(SearchCriteria criteria) {
        return searchService.buildCriteriaSummary(criteria);
    }

    /**
     * 正規化卡片類型字串。
     */
    private String normalizeCardType(String cardType) {
        String normalized = normalize(cardType);
        if ("MEMBER".equals(normalized) || "SUPPORT".equals(normalized) || "CHEER".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    /**
     * 正規化顏色字串為系統常量值。
     */
    String normalizeColorType(String color) {
        String normalized = normalize(color);
        return switch (normalized) {
            case "RED", "BLUE", "GREEN", "WHITE", "PURPLE", "YELLOW", "COLORLESS" -> normalized;
            default -> "";
        };
    }

    /**
     * 正規化 Holomem 等級字串（含 1st/2nd 同義詞）。
     */
    private String normalizeLevelType(String levelType) {
        String normalized = normalize(levelType);
        return switch (normalized) {
            case "DEBUT", "FIRST", "SECOND", "SPOT", "BUZZ" -> normalized;
            case "1ST" -> "FIRST";
            case "2ND" -> "SECOND";
            default -> "";
        };
    }

    /**
     * 正規化為 Holomem 場上可用等級，未知值回 DEBUT。
     */
    private String normalizeHolomemLevel(String levelType) {
        String normalized = normalizeLevelType(levelType);
        if ("FIRST".equals(normalized) || "SECOND".equals(normalized) || "SPOT".equals(normalized) || "BUZZ".equals(normalized)) {
            return normalized;
        }
        return "DEBUT";
    }

    /**
     * 轉換 Bloom 等級排序值，供升階合法性比較。
     */
    private int resolveBloomLevelRank(String levelType) {
        String normalized = normalizeHolomemLevel(levelType);
        return switch (normalized) {
            case "DEBUT" -> 0;
            case "FIRST" -> 1;
            case "SECOND" -> 2;
            case "BUZZ" -> 3;
            default -> -1;
        };
    }

    /**
     * 從日文文案推斷 cheer 顏色過濾條件。
     */
    private String resolveCheerColorFilter(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        if (rawText.contains("赤")) {
            return "RED";
        }
        if (rawText.contains("青")) {
            return "BLUE";
        }
        if (rawText.contains("緑")) {
            return "GREEN";
        }
        if (rawText.contains("白")) {
            return "WHITE";
        }
        if (rawText.contains("紫")) {
            return "PURPLE";
        }
        if (rawText.contains("黄")) {
            return "YELLOW";
        }
        if (rawText.contains("無色")) {
            return "COLORLESS";
        }
        return "";
    }

    /**
     * null 安全字串轉換，回傳去頭尾空白後內容。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 檢查指定行動是否被回合效果 ACTION_LOCK 封鎖。
     */
    private boolean isActionLockActive(
        Long matchId,
        Long affectedUserId,
        int currentTurn,
        String actionKey,
        String zone,
        Long holomemId
    ) {
        if (
            matchId == null ||
            affectedUserId == null ||
            affectedUserId <= 0 ||
            currentTurn <= 0 ||
            !StringUtils.hasText(actionKey)
        ) {
            return false;
        }
        List<String> payloadRows = jdbcTemplate.query(
            """
            SELECT payload::text
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ACTION_LOCK'
              AND expires_turn >= ?
            ORDER BY id DESC
            """,
            (rs, rowNum) -> rs.getString(1),
            matchId,
            affectedUserId,
            currentTurn
        );
        if (payloadRows.isEmpty()) {
            return false;
        }
        String normalizedAction = normalize(actionKey);
        String normalizedZone = normalize(zone);
        for (String payloadText : payloadRows) {
            JsonNode payload = effectTextParser.parseEffectJson(payloadText);
            if (payload == null || payload.isNull()) {
                continue;
            }
            if (!matchesActionLockEntry(payload, normalizedAction, normalizedZone, holomemId)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 驗證單筆 ACTION_LOCK payload 是否命中目前行動上下文。
     */
    private boolean matchesActionLockEntry(JsonNode payload, String actionKey, String zone, Long holomemId) {
        JsonNode actionsNode = payload.get("actions");
        if (actionsNode != null && actionsNode.isArray() && !actionsNode.isEmpty()) {
            boolean actionMatched = false;
            for (JsonNode actionNode : actionsNode) {
                if (normalize(actionNode.asText()).equals(actionKey)) {
                    actionMatched = true;
                    break;
                }
            }
            if (!actionMatched) {
                return false;
            }
        }

        JsonNode zonesNode = payload.get("zones");
        if (zonesNode != null && zonesNode.isArray() && !zonesNode.isEmpty()) {
            boolean zoneMatched = false;
            for (JsonNode zoneNode : zonesNode) {
                if (normalize(zoneNode.asText()).equals(zone)) {
                    zoneMatched = true;
                    break;
                }
            }
            if (!zoneMatched) {
                return false;
            }
        }

        JsonNode targetHolomemIdNode = payload.get("targetHolomemId");
        if (targetHolomemIdNode == null || targetHolomemIdNode.isNull()) {
            return true;
        }
        Long expectedHolomemId = asLong(targetHolomemIdNode.asText());
        return expectedHolomemId == null || expectedHolomemId <= 0 || (holomemId != null && holomemId.equals(expectedHolomemId));
    }

    /**
     * 依 targetType、指定卡 instance 與預設策略解析效果目標 Holomem。
     */
    private Long resolveEffectTargetHolomemId(
        Long matchId,
        Long userId,
        String targetType,
        Long requestedTargetCardInstanceId,
        boolean defaultOpponent
    ) {
        String normalizedTargetType = normalize(targetType);
        boolean bothSides = normalizedTargetType.contains("BOTH") || normalizedTargetType.contains("ALL");
        boolean explicitSelf = normalizedTargetType.startsWith("SELF");
        boolean preferOpponent = isOpponentTargetType(normalizedTargetType) || (!explicitSelf && defaultOpponent);
        if (bothSides && requestedTargetCardInstanceId != null && requestedTargetCardInstanceId > 0) {
            Long selfRequested = resolveTargetHolomemId(matchId, userId, requestedTargetCardInstanceId);
            if (selfRequested != null) {
                return selfRequested;
            }
            Long opponentUserId = resolveOpponentUserId(matchId, userId);
            return resolveOpponentTargetHolomemId(
                matchId,
                opponentUserId,
                requestedTargetCardInstanceId
            );
        }
        if (preferOpponent) {
            Long opponentUserId = resolveOpponentUserId(matchId, userId);
            Long opponentTarget = resolveOpponentTargetHolomemId(
                matchId,
                opponentUserId,
                requestedTargetCardInstanceId
            );
            if (opponentTarget != null || !bothSides) {
                return opponentTarget;
            }
            return resolveTargetHolomemId(matchId, userId, requestedTargetCardInstanceId);
        }
        Long selfTarget = resolveTargetHolomemId(matchId, userId, requestedTargetCardInstanceId);
        if (selfTarget != null || !bothSides) {
            return selfTarget;
        }
        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        return resolveOpponentTargetHolomemId(matchId, opponentUserId, requestedTargetCardInstanceId);
    }

    /**
     * 判斷 targetType 是否指向對手側。
     */
    private boolean isOpponentTargetType(String targetType) {
        return targetType.contains("ENEMY") || targetType.contains("OPPONENT");
    }

    /**
     * 讀取指定場上 Holomem 的擁有者 userId。
     */
    private Long resolveHolomemOwner(Long matchId, Long holomemId) {
        if (holomemId == null) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT owner_user_id
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
            """,
            rs -> rs.next() ? rs.getLong("owner_user_id") : null,
            holomemId,
            matchId
        );
    }

    /**
     * 解析 BUFF/DEBUFF 修正值並依效果類型修正正負號。
     */
    private int resolveBuffDebuffModifier(JsonNode effectNode, String effectType) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "modifier", "damageModifier", "amount", "value");
        if (fromFields != 0) {
            return normalizeModifierSign(fromFields, effectType);
        }
        String text = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        Matcher matcher = ARTS_MODIFIER_PATTERN.matcher(text);
        if (matcher.find()) {
            String token = matcher.group(1).replace("＋", "+").replace("−", "-").replaceAll("\\s+", "");
            try {
                int parsed = Integer.parseInt(token);
                return normalizeModifierSign(parsed, effectType);
            } catch (NumberFormatException ignored) {
                // 解析失敗改走 fallback
            }
        }
        return 0;
    }

    /**
     * 正規化修正值符號：DEBUFF 強制為負值。
     */
    private int normalizeModifierSign(int modifier, String effectType) {
        if (modifier == 0) {
            return 0;
        }
        return "DEBUFF".equals(effectType)
            ? -Math.abs(modifier)
            : modifier;
    }

    /**
     * 依 targetType 決定受影響玩家清單（自己、對手或雙方）。
     */
    private List<Long> resolveAffectedUserIds(Long matchId, Long userId, String targetType) {
        String normalizedTargetType = normalize(targetType);
        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        List<Long> affected = new ArrayList<>();
        boolean bothSides = normalizedTargetType.contains("BOTH") || normalizedTargetType.contains("ALL");
        if (bothSides) {
            affected.add(userId);
            if (opponentUserId != null && !opponentUserId.equals(userId)) {
                affected.add(opponentUserId);
            }
            return affected;
        }
        if (isOpponentTargetType(normalizedTargetType)) {
            if (opponentUserId != null) {
                affected.add(opponentUserId);
            }
            return affected;
        }
        affected.add(userId);
        return affected;
    }

    /**
     * 取得當前回合數，無值時回傳 1。
     */
    private int resolveCurrentTurnNumber(Long matchId) {
        Integer turn = jdbcTemplate.query(
            "SELECT turn_number FROM matches WHERE id = ?",
            rs -> rs.next() ? rs.getInt("turn_number") : null,
            matchId
        );
        if (turn == null || turn <= 0) {
            return 1;
        }
        return turn;
    }

    /**
     * 彙總目前生效中的傷害修正值（match_turn_effects）。
     */
    private int resolveActiveDamageModifier(Long matchId, Long affectedUserId, int currentTurn) {
        if (affectedUserId == null || affectedUserId <= 0) {
            return 0;
        }
        Integer modifier = jdbcTemplate.query(
            """
            SELECT COALESCE(SUM(modifier_value), 0) AS total
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'DAMAGE_MODIFIER'
              AND expires_turn >= ?
            """,
            rs -> rs.next() ? rs.getInt("total") : 0,
            matchId,
            affectedUserId,
            currentTurn
        );
        return modifier == null ? 0 : modifier;
    }

    /**
     * 彙總附屬支援卡提供的指定數值加成（HP/ARTS）。
     */
    int resolveAttachedSupportStatBonus(Long matchId, Long matchHolomemId, Pattern pattern) {
        if (matchId == null || matchHolomemId == null || pattern == null) {
            return 0;
        }
        List<String> effectJsonTexts = jdbcTemplate.query(
            """
            SELECT sc.effect_json::text AS effect_json_text
            FROM match_holomem_supports hs
            JOIN support_cards sc ON sc.card_id = hs.support_card_id
            JOIN match_holomems h ON h.id = hs.match_holomem_id
            WHERE hs.match_holomem_id = ?
              AND h.match_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> rs.getString("effect_json_text"),
            matchHolomemId,
            matchId
        );
        if (effectJsonTexts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String effectJsonText : effectJsonTexts) {
            total += extractAttachedSupportStatBonus(effectJsonText, pattern);
        }
        return total;
    }

    /**
     * 載入「常駐藝能加成的受益者」資訊。
     *
     * <p>目前只需要最基本的 3 類條件：
     *
     * <p>1. 站位：例如 `コラボポジション`
     * <p>2. 等級：例如 `Debutホロメン`
     * <p>3. tag：例如 `#4期生`
     *
     * <p>因此這裡只抓規則判斷需要的最小欄位，避免把整個 Holomem 狀態物件搬進來。
     */
    StaticArtBonusTargetContext loadStaticArtBonusTargetContext(Long matchId, Long userId, Long holomemId) {
        Set<String> opponentStageTags = loadOpponentStageTags(matchId, userId);
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   h.current_level,
                   c.name,
                   COALESCE(c.tags_json, '[]'::jsonb)::text AS tags_json_text
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new StaticArtBonusTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    rs.getString("name"),
                    parseTagsJson(rs.getString("tags_json_text")),
                    opponentStageTags
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    /**
     * 載入常駐 Gift 藝能費用減免受益者所需資訊。
     */
    PassiveGiftArtCostReductionTargetContext loadPassiveGiftArtCostReductionTargetContext(
        Long matchId,
        Long userId,
        Long holomemId,
        String attackerArtName
    ) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   h.current_level,
                   c.name,
                   COALESCE(c.tags_json, '[]'::jsonb)::text AS tags_json_text
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new PassiveGiftArtCostReductionTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    rs.getString("name"),
                    attackerArtName,
                    parseTagsJson(rs.getString("tags_json_text"))
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    /**
     * 載入藝能自體加成所需的 Holomem 狀態。
     *
     * <p>目前先沿用和 `PassiveGiftHpTargetContext` 類似的資料結構，因為 `HSD13-007` 這類文案
     * 的核心條件同樣是「這張 Holomem 現在身上究竟有幾張 Cheer」。
     */
    ArtSelfBonusTargetContext loadArtSelfBonusTargetContext(Long matchId, Long userId, Long holomemId) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   h.current_level,
                   mp.current_life,
                   oshi.name AS oshi_card_name,
                   COALESCE(c.tags_json, '[]'::jsonb)::text AS tags_json_text,
                   (
                       SELECT COUNT(*)
                       FROM match_holomem_cheers hc
                       WHERE hc.match_holomem_id = h.id
                   ) AS attached_cheer_count
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            JOIN match_players mp
              ON mp.match_id = h.match_id
             AND mp.user_id = h.owner_user_id
            LEFT JOIN cards oshi ON oshi.card_id = mp.oshi_card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new ArtSelfBonusTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    parseTagsJson(rs.getString("tags_json_text")),
                    rs.getInt("attached_cheer_count"),
                    rs.getInt("current_life"),
                    rs.getString("oshi_card_name")
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    /**
     * 載入指定 Holomem 自己的 passive gift 文案。
     *
     * <p>常駐 HP Gift 和 `HSD08-004` 這種中心位 aura 不同，效果來源就是這張卡自己，
     * 因此不應沿用「只抓我方 CENTER holder」的 loader。
     */
    PassiveGiftHolderContext loadPassiveGiftHolderContext(Long matchId, Long userId, Long holomemId) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   mc.passive_effect_json::text AS passive_effect_json_text
            FROM match_holomems h
            JOIN member_cards mc ON mc.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
              AND mc.passive_effect_json IS NOT NULL
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new PassiveGiftHolderContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    rs.getString("passive_effect_json_text")
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    /**
     * 載入常駐藝能加成可能的 holder。
     *
     * <p>`HSD08-004` 這類舊案例只需要中心位 holder，但像 `HBP05-013` 會把 holder 限制寫成
     * `[センターポジション・コラボポジション限定]`。因此藝能加成入口需要額外把 `COLLAB` holder
     * 也納入，再交由文案 matcher 做最終站位過濾。
     */
    List<PassiveGiftHolderContext> loadPassiveGiftArtBonusHolderContexts(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   mc.passive_effect_json::text AS passive_effect_json_text
            FROM match_holomems h
            JOIN member_cards mc ON mc.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER', 'COLLAB')
              AND mc.passive_effect_json IS NOT NULL
            ORDER BY CASE h.zone
                        WHEN 'CENTER' THEN 1
                        WHEN 'COLLAB' THEN 2
                        ELSE 9
                     END,
                     h.id
            """,
            (rs, rowNum) -> new PassiveGiftHolderContext(
                rs.getLong("id"),
                effectTextParser.normalizeEffectType(rs.getString("zone")),
                rs.getString("passive_effect_json_text")
            ),
            matchId,
            userId
        );
    }

    /**
     * 以單一 holder 的常駐文案判斷是否給攻擊者加成。
     *
     * <p>這裡故意保持保守：
     *
     * <p>- 文案沒有 `アーツ+N` 就不算
     * <p>- 文案要求的站位 / 等級 / tag 任一不符合就不算
     *
     * <p>如此可避免把其他非攻擊加成文案誤判為 `+damage`。
     */
    int resolvePassiveGiftArtBonusFromHolder(
        Long matchId,
        Long userId,
        PassiveGiftHolderContext holderContext,
        StaticArtBonusTargetContext attackerContext,
        String targetZone
    ) {
        String rawText = extractPassiveGiftRawText(holderContext.passiveEffectJsonText());
        if (!StringUtils.hasText(rawText)) {
            return 0;
        }
        int artBonus = extractArtsModifierTotal(rawText)
            + extractPassiveGiftSpecialDamageBonus(rawText, attackerContext, targetZone);
        if (artBonus > 0 && rawText.contains("2ndホロメンがいるなら")) {
            int conditionalExtraBonus = extractArtsModifierTotal(extractClauseAfter(rawText, "さらに"));
            if (conditionalExtraBonus > 0 && !hasStageHolomemWithLevelType(matchId, userId, "SECOND")) {
                artBonus = Math.max(artBonus - conditionalExtraBonus, 0);
            }
        }
        if (artBonus == 0) {
            return 0;
        }
        if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(rawText, holderContext.stageZone())) {
            return 0;
        }
        if (!matchesPassiveGiftAttachedSupportCondition(rawText, holderContext.holomemId())) {
            return 0;
        }
        if (!matchesPassiveGiftArtTargetZoneRestriction(rawText, attackerContext.stageZone())) {
            return 0;
        }
        if (rawText.contains("このホロメンのアーツ")
            && !Objects.equals(holderContext.holomemId(), attackerContext.holomemId())) {
            return 0;
        }
        if (!matchesPassiveGiftOpponentStageTagCondition(rawText, attackerContext.opponentStageTags())) {
            return 0;
        }
        if (!matchesPassiveGiftHistoricalOshiSkillCondition(matchId, userId, rawText)) {
            return 0;
        }
        if (rawText.contains("このホロメンのアーツ")) {
            return artBonus;
        }

        String targetClause = extractPassiveGiftArtBonusTargetClause(rawText);
        if (!matchesPassiveGiftTargetAttachedSupportCondition(targetClause, attackerContext.holomemId())) {
            return 0;
        }
        String normalizedTargetClause = stripPassiveGiftTargetAttachedSupportCondition(targetClause);
        if (normalizedTargetClause.contains("このホロメン以外")
            && Objects.equals(holderContext.holomemId(), attackerContext.holomemId())) {
            return 0;
        }
        SearchCriteria criteria = resolveMemberCriteriaFromRawText(normalizedTargetClause);
        if (StringUtils.hasText(criteria.levelType()) && !criteria.levelType().equals(attackerContext.levelType())) {
            return 0;
        }
        if (StringUtils.hasText(criteria.tag()) && !attackerContext.tags().contains(criteria.tag())) {
            return 0;
        }
        if (!matchesPassiveGiftArtTargetNameCondition(normalizedTargetClause, attackerContext.cardName())) {
            return 0;
        }
        return artBonus;
    }

    private int extractPassiveGiftSpecialDamageBonus(
        String rawText,
        StaticArtBonusTargetContext attackerContext,
        String targetZone
    ) {
        if (!StringUtils.hasText(rawText) || attackerContext == null) {
            return 0;
        }
        int specialDamageBonus = effectTextParser.extractByPattern(rawText, PASSIVE_GIFT_SPECIAL_DAMAGE_BONUS_PATTERN);
        if (specialDamageBonus <= 0) {
            return 0;
        }
        if (rawText.contains("相手のセンターホロメンに与える") && !"CENTER".equals(effectTextParser.normalizeEffectType(targetZone))) {
            return 0;
        }
        String targetClause = extractTrailingClauseBeforeMarker(rawText, "に与える特殊ダメージ");
        if (StringUtils.hasText(targetClause)
            && !matchesPassiveGiftArtTargetNameCondition(targetClause, attackerContext.cardName())) {
            return 0;
        }
        return specialDamageBonus;
    }

    private String extractClauseAfter(String rawText, String marker) {
        if (!StringUtils.hasText(rawText) || !StringUtils.hasText(marker)) {
            return "";
        }
        int index = rawText.indexOf(marker);
        if (index < 0) {
            return "";
        }
        return rawText.substring(index + marker.length());
    }

    private boolean hasStageHolomemWithLevelType(Long matchId, Long userId, String levelType) {
        if (matchId == null || userId == null || !StringUtils.hasText(levelType)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB', 'BACK')
              AND UPPER(COALESCE(current_level, '')) = UPPER(?)
            """,
            Integer.class,
            matchId,
            userId,
            levelType
        );
        return count != null && count > 0;
    }

    /**
     * 以單一 holder 的常駐文案判斷是否減少攻擊者藝能所需 Cheer。
     */
    Map<String, Integer> resolvePassiveGiftArtCostReductionFromHolder(
        Long matchId,
        Long userId,
        PassiveGiftHolderContext holderContext,
        PassiveGiftArtCostReductionTargetContext attackerContext
    ) {
        String rawText = extractPassiveGiftRawText(holderContext.passiveEffectJsonText());
        if (!StringUtils.hasText(rawText)) {
            return Map.of();
        }
        Matcher reductionMatcher = PASSIVE_GIFT_ART_COST_REDUCTION_PATTERN.matcher(rawText);
        if (!reductionMatcher.find()) {
            return Map.of();
        }
        if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(rawText, holderContext.stageZone())) {
            return Map.of();
        }
        if (!matchesPassiveGiftArtTargetZoneRestriction(rawText, attackerContext.stageZone())) {
            return Map.of();
        }
        if (!matchesPassiveGiftHistoricalOshiSkillCondition(matchId, userId, rawText)) {
            return Map.of();
        }
        if (!matchesPassiveGiftReferencedArtNameCondition(rawText, attackerContext.artName())) {
            return Map.of();
        }
        if (rawText.contains("このホロメンのアーツ")) {
            if (!Objects.equals(holderContext.holomemId(), attackerContext.holomemId())) {
                return Map.of();
            }
            return buildPassiveGiftArtCostReductionResult(reductionMatcher);
        }

        String targetClause = extractPassiveGiftArtCostTargetClause(rawText);
        if (!StringUtils.hasText(targetClause)) {
            return Map.of();
        }
        if (targetClause.contains("このホロメン")
            && !Objects.equals(holderContext.holomemId(), attackerContext.holomemId())) {
            return Map.of();
        }
        if (!matchesPassiveGiftTargetAttachedSupportCondition(targetClause, attackerContext.holomemId())) {
            return Map.of();
        }

        String normalizedTargetClause = stripPassiveGiftTargetAttachedSupportCondition(targetClause);
        if (!normalizedTargetClause.contains("このホロメン")) {
            SearchCriteria criteria = resolveMemberCriteriaFromRawText(normalizedTargetClause);
            if (!matchesPassiveGiftArtCostTargetCriteria(criteria, attackerContext)) {
                return Map.of();
            }
        }

        return buildPassiveGiftArtCostReductionResult(reductionMatcher);
    }

    private Map<String, Integer> buildPassiveGiftArtCostReductionResult(Matcher reductionMatcher) {
        if (reductionMatcher == null) {
            return Map.of();
        }
        String color = normalizeColorType(resolveCheerColorFilter(reductionMatcher.group(1)));
        int reduction = Integer.parseInt(reductionMatcher.group(2));
        if (!StringUtils.hasText(color) || reduction <= 0) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put(color, reduction);
        return result;
    }

    private boolean matchesPassiveGiftHistoricalOshiSkillCondition(
        Long matchId,
        Long userId,
        String rawText
    ) {
        if (matchId == null || userId == null || !StringUtils.hasText(rawText)) {
            return false;
        }
        Matcher matcher = PASSIVE_GIFT_REFERENCED_OSHI_SKILL_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return true;
        }
        String skillType = StringUtils.hasText(matcher.group(1)) ? "SP" : null;
        String skillName = matcher.group(2) == null ? "" : matcher.group(2).trim();
        if (!StringUtils.hasText(skillName)) {
            return false;
        }
        Integer count = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND action_type = 'USE_OSHI_SKILL'
              AND (? IS NULL OR payload ->> 'skillType' = ?)
              AND payload ->> 'skillName' = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            skillType,
            skillType,
            skillName
        );
        return count != null && count > 0;
    }

    private boolean matchesPassiveGiftReferencedArtNameCondition(String rawText, String attackerArtName) {
        if (!StringUtils.hasText(rawText)) {
            return false;
        }
        Matcher matcher = PASSIVE_GIFT_REFERENCED_ART_NAME_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return true;
        }
        String requiredArtName = matcher.group(1) == null ? "" : matcher.group(1).trim();
        return StringUtils.hasText(requiredArtName)
            && StringUtils.hasText(attackerArtName)
            && attackerArtName.contains(requiredArtName);
    }

    /**
     * 判斷常駐 Gift 是否要求 holder 身上附著指定名稱的支援卡。
     */
    boolean matchesPassiveGiftAttachedSupportCondition(String rawText, Long holderHolomemId) {
        if (!StringUtils.hasText(rawText) || holderHolomemId == null) {
            return true;
        }
        int attachedIndex = rawText.indexOf("が付いている");
        if (!rawText.contains("が付いている間") && attachedIndex < 0) {
            return true;
        }
        String requirementPrefix = attachedIndex < 0 ? rawText : rawText.substring(0, attachedIndex);
        List<String> requiredSupportNames = giftTriggerMatcher.extractNameTokens(requirementPrefix);
        if (requiredSupportNames.isEmpty()) {
            return true;
        }
        List<String> attachedSupportNames = loadAttachedSupportNames(holderHolomemId);
        if (attachedSupportNames.isEmpty()) {
            return false;
        }
        for (String attachedSupportName : attachedSupportNames) {
            if (!StringUtils.hasText(attachedSupportName)) {
                continue;
            }
            for (String requiredSupportName : requiredSupportNames) {
                if (attachedSupportName.contains(requiredSupportName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判斷常駐 Gift 是否要求「受益者」身上附著指定名稱的支援卡。
     */
    private boolean matchesPassiveGiftTargetAttachedSupportCondition(String targetClause, Long targetHolomemId) {
        if (!StringUtils.hasText(targetClause) || targetHolomemId == null) {
            return true;
        }
        int attachedIndex = targetClause.indexOf("が付いている");
        if (attachedIndex < 0) {
            return true;
        }
        String requirementPrefix = targetClause.substring(0, attachedIndex);
        if (requirementPrefix.contains("マスコット")) {
            return loadAttachedSupportTypes(targetHolomemId).contains("MASCOT");
        }
        if (requirementPrefix.contains("ツール")) {
            return loadAttachedSupportTypes(targetHolomemId).contains("TOOL");
        }
        if (requirementPrefix.contains("ファン")) {
            return loadAttachedSupportTypes(targetHolomemId).contains("FAN");
        }
        List<String> requiredSupportNames = giftTriggerMatcher.extractNameTokens(requirementPrefix);
        if (requiredSupportNames.isEmpty()) {
            return true;
        }
        List<String> attachedSupportNames = loadAttachedSupportNames(targetHolomemId);
        if (attachedSupportNames.isEmpty()) {
            return false;
        }
        for (String attachedSupportName : attachedSupportNames) {
            if (!StringUtils.hasText(attachedSupportName)) {
                continue;
            }
            for (String requiredSupportName : requiredSupportNames) {
                if (attachedSupportName.contains(requiredSupportName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> loadAttachedSupportTypes(Long holomemId) {
        if (holomemId == null) {
            return Set.of();
        }
        List<String> supportTypes = jdbcTemplate.query(
            """
            SELECT hs.support_type
            FROM match_holomem_supports hs
            WHERE hs.match_holomem_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> normalize(rs.getString("support_type")),
            holomemId
        );
        if (supportTypes == null || supportTypes.isEmpty()) {
            return Set.of();
        }
        return supportTypes.stream()
            .filter(StringUtils::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 判斷常駐藝能 buff 是否對目前攻擊者站位生效。
     *
     * <p>這裡只看「受益者」描述，避免把 holder restriction
     * （例如 `[センターポジション・コラボポジション限定]`）誤判成攻擊者一定要在 `COLLAB`。
     */
    private boolean matchesPassiveGiftArtTargetZoneRestriction(String rawText, String attackerStageZone) {
        if (!StringUtils.hasText(rawText)) {
            return true;
        }
        String targetClause = extractPassiveGiftArtBonusTargetClause(rawText);
        String zoneClause = StringUtils.hasText(targetClause) ? targetClause : rawText;
        boolean mentionsCenterHolomem = zoneClause.contains("センターホロメン");
        boolean mentionsCollabHolomem = zoneClause.contains("コラボホロメン");
        boolean mentionsBackHolomem = zoneClause.contains("バックホロメン");
        if (!mentionsCenterHolomem && !mentionsCollabHolomem && !mentionsBackHolomem) {
            return true;
        }
        if (mentionsCenterHolomem && "CENTER".equals(attackerStageZone)) {
            return true;
        }
        if (mentionsCollabHolomem && "COLLAB".equals(attackerStageZone)) {
            return true;
        }
        if (mentionsBackHolomem && "BACK".equals(attackerStageZone)) {
            return true;
        }
        return false;
    }

    /**
     * 判斷常駐 Gift 藝能費用減免的受益者是否符合名稱 / tag / level 條件。
     */
    private boolean matchesPassiveGiftArtCostTargetCriteria(
        SearchCriteria criteria,
        PassiveGiftArtCostReductionTargetContext attackerContext
    ) {
        if (criteria == null || criteria.isEmpty()) {
            return true;
        }
        if (StringUtils.hasText(criteria.levelType()) && !criteria.levelType().equals(attackerContext.levelType())) {
            return false;
        }
        if (StringUtils.hasText(criteria.tag()) && !attackerContext.tags().contains(criteria.tag())) {
            return false;
        }
        if (StringUtils.hasText(criteria.nameContains())
            && !attackerContext.cardName().contains(criteria.nameContains())) {
            return false;
        }
        return true;
    }

    /**
     * 判斷常駐藝能 buff 是否依賴「相手ステージ存在特定 tag」條件。
     */
    private boolean matchesPassiveGiftOpponentStageTagCondition(String rawText, Set<String> opponentStageTags) {
        if (!StringUtils.hasText(rawText)) {
            return true;
        }
        Matcher matcher = OPPONENT_STAGE_TAG_PRESENCE_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return true;
        }
        Set<String> requiredTags = parseTagsFromText(matcher.group(1));
        if (requiredTags.isEmpty()) {
            return false;
        }
        for (String requiredTag : requiredTags) {
            if (opponentStageTags.contains(requiredTag)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> parseTagsFromText(String text) {
        Set<String> tags = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return tags;
        }
        Matcher matcher = INLINE_TAG_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            tags.add("#" + matcher.group(1));
        }
        return tags;
    }

    /**
     * 載入指定 Holomem 目前附著的 support 名稱。
     */
    private List<String> loadAttachedSupportNames(Long holomemId) {
        if (holomemId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_holomem_supports hs
            JOIN cards c ON c.card_id = hs.support_card_id
            WHERE hs.match_holomem_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> rs.getString("name"),
            holomemId
        );
    }

    private Set<String> loadOpponentStageTags(Long matchId, Long userId) {
        if (matchId == null || userId == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbcTemplate.query(
            """
            SELECT DISTINCT tag.value AS tag
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(c.tags_json, '[]'::jsonb)) AS tag(value)
            WHERE h.match_id = ?
              AND h.owner_user_id <> ?
              AND h.zone IN ('CENTER', 'COLLAB', 'BACK')
            """,
            (rs, rowNum) -> rs.getString("tag"),
            matchId,
            userId
        ));
    }

    /**
     * 依藝能 raw text 判斷是否存在「附著 Cheer 數量決定的自體加傷」。
     *
     * <p>這裡刻意保持保守，只先吃明確且已驗證的模板：
     *
     * <p>- `このホロメンのエール1枚につき`
     * <p>- `このアーツ+N`
     *
     * <p>如此可以先讓 `HSD13-007` 正確落地，同時避免把其他尚未完整建模的 BUFF 藝能誤算成
     * 「每張 Cheer 都會放大傷害」。
     */
    int resolveArtTextDamageBonusFromRawText(
        Long matchId,
        Long userId,
        int turnNumber,
        String rawText,
        ArtSelfBonusTargetContext attackerContext
    ) {
        if (!StringUtils.hasText(rawText) || attackerContext == null) {
            return 0;
        }
        int total = 0;

        // `HSD13-007` 的每張 Cheer 加傷和 `HSD07-009` 的低 LIFE 加傷都會寫成 `このアーツ+N`。
        // 這裡先把 raw text 切成單一句子，再各自套條件，避免把同一張卡不同句子的 +N 混在一起重複計算。
        String cheerClause = extractSentenceFromMarker(rawText, "このホロメンのエール1枚につき");
        if (StringUtils.hasText(cheerClause) && cheerClause.contains("このアーツ")) {
            int artBonusPerCheer = extractArtsModifierTotal(cheerClause);
            if (artBonusPerCheer != 0 && attackerContext.attachedCheerCount() > 0) {
                total += artBonusPerCheer * attackerContext.attachedCheerCount();
            }
        }

        String lowLifeClause = extractSentenceFromMarker(rawText, "自分のライフが3以下の時");
        if (StringUtils.hasText(lowLifeClause) && lowLifeClause.contains("このアーツ") && attackerContext.currentLife() <= 3) {
            total += extractArtsModifierTotal(lowLifeClause);
        }

        String ownHolomemArtClause = extractSentenceFromMarker(rawText, "このターンに自分の〈");
        if (StringUtils.hasText(ownHolomemArtClause)
            && ownHolomemArtClause.contains("〉がアーツを使っていたなら")
            && ownHolomemArtClause.contains("このアーツ")) {
            List<String> requiredNames = giftTriggerMatcher.extractNameTokens(ownHolomemArtClause);
            if (didUserUseArtWithNamedHolomemThisTurn(matchId, userId, turnNumber, requiredNames)) {
                total += extractArtsModifierTotal(ownHolomemArtClause);
            }
        }

        String ownOshiSkillClause = extractSentenceFromMarker(rawText, "このターンに自分の推しスキル");
        if (StringUtils.hasText(ownOshiSkillClause)
            && ownOshiSkillClause.contains("使っていたなら")
            && ownOshiSkillClause.contains("このアーツ")) {
            String skillName = extractReferencedOshiSkillName(ownOshiSkillClause);
            if (didUserUseOshiSkillThisTurn(matchId, userId, turnNumber, skillName)) {
                total += extractArtsModifierTotal(ownOshiSkillClause);
            }
        }

        String attachedCheerThresholdClause = extractSentenceFromMarker(rawText, "このホロメンにエールが");
        if (StringUtils.hasText(attachedCheerThresholdClause)
            && attachedCheerThresholdClause.contains("枚以上付いているなら")
            && attachedCheerThresholdClause.contains("このアーツ")) {
            String requiredOshiName = resolveRequiredOshiName(rawText);
            Integer minimumAttachedCheerCount = extractMinimumAttachedCheerCount(attachedCheerThresholdClause);
            if (matchesRequiredOshiName(requiredOshiName, attackerContext.oshiCardName())
                && minimumAttachedCheerCount != null
                && attackerContext.attachedCheerCount() >= minimumAttachedCheerCount) {
                total += extractArtsModifierTotal(attachedCheerThresholdClause);
            }
        }

        return total;
    }

    private Integer extractMinimumAttachedCheerCount(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }
        Matcher matcher = Pattern.compile("このホロメンにエールが([0-9０-９]+)枚以上付いている").matcher(rawText);
        if (!matcher.find()) {
            return null;
        }
        return parseSignedNumber(matcher.group(1));
    }

    private boolean matchesRequiredOshiName(String requiredOshiName, String actualOshiCardName) {
        if (!StringUtils.hasText(requiredOshiName)) {
            return true;
        }
        return StringUtils.hasText(actualOshiCardName) && actualOshiCardName.contains(requiredOshiName);
    }

    private boolean didUserUseArtWithNamedHolomemThisTurn(
        Long matchId,
        Long userId,
        int turnNumber,
        List<String> requiredNames
    ) {
        if (matchId == null || userId == null || turnNumber <= 0 || requiredNames == null || requiredNames.isEmpty()) {
            return false;
        }
        List<String> attackerNames = jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_actions ma
            JOIN cards c ON c.card_id = ma.payload ->> 'attackerCardId'
            WHERE ma.match_id = ?
              AND ma.user_id = ?
              AND ma.turn_number = ?
              AND ma.action_type = 'ATTACK_ART'
            ORDER BY ma.id
            """,
            (rs, rowNum) -> rs.getString("name"),
            matchId,
            userId,
            turnNumber
        );
        if (attackerNames.isEmpty()) {
            return false;
        }
        for (String attackerName : attackerNames) {
            if (containsAnyName(attackerName, requiredNames)) {
                return true;
            }
        }
        return false;
    }

    private boolean didUserUseOshiSkillThisTurn(Long matchId, Long userId, int turnNumber, String skillName) {
        if (matchId == null || userId == null || turnNumber <= 0 || !StringUtils.hasText(skillName)) {
            return false;
        }
        Integer count = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'USE_OSHI_SKILL'
              AND payload ->> 'skillName' = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            turnNumber,
            skillName
        );
        return count != null && count > 0;
    }

    private String extractReferencedOshiSkillName(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        Matcher matcher = PASSIVE_GIFT_REFERENCED_OSHI_SKILL_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(2) == null ? "" : matcher.group(2).trim();
    }

    /**
     * 在藝能擊倒對手後，解析並執行該藝能自己的 follow-up 效果。
     *
     * <p>這裡處理的不是 Gift，也不是支援卡，而是「藝能文案本身」在 down 事件成立後才解鎖的後段效果。
     * 例如 `HSD13-007`：
     *
     * <p>- 前段：依 Cheer 數量提升本次藝能傷害
     * <p>- 後段：只有這次藝能真的把對手打倒時，才從 Cheer Deck 再貼 1 張
     *
     * <p>之所以獨立做成入口，而不是塞進 Gift trigger，是因為這類效果的來源是「本次藝能本身」，
     * 不應與 stage 上其他被動 Gift 共用同一個觸發模型。
     */
    Map<String, Object> applyArtDownTriggeredEffects(
        Long matchId,
        Long userId,
        Long attackerCardInstanceId,
        String artEffectJsonText
    ) {
        String rawText = extractAttachedSupportRawText(artEffectJsonText);
        String followupText = extractArtDownTriggeredClause(rawText);
        if (!StringUtils.hasText(followupText)) {
            return buildNoTriggeredArtEffectSummary(rawText, "藝能沒有擊倒後效果");
        }

        List<String> effectTypes = inferBloomEffectTypes(followupText);
        if (effectTypes.isEmpty()) {
            return buildNoTriggeredArtEffectSummary(followupText, "無法解析藝能擊倒後效果類型");
        }

        ObjectNode effectNode = objectMapper.createObjectNode();
        effectNode.put("type", effectTypes.get(0));
        effectNode.set("effects", objectMapper.valueToTree(effectTypes));
        effectNode.put("rawText", followupText);

        Map<String, Object> summary = applySupportEffect(
            matchId,
            userId,
            effectTypes.get(0),
            effectTextParser.toJsonString(effectNode),
            inferBloomTargetType(effectTypes.get(0)),
            null,
            attackerCardInstanceId
        );
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("triggerType", "ART_DOWNED_OPPONENT");
        wrapped.put("rawText", followupText);
        wrapped.putAll(summary);
        return wrapped;
    }

    /**
     * 從藝能全文中截出「擊倒後才發動」的後半段。
     *
     * <p>目前先支援官方常見句型 `このアーツで相手のホロメンをダウンさせた時、...`。
     * 若將來出現更多變體，再把這個 helper 擴成 pattern list 即可。
     */
    private String extractArtDownTriggeredClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }
        String marker = "このアーツで相手のホロメンをダウンさせた時";
        int markerIndex = rawText.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        String clause = rawText.substring(markerIndex + marker.length()).trim();
        while (clause.startsWith("、") || clause.startsWith("。") || clause.startsWith("：") || clause.startsWith(":")) {
            clause = clause.substring(1).trim();
        }
        return StringUtils.hasText(clause) ? clause : null;
    }

    /**
     * 從指定 marker 開始，擷取到同一句結束為止。
     *
     * <p>用途是把官方 raw text 拆成較小的條件片段，讓像 `HSD13-007`、`HSD07-009` 這種
     * 同時含有多段藝能條件的卡，只解析自己那一句對應的 `アーツ+N`。
     */
    private String extractSentenceFromMarker(String rawText, String marker) {
        if (!StringUtils.hasText(rawText) || !StringUtils.hasText(marker)) {
            return null;
        }
        int markerIndex = rawText.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        String clause = rawText.substring(markerIndex).trim();
        int sentenceEnd = clause.indexOf('。');
        if (sentenceEnd >= 0) {
            clause = clause.substring(0, sentenceEnd);
        }
        return clause.trim();
    }

    /**
     * 建立「本次藝能沒有 down 後 follow-up」的統一摘要。
     */
    private Map<String, Object> buildNoTriggeredArtEffectSummary(String rawText, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("triggerType", "ART_DOWNED_OPPONENT");
        summary.put("rawText", rawText);
        summary.put("requestedEffects", List.of());
        summary.put("executedEffects", List.of());
        summary.put("unsupportedEffects", List.of());
        summary.put("skippedEffects", List.of());
        summary.put("applied", false);
        summary.put("reason", reason);
        return summary;
    }

    /**
     * 由支援卡效果文案擷取對應加成值（可累加多段）。
     */
    private int extractAttachedSupportStatBonus(String effectJsonText, Pattern pattern) {
        if (!StringUtils.hasText(effectJsonText) || pattern == null) {
            return 0;
        }
        String rawText = extractAttachedSupportRawText(effectJsonText);
        if (!StringUtils.hasText(rawText)) {
            return 0;
        }
        int conditionalIndex = rawText.indexOf('◆');
        String baseSegment = conditionalIndex >= 0 ? rawText.substring(0, conditionalIndex) : rawText;
        Matcher matcher = pattern.matcher(baseSegment);
        int total = 0;
        while (matcher.find()) {
            total += parseSignedNumber(matcher.group(1));
        }
        return total;
    }

    private boolean isAttachedSupportHolderClause(String rawText) {
        return rawText.contains("このマスコットが付いているホロメン")
            || rawText.contains("このツールが付いているホロメン")
            || rawText.contains("このファンが付いているホロメン");
    }

    String extractAttachedSupportConditionalTriggerClause(String rawText, String triggerType) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int conditionalIndex = rawText.indexOf('◆');
        String baseSegment = conditionalIndex >= 0 ? rawText.substring(0, conditionalIndex) : rawText;
        String normalizedTriggerType = normalize(triggerType);
        for (String clause : baseSegment.split("[。\\n]")) {
            if (!StringUtils.hasText(clause) || !isAttachedSupportHolderClause(clause)) {
                continue;
            }
            if ("SELF_DOWNED".equals(normalizedTriggerType) && clause.contains("ダウンした時")) {
                return clause.trim();
            }
            if ("DAMAGE_RECEIVED".equals(normalizedTriggerType) && clause.contains("ダメージを受ける時")) {
                return clause.trim();
            }
        }
        return "";
    }

    List<String> inferAttachedSupportConditionalRequestedEffects(
        String triggerClause,
        String effectType,
        String triggerType
    ) {
        if (!StringUtils.hasText(triggerClause)) {
            return List.of();
        }
        List<String> effects = new ArrayList<>();
        if ("DAMAGE_RECEIVED".equals(normalize(triggerType)) && triggerClause.contains("受けるダメージ")) {
            addUniqueEffect(effects, "DAMAGE_REDUCTION");
        }
        if (triggerClause.contains("このファンをアーカイブ") || triggerClause.contains("このツールをアーカイブ")) {
            addUniqueEffect(effects, "ARCHIVE_SUPPORT_COST");
        }
        if (triggerClause.contains("手札") && triggerClause.contains("アーカイブ") && triggerClause.contains("手札に戻")) {
            addUniqueEffect(effects, "ARCHIVE_HAND_COST");
            addUniqueEffect(effects, "RETURN_SUPPORT_TO_HAND");
        }
        if (triggerClause.contains("付け替え")) {
            addUniqueEffect(effects, "REATTACH");
        }
        if (triggerClause.contains("エールデッキ") && triggerClause.contains("送")) {
            addUniqueEffect(effects, "ADD_CHEER");
        }
        if (triggerClause.contains("アーカイブ") && triggerClause.contains("エール") && triggerClause.contains("送")) {
            addUniqueEffect(effects, "ATTACH_ARCHIVE_CHEER");
        }
        if (triggerClause.contains("デッキ") && triggerClause.contains("引")) {
            addUniqueEffect(effects, "DRAW");
        }
        String normalizedEffectType = normalize(effectType);
        if (effects.isEmpty() && StringUtils.hasText(normalizedEffectType)) {
            addUniqueEffect(effects, normalizedEffectType);
        }
        return effects;
    }

    private void addUniqueEffect(List<String> effects, String effectType) {
        if (effects == null || !StringUtils.hasText(effectType)) {
            return;
        }
        String normalizedEffectType = normalize(effectType);
        if (StringUtils.hasText(normalizedEffectType) && !effects.contains(normalizedEffectType)) {
            effects.add(normalizedEffectType);
        }
    }

    boolean hasAttachedSupportOptionalOrCostText(String triggerClause) {
        return StringUtils.hasText(triggerClause)
            && (
                triggerClause.contains("できる")
                    || triggerClause.contains("送れる")
                    || triggerClause.contains("引ける")
                    || triggerClause.contains("付け替えられる")
                    || triggerClause.contains("：")
            );
    }

    /**
     * 解析支援效果 JSON 並抽取可判讀的 raw text。
     */
    String extractAttachedSupportRawText(String effectJsonText) {
        try {
            JsonNode node = objectMapper.readTree(effectJsonText);
            return effectTextParser.normalizeDigits(effectTextParser.extractText(node, "rawText", "rawEffect", "rawHeader"));
        } catch (Exception ignored) {
            return effectTextParser.normalizeDigits(effectJsonText);
        }
    }

    /**
     * 解析 member passive gift JSON 並抽出可判讀的 raw text。
     *
     * <p>官方 passive 效果目前常見欄位是 `キーワード`，但測試資料與部分結構化效果仍可能寫在
     * `rawText / rawEffect / rawHeader`。這裡統一做欄位合併，避免每個靜態判斷點各自猜欄位。
     */
    private String extractPassiveGiftRawText(String passiveEffectJsonText) {
        try {
            JsonNode node = objectMapper.readTree(passiveEffectJsonText);
            return effectTextParser.normalizeDigits(
                effectTextParser.extractText(node, "キーワード", "rawText", "rawEffect", "rawHeader")
            );
        } catch (Exception ignored) {
            return effectTextParser.normalizeDigits(passiveEffectJsonText);
        }
    }

    /**
     * 從靜態文案中擷取藝能加成值。
     *
     * <p>這裡採可累加寫法，而不是只抓第一個 `アーツ+N`，是為了保留未來處理複數敘述的空間。
     */
    private int extractArtsModifierTotal(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return 0;
        }
        Matcher matcher = ARTS_MODIFIER_PATTERN.matcher(rawText);
        int total = 0;
        while (matcher.find()) {
            total += parseSignedNumber(matcher.group(1));
        }
        return total;
    }

    /**
     * 從完整文案中抽出冒號前的成本段。
     *
     * <p>Gift / Bloom / Collab 常見寫法是 `成本：效果`。像 `HSD11-006` 這類卡必須用成本段決定
     * 「可以丟哪張手牌」，不能把冒號後的目標條件也一起拿去限制手牌。
     */
    private String extractCostClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int splitIndex = findClauseSeparator(rawText);
        return splitIndex < 0 ? rawText : rawText.substring(0, splitIndex).trim();
    }

    /**
     * 從完整文案中抽出冒號後的主要效果段。
     *
     * <p>這主要用在像 `送 Cheer` 這種「來源條件在前、目標條件在後」的效果。
     * 若直接用整句文案解析，前段的成本/來源 tag 會污染後段的目標推斷。
     */
    private String extractResolvedEffectClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int splitIndex = findClauseSeparator(rawText);
        return splitIndex < 0 || splitIndex + 1 >= rawText.length() ? rawText : rawText.substring(splitIndex + 1).trim();
    }

    /**
     * 從送 Cheer 效果段中擷取來源描述。
     *
     * <p>例如：
     * - `自分のアーカイブの黄エール1枚を自分の〈虎金妃笑虎〉に送る`
     * 這裡真正決定來源的是前半句 `自分のアーカイブの黄エール1枚`。
     */
    private String extractAddCheerSourceClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        String clause = rawText;
        int sourceStart = -1;
        String[] markers = {
            "自分のエールデッキ",
            "相手のエールデッキ",
            "エールデッキ",
            "自分のアーカイブの",
            "相手のアーカイブの",
            "アーカイブの"
        };
        for (String marker : markers) {
            int index = rawText.lastIndexOf(marker);
            if (index > sourceStart) {
                sourceStart = index;
            }
        }
        if (sourceStart >= 0) {
            clause = rawText.substring(sourceStart);
        }
        int splitIndex = clause.indexOf('を');
        return splitIndex < 0 ? clause.trim() : clause.substring(0, splitIndex).trim();
    }

    /**
     * 從送 Cheer 效果段中擷取目標描述。
     *
     * <p>例如：
     * - `自分のアーカイブの黄エール1枚を自分の〈虎金妃笑虎〉に送る`
     * 這裡真正決定貼到誰的是中段 `自分の〈虎金妃笑虎〉`。
     */
    private String extractAddCheerTargetClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        Matcher matcher = Pattern.compile("を(.+?)に送る").matcher(rawText);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return rawText;
    }

    /**
     * 直接把一小段 raw text 轉成 SearchCriteria。
     *
     * <p>這個 helper 的重點是降低 execution 層建立暫時 JSON probe 的重覆樣板，
     * 讓後續要在其他效果重用相同 parser 時不必再手工包一層 ObjectNode。
     */
    private SearchCriteria resolveSearchCriteriaFromRawText(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return SearchCriteria.empty();
        }
        ObjectNode probe = objectMapper.createObjectNode();
        probe.put("rawText", rawText);
        return searchCriteriaParser.resolveSearchCriteria(probe);
    }

    /**
     * 從目標子句推斷名稱限制。
     *
     * <p>這裡只看 `送 Cheer` 的目標段，避免把來源描述裡的卡名誤當成目標名稱。
     */
    private String resolveTargetNameContains(String targetClause) {
        return resolveSearchCriteriaFromRawText(targetClause).nameContains();
    }

    /**
     * 擷取 `...のアーツ+N` / `...全員のアーツ+N` 前真正決定受益者的子句。
     */
    private String extractPassiveGiftArtBonusTargetClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int markerIndex = rawText.indexOf("のアーツ");
        if (markerIndex >= 0) {
            return rawText.substring(0, markerIndex).trim();
        }
        String specialDamageClause = extractTrailingClauseBeforeMarker(rawText, "に与える特殊ダメージ");
        if (StringUtils.hasText(specialDamageClause)) {
            int opponentTargetIndex = specialDamageClause.indexOf("が相手の");
            if (opponentTargetIndex > 0) {
                return specialDamageClause.substring(0, opponentTargetIndex).trim();
            }
            return specialDamageClause;
        }
        return rawText.trim();
    }

    /**
     * 擷取 `...のアーツに必要な` 前的受益者子句。
     */
    private String extractPassiveGiftArtCostTargetClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        String clause = extractTrailingClauseBeforeMarker(rawText, "のアーツに必要な");
        if (StringUtils.hasText(clause)) {
            return clause;
        }
        return extractTrailingClauseBeforeMarker(rawText, "のアーツ");
    }

    /**
     * 擷取 `...が受けるダメージ` / `...で受けるダメージ` 前真正決定受保護對象的子句。
     */
    private String extractPassiveGiftIncomingDamageReductionTargetClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int gaIndex = rawText.indexOf("が受ける");
        int deIndex = rawText.indexOf("で受ける");
        int markerIndex;
        if (gaIndex >= 0 && deIndex >= 0) {
            markerIndex = Math.min(gaIndex, deIndex);
        } else {
            markerIndex = Math.max(gaIndex, deIndex);
        }
        if (markerIndex < 0) {
            return rawText.trim();
        }
        int clauseStart = Math.max(
            Math.max(rawText.lastIndexOf('、', markerIndex), rawText.lastIndexOf('。', markerIndex)),
            rawText.lastIndexOf('\n', markerIndex)
        );
        return rawText.substring(clauseStart < 0 ? 0 : clauseStart + 1, markerIndex).trim();
    }

    /**
     * 移除受益者子句中「附著指定 support」的前置描述，避免把 support 名稱誤當成受益者卡名。
     */
    private String stripPassiveGiftTargetAttachedSupportCondition(String targetClause) {
        if (!StringUtils.hasText(targetClause)) {
            return "";
        }
        int attachedIndex = targetClause.indexOf("が付いている");
        if (attachedIndex < 0) {
            return targetClause;
        }
        return targetClause.substring(attachedIndex + "が付いている".length()).trim();
    }

    /**
     * 判斷常駐藝能 buff 的受益者是否符合具名角色限制。
     *
     * <p>像 `HSD03-008` 這種文案會在同一子句列出多個 `〈名稱〉`，表示任一名稱符合即可受益。
     * 若子句中沒有具名 token，才 fallback 到既有 `nameContains` 的單一名稱判斷。
     */
    private boolean matchesPassiveGiftArtTargetNameCondition(String targetClause, String attackerCardName) {
        if (!StringUtils.hasText(targetClause)) {
            return true;
        }
        String normalizedCardName = nullToEmpty(attackerCardName);
        List<String> explicitNameTokens = giftTriggerMatcher.extractNameTokens(targetClause);
        if (!explicitNameTokens.isEmpty()) {
            for (String explicitNameToken : explicitNameTokens) {
                if (StringUtils.hasText(explicitNameToken) && normalizedCardName.contains(explicitNameToken)) {
                    return true;
                }
            }
            return false;
        }
        String nameContains = resolveSearchCriteriaFromRawText(targetClause).nameContains();
        return !StringUtils.hasText(nameContains) || normalizedCardName.contains(nameContains);
    }

    private String extractTrailingClauseBeforeMarker(String rawText, String marker) {
        if (!StringUtils.hasText(rawText) || !StringUtils.hasText(marker)) {
            return "";
        }
        int markerIndex = rawText.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        int clauseStart = Math.max(
            Math.max(rawText.lastIndexOf('、', markerIndex), rawText.lastIndexOf('。', markerIndex)),
            rawText.lastIndexOf('\n', markerIndex)
        );
        return rawText.substring(clauseStart < 0 ? 0 : clauseStart + 1, markerIndex).trim();
    }

    private int findClauseSeparator(String rawText) {
        int fullWidthIndex = rawText.indexOf('：');
        int halfWidthIndex = rawText.indexOf(':');
        if (fullWidthIndex < 0) {
            return halfWidthIndex;
        }
        if (halfWidthIndex < 0) {
            return fullWidthIndex;
        }
        return Math.min(fullWidthIndex, halfWidthIndex);
    }

    /**
     * 把靜態常駐文案轉成可重用的條件模型。
     *
     * <p>雖然這裡不是 search 效果，但條件語彙本質上仍是同一組：
     *
     * <p>- `#4期生`
     * <p>- `Debut`
     * <p>- `2nd`
     *
     * <p>沿用 `SearchCriteriaParser` 可以避免同一套 tag / level 解析規則分叉。
     */
    private SearchCriteria resolveMemberCriteriaFromRawText(String rawText) {
        ObjectNode probe = objectMapper.createObjectNode();
        probe.put("rawText", rawText);
        return searchCriteriaParser.resolveSearchCriteria(probe);
    }

    /**
     * 安全把 tags JSON 轉成字串集合。
     */
    private Set<String> parseTagsJson(String tagsJsonText) {
        if (!StringUtils.hasText(tagsJsonText)) {
            return Set.of();
        }
        try {
            JsonNode node = objectMapper.readTree(tagsJsonText);
            if (node == null || !node.isArray()) {
                return Set.of();
            }
            Set<String> tags = new LinkedHashSet<>();
            for (JsonNode child : node) {
                if (child != null && child.isTextual() && StringUtils.hasText(child.asText())) {
                    tags.add(child.asText().trim());
                }
            }
            return tags;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    /**
     * 解析帶正負號的數字字串（全形符號相容）。
     */
    private int parseSignedNumber(String token) {
        if (!StringUtils.hasText(token)) {
            return 0;
        }
        String normalized = token.replace("＋", "+").replace("−", "-").replaceAll("\\s+", "");
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 檢查來源字串是否包含候選名稱中的任一項。
     */
    private boolean containsAnyName(String source, List<String> candidates) {
        if (!StringUtils.hasText(source) || candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && source.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 cheer 張數，若無法判讀則回傳預設值。
     */
    private int resolveCheerCount(JsonNode effectNode, int defaultValue) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        int byText = effectTextParser.extractByPattern(effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect")), CHEER_COUNT_PATTERN);
        if (byText > 0) {
            return byText;
        }
        return defaultValue;
    }

    /**
     * 在場地容量限制下，解析可放置的實際舞台區位。
     */
    private String resolveAvailableStageZone(Long matchId, Long userId, String preferredZone) {
        String preferred = normalize(preferredZone);
        int centerCount = countHolomemsInZone(matchId, userId, "CENTER");
        int backCount = countHolomemsInZone(matchId, userId, "BACK");

        if ("CENTER".equals(preferred) && centerCount == 0) {
            return "CENTER";
        }
        if ("BACK".equals(preferred) && backCount < 5) {
            return "BACK";
        }
        if (backCount < 5) {
            return "BACK";
        }
        if (centerCount == 0) {
            return "CENTER";
        }
        return "";
    }

    /**
     * 計算玩家在指定區位的 Holomem 數量。
     */
    private int countHolomemsInZone(Long matchId, Long userId, String zone) {
        Integer count = jdbcTemplate.queryForObject(
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
            zone
        );
        return count == null ? 0 : count;
    }

    /**
     * 將指定 Holomem 身上的 cheer 全部歸檔，回傳成功移動的卡 instance id。
     */
    private List<Long> archiveAttachedCheerCards(Long matchId, Long matchHolomemId, Long ownerUserId) {
        if (matchHolomemId == null || ownerUserId == null) {
            return List.of();
        }
        Long lunaKnightReattachTargetHolomemId = resolveLunaKnightCheerReattachTargetHolomemId(
            matchId,
            ownerUserId,
            matchHolomemId
        );
        List<Map<String, Object>> cheerRows = jdbcTemplate.query(
            """
            SELECT id, cheer_card_id, match_card_id
            FROM match_holomem_cheers
            WHERE match_holomem_id = ?
            ORDER BY id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("cheer_card_id", rs.getString("cheer_card_id"));
                long matchCardId = rs.getLong("match_card_id");
                row.put("match_card_id", rs.wasNull() ? null : matchCardId);
                return row;
            },
            matchHolomemId
        );
        if (cheerRows.isEmpty()) {
            return List.of();
        }
        List<Long> archived = new ArrayList<>();
        for (Map<String, Object> row : cheerRows) {
            Long cheerRowId = asLong(row.get("id"));
            String cheerCardId = asText(row.get("cheer_card_id"));
            Long matchCardId = asLong(row.get("match_card_id"));
            if (
                lunaKnightReattachTargetHolomemId != null
                    && cheerRowId != null
                    && isLunaKnightCheerCard(cheerCardId)
            ) {
                int moved = jdbcTemplate.update(
                    """
                    UPDATE match_holomem_cheers
                    SET match_holomem_id = ?
                    WHERE id = ?
                      AND match_holomem_id = ?
                    """,
                    lunaKnightReattachTargetHolomemId,
                    cheerRowId,
                    matchHolomemId
                );
                if (moved == 1) {
                    continue;
                }
            }
            Long archivedCardInstanceId = moveCheerCardInstanceToArchive(matchId, ownerUserId, matchCardId, cheerCardId);
            if (archivedCardInstanceId != null) {
                archived.add(archivedCardInstanceId);
            }
        }
        return archived;
    }

    private Long resolveLunaKnightCheerReattachTargetHolomemId(Long matchId, Long ownerUserId, Long sourceHolomemId) {
        if (matchId == null || ownerUserId == null || sourceHolomemId == null) {
            return null;
        }
        String sourceZone = jdbcTemplate.query(
            """
            SELECT zone
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? normalize(rs.getString("zone")) : null,
            sourceHolomemId,
            matchId,
            ownerUserId
        );
        if (!"CENTER".equals(sourceZone)) {
            return null;
        }
        Long currentTurnPlayerId = jdbcTemplate.query(
            """
            SELECT current_turn_player_id
            FROM matches
            WHERE id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? asLong(rs.getObject("current_turn_player_id")) : null,
            matchId
        );
        if (currentTurnPlayerId == null || Objects.equals(currentTurnPlayerId, ownerUserId)) {
            return null;
        }
        Integer collabHolderCount = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'COLLAB'
              AND card_id = 'HBP06-030'
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            ownerUserId
        );
        if (collabHolderCount == null || collabHolderCount <= 0) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT h.id
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'BACK'
              AND c.name LIKE '%姫森ルーナ%'
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId
        );
    }

    private boolean isLunaKnightCheerCard(String cheerCardId) {
        if (!StringUtils.hasText(cheerCardId)) {
            return false;
        }
        Boolean matched = jdbcTemplate.query(
            """
            SELECT c.name LIKE '%ルーナイト%'
            FROM cards c
            WHERE c.card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getBoolean(1) : false,
            cheerCardId
        );
        return Boolean.TRUE.equals(matched);
    }

    /**
     * 將指定 Holomem 身上的支援卡全部歸檔。
     */
    private List<Long> archiveAttachedSupportCards(Long matchId, Long matchHolomemId, Long ownerUserId) {
        if (matchHolomemId == null || ownerUserId == null) {
            return List.of();
        }
        List<Long> supportCardInstanceIds = jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomem_supports
            WHERE match_holomem_id = ?
            ORDER BY id
            """,
            (rs, rowNum) -> rs.getLong("match_card_id"),
            matchHolomemId
        );
        if (supportCardInstanceIds.isEmpty()) {
            return List.of();
        }
        List<Long> archived = new ArrayList<>();
        for (Long supportCardInstanceId : supportCardInstanceIds) {
            Long archivedCardInstanceId = moveSupportCardInstanceToArchive(matchId, ownerUserId, supportCardInstanceId);
            if (archivedCardInstanceId != null) {
                archived.add(archivedCardInstanceId);
            }
        }
        return archived;
    }

    /**
     * 記錄 Holomem 疊卡關聯與堆疊順序（Bloom 繼承用）。
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
     * 將 Holomem 疊卡序列整批歸檔，並保留由上到下順序。
     */
    private List<Long> archiveHolomemStackCards(Long matchId, Long matchHolomemId, Long ownerUserId) {
        if (matchHolomemId == null || ownerUserId == null) {
            return List.of();
        }
        List<Long> stackCardInstanceIds = jdbcTemplate.query(
            """
            SELECT s.match_card_id
            FROM match_holomem_stack_cards s
            JOIN match_cards mc ON mc.id = s.match_card_id
            WHERE s.match_holomem_id = ?
              AND mc.match_id = ?
              AND mc.owner_user_id = ?
            ORDER BY s.stack_order DESC, s.id DESC
            """,
            (rs, rowNum) -> rs.getLong("match_card_id"),
            matchHolomemId,
            matchId,
            ownerUserId
        );
        if (stackCardInstanceIds.isEmpty()) {
            return List.of();
        }
        List<Long> archived = new ArrayList<>();
        for (Long stackCardInstanceId : stackCardInstanceIds) {
            int archiveOrder = nextZoneOrder(matchId, ownerUserId, "ARCHIVE");
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
                """,
                archiveOrder,
                stackCardInstanceId,
                matchId,
                ownerUserId
            );
            if (updated == 1) {
                archived.add(stackCardInstanceId);
            }
        }
        return archived;
    }

    /**
     * 將指定 cheer card_id 的場上實例移入 ARCHIVE，回傳該 instance id。
     */
    private Long moveCheerCardInstanceToArchive(Long matchId, Long ownerUserId, String cheerCardId) {
        return moveCheerCardInstanceToArchive(matchId, ownerUserId, null, cheerCardId);
    }

    private Long moveCheerCardInstanceToArchive(
        Long matchId,
        Long ownerUserId,
        Long cheerCardInstanceId,
        String cheerCardId
    ) {
        if ((cheerCardInstanceId == null || cheerCardInstanceId <= 0) && !StringUtils.hasText(cheerCardId)) {
            return null;
        }
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
        int archiveOrder = nextZoneOrder(matchId, ownerUserId, "ARCHIVE");
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
            archiveOrder,
            resolvedCardInstanceId,
            matchId,
            ownerUserId
        );
        return updated == 1 ? resolvedCardInstanceId : null;
    }

    /**
     * 將指定支援卡實例由 STAGE 移入 ARCHIVE。
     */
    private Long moveSupportCardInstanceToArchive(Long matchId, Long ownerUserId, Long supportCardInstanceId) {
        if (supportCardInstanceId == null || supportCardInstanceId <= 0) {
            return null;
        }
        int archiveOrder = nextZoneOrder(matchId, ownerUserId, "ARCHIVE");
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
            archiveOrder,
            supportCardInstanceId,
            matchId,
            ownerUserId
        );
        return updated == 1 ? supportCardInstanceId : null;
    }

    /**
     * 解析己方目標 Holomem：優先指定卡，其次 CENTER，最後任一可用場上目標。
     */
    private Long resolveTargetHolomemId(Long matchId, Long userId, Long targetHolomemCardInstanceId) {
        if (targetHolomemCardInstanceId != null && targetHolomemCardInstanceId > 0) {
            Long directMatch = jdbcTemplate.query(
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
            if (directMatch != null) {
                return directMatch;
            }
            Long stackedMatch = jdbcTemplate.query(
                """
                SELECT h.id
                FROM match_holomems h
                JOIN match_holomem_stack_cards s ON s.match_holomem_id = h.id
                WHERE h.match_id = ?
                  AND h.owner_user_id = ?
                  AND s.match_card_id = ?
                ORDER BY s.stack_order DESC, h.id
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                userId,
                targetHolomemCardInstanceId
            );
            if (stackedMatch != null) {
                return stackedMatch;
            }
        }
        Long centerId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        if (centerId != null) {
            return centerId;
        }
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
            ORDER BY CASE zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
    }

    /**
     * 由 match_holomems.id 反查對應的 match_cards.id。
     */
    private Long resolveHolomemCardInstanceId(Long matchHolomemId) {
        if (matchHolomemId == null) {
            return null;
        }
        return jdbcTemplate.query(
            "SELECT match_card_id FROM match_holomems WHERE id = ?",
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchHolomemId
        );
    }

    /**
     * 檢查同一張 Gift 是否在本回合已觸發過（turn once）。
     */
    boolean isGiftAlreadyUsedThisTurn(Long matchId, Long userId, int turnNumber, Long holderHolomemId) {
        return giftTurnUsageReader.isGiftAlreadyUsedThisTurn(matchId, userId, turnNumber, holderHolomemId);
    }

    /**
     * 取得可附加的 cheer 卡候選，依 CHEER_DECK > ARCHIVE > HAND 優先。
     */
    private Map<String, Object> findAttachableCheerCard(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, mc.zone
            FROM match_cards mc
            JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone IN ('CHEER_DECK','ARCHIVE','HAND')
            ORDER BY CASE mc.zone WHEN 'CHEER_DECK' THEN 1 WHEN 'ARCHIVE' THEN 2 WHEN 'HAND' THEN 3 ELSE 9 END,
                     mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("zone", rs.getString("zone"));
                return row;
            },
            matchId,
            userId
        );
    }

    /**
     * 從指定區域挑選一張 cheer 卡候選。
     */
    private Map<String, Object> findCheerCardFromZone(Long matchId, Long userId, String zone) {
        return findCheerCardFromZone(matchId, userId, zone, SearchCriteria.empty());
    }

    /**
     * 從指定區域挑選一張符合條件的 Cheer。
     *
     * <p>這裡保留舊的 zone-only 版本，同時新增可帶 SearchCriteria 的 overload，
     * 讓 `黄エール`、`赤エール` 這類官方文案能沿用同一套過濾能力，而不是另外寫成特例 SQL。
     */
    private Map<String, Object> findCheerCardFromZone(Long matchId, Long userId, String zone, SearchCriteria criteria) {
        String normalizedZone = normalize(zone);
        if (!"CHEER_DECK".equals(normalizedZone) && !"ARCHIVE".equals(normalizedZone) && !"STAGE".equals(normalizedZone)) {
            return null;
        }
        List<Map<String, Object>> candidates = loadCandidatesFromZone(matchId, userId, normalizedZone, criteria, false);
        if (candidates.isEmpty()) {
            return null;
        }
        Map<String, Object> candidate = candidates.get(0);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", candidate.get("id"));
        row.put("card_id", candidate.get("card_id"));
        row.put("zone", normalizedZone);
        return row;
    }

    /**
     * 由 matches 的 player_a/player_b 解析對手 userId。
     */
    private Long resolveOpponentUserId(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT player_a_id, player_b_id
            FROM matches
            WHERE id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Long a = asLong(rs.getObject("player_a_id"));
                Long b = asLong(rs.getObject("player_b_id"));
                if (a != null && !a.equals(userId)) {
                    return a;
                }
                if (b != null && !b.equals(userId)) {
                    return b;
                }
                return null;
            },
            matchId
        );
    }

    /**
     * 解析對手側目標 Holomem：優先指定卡，其次 CENTER，最後任一場上目標。
     */
    private Long resolveOpponentTargetHolomemId(
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
                SELECT id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND match_card_id = ?
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                opponentUserId,
                requestedTargetCardInstanceId
            );
        }
        Long center = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            opponentUserId
        );
        if (center != null) {
            return center;
        }
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
            ORDER BY CASE zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            opponentUserId
        );
    }

    /**
     * 取得對手 COLLAB 區位的 Holomem 卡片實例 id。
     */
    Long resolveOpponentCollabCardInstanceId(Long matchId, Long userId) {
        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        if (opponentUserId == null || opponentUserId <= 0) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'COLLAB'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            opponentUserId
        );
    }

    /**
     * 執行一次失去生命：LIFE 頂牌移到 ARCHIVE，並同步扣減 current_life。
     */
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
        int archiveOrder = nextZoneOrder(matchId, ownerUserId, "ARCHIVE");
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

    /**
     * 結算 Down 觸發的額外效果（例如 Buzz 的額外失去生命），走 Action Pipeline。
     */
    Map<String, Object> executeDownEvent(
        Long matchId,
        Long actorUserId,
        Long downedOwnerUserId,
        String downedCardId,
        int currentTurn,
        boolean applyLifeLoss,
        String downedStageZone
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("triggered", false);
        summary.put("effectType", "DOWN_EVENT");
        summary.put("downedCardId", downedCardId);
        summary.put("downedOwnerUserId", downedOwnerUserId);
        summary.put("downedStageZone", normalize(downedStageZone));
        summary.put("turnNumber", currentTurn);
        if (matchId == null || downedOwnerUserId == null || !StringUtils.hasText(downedCardId)) {
            return summary;
        }
        String passiveText = loadPassiveEffectText(downedCardId);
        String extraText = loadExtraEffectText(passiveText);
        String giftText = loadGiftEffectText(passiveText);
        String normalizedExtraText = effectTextParser.normalizeDigits(extraText);
        int requestedLifeLoss = 0;
        String effectRawText = null;
        if (StringUtils.hasText(normalizedExtraText) && normalizedExtraText.contains("ダウンした時")) {
            requestedLifeLoss = resolveDownExtraLifeCount(
                objectMapper.valueToTree(Map.of("rawText", normalizedExtraText))
            );
            effectRawText = extraText;
        }
        // Gift-only down-event life modifiers (e.g. HSD09-007) still require a default life-loss baseline on non-center down.
        if (
            requestedLifeLoss <= 0
                && StringUtils.hasText(giftText)
                && giftText.contains("このホロメンがダウンした時")
                && giftText.contains("減るライフ")
                && !"CENTER".equals(normalize(downedStageZone))
        ) {
            requestedLifeLoss = 1;
            effectRawText = giftText;
        }
        if (requestedLifeLoss <= 0) {
            summary.put("reason", "NO_EXTRA_LIFE_LOSS");
            return summary;
        }
        int lifeLossModifier = resolveDownEventLifeLossModifier(
            matchId,
            downedOwnerUserId,
            downedCardId,
            downedStageZone,
            giftText,
            requestedLifeLoss
        );
        int resolvedLifeLoss = Math.max(requestedLifeLoss + lifeLossModifier, 0);

        List<Long> lostLifeCardInstanceIds = new ArrayList<>();
        if (applyLifeLoss && resolvedLifeLoss > 0) {
            EffectContext context = new EffectContext(
                matchId,
                actorUserId,
                Math.max(currentTurn, 1),
                "DOWN_EVENT",
                null,
                downedCardId
            );
            ReduceLifeAction reduceLifeAction = new ReduceLifeAction(
                downedOwnerUserId,
                resolvedLifeLoss,
                "DOWN_EVENT_EXTRA_LIFE"
            );
            List<ActionResult> results = gameActionExecutor.execute(context, List.of(reduceLifeAction));
            if (!results.isEmpty() && results.get(0).success()) {
                lostLifeCardInstanceIds.addAll(extractLostLifeCardInstanceIds(results.get(0).details()));
            }
        }

        summary.put("triggered", true);
        summary.put("deferred", !applyLifeLoss);
        summary.put("rawText", effectRawText);
        summary.put("requestedLifeLoss", requestedLifeLoss);
        summary.put("lifeLossModifier", lifeLossModifier);
        summary.put("resolvedLifeLoss", resolvedLifeLoss);
        summary.put("appliedLifeLoss", lostLifeCardInstanceIds.size());
        summary.put("lifeReduced", !lostLifeCardInstanceIds.isEmpty());
        summary.put("lostLifeCardInstanceId", lostLifeCardInstanceIds.isEmpty() ? null : lostLifeCardInstanceIds.get(0));
        summary.put("lostLifeCardInstanceIds", lostLifeCardInstanceIds);
        return summary;
    }

    private int resolveDownEventLifeLossModifier(
        Long matchId,
        Long downedOwnerUserId,
        String downedCardId,
        String downedStageZone,
        String giftText,
        int requestedLifeLoss
    ) {
        if (
            matchId == null
                || downedOwnerUserId == null
                || requestedLifeLoss <= 0
                || !StringUtils.hasText(downedCardId)
                || !StringUtils.hasText(giftText)
        ) {
            return 0;
        }
        if (!giftText.contains("このホロメンがダウンした時")) {
            return 0;
        }
        if (!giftText.contains("減るライフ")) {
            return 0;
        }
        if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(giftText, normalize(downedStageZone))) {
            return 0;
        }
        if (!matchesGiftTurnOwnershipCondition(matchId, downedOwnerUserId, giftText)) {
            return 0;
        }
        if (!matchesGiftLifeComparisonCondition(matchId, downedOwnerUserId, giftText)) {
            return 0;
        }
        String normalizedGiftText = effectTextParser.normalizeDigits(giftText);
        int reduction = effectTextParser.extractByPattern(normalizedGiftText, DOWN_EXTRA_LIFE_MINUS_PATTERN);
        if (reduction <= 0) {
            return 0;
        }
        return -Math.min(reduction, requestedLifeLoss);
    }

    /**
     * 從被動效果 JSON/文字中抽出「エクストラ」欄位文字。
     */
    private String loadExtraEffectText(String passiveText) {
        if (!StringUtils.hasText(passiveText)) {
            return null;
        }
        JsonNode passiveNode = effectTextParser.parseEffectJson(passiveText);
        if (passiveNode != null && passiveNode.isObject()) {
            JsonNode extraNode = passiveNode.get("エクストラ");
            if (extraNode != null && extraNode.isTextual() && StringUtils.hasText(extraNode.asText())) {
                return extraNode.asText();
            }
            JsonNode extraEffectNode = passiveNode.get("extraEffect");
            if (extraEffectNode != null && extraEffectNode.isTextual() && StringUtils.hasText(extraEffectNode.asText())) {
                return extraEffectNode.asText();
            }
            if (extraEffectNode != null && extraEffectNode.isObject()) {
                String rawText = readText(extraEffectNode, "rawText", "rawEffect", "text");
                if (StringUtils.hasText(rawText)) {
                    return rawText;
                }
            }
        }
        if (passiveText.contains("エクストラ")) {
            return passiveText;
        }
        return null;
    }

    /**
     * 從效果摘要中抽取所有生命牌 instance id（兼容多種鍵名）。
     */
    private List<Long> extractLostLifeCardInstanceIds(Map<String, Object> effectSummary) {
        if (effectSummary == null || effectSummary.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        Long single = asLong(effectSummary.get("lostLifeCardInstanceId"));
        if (single != null && single > 0) {
            ids.add(single);
        }
        Object listObject = effectSummary.get("lostLifeCardInstanceIds");
        if (listObject instanceof List<?> list) {
            for (Object value : list) {
                Long id = asLong(value);
                if (id != null && id > 0 && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        // ActionResult(REDUCE_LIFE) uses lifeCardInstanceIds; down-event summary uses lostLifeCardInstanceIds.
        Object actionListObject = effectSummary.get("lifeCardInstanceIds");
        if (actionListObject instanceof List<?> list) {
            for (Object value : list) {
                Long id = asLong(value);
                if (id != null && id > 0 && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * 判斷此次擊倒是否屬於「不減生命」例外。
     */
    private boolean isDownWithoutLifeLoss(JsonNode effectNode) {
        if (effectNode != null && effectNode.has("downDoesNotReduceLife")) {
            return effectNode.path("downDoesNotReduceLife").asBoolean(false);
        }
        String merged = effectTextParser.extractText(effectNode, "rawText", "rawEffect");
        return StringUtils.hasText(merged) && merged.contains("ダウンしても相手のライフは減らない");
    }

    /**
     * 解析 Down 額外生命扣減值（支援結構化欄位與日文文案）。
     */
    private int resolveDownExtraLifeCount(JsonNode effectNode) {
        int fromField = effectTextParser.extractInt(effectNode, 0, "extraLifeLoss", "lifeLoss", "value", "amount");
        if (fromField > 0) {
            return fromField;
        }
        String merged = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        int byPattern = effectTextParser.extractByPattern(merged, DOWN_EXTRA_LIFE_PATTERN);
        if (byPattern > 0) {
            return byPattern;
        }
        int minusPattern = effectTextParser.extractByPattern(merged, DOWN_EXTRA_LIFE_MINUS_PATTERN);
        if (minusPattern > 0) {
            return minusPattern;
        }
        if (StringUtils.hasText(merged) && merged.contains("ライフ")) {
            return 1;
        }
        return 0;
    }

    /**
     * 通用字串正規化：null 安全、trim、轉大寫。
     */
    private String normalize(Object value) {
        return MatchEffectValueHelper.normalize(value);
    }

    /**
     * 安全轉 Long，支援 Number 與字串輸入。
     */
    private Long asLong(Object value) {
        return MatchEffectValueHelper.asLong(value);
    }

    /**
     * 安全轉 int，失敗時回 0。
     */
    int asInt(Object value) {
        return MatchEffectValueHelper.asInt(value);
    }

    /**
     * null 安全字串轉換。
     */
    private String asText(Object value) {
        return MatchEffectValueHelper.asText(value);
    }

    /**
     * 寬鬆布林轉換（Boolean/Number/String）。
     */
    private boolean toBoolean(Object value) {
        return MatchEffectValueHelper.toBoolean(value);
    }

    /**
     * 前端決策候選卡資料模型。
     */
    public record TriggeredEffectPreview(
        boolean hasEffect,
        List<String> effectTypes,
        String rawText,
        Integer diceRoll
    ) {}

    /**
     * 前端決策候選卡資料模型。
     */
    public record DecisionCandidate(
        Long cardInstanceId,
        String cardId,
        String name,
        String cardType,
        String levelType,
        String zone
    ) {}

    /**
     * Collab 效果場況上下文（供規則分支判斷使用）。
     */
    record CollabRuntimeContext(
        Long selfHolomemCardInstanceId,
        int turnNumber,
        boolean secondPlayerFirstTurn,
        Long centerHolomemCardInstanceId,
        String centerHolomemCardId,
        String centerHolomemName,
        String oshiCardName,
        Long firstBackHolomemCardInstanceId,
        int ownHandCount,
        int opponentHandCount,
        boolean opponentHandHasSupport,
        int ownedStageCheerCount
    ) {}

    /**
     * Bloom 效果場況上下文（目前僅補來源等級與通用場況）。
     */
    record BloomRuntimeContext(
        String sourceLevelType,
        CollabRuntimeContext common
    ) {}

    /**
     * 支援卡互動決策計畫（effectType、選牌數、候選列表）。
     */
    public record SupportDecisionPlan(
        String effectType,
        int minSelect,
        int maxSelect,
        List<DecisionCandidate> candidates
    ) {}

    /**
     * Bloom/Collab 解析後的效果計畫模型。
     */
    record BloomEffectPlan(
        boolean hasBloomEffect,
        List<String> effectTypes,
        JsonNode effectNode,
        String rawText,
        Integer diceRoll
    ) {}

    /**
     * 骰子解析結果（最終值、所有擲骰、挑選策略與是否固定值）。
     */
    private record DiceResolution(
        int chosenRoll,
        List<Integer> rolls,
        String strategy,
        boolean fixedApplied
    ) {}

    private record Hbp01123RerollResult(
        boolean applied,
        List<Integer> rerolledRolls,
        Long archivedSupportCardInstanceId
    ) {
        private static Hbp01123RerollResult none() {
            return new Hbp01123RerollResult(false, List.of(), null);
        }
    }
}
