package com.hololive.cardgame.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.AtomicAction;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.EffectResolver;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.game.action.HolomemMoveZoneAction;
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
import java.util.Locale;
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

    private static final Pattern SPECIAL_DAMAGE_PATTERN = Pattern.compile("特殊ダメージ\\s*(\\d+)");
    private static final Pattern DAMAGE_PATTERN = Pattern.compile("ダメージ\\s*(\\d+)");
    private static final Pattern DRAW_COUNT_PATTERN = Pattern.compile("デッキを\\s*(\\d+)\\s*枚引く");
    private static final Pattern DRAW_COUNT_FALLBACK_PATTERN = Pattern.compile("(\\d+)\\s*枚引く");
    private static final Pattern HEAL_PATTERN = Pattern.compile("HP\\s*(\\d+)\\s*回復");
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
    private static final Pattern ATTACHED_SUPPORT_DAMAGE_REDUCTION_PATTERN = Pattern.compile(
        "受けるダメージ\\s*[−-]\\s*(\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_HP_PATTERN = Pattern.compile(
        "HP\\s*([+＋−-]\\s*\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_DAMAGE_REDUCTION_VALUE_PATTERN = Pattern.compile(
        "受ける(?:アーツ)?ダメージ\\s*[ー\\-−]\\s*(\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_DICE_ODD_DAMAGE_REDUCTION_PATTERN = Pattern.compile(
        "奇数なら、[^。]*?受ける(?:アーツ)?ダメージ\\s*[ー\\-−]\\s*(\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_DICE_EVEN_DAMAGE_REDUCTION_PATTERN = Pattern.compile(
        "偶数なら、[^。]*?受ける(?:アーツ)?ダメージ\\s*[ー\\-−]\\s*(\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_ART_COST_REDUCTION_PATTERN = Pattern.compile(
        "アーツ(?:[「『][^」』]+[」』])?に必要な\\s*(赤|青|緑|白|紫|黄|無色)\\s*[ー\\-−]\\s*(\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_SELF_DAMAGE_CLAUSE_PATTERN = Pattern.compile(
        "このホロメン(?:[^。]*?)受ける(?:アーツ)?ダメージ"
    );
    private static final Pattern PASSIVE_GIFT_OWN_COLLAB_DAMAGE_CLAUSE_PATTERN = Pattern.compile(
        "自分のコラボホロメン(?:[^。]*?)受ける(?:アーツ)?ダメージ"
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
    private static final Pattern BATON_TOUCH_COST_MODIFIER_PATTERN = Pattern.compile("バトンタッチに必要な無色\\s*[+＋]\\s*(\\d+)");
    private static final Pattern DOWN_EXTRA_LIFE_PATTERN = Pattern.compile("ライフを\\s*(\\d+)\\s*つ?減ら");
    private static final Pattern DOWN_EXTRA_LIFE_MINUS_PATTERN = Pattern.compile("ライフ\\s*[ー\\-−]\\s*(\\d+)");
    private static final Pattern PASSIVE_GIFT_SPECIAL_DAMAGE_BONUS_PATTERN = Pattern.compile("特殊ダメージ\\s*[+＋]\\s*(\\d+)");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DiceService diceService;
    private final EffectResolver effectResolver;
    private final GameActionExecutor gameActionExecutor;
    private final EffectTextParser effectTextParser;
    private final GiftTriggerMatcher giftTriggerMatcher;
    private final SearchCriteriaParser searchCriteriaParser;
    private final MatchEffectSearchService searchService;
    private final MatchGiftTriggerConditionService giftTriggerConditionService;
    private final MatchGiftTriggerContextService giftTriggerContextService;
    private final PassiveGiftTriggerActionWriter passiveGiftTriggerActionWriter;
    private final GiftTurnUsageReader giftTurnUsageReader;
    private final MatchCardSelectionRequestResolver cardSelectionRequestResolver;
    private final MatchCardSelectionProbeBuilder cardSelectionProbeBuilder;
    private final MatchCardSelectionExecutionService cardSelectionExecutionService;
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
        this.effectResolver = effectResolver;
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
        this.passiveGiftTriggerActionWriter = new PassiveGiftTriggerActionWriter(
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
        this.bloomEffectDispatcher = new MatchBloomEffectDispatcher(cardSelectionExecutionService, this);
        this.collabEffectDispatcher = new MatchCollabEffectDispatcher(cardSelectionExecutionService, this);
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
                    case "DRAW" -> executed.add(executeDrawEffect(matchId, userId, type, effectNode));
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
                        executeHealEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "REMOVE_CHEER" -> executed.add(
                        executeRemoveCheerEffect(
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
                        executeMoveZoneEffect(
                            matchId,
                            userId,
                            type,
                            effectNode,
                            targetType,
                            targetHolomemCardInstanceId
                        )
                    );
                    case "SUMMON_TO_STAGE" -> executed.add(
                        executeSummonToStageEffect(matchId, userId, type, effectNode)
                    );
                    case "REVEAL_TO_ARCHIVE" -> executed.add(
                        executeRevealToArchiveEffect(matchId, userId, type, effectNode)
                    );
                    case "BLOOM_FROM_ARCHIVE" -> executed.add(
                        executeBloomFromArchiveEffect(matchId, userId, type, effectNode)
                    );
                    case "RETURN_CHEER_TO_DECK_BOTTOM" -> executed.add(
                        executeReturnCheerToDeckBottomEffect(matchId, userId, type, effectNode)
                    );
                    case "DISCARD_HAND" -> executed.add(
                        executeDiscardHandEffect(matchId, userId, type, effectNode)
                    );
                    case "REST" -> executed.add(
                        executeRestEffect(matchId, userId, type, effectNode, targetType, targetHolomemCardInstanceId)
                    );
                    case "SWAP_CENTER_BACK" -> executed.add(
                        executeSwapCenterBackEffect(matchId, userId, type, effectNode)
                    );
                    case "MOVE_TO_HOLOPOWER" -> executed.add(
                        executeMoveToHolopowerEffect(matchId, userId, type, effectNode)
                    );
                    case "DOWN_NO_LIFE" -> executed.add(
                        executeDownNoLifeEffect(matchId, userId, type, effectNode)
                    );
                    case "DOWN_EXTRA_LIFE" -> executed.add(
                        executeDownExtraLifeEffect(matchId, userId, type, effectNode)
                    );
                    case "BATON_TOUCH_COST_MODIFIER" -> executed.add(
                        executeBatonTouchCostModifierEffect(matchId, userId, type, effectNode, targetType, targetHolomemCardInstanceId)
                    );
                    case "ACTION_LOCK" -> executed.add(
                        executeActionLockEffect(matchId, userId, type, effectNode, targetType, targetHolomemCardInstanceId)
                    );
                    case "ALLOW_EXTRA_BLOOM" -> executed.add(
                        executeAllowExtraBloomEffect(matchId, userId, type, effectNode)
                    );
                    case "LOOK_TOP_DECK" -> executed.add(
                        executeLookTopDeckEffect(matchId, userId, type, effectNode)
                    );
                    case "LOOK_OPPONENT_HAND" -> executed.add(
                        executeLookOpponentHandEffect(matchId, userId, type, effectNode)
                    );
                    case "LOOK_HOLOPOWER" -> executed.add(
                        executeLookHolopowerEffect(matchId, userId, type, effectNode)
                    );
                    case "SWAP_WITH_COLLAB" -> executed.add(
                        executeSwapWithCollabEffect(matchId, userId, type, effectNode, targetHolomemCardInstanceId)
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
                        executeMatchResultEffect(matchId, userId, type, effectNode)
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
        Map<String, Object> archiveSummary = executeRevealToArchiveEffect(
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
            Map<String, Object> drawSummary = executeDrawEffect(matchId, userId, "DRAW", drawNode);
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
            case "DRAW" -> executeDrawEffect(matchId, userId, effectType, giftNode);
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
            case "SUMMON_TO_STAGE" -> executeSummonToStageEffect(matchId, userId, effectType, giftNode);
            case "REVEAL_TO_ARCHIVE" -> executeRevealToArchiveEffect(matchId, userId, effectType, giftNode);
            case "BLOOM_FROM_ARCHIVE" -> executeBloomFromArchiveEffect(matchId, userId, effectType, giftNode);
            case "RETURN_CHEER_TO_DECK_BOTTOM" -> executeReturnCheerToDeckBottomEffect(matchId, userId, effectType, giftNode);
            case "DISCARD_HAND" -> executeDiscardHandEffect(matchId, userId, effectType, giftNode);
            case "REST" -> executeRestEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId);
            case "SWAP_CENTER_BACK" -> executeSwapCenterBackEffect(matchId, userId, effectType, giftNode);
            case "MOVE_TO_HOLOPOWER" -> executeMoveToHolopowerEffect(matchId, userId, effectType, giftNode);
            case "DOWN_NO_LIFE" -> executeDownNoLifeEffect(matchId, userId, effectType, giftNode);
            case "DOWN_EXTRA_LIFE" -> executeDownExtraLifeEffect(matchId, userId, effectType, giftNode);
            case "BATON_TOUCH_COST_MODIFIER" -> executeBatonTouchCostModifierEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                targetType,
                holderCardInstanceId
            );
            case "ACTION_LOCK" -> executeActionLockEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId);
            case "ALLOW_EXTRA_BLOOM" -> executeAllowExtraBloomEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                null,
                holderCardInstanceId
            );
            case "LOOK_TOP_DECK" -> executeLookTopDeckEffect(matchId, userId, effectType, giftNode);
            case "LOOK_OPPONENT_HAND" -> executeLookOpponentHandEffect(matchId, userId, effectType, giftNode);
            case "LOOK_HOLOPOWER" -> executeLookHolopowerEffect(matchId, userId, effectType, giftNode);
            case "ARCHIVE_STACK_CARD" -> executeArchiveStackCardEffect(
                matchId,
                userId,
                effectType,
                giftNode,
                holderCardInstanceId
            );
            case "SWAP_WITH_COLLAB" -> executeSwapWithCollabEffect(matchId, userId, effectType, giftNode, holderCardInstanceId);
            case "HEAL" -> executeHealEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId);
            case "BUFF", "DEBUFF" -> executeBuffDebuffEffect(matchId, userId, effectType, giftNode, targetType);
            case "MATCH_RESULT", "WIN", "LOSE" -> executeMatchResultEffect(matchId, userId, effectType, giftNode);
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
     * 描述「常駐 Gift HP 加成」的受益者。
     *
     * <p>目前只先保留 `HSD13-007` 這類判斷真正需要的欄位：
     *
     * <p>- 自己是誰（避免把「このホロメン」誤套到別人身上）
     * <p>- 站位 / 等級 / tag（保留未來擴到其他自動常駐文案的空間）
     * <p>- 身上的 Cheer 數量（像 `このホロメンのエール1枚につき` 會直接用到）
     */
    record PassiveGiftHpTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        Set<String> tags,
        int attachedCheerCount
    ) {}

    /**
     * 描述「常駐 Gift 受傷減免」的受益者。
     *
     * <p>這和 HP 加成類似，也只保留目前規則判斷真正需要的欄位；差別是受傷減免文案目前先聚焦：
     *
     * <p>- `このホロメンが受けるダメージ-10`
     * <p>- `このホロメンが相手の1stホロメンから受けるダメージ-20`
     * <p>- `自分のコラボホロメンが受けるダメージ-10`
     * <p>- `このホロメンと自分のコラボホロメンが受けるダメージ-10`
     *
     * <p>因此這裡只需要知道：
     *
     * <p>- 受擊目標是不是 holder 自己
     * <p>- 受擊目標目前是不是在 `COLLAB`
     *
     * <p>先把這個 target context 單獨抽出來，可以避免後面在 matcher 裡反覆 query stage zone，
     * 也讓 `HSD07-009` / `HBP06-009` 這種固定格式共享同一條保守主幹。
     */
    record PassiveGiftIncomingDamageReductionTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        Set<String> tags,
        String oshiCardName,
        String incomingSourceLevelType
    ) {}

    /**
     * 描述「相手能力不能改變 HP」判斷所需的最小 target 狀態。
     */
    private record PassiveGiftHpChangePreventionTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        Set<String> tags
    ) {}

    /**
     * 描述當前回合狀態，供靜態常駐條件判斷。
     */
    private record MatchTurnContext(
        String phase,
        Long currentTurnPlayerId
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
     * 執行抽牌效果，優先走 Action Pipeline，失敗時回退到既有 SQL 流程。
     */
    Map<String, Object> executeDrawEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        int requestedCount = resolveDrawCount(effectNode);
        int drawCount = Math.max(requestedCount, 1);

        List<Long> drawnCardInstanceIds = new ArrayList<>();
        // P1-3: 以 EffectResolver + GameActionExecutor 執行 DRAW，舊 SQL 作為安全 fallback。
        try {
            ObjectNode pipelineNode = objectMapper.createObjectNode();
            pipelineNode.put("drawCount", drawCount);
            pipelineNode.put("fromZone", "DECK");
            pipelineNode.put("toZone", "HAND");
            EffectContext context = new EffectContext(
                matchId,
                userId,
                resolveCurrentTurnNumber(matchId),
                "SUPPORT_EFFECT",
                null,
                null
            );
            List<AtomicAction> actions = effectResolver.resolve(context, effectType, pipelineNode);
            if (!actions.isEmpty()) {
                List<ActionResult> results = gameActionExecutor.execute(context, actions);
                if (!results.isEmpty() && results.get(0).success()) {
                    Object movedCards = results.get(0).details().get("cardInstanceIds");
                    if (movedCards instanceof List<?> list) {
                        for (Object id : list) {
                            if (id instanceof Number n) {
                                drawnCardInstanceIds.add(n.longValue());
                            } else if (id instanceof String s) {
                                try {
                                    drawnCardInstanceIds.add(Long.parseLong(s));
                                } catch (NumberFormatException ignored) {
                                    // ignore invalid id payload
                                }
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fallback below
        }
        if (drawnCardInstanceIds.isEmpty()) {
            int nextHandOrder = nextZoneOrder(matchId, userId, "HAND");
            for (int i = 0; i < drawCount; i++) {
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
                    break;
                }
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
                    nextHandOrder++,
                    deckCardInstanceId,
                    matchId,
                    userId
                );
                drawnCardInstanceIds.add(deckCardInstanceId);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("drawRequested", drawCount);
        summary.put("drawApplied", drawnCardInstanceIds.size());
        summary.put("drawnCardInstanceIds", drawnCardInstanceIds);
        return summary;
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
     * 執行上場效果：從手牌/檔案區等來源召喚 Holomem 到場地可用區位。
     */
    Map<String, Object> executeSummonToStageEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "ステージに出", 1);
        int summonCount = Math.max(requestedCount, 1);
        SearchCriteria resolved = searchCriteriaParser.resolveSearchCriteria(effectNode);
        SearchCriteria criteria = new SearchCriteria(
            "MEMBER",
            resolved.levelType(),
            resolved.tag(),
            resolved.nameContains(),
            resolved.color(),
            resolved.rested(),
            resolved.minRemainHp(),
            resolved.maxRemainHp(),
            resolved.allOf(),
            resolved.anyOf()
        );
        List<Map<String, Object>> candidates = loadCandidatesFromZone(
            matchId,
            userId,
            "DECK",
            criteria,
            false
        );
        List<Map<String, Object>> selected = candidates.subList(0, Math.min(summonCount, candidates.size()));
        String preferredZone = resolveMoveDestinationZone(effectNode);
        int currentTurn = resolveCurrentTurnNumber(matchId);

        List<Long> summonedCardInstanceIds = new ArrayList<>();
        List<Long> summonedHolomemIds = new ArrayList<>();
        List<String> summonedCardIds = new ArrayList<>();
        List<String> summonedZones = new ArrayList<>();
        for (Map<String, Object> row : selected) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            String levelType = asText(row.get("level_type"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            String targetZone = resolveAvailableStageZone(matchId, userId, preferredZone);
            if (!StringUtils.hasText(targetZone)) {
                break;
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
                  AND zone = 'DECK'
                """,
                cardInstanceId,
                matchId,
                userId
            );
            if (moved != 1) {
                continue;
            }

            Long holomemId = jdbcTemplate.query(
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
                normalizeHolomemLevel(levelType),
                currentTurn
            );
            if (holomemId == null) {
                continue;
            }
            recordHolomemStackCard(holomemId, cardInstanceId);

            summonedCardInstanceIds.add(cardInstanceId);
            summonedHolomemIds.add(holomemId);
            summonedCardIds.add(cardId);
            summonedZones.add(targetZone);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("summonRequested", summonCount);
        summary.put("candidateCount", candidates.size());
        summary.put("summonApplied", summonedCardInstanceIds.size());
        summary.put("summonedCardInstanceIds", summonedCardInstanceIds);
        summary.put("summonedHolomemIds", summonedHolomemIds);
        summary.put("summonedCardIds", summonedCardIds);
        summary.put("summonedZones", summonedZones);
        summary.put("criteria", buildCriteriaSummary(criteria));
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
     * 執行展示後歸檔效果（Reveal -> Archive）。
     */
    Map<String, Object> executeRevealToArchiveEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "アーカイブ", 1);
        int archiveCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = searchCriteriaParser.resolveSearchCriteria(effectNode);
        List<Map<String, Object>> candidates = loadCandidatesFromZone(
            matchId,
            userId,
            "DECK",
            criteria,
            false
        );
        List<Map<String, Object>> selected = candidates.subList(0, Math.min(archiveCount, candidates.size()));

        List<Long> archivedCardInstanceIds = new ArrayList<>();
        List<String> archivedCardIds = new ArrayList<>();
        int nextArchiveOrder = nextZoneOrder(matchId, userId, "ARCHIVE");
        for (Map<String, Object> row : selected) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
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
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("archiveRequested", archiveCount);
        summary.put("candidateCount", candidates.size());
        summary.put("archiveApplied", archivedCardInstanceIds.size());
        summary.put("archivedCardInstanceIds", archivedCardInstanceIds);
        summary.put("archivedCardIds", archivedCardIds);
        summary.put("criteria", buildCriteriaSummary(criteria));
        return summary;
    }

    /**
     * 執行從 Archive Bloom 的特殊效果，並保留疊卡繼承資料。
     */
    Map<String, Object> executeBloomFromArchiveEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int currentTurn = resolveCurrentTurnNumber(matchId);
        SearchCriteria criteria = searchCriteriaParser.resolveSearchCriteria(effectNode);
        String requiredLevel = StringUtils.hasText(criteria.levelType()) ? criteria.levelType() : "DEBUT";

        List<Map<String, Object>> targetCandidates = jdbcTemplate.query(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   h.current_level,
                   h.damage_taken,
                   h.last_bloom_turn,
                   h.is_rested,
                   'MEMBER' AS card_type,
                   h.current_level AS level_type,
                   c.name,
                   c.tags_json::text AS tags_json,
                   m.main_color,
                   m.sub_color,
                   GREATEST(COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0), 0) AS remain_hp
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            LEFT JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER','COLLAB','BACK')
              AND (? = '' OR h.current_level = ?)
              AND (? = '' OR c.name ILIKE '%' || ? || '%')
              AND (
                    ? = ''
                    OR EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements_text(COALESCE(c.tags_json, '[]'::jsonb)) AS t(tag)
                        WHERE t.tag = ?
                    )
                  )
            ORDER BY CASE h.zone WHEN 'CENTER' THEN 1 WHEN 'COLLAB' THEN 2 WHEN 'BACK' THEN 3 ELSE 9 END,
                     h.id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("holomem_id", rs.getLong("holomem_id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("current_level", rs.getString("current_level"));
                row.put("damage_taken", rs.getInt("damage_taken"));
                row.put("last_bloom_turn", rs.getObject("last_bloom_turn"));
                row.put("is_rested", rs.getObject("is_rested"));
                row.put("card_type", rs.getString("card_type"));
                row.put("level_type", rs.getString("level_type"));
                row.put("name", rs.getString("name"));
                row.put("tags_json", rs.getString("tags_json"));
                row.put("main_color", rs.getString("main_color"));
                row.put("sub_color", rs.getString("sub_color"));
                row.put("remain_hp", rs.getObject("remain_hp"));
                return row;
            },
            matchId,
            userId,
            nullToEmpty(requiredLevel),
            nullToEmpty(requiredLevel),
            nullToEmpty(criteria.nameContains()),
            nullToEmpty(criteria.nameContains()),
            nullToEmpty(criteria.tag()),
            nullToEmpty(criteria.tag())
        );
        Map<String, Object> target = targetCandidates.stream()
            .filter(row -> {
                Object lastBloomTurn = row.get("last_bloom_turn");
                if (lastBloomTurn instanceof Number number) {
                    return number.intValue() != currentTurn;
                }
                return true;
            })
            .filter(row -> matchesSearchCriteria(row, criteria))
            .findFirst()
            .orElse(null);
        if (target == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有可從 Archive 進行 Bloom 的目標");
        }

        Long targetHolomemId = asLong(target.get("holomem_id"));
        String targetName = asText(target.get("name"));
        String targetLevel = asText(target.get("current_level"));
        int targetDamageTaken = asInt(target.get("damage_taken"));
        int targetRank = resolveBloomLevelRank(targetLevel);
        if (targetHolomemId == null || !StringUtils.hasText(targetName) || targetRank < 0) {
            return executeNoOpEffect(effectType, effectNode, "目標 Holomem 資料不足，無法執行 Archive Bloom");
        }

        Map<String, Object> archiveBloomCard = jdbcTemplate.query(
            """
            SELECT mc.id AS card_instance_id,
                   mc.card_id,
                   m.level_type,
                   m.hp,
                   m.bloom_level
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'ARCHIVE'
              AND c.name = ?
              AND m.bloom_level > ?
              AND m.hp >= ?
            ORDER BY m.bloom_level, mc.order_index NULLS LAST, mc.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("card_instance_id", rs.getLong("card_instance_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("level_type", rs.getString("level_type"));
                row.put("hp", rs.getInt("hp"));
                row.put("bloom_level", rs.getInt("bloom_level"));
                return row;
            },
            matchId,
            userId,
            targetName,
            targetRank,
            targetDamageTaken
        );
        if (archiveBloomCard == null) {
            return executeNoOpEffect(effectType, effectNode, "Archive 中找不到可用的 Bloom 卡");
        }

        Long bloomCardInstanceId = asLong(archiveBloomCard.get("card_instance_id"));
        String bloomCardId = asText(archiveBloomCard.get("card_id"));
        String bloomLevelType = asText(archiveBloomCard.get("level_type"));
        if (bloomCardInstanceId == null || !StringUtils.hasText(bloomCardId)) {
            return executeNoOpEffect(effectType, effectNode, "Archive Bloom 卡資料不完整");
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
            bloomCardInstanceId,
            matchId,
            userId
        );
        if (moved != 1) {
            return executeNoOpEffect(effectType, effectNode, "Archive Bloom 移動卡片失敗");
        }

        jdbcTemplate.update(
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
            normalizeHolomemLevel(bloomLevelType),
            currentTurn,
            targetHolomemId,
            matchId,
            userId
        );
        recordHolomemStackCard(targetHolomemId, bloomCardInstanceId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("bloomCardInstanceId", bloomCardInstanceId);
        summary.put("bloomCardId", bloomCardId);
        summary.put("bloomLevelType", normalizeHolomemLevel(bloomLevelType));
        return summary;
    }

    /**
     * 將目標 Holomem 身上的 cheer 返回牌庫底。
     */
    Map<String, Object> executeReturnCheerToDeckBottomEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        String colorFilter = resolveCheerColorFilter(rawText);
        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "エールデッキの下に戻", 1);
        int returnCount = Math.max(requestedCount, 1);
        boolean fromStageAttachedCheer = rawText.contains("ステージのエール");

        List<Map<String, Object>> candidates;
        if (fromStageAttachedCheer) {
            candidates = jdbcTemplate.query(
                """
                SELECT hc.id AS cheer_row_id,
                       hc.match_card_id,
                       hc.cheer_card_id AS card_id,
                       cc.color
                FROM match_holomem_cheers hc
                JOIN match_holomems h ON h.id = hc.match_holomem_id
                JOIN cheer_cards cc ON cc.card_id = hc.cheer_card_id
                WHERE h.match_id = ?
                  AND h.owner_user_id = ?
                  AND (? = '' OR cc.color = ?)
                ORDER BY hc.id
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("cheer_row_id", rs.getLong("cheer_row_id"));
                    long matchCardId = rs.getLong("match_card_id");
                    row.put("match_card_id", rs.wasNull() ? null : matchCardId);
                    row.put("card_id", rs.getString("card_id"));
                    row.put("color", rs.getString("color"));
                    return row;
                },
                matchId,
                userId,
                nullToEmpty(colorFilter),
                nullToEmpty(colorFilter),
                returnCount
            );
        } else {
            candidates = jdbcTemplate.query(
                """
                SELECT mc.id, mc.card_id, cc.color
                FROM match_cards mc
                JOIN cheer_cards cc ON cc.card_id = mc.card_id
                WHERE mc.match_id = ?
                  AND mc.owner_user_id = ?
                  AND mc.zone = 'ARCHIVE'
                  AND (? = '' OR cc.color = ?)
                ORDER BY mc.order_index NULLS LAST, mc.id
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("card_id", rs.getString("card_id"));
                    row.put("color", rs.getString("color"));
                    return row;
                },
                matchId,
                userId,
                nullToEmpty(colorFilter),
                nullToEmpty(colorFilter),
                returnCount
            );
        }

        List<Long> movedCardInstanceIds = new ArrayList<>();
        List<String> movedCardIds = new ArrayList<>();
        int nextCheerDeckOrder = nextZoneOrder(matchId, userId, "CHEER_DECK");
        for (Map<String, Object> row : candidates) {
            String cardId = asText(row.get("card_id"));
            if (!StringUtils.hasText(cardId)) {
                continue;
            }
            Long cardInstanceId;
            if (fromStageAttachedCheer) {
                cardInstanceId = asLong(row.get("match_card_id"));
                if (cardInstanceId == null || cardInstanceId <= 0) {
                    cardInstanceId = jdbcTemplate.query(
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
                        userId,
                        cardId
                    );
                }
            } else {
                cardInstanceId = asLong(row.get("id"));
            }
            if (cardInstanceId == null || cardInstanceId <= 0) {
                continue;
            }
            String sourceZone = fromStageAttachedCheer ? "STAGE" : "ARCHIVE";
            int moved = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'CHEER_DECK',
                    order_index = ?,
                    is_face_down = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = ?
                """,
                nextCheerDeckOrder++,
                cardInstanceId,
                matchId,
                userId,
                sourceZone
            );
            if (moved != 1) {
                continue;
            }
            if (fromStageAttachedCheer) {
                Long cheerRowId = asLong(row.get("cheer_row_id"));
                if (cheerRowId != null && cheerRowId > 0) {
                    jdbcTemplate.update(
                        """
                        DELETE FROM match_holomem_cheers
                        WHERE id = ?
                        """,
                        cheerRowId
                    );
                }
            }
            movedCardInstanceIds.add(cardInstanceId);
            movedCardIds.add(cardId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("sourceZone", fromStageAttachedCheer ? "STAGE" : "ARCHIVE");
        summary.put("returnRequested", returnCount);
        summary.put("returnApplied", movedCardInstanceIds.size());
        summary.put("colorFilter", colorFilter);
        summary.put("returnedCardInstanceIds", movedCardInstanceIds);
        summary.put("returnedCardIds", movedCardIds);
        return summary;
    }

    /**
     * 執行棄手牌效果，支援指定卡與自動挑選。
     */
    Map<String, Object> executeDiscardHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        String discardClause = extractCostClause(rawText);
        SearchCriteria discardCriteria = resolveSearchCriteriaFromRawText(discardClause);
        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "手札", 1);
        int discardCount = Math.max(requestedCount, 1);

        List<Map<String, Object>> handCards;
        if (discardCriteria.isEmpty()) {
            handCards = jdbcTemplate.query(
                """
                SELECT id, card_id
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'HAND'
                ORDER BY order_index NULLS LAST, id
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("card_id", rs.getString("card_id"));
                    return row;
                },
                matchId,
                userId,
                discardCount
            );
        } else {
            // 某些官方文案把「要丟哪一張手牌」寫在冒號前的成本段，例如：
            // `自分の手札の#FLOW GLOWを持つホロメン1枚をアーカイブできる：...`
            //
            // 若這裡仍沿用「拿手牌前 N 張」的舊邏輯，就會把不符合條件的手牌誤當成本。
            // 因此只在成本段能解析出條件時，切換成同一套 SearchCriteria 過濾流程。
            handCards = new ArrayList<>(loadCandidatesFromZone(matchId, userId, "HAND", discardCriteria, false));
            if (handCards.size() > discardCount) {
                handCards = new ArrayList<>(handCards.subList(0, discardCount));
            }
        }

        List<Long> discardedCardInstanceIds = new ArrayList<>();
        List<String> discardedCardIds = new ArrayList<>();
        int nextArchiveOrder = nextZoneOrder(matchId, userId, "ARCHIVE");
        for (Map<String, Object> row : handCards) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
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
                  AND zone = 'HAND'
                """,
                nextArchiveOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (moved != 1) {
                continue;
            }
            discardedCardInstanceIds.add(cardInstanceId);
            discardedCardIds.add(cardId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("discardRequested", discardCount);
        summary.put("discardApplied", discardedCardInstanceIds.size());
        if (!discardCriteria.isEmpty()) {
            summary.put("discardCriteria", buildCriteriaSummary(discardCriteria));
        }
        summary.put("discardedCardInstanceIds", discardedCardInstanceIds);
        summary.put("discardedCardIds", discardedCardIds);
        return summary;
    }

    /**
     * 執行休息效果（將目標 Holomem 設為 rested）。
     */
    Map<String, Object> executeRestEffect(
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
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null && rawText.contains("バックホロメン")) {
            Long ownerUserId = isOpponentTargetType(normalize(targetType))
                ? resolveOpponentUserId(matchId, userId)
                : userId;
            if (ownerUserId != null) {
                targetHolomemId = jdbcTemplate.query(
                    """
                    SELECT id
                    FROM match_holomems
                    WHERE match_id = ?
                      AND owner_user_id = ?
                      AND zone = 'BACK'
                    ORDER BY id
                    LIMIT 1
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    matchId,
                    ownerUserId
                );
            }
        }
        if (targetHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到可設為休息的 Holomem");
        }

        int updated = jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET is_rested = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
            """,
            targetHolomemId,
            matchId
        );
        if (updated != 1) {
            return executeNoOpEffect(effectType, effectNode, "設定休息狀態失敗");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("rested", true);
        return summary;
    }

    /**
     * 執行 CENTER/BACK 交換效果。
     */
    Map<String, Object> executeSwapCenterBackEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        boolean targetOpponent = rawText.contains("相手の");
        boolean requireBackNotRested = rawText.contains("お休みしていない");
        Long ownerUserId = targetOpponent ? resolveOpponentUserId(matchId, userId) : userId;
        if (ownerUserId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到交換目標玩家");
        }

        Long centerHolomemId = jdbcTemplate.query(
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
            ownerUserId
        );
        if (centerHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有可交換的 CENTER");
        }
        int currentTurn = resolveCurrentTurnNumber(matchId);
        if (isActionLockActive(matchId, ownerUserId, currentTurn, "SWAP", "CENTER", centerHolomemId)) {
            return executeNoOpEffect(effectType, effectNode, "目前效果限制：不可交代");
        }

        Long backHolomemId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'BACK'
              AND (? = FALSE OR is_rested = FALSE)
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            requireBackNotRested
        );
        if (backHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有可交換的 BACK");
        }
        if (isActionLockActive(matchId, ownerUserId, currentTurn, "SWAP", "BACK", backHolomemId)) {
            return executeNoOpEffect(effectType, effectNode, "目前效果限制：不可交代");
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'BACK',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
            """,
            centerHolomemId,
            matchId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'CENTER',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
            """,
            backHolomemId,
            matchId
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("targetOwnerUserId", ownerUserId);
        summary.put("fromCenterHolomemId", centerHolomemId);
        summary.put("fromBackHolomemId", backHolomemId);
        summary.put("centerHolomemCardInstanceId", resolveHolomemCardInstanceId(backHolomemId));
        summary.put("backHolomemCardInstanceId", resolveHolomemCardInstanceId(centerHolomemId));
        return summary;
    }

    /**
     * 執行移入 Holopower 的效果（通常來自 Deck/Archive 等）。
     */
    Map<String, Object> executeMoveToHolopowerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        String sourceZone = resolveMoveToHolopowerSourceZone(effectNode, rawText);
        int requestedCount = cardSelectionRequestResolver.resolveActionCount(effectNode, "ホロパワー", 1);
        int moveCount = Math.max(requestedCount, 1);

        List<Long> movedCardInstanceIds = new ArrayList<>();
        for (int i = 0; i < moveCount; i++) {
            Long deckCardInstanceId = jdbcTemplate.query(
                """
                SELECT id
                FROM match_cards
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = ?
                ORDER BY order_index NULLS LAST, id
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                userId,
                sourceZone
            );
            if (deckCardInstanceId == null) {
                break;
            }
            int nextOrder = nextZoneOrder(matchId, userId, "HOLOPOWER");
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
                  AND zone = ?
                """,
                nextOrder,
                deckCardInstanceId,
                matchId,
                userId,
                sourceZone
            );
            if (moved == 1) {
                movedCardInstanceIds.add(deckCardInstanceId);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("sourceZone", sourceZone);
        summary.put("moveRequested", moveCount);
        summary.put("moveApplied", movedCardInstanceIds.size());
        summary.put("movedCardInstanceIds", movedCardInstanceIds);
        return summary;
    }

    /**
     * 執行擊倒但不扣生命的效果分支。
     */
    Map<String, Object> executeDownNoLifeEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        if (opponentUserId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到對手");
        }
        boolean requireDamaged40 = rawText.contains("HPが40以上減っている");

        Map<String, Object> target = jdbcTemplate.query(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.damage_taken
            FROM match_holomems h
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'BACK'
              AND (? = FALSE OR COALESCE(h.damage_taken, 0) >= 40)
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("holomem_id", rs.getLong("holomem_id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("damage_taken", rs.getInt("damage_taken"));
                return row;
            },
            matchId,
            opponentUserId,
            requireDamaged40
        );
        if (target == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有符合條件的 BACK 目標可 Down");
        }

        Long targetHolomemId = asLong(target.get("holomem_id"));
        Long targetCardInstanceId = asLong(target.get("match_card_id"));
        if (targetHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "目標 Holomem 資料不足");
        }
        String targetCardId = targetCardInstanceId == null
            ? null
            : jdbcTemplate.query(
                """
                SELECT card_id
                FROM match_cards
                WHERE match_id = ?
                  AND id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("card_id") : null,
                matchId,
                targetCardInstanceId
            );

        List<Long> archivedCheerCardInstanceIds = archiveAttachedCheerCards(matchId, targetHolomemId, opponentUserId);
        List<Long> archivedSupportCardInstanceIds = archiveAttachedSupportCards(matchId, targetHolomemId, opponentUserId);
        List<Long> archivedHolomemCardInstanceIds = archiveHolomemStackCards(matchId, targetHolomemId, opponentUserId);

        jdbcTemplate.update(
            "DELETE FROM match_holomems WHERE id = ? AND match_id = ?",
            targetHolomemId,
            matchId
        );
        if (archivedHolomemCardInstanceIds.isEmpty() && targetCardInstanceId != null) {
            int archiveOrder = nextZoneOrder(matchId, opponentUserId, "ARCHIVE");
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
                """,
                archiveOrder,
                targetCardInstanceId,
                matchId,
                opponentUserId
            );
        }

        int currentTurn = resolveCurrentTurnNumber(matchId);
        Map<String, Object> downEventSummary = executeDownEvent(
            matchId,
            userId,
            opponentUserId,
            targetCardId,
            currentTurn,
            true,
            "BACK"
        );
        List<Long> lostLifeCardInstanceIds = extractLostLifeCardInstanceIds(downEventSummary);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", targetCardInstanceId);
        summary.put("targetOwnerUserId", opponentUserId);
        summary.put("downed", true);
        summary.put("lifeReduced", !lostLifeCardInstanceIds.isEmpty());
        summary.put("lostLifeCardInstanceId", lostLifeCardInstanceIds.isEmpty() ? null : lostLifeCardInstanceIds.get(0));
        summary.put("lostLifeCardInstanceIds", lostLifeCardInstanceIds);
        summary.put("archivedCheerCardInstanceIds", archivedCheerCardInstanceIds);
        summary.put("archivedSupportCardInstanceIds", archivedSupportCardInstanceIds);
        summary.put("archivedHolomemCardInstanceIds", archivedHolomemCardInstanceIds);
        summary.put("downEvent", downEventSummary);
        return summary;
    }

    /**
     * 執行擊倒並額外扣生命的效果分支。
     */
    Map<String, Object> executeDownExtraLifeEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        Map<String, Object> summary = executeDownNoLifeEffect(matchId, userId, effectType, effectNode);
        if (!toBoolean(summary.get("applied")) || !toBoolean(summary.get("downed"))) {
            return summary;
        }
        Long targetOwnerUserId = asLong(summary.get("targetOwnerUserId"));
        Long targetHolomemCardInstanceId = asLong(summary.get("targetHolomemCardInstanceId"));
        if ((targetOwnerUserId == null || targetOwnerUserId <= 0) && targetHolomemCardInstanceId != null && targetHolomemCardInstanceId > 0) {
            targetOwnerUserId = jdbcTemplate.query(
                """
                SELECT owner_user_id
                FROM match_cards
                WHERE match_id = ?
                  AND id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("owner_user_id") : null,
                matchId,
                targetHolomemCardInstanceId
            );
        }
        if (targetOwnerUserId == null || targetOwnerUserId <= 0) {
            return summary;
        }

        int requestedLifeLoss = resolveDownExtraLifeCount(effectNode);
        List<Long> lostLifeCardInstanceIds = new ArrayList<>();
        if (requestedLifeLoss > 0) {
            EffectContext actionContext = new EffectContext(
                matchId,
                userId,
                resolveCurrentTurnNumber(matchId),
                effectType,
                targetHolomemCardInstanceId,
                null
            );
            ReduceLifeAction reduceLifeAction = new ReduceLifeAction(targetOwnerUserId, requestedLifeLoss, "DOWN_EXTRA_LIFE");
            List<ActionResult> actionResults = gameActionExecutor.execute(actionContext, List.of(reduceLifeAction));
            if (!actionResults.isEmpty() && actionResults.get(0).success()) {
                Object ids = actionResults.get(0).details().get("lifeCardInstanceIds");
                if (ids instanceof List<?> list) {
                    for (Object id : list) {
                        if (id instanceof Number n) {
                            lostLifeCardInstanceIds.add(n.longValue());
                        } else if (id instanceof String s) {
                            try {
                                lostLifeCardInstanceIds.add(Long.parseLong(s));
                            } catch (NumberFormatException ignored) {
                                // ignore malformed id value
                            }
                        }
                    }
                }
            }
        }
        if (lostLifeCardInstanceIds.isEmpty()) {
            for (int index = 0; index < requestedLifeLoss; index += 1) {
                Long lostLifeCardInstanceId = loseLifeOnce(matchId, targetOwnerUserId);
                if (lostLifeCardInstanceId == null) {
                    break;
                }
                lostLifeCardInstanceIds.add(lostLifeCardInstanceId);
            }
        }

        if (!lostLifeCardInstanceIds.isEmpty()) {
            summary.put("lifeReduced", true);
            summary.put("lostLifeCardInstanceId", lostLifeCardInstanceIds.get(0));
            summary.put("lostLifeCardInstanceIds", lostLifeCardInstanceIds);
        }
        summary.put("extraLifeLossRequested", requestedLifeLoss);
        summary.put("extraLifeLossApplied", lostLifeCardInstanceIds.size());
        return summary;
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
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        int modifier = effectTextParser.extractByPattern(rawText, BATON_TOUCH_COST_MODIFIER_PATTERN);
        if (modifier <= 0) {
            modifier = effectTextParser.extractInt(effectNode, 0, "modifier", "value", "amount");
        }
        if (modifier <= 0) {
            return executeNoOpEffect(effectType, effectNode, "找不到有效的バトンタッチ無色修正值");
        }

        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            Long ownerUserId = isOpponentTargetType(normalize(targetType))
                ? resolveOpponentUserId(matchId, userId)
                : userId;
            if (ownerUserId != null) {
                targetHolomemId = jdbcTemplate.query(
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
                    ownerUserId
                );
            }
        }
        if (targetHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到可套用バトンタッチ修正的 CENTER 目標");
        }

        int currentTurn = resolveCurrentTurnNumber(matchId);
        int expiresTurn = currentTurn + 1;
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
            ) VALUES (?, ?, ?, ?, 'BATON_TOUCH_COLORLESS_MODIFIER', ?, ?, CAST(? AS jsonb))
            """,
            matchId,
            userId,
            resolveHolomemOwner(matchId, targetHolomemId),
            "DEBUFF",
            modifier,
            expiresTurn,
            effectTextParser.toJsonString(Map.of("targetHolomemId", targetHolomemId, "rawText", rawText))
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", inserted == 1);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("modifierValue", modifier);
        summary.put("expiresTurn", expiresTurn);
        return summary;
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
        return executeAllowExtraBloomEffect(matchId, userId, effectType, effectNode, null, null);
    }

    /**
     * 設定本回合額外 Bloom 許可效果。
     *
     * <p>這個 effectType 被多張官方卡共用，但它們的條件並不一樣：
     *
     * <p>- `HBP05-040`：Life <= 3，且目標是本回合已 Bloom 的特定 CENTER 成員
     * <p>- `HSD10-004`：自己的推し是〈輪堂千速〉、相手ステージ有 1st，且目標就是「這張剛 Bloom 的自己」
     *
     * <p>因此這裡不再把規則寫死成單一卡特例，而是先讀文案，再用保守條件把 allowance 寫到
     * `match_turn_effects`。只要 target 最終沒有被唯一辨識出來，就回傳 skipped，避免誤放寬 Bloom 規則。
     */
    Map<String, Object> executeAllowExtraBloomEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long preferredTargetHolomemId,
        Long holderCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (!StringUtils.hasText(rawText)) {
            return executeNoOpEffect(effectType, effectNode, "沒有可判讀的額外 Bloom 文案");
        }

        int currentLife = jdbcTemplate.query(
            """
            SELECT current_life
            FROM match_players
            WHERE match_id = ?
              AND user_id = ?
            """,
            rs -> rs.next() ? rs.getInt("current_life") : 0,
            matchId,
            userId
        );
        Integer maxAllowedLife = resolveExtraBloomLifeThreshold(rawText);
        if (maxAllowedLife != null && currentLife > maxAllowedLife) {
            return executeNoOpEffect(effectType, effectNode, "條件不成立：目前 Life 大於 " + maxAllowedLife);
        }

        String requiredOshiName = resolveRequiredOshiName(rawText);
        if (StringUtils.hasText(requiredOshiName)) {
            String currentOshiName = resolvePlayerOshiCardName(matchId, userId);
            if (!requiredOshiName.equals(currentOshiName)) {
                return executeNoOpEffect(effectType, effectNode, "條件不成立：推しホロメン不符合要求");
            }
        }
        if (rawText.contains("相手のステージに1stホロメンがいる") && !hasOpponentStageHolomemWithLevel(matchId, userId, "FIRST")) {
            return executeNoOpEffect(effectType, effectNode, "條件不成立：相手ステージ沒有 1st Holomem");
        }

        List<String> allowedNames = new ArrayList<>();
        if (rawText.contains("このターンにBloomした")) {
            for (String token : giftTriggerMatcher.extractNameTokens(rawText)) {
                if (!allowedNames.contains(token)) {
                    allowedNames.add(token);
                }
            }
        }
        if (rawText.contains("〈さくらみこ〉")) {
            allowedNames.add("さくらみこ");
        }
        if (rawText.contains("〈星街すいせい〉")) {
            allowedNames.add("星街すいせい");
        }

        int currentTurn = resolveCurrentTurnNumber(matchId);

        Map<String, Object> target;
        if (preferredTargetHolomemId != null && rawText.contains("このホロメン")) {
            target = jdbcTemplate.query(
                """
                SELECT h.id AS holomem_id,
                       h.match_card_id,
                       h.card_id,
                       c.name,
                       h.zone,
                       h.last_bloom_turn
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
                    if (asInt(rs.getObject("last_bloom_turn")) != currentTurn) {
                        return null;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("holomem_id", rs.getLong("holomem_id"));
                    row.put("match_card_id", rs.getLong("match_card_id"));
                    row.put("card_id", rs.getString("card_id"));
                    row.put("name", rs.getString("name"));
                    row.put("zone", rs.getString("zone"));
                    return row;
                },
                matchId,
                userId,
                preferredTargetHolomemId
            );
        } else {
            String requiredZone = rawText.contains("センターホロメン") ? "CENTER" : null;
            target = jdbcTemplate.query(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   c.name,
                   h.zone
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER', 'COLLAB', 'BACK')
              AND h.last_bloom_turn = ?
            ORDER BY h.id
            """,
            rs -> {
                while (rs.next()) {
                    String name = rs.getString("name");
                    String zone = rs.getString("zone");
                    if (StringUtils.hasText(requiredZone) && !requiredZone.equals(effectTextParser.normalizeEffectType(zone))) {
                        continue;
                    }
                    if (!allowedNames.isEmpty() && !containsAnyName(name, allowedNames)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("holomem_id", rs.getLong("holomem_id"));
                    row.put("match_card_id", rs.getLong("match_card_id"));
                    row.put("card_id", rs.getString("card_id"));
                    row.put("name", name);
                    row.put("zone", zone);
                    return row;
                }
                return null;
            },
            matchId,
            userId,
            currentTurn
            );
        }
        if (target == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有符合條件且本回合已 Bloom 的目標");
        }

        Long targetHolomemId = asLong(target.get("holomem_id"));
        Integer existingAllowanceCount = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ALLOW_EXTRA_BLOOM'
              AND expires_turn >= ?
              AND (payload ->> 'targetHolomemId') = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            currentTurn,
            targetHolomemId.toString()
        );
        if (existingAllowanceCount != null && existingAllowanceCount > 0) {
            return executeNoOpEffect(effectType, effectNode, "本回合已存在同目標的額外 Bloom 許可");
        }
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
            ) VALUES (?, ?, ?, ?, 'ALLOW_EXTRA_BLOOM', 1, ?, CAST(? AS jsonb))
            """,
            matchId,
            userId,
            userId,
            "BUFF",
            currentTurn,
            effectTextParser.toJsonString(
                Map.of(
                    "targetHolomemId", targetHolomemId,
                    "targetHolomemCardInstanceId", asLong(target.get("match_card_id")),
                    "targetCardId", asText(target.get("card_id")),
                    "targetName", asText(target.get("name")),
                    "holderCardInstanceId", holderCardInstanceId,
                    "rawText", rawText
                )
            )
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", inserted == 1);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", asLong(target.get("match_card_id")));
        summary.put("targetCardId", asText(target.get("card_id")));
        summary.put("targetName", asText(target.get("name")));
        summary.put("targetZone", asText(target.get("zone")));
        summary.put("expiresTurn", currentTurn);
        return summary;
    }

    /**
     * 從文案抽出「Life 必須不高於多少」的門檻。
     *
     * <p>目前只處理額外 Bloom 相關卡文中已出現且可穩定辨識的寫法，避免把其它數字條件誤吃進來。
     */
    private Integer resolveExtraBloomLifeThreshold(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }
        if (rawText.contains("ライフが3以下")) {
            return 3;
        }
        if (rawText.contains("ライフが4以下")) {
            return 4;
        }
        return null;
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
     * 讀取玩家目前的推し名稱。
     */
    private String resolvePlayerOshiCardName(Long matchId, Long userId) {
        return jdbcTemplate.query(
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
     * 寫入 ACTION_LOCK 封鎖效果（禁止指定動作/區位/目標）。
     */
    Map<String, Object> executeActionLockEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        List<String> actions = new ArrayList<>();
        if (rawText.contains("バトンタッチ") && rawText.contains("できない")) {
            actions.add("BATON_TOUCH");
        }
        if (rawText.contains("移動") && rawText.contains("できない")) {
            actions.add("MOVE_STAGE");
        }
        if (rawText.contains("交代") && rawText.contains("できない")) {
            actions.add("SWAP");
        }
        if ((rawText.contains("Bloom") || rawText.contains("ブルーム")) && rawText.contains("できない")) {
            actions.add("BLOOM");
        }
        if (rawText.contains("アクティブにならない")) {
            actions.add("UNREST");
        }
        if (actions.isEmpty()) {
            return executeNoOpEffect(effectType, effectNode, "無可套用的行動封鎖條件");
        }

        List<String> zones = new ArrayList<>();
        if (rawText.contains("センターホロメン")) {
            zones.add("CENTER");
        }
        if (rawText.contains("コラボホロメン")) {
            zones.add("COLLAB");
        }

        Long affectedUserId = rawText.contains("相手の") ? resolveOpponentUserId(matchId, userId) : userId;
        if (affectedUserId == null || affectedUserId <= 0) {
            return executeNoOpEffect(effectType, effectNode, "找不到封鎖效果目標玩家");
        }
        int currentTurn = resolveCurrentTurnNumber(matchId);
        int expiresTurn = rawText.contains("次の相手の") ? currentTurn + 1 : currentTurn;
        Long targetHolomemId = null;
        boolean lockSpecificHolomem = rawText.contains("このホロメン")
            || rawText.contains("このカード")
            || rawText.contains("選んだホロメン")
            || rawText.contains("そのホロメン");
        if (lockSpecificHolomem) {
            targetHolomemId = resolveEffectTargetHolomemId(
                matchId,
                userId,
                targetType,
                targetHolomemCardInstanceId,
                true
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actions", actions);
        payload.put("zones", zones);
        payload.put("rawText", rawText);
        if (targetHolomemId != null && targetHolomemId > 0) {
            payload.put("targetHolomemId", targetHolomemId);
            payload.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        }
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
            expiresTurn,
            effectTextParser.toJsonString(payload)
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", inserted == 1);
        summary.put("statType", "ACTION_LOCK");
        summary.put("actions", actions);
        summary.put("zones", zones);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", targetHolomemId == null ? null : resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("affectedUserId", affectedUserId);
        summary.put("expiresTurn", expiresTurn);
        return summary;
    }

    /**
     * 執行查看牌庫頂效果，產生 pending decision 所需資料。
     */
    Map<String, Object> executeLookTopDeckEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        if (rawText.contains("マスコットが付いている")) {
            Integer mascotAttachedCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_holomem_supports hs
                JOIN match_holomems h ON h.id = hs.match_holomem_id
                JOIN support_cards sc ON sc.card_id = hs.support_card_id
                WHERE h.match_id = ?
                  AND h.owner_user_id = ?
                  AND hs.support_type = 'MASCOT'
                """,
                Integer.class,
                matchId,
                userId
            );
            if (mascotAttachedCount == null || mascotAttachedCount <= 0) {
                return executeNoOpEffect(effectType, effectNode, "條件不成立：沒有附加中的マスコット");
            }
        }

        Map<String, Object> topCard = jdbcTemplate.query(
            """
            SELECT id, card_id, order_index
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("order_index", rs.getObject("order_index"));
                return row;
            },
            matchId,
            userId
        );
        if (topCard == null) {
            return executeNoOpEffect(effectType, effectNode, "牌庫沒有可查看的卡片");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("lookedCardInstanceId", asLong(topCard.get("id")));
        summary.put("lookedCardId", asText(topCard.get("card_id")));
        summary.put("reordered", false);
        summary.put("reason", "目前預設維持牌庫頂部順序");
        return summary;
    }

    /**
     * 執行查看對手手牌效果（只回傳可公開資訊）。
     */
    Map<String, Object> executeLookOpponentHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        if (opponentUserId == null || opponentUserId <= 0) {
            return executeNoOpEffect(effectType, effectNode, "找不到可查看手牌的對手");
        }
        List<Map<String, Object>> lookedCards = loadCardsForLookDecision(matchId, opponentUserId, "HAND");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("lookedUserId", opponentUserId);
        summary.put("lookedZone", "HAND");
        summary.put("lookedCardCount", lookedCards.size());
        summary.put("lookedCards", lookedCards);
        return summary;
    }

    /**
     * 執行查看 Holopower 區效果。
     */
    Map<String, Object> executeLookHolopowerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        boolean lookOpponent = rawText.contains("相手");
        Long lookedUserId = lookOpponent ? resolveOpponentUserId(matchId, userId) : userId;
        if (lookedUserId == null || lookedUserId <= 0) {
            return executeNoOpEffect(effectType, effectNode, "找不到可查看 HOLOPOWER 的玩家");
        }
        List<Map<String, Object>> lookedCards = loadCardsForLookDecision(matchId, lookedUserId, "HOLOPOWER");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("lookedUserId", lookedUserId);
        summary.put("lookedZone", "HOLOPOWER");
        summary.put("lookedCardCount", lookedCards.size());
        summary.put("lookedCards", lookedCards);
        return summary;
    }

    /**
     * 載入「查看類互動」要展示的卡片清單。
     */
    private List<Map<String, Object>> loadCardsForLookDecision(Long matchId, Long ownerUserId, String zone) {
        return jdbcTemplate.query(
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
              AND mc.zone = ?
            ORDER BY mc.order_index NULLS LAST, mc.id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cardInstanceId", rs.getLong("card_instance_id"));
                row.put("cardId", rs.getString("card_id"));
                row.put("zone", normalize(rs.getString("zone")));
                row.put("name", rs.getString("name"));
                row.put("cardType", rs.getString("card_type"));
                row.put("imageUrl", rs.getString("image_url"));
                row.put("levelType", rs.getString("level_type"));
                return row;
            },
            matchId,
            ownerUserId,
            zone
        );
    }

    /**
     * 執行與 Collab 位互換位置效果。
     */
    Map<String, Object> executeSwapWithCollabEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long selfHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        Long sourceHolomemId = resolveTargetHolomemId(matchId, userId, selfHolomemCardInstanceId);
        if (sourceHolomemId == null) {
            sourceHolomemId = jdbcTemplate.query(
                """
                SELECT id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'BACK'
                ORDER BY id
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                userId
            );
        }
        if (sourceHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到可交換的來源 Holomem");
        }

        Map<String, Object> source = jdbcTemplate.query(
            """
            SELECT h.id, h.zone, COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0) AS remain_hp
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.id = ?
              AND h.match_id = ?
              AND h.owner_user_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("zone", rs.getString("zone"));
                row.put("remain_hp", rs.getInt("remain_hp"));
                return row;
            },
            sourceHolomemId,
            matchId,
            userId
        );
        if (source == null) {
            return executeNoOpEffect(effectType, effectNode, "來源 Holomem 不存在");
        }
        String sourceZone = normalize(source.get("zone"));
        if (rawText.contains("バックポジション限定") && !"BACK".equals(sourceZone)) {
            return executeNoOpEffect(effectType, effectNode, "來源 Holomem 不在 BACK，無法交換");
        }

        boolean requireLowHpCollab = rawText.contains("残りHP70以下");
        Map<String, Object> collabTarget = jdbcTemplate.query(
            """
            SELECT h.id, h.match_card_id, COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0) AS remain_hp
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'COLLAB'
              AND (? = FALSE OR (COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0)) <= 70)
            ORDER BY h.id
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("remain_hp", rs.getInt("remain_hp"));
                return row;
            },
            matchId,
            userId,
            requireLowHpCollab
        );
        if (collabTarget == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有符合條件的 COLLAB 目標可交換");
        }
        Long collabHolomemId = asLong(collabTarget.get("id"));
        if (collabHolomemId == null) {
            return executeNoOpEffect(effectType, effectNode, "COLLAB 目標資料不足");
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'COLLAB',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            sourceHolomemId,
            matchId,
            userId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET zone = 'BACK',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            collabHolomemId,
            matchId,
            userId
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("swapped", true);
        summary.put("sourceHolomemId", sourceHolomemId);
        summary.put("targetHolomemId", collabHolomemId);
        summary.put("sourceHolomemCardInstanceId", resolveHolomemCardInstanceId(sourceHolomemId));
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(collabHolomemId));
        summary.put("requireLowHpCollab", requireLowHpCollab);
        return summary;
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
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("DAMAGE 找不到可攻擊的對手 Holomen");
        }
        Long targetOwnerUserId = resolveHolomemOwner(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("DAMAGE 結算失敗：找不到目標擁有者");
        }

        int baseDamage = resolveDamageValue(effectNode);
        if (baseDamage <= 0) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("damageRequested", 0);
            summary.put("damageApplied", 0);
            summary.put("baseDamage", 0);
            summary.put("damageModifierApplied", 0);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("downed", false);
            summary.put("lifeReduced", false);
            summary.put("reason", "無可用傷害數值");
            return summary;
        }
        int currentTurn = resolveCurrentTurnNumber(matchId);
        int damageModifier = resolveActiveDamageModifier(matchId, userId, currentTurn);
        int damage = Math.max(baseDamage + damageModifier, 0);
        boolean specialDamage = StringUtils.hasText(rawText) && rawText.contains("特殊ダメージ");
        if (damage <= 0) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("damageRequested", baseDamage);
            summary.put("damageApplied", 0);
            summary.put("baseDamage", baseDamage);
            summary.put("damageModifierApplied", damageModifier);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("downed", false);
            summary.put("lifeReduced", false);
            summary.put("reason", "修正後傷害小於等於 0");
            return summary;
        }
        if (isHpChangeBlockedByOpponentAbility(matchId, userId, targetOwnerUserId, targetHolomemId, effectType)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("damageRequested", baseDamage);
            summary.put("damageApplied", 0);
            summary.put("baseDamage", baseDamage);
            summary.put("damageModifierApplied", damageModifier);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("downed", false);
            summary.put("lifeReduced", false);
            summary.put("reason", "目標在相手のメインステップ中不受相手能力的 HP 變動影響");
            return summary;
        }
        String targetCurrentZone = resolveHolomemZone(matchId, targetHolomemId);
        if (
            specialDamage
                && isSpecialDamageImmunityActive(matchId, targetOwnerUserId, currentTurn, targetCurrentZone)
        ) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("damageRequested", baseDamage);
            summary.put("damageApplied", 0);
            summary.put("baseDamage", baseDamage);
            summary.put("damageModifierApplied", damageModifier);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("downed", false);
            summary.put("lifeReduced", false);
            summary.put("specialDamagePrevented", true);
            summary.put("reason", "特殊ダメージ無効化効果が有効");
            return summary;
        }
        Map<String, Object> specialDamageGiftSummary = specialDamage
            ? tryActivateHsd13012SpecialDamageImmunity(
                matchId,
                userId,
                targetOwnerUserId,
                targetHolomemId,
                targetCurrentZone,
                currentTurn
            )
            : null;
        if (specialDamageGiftSummary != null && toBoolean(specialDamageGiftSummary.get("preventedDamage"))) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("damageRequested", baseDamage);
            summary.put("damageApplied", 0);
            summary.put("baseDamage", baseDamage);
            summary.put("damageModifierApplied", damageModifier);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("downed", false);
            summary.put("lifeReduced", false);
            summary.put("specialDamagePrevented", true);
            summary.put("specialDamageGift", specialDamageGiftSummary);
            summary.put("reason", "HSD13-012 特殊ダメージ無効化");
            return summary;
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = COALESCE(damage_taken, 0) + ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            damage,
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );

        Map<String, Object> holomemState = jdbcTemplate.query(
            """
            SELECT id, match_card_id, card_id, zone, damage_taken
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("zone", rs.getString("zone"));
                row.put("damage_taken", rs.getInt("damage_taken"));
                return row;
            },
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );
        if (holomemState == null) {
            throw new IllegalStateException("DAMAGE 結算失敗：找不到目標 Holomen");
        }

        String targetCardId = asText(holomemState.get("card_id"));
        int baseHp = jdbcTemplate.query(
            "SELECT hp FROM member_cards WHERE card_id = ?",
            rs -> rs.next() ? rs.getInt("hp") : 0,
            targetCardId
        );
        int attachedSupportHpBonus = resolveAttachedSupportStatBonus(
            matchId,
            targetHolomemId,
            ATTACHED_SUPPORT_HP_PATTERN
        );
        PassiveGiftHpTargetContext targetContext = loadPassiveGiftHpTargetContext(matchId, targetOwnerUserId, targetHolomemId);
        PassiveGiftHolderContext holderContext = loadPassiveGiftHolderContext(matchId, targetOwnerUserId, targetHolomemId);
        int passiveGiftHpBonus = targetContext == null || holderContext == null
            ? 0
            : resolvePassiveGiftHpBonusFromHolder(holderContext, targetContext);
        int hp = Math.max(baseHp + attachedSupportHpBonus + passiveGiftHpBonus, 0);
        int damageTaken = asInt(holomemState.get("damage_taken"));

        boolean downed = hp > 0 && damageTaken >= hp;
        boolean lifeReduced = false;
        Long lostLifeCardInstanceId = null;
        List<Long> lostLifeCardInstanceIds = new ArrayList<>();
        Map<String, Object> downEventSummary = null;
        List<Long> archivedCheerCardInstanceIds = new ArrayList<>();
        List<Long> archivedSupportCardInstanceIds = new ArrayList<>();
        List<Long> archivedHolomemCardInstanceIds = new ArrayList<>();
        if (downed) {
            Long targetCardInstanceId = asLong(holomemState.get("match_card_id"));
            String targetZone = normalize(holomemState.get("zone"));
            archivedCheerCardInstanceIds = archiveAttachedCheerCards(matchId, targetHolomemId, targetOwnerUserId);
            archivedSupportCardInstanceIds = archiveAttachedSupportCards(matchId, targetHolomemId, targetOwnerUserId);
            archivedHolomemCardInstanceIds = archiveHolomemStackCards(matchId, targetHolomemId, targetOwnerUserId);

            jdbcTemplate.update(
                "DELETE FROM match_holomems WHERE id = ? AND match_id = ?",
                targetHolomemId,
                matchId
            );
            if (archivedHolomemCardInstanceIds.isEmpty() && targetCardInstanceId != null) {
                int archiveOrder = nextZoneOrder(matchId, targetOwnerUserId, "ARCHIVE");
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
                    """,
                    archiveOrder,
                    targetCardInstanceId,
                    matchId,
                    targetOwnerUserId
                );
            }

            boolean suppressLifeLoss = isDownWithoutLifeLoss(effectNode);
            if (!suppressLifeLoss && "CENTER".equals(targetZone)) {
                lostLifeCardInstanceId = loseLifeOnce(matchId, targetOwnerUserId);
                lifeReduced = lostLifeCardInstanceId != null;
                if (lostLifeCardInstanceId != null) {
                    lostLifeCardInstanceIds.add(lostLifeCardInstanceId);
                }
            }
            boolean deferDownEvent = effectNode != null && effectNode.path("deferDownEvent").asBoolean(false);
            downEventSummary = executeDownEvent(
                matchId,
                userId,
                targetOwnerUserId,
                targetCardId,
                currentTurn,
                !deferDownEvent,
                targetZone
            );
            if (!deferDownEvent && toBoolean(downEventSummary.get("lifeReduced"))) {
                lifeReduced = true;
                lostLifeCardInstanceIds.addAll(extractLostLifeCardInstanceIds(downEventSummary));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("damageRequested", baseDamage);
        summary.put("damageApplied", damage);
        summary.put("baseDamage", baseDamage);
        summary.put("damageModifierApplied", damageModifier);
        summary.put("targetBaseHp", baseHp);
        summary.put("targetAttachedSupportHpBonus", attachedSupportHpBonus);
        summary.put("targetHp", hp);
        summary.put("targetDamageTaken", damageTaken);
        summary.put("downed", downed);
        summary.put("archivedCheerCardInstanceIds", archivedCheerCardInstanceIds);
        summary.put("archivedSupportCardInstanceIds", archivedSupportCardInstanceIds);
        summary.put("archivedHolomemCardInstanceIds", archivedHolomemCardInstanceIds);
        summary.put("lifeReduced", lifeReduced);
        summary.put("lostLifeCardInstanceId", lostLifeCardInstanceIds.isEmpty() ? null : lostLifeCardInstanceIds.get(0));
        summary.put("lostLifeCardInstanceIds", lostLifeCardInstanceIds);
        if (downEventSummary != null) {
            summary.put("downEvent", downEventSummary);
        }
        return summary;
    }

    private Map<String, Object> tryActivateHsd13012SpecialDamageImmunity(
        Long matchId,
        Long sourceUserId,
        Long defendingUserId,
        Long targetHolomemId,
        String targetZone,
        int currentTurn
    ) {
        if (
            matchId == null
                || sourceUserId == null
                || defendingUserId == null
                || targetHolomemId == null
                || currentTurn <= 0
        ) {
            return null;
        }
        if (Objects.equals(sourceUserId, defendingUserId)) {
            return null;
        }
        if (!"BACK".equals(normalize(targetZone))) {
            return null;
        }
        if (!isOpponentTurnForUser(matchId, defendingUserId)) {
            return null;
        }
        List<Map<String, Object>> holders = jdbcTemplate.queryForList(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   m.passive_effect_json::text AS passive_text
            FROM match_holomems h
            JOIN member_cards m ON m.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.card_id = 'HSD13-012'
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
            String giftText = loadGiftEffectText(asText(holder.get("passive_text")));
            if (!StringUtils.hasText(giftText)) {
                continue;
            }
            if (!giftText.contains("自分のバックホロメンが相手から特殊ダメージを受ける時")) {
                continue;
            }
            if (!giftText.contains("このターンの間、自分のバックホロメン全員は特殊ダメージを受けない")) {
                continue;
            }
            if (!matchesGiftTurnOwnershipCondition(matchId, defendingUserId, giftText)) {
                continue;
            }
            Long archivedStackCardInstanceId = archiveOneStackCardFromHolder(
                matchId,
                defendingUserId,
                holderHolomemId,
                holderCardInstanceId
            );
            if (archivedStackCardInstanceId == null || archivedStackCardInstanceId <= 0) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actions", List.of("SPECIAL_DAMAGE_IMMUNITY"));
            payload.put("zones", List.of("BACK"));
            payload.put("sourceCardId", asText(holder.get("card_id")));
            payload.put("holderHolomemId", holderHolomemId);
            payload.put("holderCardInstanceId", holderCardInstanceId);
            payload.put("rawText", giftText);
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
                defendingUserId,
                defendingUserId,
                "BUFF",
                currentTurn,
                effectTextParser.toJsonString(payload)
            );
            if (inserted != 1) {
                return null;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("triggerType", "SPECIAL_DAMAGE_RECEIVED");
            summary.put("preventedDamage", true);
            summary.put("holderHolomemId", holderHolomemId);
            summary.put("holderCardInstanceId", holderCardInstanceId);
            summary.put("holderCardId", asText(holder.get("card_id")));
            summary.put("archivedStackCardInstanceId", archivedStackCardInstanceId);
            summary.put("expiresTurn", currentTurn);
            summary.put("targetHolomemId", targetHolomemId);
            return summary;
        }
        return null;
    }

    private Long archiveOneStackCardFromHolder(
        Long matchId,
        Long userId,
        Long holderHolomemId,
        Long holderCardInstanceId
    ) {
        if (matchId == null || userId == null || holderHolomemId == null || holderCardInstanceId == null) {
            return null;
        }
        Long stackCardInstanceId = jdbcTemplate.query(
            """
            SELECT s.match_card_id
            FROM match_holomem_stack_cards s
            WHERE s.match_holomem_id = ?
              AND s.match_card_id <> ?
            ORDER BY s.stack_order DESC, s.id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            holderHolomemId,
            holderCardInstanceId
        );
        if (stackCardInstanceId == null || stackCardInstanceId <= 0) {
            return null;
        }
        int archiveOrder = nextZoneOrder(matchId, userId, "ARCHIVE");
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
            """,
            archiveOrder,
            stackCardInstanceId,
            matchId,
            userId
        );
        if (moved != 1) {
            return null;
        }
        jdbcTemplate.update(
            """
            DELETE FROM match_holomem_stack_cards
            WHERE match_holomem_id = ?
              AND match_card_id = ?
            """,
            holderHolomemId,
            stackCardInstanceId
        );
        return stackCardInstanceId;
    }

    private boolean isSpecialDamageImmunityActive(
        Long matchId,
        Long affectedUserId,
        int currentTurn,
        String targetZone
    ) {
        if (
            matchId == null
                || affectedUserId == null
                || currentTurn <= 0
                || !"BACK".equals(normalize(targetZone))
        ) {
            return false;
        }
        Integer count = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_turn_effects
            WHERE match_id = ?
              AND affected_user_id = ?
              AND stat_type = 'ACTION_LOCK'
              AND expires_turn >= ?
              AND payload::text LIKE '%"SPECIAL_DAMAGE_IMMUNITY"%'
              AND payload::text LIKE '%"BACK"%'
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            affectedUserId,
            currentTurn
        );
        return count != null && count > 0;
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
     * 執行回復效果，將目標傷害值下修至不低於 0。
     */
    Map<String, Object> executeHealEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            false
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("HEAL 找不到可回復的 Holomen");
        }
        Long targetOwnerUserId = resolveHolomemOwner(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("HEAL 結算失敗：找不到目標擁有者");
        }

        int healRequested = resolveHealValue(effectNode);
        if (healRequested <= 0) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("healRequested", 0);
            summary.put("healApplied", 0);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("reason", "無可用回復數值");
            return summary;
        }
        if (isHpChangeBlockedByOpponentAbility(matchId, userId, targetOwnerUserId, targetHolomemId, effectType)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
            summary.put("healRequested", healRequested);
            summary.put("healApplied", 0);
            summary.put("reason", "目標在相手のメインステップ中不受相手能力的 HP 變動影響");
            return summary;
        }

        Integer beforeDamage = jdbcTemplate.query(
            """
            SELECT damage_taken
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            rs -> rs.next() ? rs.getInt("damage_taken") : null,
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );
        if (beforeDamage == null) {
            throw new IllegalStateException("HEAL 結算失敗：找不到目標 Holomen");
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = GREATEST(COALESCE(damage_taken, 0) - ?, 0),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            healRequested,
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );

        int afterDamage = jdbcTemplate.query(
            "SELECT COALESCE(damage_taken, 0) FROM match_holomems WHERE id = ?",
            rs -> rs.next() ? rs.getInt(1) : 0,
            targetHolomemId
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("healRequested", healRequested);
        summary.put("healApplied", Math.max(beforeDamage - afterDamage, 0));
        summary.put("damageBefore", beforeDamage);
        summary.put("damageAfter", afterDamage);
        return summary;
    }

    /**
     * 執行移除 cheer 效果，將 cheer 從 Holomem 轉移至指定區域。
     */
    Map<String, Object> executeRemoveCheerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("REMOVE_CHEER 找不到目標 Holomen");
        }
        Long targetOwnerUserId = resolveHolomemOwner(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("REMOVE_CHEER 結算失敗：找不到目標擁有者");
        }

        int removeRequested = resolveCheerCount(effectNode, 1);
        int removeCount = Math.max(removeRequested, 1);
        List<Map<String, Object>> cheerRows = jdbcTemplate.queryForList(
            """
            SELECT id, cheer_card_id, match_card_id
            FROM match_holomem_cheers
            WHERE match_holomem_id = ?
            ORDER BY id
            LIMIT ?
            """,
            targetHolomemId,
            removeCount
        );

        List<Long> archivedCardInstanceIds = new ArrayList<>();
        List<String> removedCheerCardIds = new ArrayList<>();
        for (Map<String, Object> row : cheerRows) {
            Long cheerRowId = asLong(row.get("id"));
            Long cheerCardInstanceId = asLong(row.get("match_card_id"));
            String cheerCardId = asText(row.get("cheer_card_id"));
            if (cheerRowId == null || !StringUtils.hasText(cheerCardId)) {
                continue;
            }
            int deleted = jdbcTemplate.update(
                "DELETE FROM match_holomem_cheers WHERE id = ? AND match_holomem_id = ?",
                cheerRowId,
                targetHolomemId
            );
            if (deleted != 1) {
                continue;
            }
            removedCheerCardIds.add(cheerCardId);
            Long archivedCardInstanceId = moveCheerCardInstanceToArchive(
                matchId,
                targetOwnerUserId,
                cheerCardInstanceId,
                cheerCardId
            );
            if (archivedCardInstanceId != null) {
                archivedCardInstanceIds.add(archivedCardInstanceId);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("removeRequested", removeCount);
        summary.put("removeApplied", removedCheerCardIds.size());
        summary.put("removedCheerCardIds", removedCheerCardIds);
        summary.put("archivedCheerCardInstanceIds", archivedCardInstanceIds);
        return summary;
    }

    /**
     * 執行移除場上 Cheer 效果，從自己場上任一 Holomem 的附屬 Cheer 中移除指定數量。
     */
    Map<String, Object> executeRemoveStageCheerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int removeRequested = resolveCheerCount(effectNode, 1);
        int removeCount = Math.max(removeRequested, 1);
        List<Map<String, Object>> cheerRows = jdbcTemplate.queryForList(
            """
            SELECT hc.id, hc.cheer_card_id, hc.match_holomem_id, hc.match_card_id
            FROM match_holomem_cheers hc
            JOIN match_holomems h ON h.id = hc.match_holomem_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
            ORDER BY h.id, hc.id
            LIMIT ?
            """,
            matchId,
            userId,
            removeCount
        );

        List<Long> archivedCardInstanceIds = new ArrayList<>();
        List<String> removedCheerCardIds = new ArrayList<>();
        List<Long> sourceHolomemIds = new ArrayList<>();
        for (Map<String, Object> row : cheerRows) {
            Long cheerRowId = asLong(row.get("id"));
            String cheerCardId = asText(row.get("cheer_card_id"));
            Long sourceHolomemId = asLong(row.get("match_holomem_id"));
            Long cheerCardInstanceId = asLong(row.get("match_card_id"));
            if (cheerRowId == null || !StringUtils.hasText(cheerCardId) || sourceHolomemId == null) {
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
            removedCheerCardIds.add(cheerCardId);
            sourceHolomemIds.add(sourceHolomemId);
            Long archivedCardInstanceId = moveCheerCardInstanceToArchive(
                matchId,
                userId,
                cheerCardInstanceId,
                cheerCardId
            );
            if (archivedCardInstanceId != null) {
                archivedCardInstanceIds.add(archivedCardInstanceId);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("removeRequested", removeCount);
        summary.put("removeApplied", removedCheerCardIds.size());
        summary.put("removedCheerCardIds", removedCheerCardIds);
        summary.put("sourceHolomemIds", sourceHolomemIds);
        summary.put("archivedCheerCardInstanceIds", archivedCardInstanceIds);
        return summary;
    }

    /**
     * 執行區域移動效果（CENTER/BACK/COLLAB 等），含休息狀態調整。
     */
    Map<String, Object> executeMoveZoneEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("MOVE_ZONE 找不到目標 Holomen");
        }
        Map<String, Object> holomem = jdbcTemplate.query(
            """
            SELECT owner_user_id, zone
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("owner_user_id", rs.getLong("owner_user_id"));
                row.put("zone", rs.getString("zone"));
                return row;
            },
            targetHolomemId,
            matchId
        );
        if (holomem == null) {
            throw new IllegalStateException("MOVE_ZONE 結算失敗：找不到目標 Holomen");
        }

        Long targetOwnerUserId = asLong(holomem.get("owner_user_id"));
        String fromZone = normalize(holomem.get("zone"));
        String toZone = resolveMoveDestinationZone(effectNode);
        boolean restAfterMove = shouldRestAfterMove(effectNode);
        String rawText = effectTextParser.extractText(effectNode, "rawText", "rawEffect");
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        int currentTurn = resolveCurrentTurnNumber(matchId);
        if (isActionLockActive(matchId, targetOwnerUserId, currentTurn, "MOVE_STAGE", fromZone, targetHolomemId)) {
            return executeNoOpEffect(effectType, effectNode, "目前效果限制：不可移動");
        }

        if (
            targetHolomemCardInstanceId == null
            && StringUtils.hasText(rawText)
            && rawText.contains("バックホロメン")
            && targetOwnerUserId != null
        ) {
            Long backTargetHolomemId = jdbcTemplate.query(
                """
                SELECT id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'BACK'
                ORDER BY id
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getLong("id") : null,
                matchId,
                targetOwnerUserId
            );
            if (backTargetHolomemId != null) {
                targetHolomemId = backTargetHolomemId;
                holomem = jdbcTemplate.query(
                    """
                    SELECT owner_user_id, zone
                    FROM match_holomems
                    WHERE id = ?
                      AND match_id = ?
                    """,
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("owner_user_id", rs.getLong("owner_user_id"));
                        row.put("zone", rs.getString("zone"));
                        return row;
                    },
                    targetHolomemId,
                    matchId
                );
                if (holomem != null) {
                    targetOwnerUserId = asLong(holomem.get("owner_user_id"));
                    fromZone = normalize(holomem.get("zone"));
                }
            }
        }

        if (!"BACK".equals(toZone) && !"CENTER".equals(toZone) && !"COLLAB".equals(toZone)) {
            toZone = "BACK";
        }

        if (
            StringUtils.hasText(rawText)
            && rawText.contains("コラボホロメンがいないなら")
            && targetOwnerUserId != null
        ) {
            Integer collabCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'COLLAB'
                """,
                Integer.class,
                matchId,
                targetOwnerUserId
            );
            if (collabCount != null && collabCount > 0) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("effectType", effectType);
                summary.put("targetHolomemId", targetHolomemId);
                summary.put("fromZone", fromZone);
                summary.put("toZone", toZone);
                summary.put("moved", false);
                summary.put("reason", "條件不成立：目標玩家已有 COLLAB");
                return summary;
            }
        }

        if (toZone.equals(fromZone)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("effectType", effectType);
            summary.put("targetHolomemId", targetHolomemId);
            summary.put("fromZone", fromZone);
            summary.put("toZone", toZone);
            summary.put("moved", false);
            summary.put("reason", "目標已在同區域");
            return summary;
        }

        if ("BACK".equals(toZone) && targetOwnerUserId != null) {
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
                targetOwnerUserId
            );
            if (backCount != null && backCount >= 5) {
                throw new IllegalStateException("MOVE_ZONE 失敗：目標 BACK 已滿");
            }
        }
        if ("CENTER".equals(toZone) && targetOwnerUserId != null) {
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
                targetOwnerUserId
            );
            if (centerCount != null && centerCount > 0) {
                throw new IllegalStateException("MOVE_ZONE 失敗：目標 CENTER 已有 Holomen");
            }
        }
        if ("COLLAB".equals(toZone) && targetOwnerUserId != null) {
            Integer collabCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'COLLAB'
                """,
                Integer.class,
                matchId,
                targetOwnerUserId
            );
            if (collabCount != null && collabCount > 0) {
                throw new IllegalStateException("MOVE_ZONE 失敗：目標 COLLAB 已有 Holomen");
            }
        }

        Boolean restedAfterMove = null;
        EffectContext actionContext = new EffectContext(
            matchId,
            userId,
            currentTurn,
            effectType,
            resolveHolomemCardInstanceId(targetHolomemId),
            null
        );
        HolomemMoveZoneAction moveAction = new HolomemMoveZoneAction(targetHolomemId, fromZone, toZone, restAfterMove);
        List<ActionResult> actionResults = gameActionExecutor.execute(actionContext, List.of(moveAction));
        if (!actionResults.isEmpty() && actionResults.get(0).success()) {
            Object rested = actionResults.get(0).details().get("rested");
            if (rested instanceof Boolean value) {
                restedAfterMove = value;
            }
        } else {
            // fallback: preserve previous SQL behavior
            jdbcTemplate.update(
                """
                UPDATE match_holomems
                SET zone = ?,
                    is_rested = CASE WHEN ? THEN TRUE ELSE is_rested END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                """,
                toZone,
                restAfterMove,
                targetHolomemId,
                matchId
            );
            restedAfterMove = jdbcTemplate.query(
                "SELECT is_rested FROM match_holomems WHERE id = ? AND match_id = ?",
                rs -> rs.next() ? rs.getBoolean("is_rested") : null,
                targetHolomemId,
                matchId
            );
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", resolveHolomemCardInstanceId(targetHolomemId));
        summary.put("fromZone", fromZone);
        summary.put("toZone", toZone);
        summary.put("rested", restedAfterMove);
        summary.put("moved", true);
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
     * 直接結算勝負效果（WIN/LOSE/MATCH_RESULT）。
     */
    Map<String, Object> executeMatchResultEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        Long opponentUserId = resolveOpponentUserId(matchId, userId);
        MatchResultDecision decision = resolveMatchResultDecision(effectType, effectNode, userId, opponentUserId);
        if (decision == null) {
            return executeNoOpEffect(effectType, effectNode, "MATCH_RESULT 無法解析出勝負結果");
        }

        Map<String, Object> matchResult = new LinkedHashMap<>();
        matchResult.put("draw", decision.draw());
        matchResult.put("winnerUserId", decision.winnerUserId());
        matchResult.put("loserUserId", decision.loserUserId());
        matchResult.put("reason", decision.reason());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectTextParser.normalizeEffectType(effectType));
        summary.put("applied", true);
        summary.put("matchResult", matchResult);
        return summary;
    }

    /**
     * 解析勝負效果對應的勝者/敗者與 reason code。
     */
    private MatchResultDecision resolveMatchResultDecision(
        String effectType,
        JsonNode effectNode,
        Long actorUserId,
        Long opponentUserId
    ) {
        String explicitResult = effectTextParser.normalizeEffectType(readText(effectNode, "result", "outcome", "matchResult"));
        String normalizedEffectType = effectTextParser.normalizeEffectType(effectType);
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));

        String resolvedReason = readText(effectNode, "reason");
        if (!StringUtils.hasText(resolvedReason)) {
            resolvedReason = "CARD_EFFECT_MATCH_RESULT";
        }

        String winnerToken = effectTextParser.normalizeEffectType(readText(effectNode, "winner", "winnerSide", "winnerUser"));
        String loserToken = effectTextParser.normalizeEffectType(readText(effectNode, "loser", "loserSide", "loserUser"));

        if ("WIN".equals(normalizedEffectType) || "LOSE".equals(normalizedEffectType) || "DRAW".equals(normalizedEffectType)) {
            explicitResult = normalizedEffectType;
        }

        if ("DRAW".equals(explicitResult)) {
            return new MatchResultDecision(true, null, null, "CARD_EFFECT_DRAW");
        }
        if ("WIN".equals(explicitResult)) {
            if (opponentUserId == null) {
                return null;
            }
            return new MatchResultDecision(false, actorUserId, opponentUserId, "CARD_EFFECT_WIN");
        }
        if ("LOSE".equals(explicitResult)) {
            if (opponentUserId == null) {
                return null;
            }
            return new MatchResultDecision(false, opponentUserId, actorUserId, "CARD_EFFECT_LOSE");
        }

        if (isBothToken(winnerToken) || isBothToken(loserToken)) {
            return new MatchResultDecision(true, null, null, "CARD_EFFECT_DRAW");
        }

        Long winnerUserId = resolveSideUserId(winnerToken, actorUserId, opponentUserId);
        Long loserUserId = resolveSideUserId(loserToken, actorUserId, opponentUserId);
        if (winnerUserId != null && loserUserId == null) {
            loserUserId = winnerUserId.equals(actorUserId) ? opponentUserId : actorUserId;
        } else if (winnerUserId == null && loserUserId != null) {
            winnerUserId = loserUserId.equals(actorUserId) ? opponentUserId : actorUserId;
        }
        if (winnerUserId != null && loserUserId != null && !winnerUserId.equals(loserUserId)) {
            return new MatchResultDecision(false, winnerUserId, loserUserId, resolvedReason);
        }

        if (StringUtils.hasText(rawText)) {
            if (rawText.contains("引き分け")) {
                return new MatchResultDecision(true, null, null, "CARD_EFFECT_DRAW");
            }
            if (rawText.contains("あなた") && rawText.contains("勝利")) {
                if (opponentUserId == null) {
                    return null;
                }
                return new MatchResultDecision(false, actorUserId, opponentUserId, "CARD_EFFECT_WIN");
            }
            if (rawText.contains("相手") && rawText.contains("敗北")) {
                if (opponentUserId == null) {
                    return null;
                }
                return new MatchResultDecision(false, actorUserId, opponentUserId, "CARD_EFFECT_WIN");
            }
            if (rawText.contains("あなた") && rawText.contains("敗北")) {
                if (opponentUserId == null) {
                    return null;
                }
                return new MatchResultDecision(false, opponentUserId, actorUserId, "CARD_EFFECT_LOSE");
            }
        }
        return null;
    }

    /**
     * 判斷 winner/loser token 是否代表雙方（平手）。
     */
    private boolean isBothToken(String token) {
        String normalized = effectTextParser.normalizeEffectType(token);
        return "BOTH".equals(normalized) || "ALL".equals(normalized);
    }

    /**
     * 將 side token（SELF/OPPONENT 等）解析成實際 userId。
     */
    private Long resolveSideUserId(String sideToken, Long actorUserId, Long opponentUserId) {
        String normalized = effectTextParser.normalizeEffectType(sideToken);
        return switch (normalized) {
            case "SELF", "YOU", "ME", "ACTOR", "CURRENT" -> actorUserId;
            case "OPPONENT", "ENEMY", "OTHER" -> opponentUserId;
            default -> null;
        };
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
     * 解析 MOVE_TO_HOLOPOWER 的來源區，預設 DECK。
     */
    private String resolveMoveToHolopowerSourceZone(JsonNode effectNode, String rawText) {
        String explicit = effectTextParser.normalizeEffectType(readText(effectNode, "holopowerSourceZone", "moveSourceZone", "sourceZone"));
        if ("DECK".equals(explicit) || "ARCHIVE".equals(explicit) || "HAND".equals(explicit)) {
            return explicit;
        }
        String text = effectTextParser.normalizeDigits(rawText);
        if (text.contains("手札") && text.contains("ホロパワーにする")) {
            return "HAND";
        }
        if (text.contains("アーカイブ") && text.contains("ホロパワーにする")) {
            return "ARCHIVE";
        }
        return "DECK";
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
     * 解析抽牌數量，支援欄位讀值與文案推斷。
     */
    private int resolveDrawCount(JsonNode effectNode) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        int exact = effectTextParser.extractByPattern(text, DRAW_COUNT_PATTERN);
        if (exact > 0) {
            return exact;
        }
        int fallback = effectTextParser.extractByPattern(text, DRAW_COUNT_FALLBACK_PATTERN);
        if (fallback > 0) {
            return fallback;
        }
        return text.contains("引く") ? 1 : 0;
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
     * 讀取當前 phase 與 turn player。
     */
    private MatchTurnContext loadMatchTurnContext(Long matchId) {
        return jdbcTemplate.query(
            """
            SELECT current_phase, current_turn_player_id
            FROM matches
            WHERE id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new MatchTurnContext(
                    effectTextParser.normalizeEffectType(rs.getString("current_phase")),
                    asLong(rs.getObject("current_turn_player_id"))
                );
            },
            matchId
        );
    }

    /**
     * 判斷靜態 Gift 指向的受益目標是否要求特定站位。
     */
    private boolean matchesPassiveGiftTargetZoneRestriction(String rawText, String targetStageZone) {
        if (!StringUtils.hasText(rawText)) {
            return true;
        }
        boolean mentionsCenterHolomem = rawText.contains("センターホロメン");
        boolean mentionsCollabHolomem = rawText.contains("コラボホロメン");
        boolean mentionsBackHolomem = rawText.contains("バックホロメン");
        boolean mentionsCenterPositionDamageTarget = rawText.contains("センターポジションで受けるダメージ");
        boolean mentionsCollabPositionDamageTarget = rawText.contains("コラボポジションで受けるダメージ");
        boolean mentionsBackPositionDamageTarget = rawText.contains("バックポジションで受けるダメージ");
        if (!mentionsCenterHolomem
            && !mentionsCollabHolomem
            && !mentionsBackHolomem
            && !mentionsCenterPositionDamageTarget
            && !mentionsCollabPositionDamageTarget
            && !mentionsBackPositionDamageTarget) {
            return true;
        }
        if ((mentionsCenterHolomem || mentionsCenterPositionDamageTarget) && "CENTER".equals(targetStageZone)) {
            return true;
        }
        if ((mentionsCollabHolomem || mentionsCollabPositionDamageTarget) && "COLLAB".equals(targetStageZone)) {
            return true;
        }
        if ((mentionsBackHolomem || mentionsBackPositionDamageTarget) && "BACK".equals(targetStageZone)) {
            return true;
        }
        return false;
    }

    /**
     * 判斷常駐減傷是否要求來自特定等級 Holomem 的傷害來源。
     */
    private boolean matchesIncomingDamageSourceLevelRestriction(String rawText, String incomingSourceLevelType) {
        if (!StringUtils.hasText(rawText)) {
            return true;
        }
        String normalizedText = rawText.toUpperCase(Locale.ROOT);
        String normalizedLevel = effectTextParser.normalizeEffectType(incomingSourceLevelType);
        boolean mentionsDebut = normalizedText.contains("DEBUTホロメンから受けるダメージ")
            || normalizedText.contains("DEBUTホロメンから受けるアーツダメージ");
        boolean mentionsFirst = normalizedText.contains("1STホロメンから受けるダメージ")
            || normalizedText.contains("FIRSTホロメンから受けるダメージ")
            || normalizedText.contains("1STホロメンから受けるアーツダメージ")
            || normalizedText.contains("FIRSTホロメンから受けるアーツダメージ");
        boolean mentionsSecond = normalizedText.contains("2NDホロメンから受けるダメージ")
            || normalizedText.contains("SECONDホロメンから受けるダメージ")
            || normalizedText.contains("2NDホロメンから受けるアーツダメージ")
            || normalizedText.contains("SECONDホロメンから受けるアーツダメージ");
        boolean mentionsSpot = normalizedText.contains("SPOTホロメンから受けるダメージ")
            || normalizedText.contains("SPOTホロメンから受けるアーツダメージ");
        boolean mentionsBuzz = normalizedText.contains("BUZZホロメンから受けるダメージ")
            || normalizedText.contains("BUZZホロメンから受けるアーツダメージ");
        if (!mentionsDebut && !mentionsFirst && !mentionsSecond && !mentionsSpot && !mentionsBuzz) {
            return true;
        }
        if (mentionsDebut && "DEBUT".equals(normalizedLevel)) {
            return true;
        }
        if (mentionsFirst && "FIRST".equals(normalizedLevel)) {
            return true;
        }
        if (mentionsSecond && "SECOND".equals(normalizedLevel)) {
            return true;
        }
        if (mentionsSpot && "SPOT".equals(normalizedLevel)) {
            return true;
        }
        if (mentionsBuzz && "BUZZ".equals(normalizedLevel)) {
            return true;
        }
        return false;
    }

    private boolean matchesPassiveGiftRequiredOshiName(String rawText, String actualOshiCardName) {
        String requiredOshiName = resolveRequiredOshiName(rawText);
        if (!StringUtils.hasText(requiredOshiName)) {
            return true;
        }
        return StringUtils.hasText(actualOshiCardName) && actualOshiCardName.contains(requiredOshiName);
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
     * 載入常駐 Gift HP 加成受益者所需資訊。
     *
     * <p>這和藝能加成不同，因為 `HSD13-007` 的條件直接依賴「這張 Holomem 身上有幾張 Cheer」。
     * 因此這裡除了基本站位/等級/tag，還要把附著 Cheer 數量一起帶出來。
     */
    PassiveGiftHpTargetContext loadPassiveGiftHpTargetContext(Long matchId, Long userId, Long holomemId) {
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
                return new PassiveGiftHpTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    parseTagsJson(rs.getString("tags_json_text")),
                    rs.getInt("attached_cheer_count")
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    /**
     * 載入受傷減免判斷所需的最小 target 狀態。
     *
     * <p>目前除了既有的 self / collab 類型，也開始支援：
     * 例如：
     *
     * <p>- `HSD07-009` 需要知道受擊者是不是 holder 自己
     * <p>- `HBP06-009` 需要知道受擊者是不是 `COLLAB`
     * <p>- `HBP04-087` / `HBP05-008` 需要知道受擊者的站位 / 等級 / tag
     *
     * <p>若未來出現更多「依等級 / tag / 名稱決定誰能被保護」的常駐減傷文案，再往這個 target context
     * 補欄位即可，不需要回頭重寫 `attackArt(...)` 主流程。
     */
    PassiveGiftIncomingDamageReductionTargetContext loadPassiveGiftIncomingDamageReductionTargetContext(
        Long matchId,
        Long userId,
        Long holomemId,
        String incomingSourceLevelType
    ) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   h.current_level,
                   c.name,
                   COALESCE(c.tags_json, '[]'::jsonb)::text AS tags_json_text,
                   oc.name AS oshi_card_name
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            JOIN match_players mp
              ON mp.match_id = h.match_id
             AND mp.user_id = h.owner_user_id
            JOIN cards oc ON oc.card_id = mp.oshi_card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new PassiveGiftIncomingDamageReductionTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    rs.getString("name"),
                    parseTagsJson(rs.getString("tags_json_text")),
                    rs.getString("oshi_card_name"),
                    effectTextParser.normalizeEffectType(incomingSourceLevelType)
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    /**
     * 載入「相手能力不能改變 HP」所需的 target 狀態。
     */
    private PassiveGiftHpChangePreventionTargetContext loadPassiveGiftHpChangePreventionTargetContext(
        Long matchId,
        Long userId,
        Long holomemId
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
                return new PassiveGiftHpChangePreventionTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    rs.getString("name"),
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
     * 載入我方場上可提供被動文案的 holder。
     *
     * <p>這裡先抓 `CENTER / COLLAB`，再交由文案 matcher 做最終站位過濾。
     * 這樣像 `HSD07-009`（center 限定）與 `HBP04-068`（center/collab 限定）可共用同一條主幹。
     */
    List<PassiveGiftHolderContext> loadPassiveGiftHolderContexts(Long matchId, Long userId) {
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
     * 載入場上所有可能提供「相手能力不能改變 HP」的 holder。
     *
     * <p>目前官方已知案例落在 `CENTER / COLLAB`，並交由文案 matcher 做最終站位判斷。
     */
    private List<PassiveGiftHolderContext> loadPassiveGiftHpChangePreventionHolderContexts(Long matchId, Long userId) {
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
     * 以單一 holder 的常駐文案判斷是否給自己 HP 加成。
     *
     * <p>目前先保守支援 `HSD13-007` 這類「這張 Holomem 每有 1 張 Cheer 就 HP+N」。
     * 這裡故意不把所有 `HP+N` 文案都吃掉，而是要求：
     *
     * <p>- 文案明確寫 `このホロメン`
     * <p>- 文案明確依 `エール1枚につき`
     * <p>- 加成目標必須就是 holder 自己
     *
     * <p>如此可以避免把其他條件更複雜、尚未建模完成的 HP 文案誤判成已支援。
     */
    int resolvePassiveGiftHpBonusFromHolder(
        PassiveGiftHolderContext holderContext,
        PassiveGiftHpTargetContext targetContext
    ) {
        String rawText = extractPassiveGiftRawText(holderContext.passiveEffectJsonText());
        if (!StringUtils.hasText(rawText)) {
            return 0;
        }
        if (!rawText.contains("このホロメン") || !rawText.contains("エール1枚につき")) {
            return 0;
        }
        if (!Objects.equals(holderContext.holomemId(), targetContext.holomemId())) {
            return 0;
        }

        Matcher matcher = PASSIVE_GIFT_HP_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return 0;
        }
        int hpBonusPerCheer = parseSignedNumber(matcher.group(1));
        if (hpBonusPerCheer == 0 || targetContext.attachedCheerCount() <= 0) {
            return 0;
        }
        return hpBonusPerCheer * targetContext.attachedCheerCount();
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
     * 以單一 holder 的常駐文案判斷是否給指定受擊目標減傷。
     *
     * <p>這裡刻意只接受少數已驗證的固定寫法：
     *
     * <p>- `このホロメンが受けるダメージ-10`
     * <p>- `このホロメンが相手の1stホロメンから受けるダメージ-20`
     * <p>- `自分のコラボホロメンが受けるダメージ-10`
     * <p>- `このホロメンと自分のコラボホロメンが受けるダメージ-10`
     *
     * <p>原因是常駐防禦文案之後很可能還會出現：
     *
     * <p>- 附著支援限定
     * <p>- 指定名稱 / tag / 顏色
     * <p>- 對手回合限定
     *
     * <p>若現在直接把所有 `受けるダメージ-N` 都視為同一種 aura，很容易在沒有完整規則模型時誤支援。
     * 因此這裡維持「先辨識受保護對象，再抓減傷數值」的保守順序。
     */
    int resolvePassiveGiftIncomingDamageReductionFromHolder(
        Long matchId,
        Long userId,
        PassiveGiftHolderContext holderContext,
        PassiveGiftIncomingDamageReductionTargetContext targetContext
    ) {
        String rawText = extractPassiveGiftRawText(holderContext.passiveEffectJsonText());
        if (!StringUtils.hasText(rawText) || targetContext == null) {
            return 0;
        }
        if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(rawText, holderContext.stageZone())) {
            return 0;
        }
        if (!matchesPassiveGiftRequiredOshiName(rawText, targetContext.oshiCardName())) {
            return 0;
        }
        if (!matchesIncomingDamageSourceLevelRestriction(rawText, targetContext.incomingSourceLevelType())) {
            return 0;
        }
        Integer diceConditionalReduction = resolveDiceConditionalPassiveGiftIncomingDamageReduction(
            matchId,
            userId,
            holderContext,
            targetContext,
            rawText
        );
        if (diceConditionalReduction != null) {
            return diceConditionalReduction;
        }
        boolean protectsSelfAndOwnCollab = rawText.contains("このホロメンと自分のコラボホロメンが受けるダメージ");
        boolean mentionsSelfDamageClause = PASSIVE_GIFT_SELF_DAMAGE_CLAUSE_PATTERN.matcher(rawText).find();
        boolean mentionsOwnCollabDamageClause = PASSIVE_GIFT_OWN_COLLAB_DAMAGE_CLAUSE_PATTERN.matcher(rawText).find();
        boolean protectsSelf = (mentionsSelfDamageClause || protectsSelfAndOwnCollab)
            && Objects.equals(holderContext.holomemId(), targetContext.holomemId());
        boolean protectsOwnCollab = (mentionsOwnCollabDamageClause || protectsSelfAndOwnCollab)
            && "COLLAB".equals(targetContext.stageZone());
        if (!protectsSelf && !protectsOwnCollab) {
            if (!matchesPassiveGiftTargetZoneRestriction(rawText, targetContext.stageZone())) {
                return 0;
            }
            String targetClause = extractPassiveGiftIncomingDamageReductionTargetClause(rawText);
            if (!matchesPassiveGiftTargetAttachedSupportCondition(targetClause, targetContext.holomemId())) {
                return 0;
            }
            SearchCriteria criteria = resolveMemberCriteriaFromRawText(
                stripPassiveGiftTargetAttachedSupportCondition(targetClause)
            );
            if (StringUtils.hasText(criteria.levelType()) && !criteria.levelType().equals(targetContext.levelType())) {
                return 0;
            }
            if (StringUtils.hasText(criteria.tag()) && !targetContext.tags().contains(criteria.tag())) {
                return 0;
            }
            if (StringUtils.hasText(criteria.nameContains())
                && !nullToEmpty(targetContext.cardName()).contains(criteria.nameContains())) {
                return 0;
            }
        }
        Matcher matcher = PASSIVE_GIFT_DAMAGE_REDUCTION_VALUE_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return 0;
        }
        return Math.max(parseSignedNumber("-" + matcher.group(1)) * -1, 0);
    }

    private Integer resolveDiceConditionalPassiveGiftIncomingDamageReduction(
        Long matchId,
        Long userId,
        PassiveGiftHolderContext holderContext,
        PassiveGiftIncomingDamageReductionTargetContext targetContext,
        String rawText
    ) {
        if (!StringUtils.hasText(rawText)
            || !rawText.contains("サイコロを1回振れる")
            || !rawText.contains("奇数なら")
            || !rawText.contains("偶数なら")) {
            return null;
        }
        if (!rawText.contains("このホロメン以外の自分のホロメンが相手からダメージを受ける時")) {
            return null;
        }
        if (Objects.equals(holderContext.holomemId(), targetContext.holomemId())) {
            return 0;
        }
        int turnNumber = loadCurrentTurnNumber(matchId);
        if (rawText.contains("ターンに1回")
            && isGiftAlreadyUsedThisTurn(matchId, userId, turnNumber, holderContext.holomemId())) {
            return 0;
        }
        int oddReduction = effectTextParser.extractByPattern(rawText, PASSIVE_GIFT_DICE_ODD_DAMAGE_REDUCTION_PATTERN);
        int evenReduction = effectTextParser.extractByPattern(rawText, PASSIVE_GIFT_DICE_EVEN_DAMAGE_REDUCTION_PATTERN);
        if (oddReduction <= 0 && evenReduction <= 0) {
            return 0;
        }
        int diceRoll = diceService.rollD6();
        int reduction = (diceRoll % 2 == 1) ? oddReduction : evenReduction;
        recordPassiveGiftTurnUsage(matchId, userId, turnNumber, holderContext.holomemId(), rawText, diceRoll);
        return Math.max(reduction, 0);
    }

    private int loadCurrentTurnNumber(Long matchId) {
        if (matchId == null) {
            return 0;
        }
        Integer turn = jdbcTemplate.query(
            "SELECT turn_number FROM matches WHERE id = ?",
            rs -> rs.next() ? rs.getInt("turn_number") : 0,
            matchId
        );
        return turn == null ? 0 : turn;
    }

    private void recordPassiveGiftTurnUsage(
        Long matchId,
        Long userId,
        int turnNumber,
        Long holderHolomemId,
        String rawText,
        int diceRoll
    ) {
        passiveGiftTriggerActionWriter.appendIncomingDamageReductionTrigger(
            matchId,
            userId,
            turnNumber,
            holderHolomemId,
            rawText,
            diceRoll
        );
    }

    /**
     * 判斷單一 holder 是否提供「相手能力不能改變 HP」保護。
     */
    private boolean blocksOpponentAbilityHpChangeFromHolder(
        PassiveGiftHolderContext holderContext,
        PassiveGiftHpChangePreventionTargetContext targetContext
    ) {
        String rawText = extractPassiveGiftRawText(holderContext.passiveEffectJsonText());
        if (!StringUtils.hasText(rawText) || targetContext == null) {
            return false;
        }
        if (!rawText.contains("相手のメインステップ")
            || !rawText.contains("HP")
            || !rawText.contains("相手の能力")
            || !rawText.contains("減らず")
            || !rawText.contains("変動しない")) {
            return false;
        }
        if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(rawText, holderContext.stageZone())) {
            return false;
        }
        if (rawText.contains("このホロメンのHP")) {
            return Objects.equals(holderContext.holomemId(), targetContext.holomemId());
        }

        if (!matchesPassiveGiftTargetZoneRestriction(rawText, targetContext.stageZone())) {
            return false;
        }
        SearchCriteria criteria = resolveMemberCriteriaFromRawText(rawText);
        if (StringUtils.hasText(criteria.levelType()) && !criteria.levelType().equals(targetContext.levelType())) {
            return false;
        }
        if (StringUtils.hasText(criteria.tag()) && !targetContext.tags().contains(criteria.tag())) {
            return false;
        }
        if (StringUtils.hasText(criteria.nameContains())) {
            String cardName = targetContext.cardName() == null ? "" : targetContext.cardName();
            if (!cardName.contains(criteria.nameContains())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判斷目標是否受到「相手能力不能改變 HP」靜態 Gift 保護。
     */
    private boolean isHpChangeBlockedByOpponentAbility(
        Long matchId,
        Long sourceUserId,
        Long targetOwnerUserId,
        Long targetHolomemId,
        String effectType
    ) {
        if (matchId == null
            || sourceUserId == null
            || targetOwnerUserId == null
            || targetHolomemId == null
            || Objects.equals(sourceUserId, targetOwnerUserId)
            || "ART_DAMAGE".equals(normalize(effectType))) {
            return false;
        }
        MatchTurnContext turnContext = loadMatchTurnContext(matchId);
        if (turnContext == null
            || !"MAIN".equals(turnContext.phase())
            || !Objects.equals(turnContext.currentTurnPlayerId(), sourceUserId)) {
            return false;
        }
        PassiveGiftHpChangePreventionTargetContext targetContext =
            loadPassiveGiftHpChangePreventionTargetContext(matchId, targetOwnerUserId, targetHolomemId);
        if (targetContext == null) {
            return false;
        }
        for (PassiveGiftHolderContext holderContext : loadPassiveGiftHpChangePreventionHolderContexts(matchId, targetOwnerUserId)) {
            if (blocksOpponentAbilityHpChangeFromHolder(holderContext, targetContext)) {
                return true;
            }
        }
        return false;
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

    /**
     * 由附加支援文案擷取「受擊時自動套用」的常駐減傷。
     */
    int extractAttachedSupportIncomingDamageReduction(String effectJsonText, String targetStageZone) {
        if (!StringUtils.hasText(effectJsonText)) {
            return 0;
        }
        String rawText = extractAttachedSupportRawText(effectJsonText);
        if (!StringUtils.hasText(rawText)) {
            return 0;
        }
        int conditionalIndex = rawText.indexOf('◆');
        String baseSegment = conditionalIndex >= 0 ? rawText.substring(0, conditionalIndex) : rawText;
        String normalizedTargetStageZone = normalize(targetStageZone);
        int total = 0;
        for (String clause : baseSegment.split("[。\\n]")) {
            if (!StringUtils.hasText(clause)
                || !isAttachedSupportHolderClause(clause)
                || !clause.contains("受けるダメージ")
                || clause.contains("できる")
                || clause.contains("：")
                || !matchesAttachedSupportDamageReductionTargetZone(clause, normalizedTargetStageZone)) {
                continue;
            }
            Matcher matcher = ATTACHED_SUPPORT_DAMAGE_REDUCTION_PATTERN.matcher(clause);
            while (matcher.find()) {
                total += Integer.parseInt(matcher.group(1));
            }
        }
        return total;
    }

    private boolean isAttachedSupportHolderClause(String rawText) {
        return rawText.contains("このマスコットが付いているホロメン")
            || rawText.contains("このツールが付いているホロメン")
            || rawText.contains("このファンが付いているホロメン");
    }

    private boolean matchesAttachedSupportDamageReductionTargetZone(String rawText, String targetStageZone) {
        boolean mentionsCenter = rawText.contains("センターポジション");
        boolean mentionsCollab = rawText.contains("コラボポジション");
        boolean mentionsBack = rawText.contains("バックポジション");
        if (!mentionsCenter && !mentionsCollab && !mentionsBack) {
            return true;
        }
        return (mentionsCenter && "CENTER".equals(targetStageZone))
            || (mentionsCollab && "COLLAB".equals(targetStageZone))
            || (mentionsBack && "BACK".equals(targetStageZone));
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
     * 解析回復值，支援 heal 欄位與 HP 文案。
     */
    private int resolveHealValue(JsonNode effectNode) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "value", "amount", "heal");
        if (fromFields > 0) {
            return fromFields;
        }
        return effectTextParser.extractByPattern(effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect")), HEAL_PATTERN);
    }

    /**
     * 解析移動目的區（CENTER/COLLAB/BACK），預設 BACK。
     */
    private String resolveMoveDestinationZone(JsonNode effectNode) {
        String explicit = effectTextParser.normalizeEffectType(effectTextParser.extractText(effectNode, "toZone", "targetZone"));
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        String text = effectTextParser.extractText(effectNode, "rawText", "rawEffect");
        if (text.contains("コラボ")) {
            return "COLLAB";
        }
        if (text.contains("センター")) {
            return "CENTER";
        }
        if (text.contains("バック")) {
            return "BACK";
        }
        return "BACK";
    }

    /**
     * 判斷移動後是否需改為休息狀態。
     */
    private boolean shouldRestAfterMove(JsonNode effectNode) {
        String text = effectTextParser.extractText(effectNode, "rawText", "rawEffect");
        return text.contains("お休み");
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
     * 解析傷害數值，支援 value/amount 欄位與「ダメージ」文案。
     */
    private int resolveDamageValue(JsonNode effectNode) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "value", "amount", "damage");
        if (fromFields > 0) {
            return fromFields;
        }
        String merged = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        int special = effectTextParser.extractByPattern(merged, SPECIAL_DAMAGE_PATTERN);
        if (special > 0) {
            return special;
        }
        int normal = effectTextParser.extractByPattern(merged, DAMAGE_PATTERN);
        if (normal > 0) {
            return normal;
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
     * 勝負效果決策模型（勝負方、reason 與敘述）。
     */
    private record MatchResultDecision(
        boolean draw,
        Long winnerUserId,
        Long loserUserId,
        String reason
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
