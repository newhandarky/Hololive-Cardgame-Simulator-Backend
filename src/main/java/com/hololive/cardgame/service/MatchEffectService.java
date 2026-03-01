package com.hololive.cardgame.service;

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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern SEARCH_RANGE_PATTERN = Pattern.compile("(\\d+)\\s*[~〜～]\\s*(\\d+)\\s*枚");
    private static final Pattern SEARCH_COUNT_PATTERN = Pattern.compile("(\\d+)\\s*枚");
    private static final Pattern TAG_PATTERN = Pattern.compile(
        "#([\\p{L}\\p{N}_'\\-]+?)(?=(?:を|が|に|で|と|へ|や|も|、|。|\\s|$))"
    );
    private static final Pattern NAME_TOKEN_PATTERN = Pattern.compile("〈([^〉]+)〉");
    private static final Pattern ARTS_MODIFIER_PATTERN = Pattern.compile("アーツ\\s*([+＋\\-−]\\s*\\d+)");
    private static final Pattern DICE_AT_LEAST_PATTERN = Pattern.compile("(\\d+)\\s*以上の時");
    private static final Pattern DICE_AT_MOST_PATTERN = Pattern.compile("(\\d+)\\s*以下の時");
    private static final Pattern DICE_ROLL_COUNT_PATTERN = Pattern.compile("サイコロ\\D*(\\d+)\\s*回");
    private static final Pattern SEARCH_LOOK_TOP_COUNT_PATTERN = Pattern.compile("デッキの上から\\s*(\\d+)\\s*枚を見る");
    private static final Pattern ATTACHED_SUPPORT_HP_PATTERN = Pattern.compile(
        "この(?:マスコット|ツール|ファン)が付いているホロメンのHP\\s*([+＋−-]\\s*\\d+)"
    );
    private static final Pattern ATTACHED_SUPPORT_ARTS_PATTERN = Pattern.compile(
        "この(?:マスコット|ツール|ファン)が付いているホロメンのアーツ\\s*([+＋−-]\\s*\\d+)"
    );
    private static final Pattern BATON_TOUCH_COST_MODIFIER_PATTERN = Pattern.compile("バトンタッチに必要な無色\\s*[+＋]\\s*(\\d+)");
    private static final Pattern DOWN_EXTRA_LIFE_PATTERN = Pattern.compile("ライフを\\s*(\\d+)\\s*つ?減ら");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DiceService diceService;
    private final EffectResolver effectResolver;
    private final GameActionExecutor gameActionExecutor;

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
    }

    public Map<String, Object> applySupportEffect(
        Long matchId,
        Long userId,
        String effectType,
        String effectJson,
        String targetType,
        List<Long> selectedCardInstanceIds,
        Long targetHolomemCardInstanceId
    ) {
        JsonNode effectNode = parseEffectJson(effectJson);
        List<String> effectTypes = resolveEffectTypes(effectType, effectNode);
        List<Map<String, Object>> executed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<Map<String, Object>> skippedEffects = new ArrayList<>();

        for (String type : effectTypes) {
            try {
                switch (type) {
                    case "DRAW" -> executed.add(executeDrawEffect(matchId, userId, type, effectNode));
                    case "SEARCH" -> executed.add(
                        executeSearchEffect(matchId, userId, type, effectNode, selectedCardInstanceIds)
                    );
                    case "RETURN_TO_HAND" -> executed.add(
                        executeReturnToHandEffect(matchId, userId, type, effectNode, selectedCardInstanceIds)
                    );
                    case "RETURN_TO_DECK_TOP" -> executed.add(
                        executeReturnToDeckTopEffect(matchId, userId, type, effectNode, selectedCardInstanceIds)
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
                            effectNode,
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

    public SupportDecisionPlan buildSupportDecisionPlan(
        Long matchId,
        Long userId,
        String effectType,
        String effectJson
    ) {
        JsonNode effectNode = parseEffectJson(effectJson);
        List<String> effectTypes = resolveEffectTypes(effectType, effectNode);
        for (String type : effectTypes) {
            SelectionProbe probe = probeSelectionCandidates(matchId, userId, type, effectNode);
            if (probe == null || probe.candidates().isEmpty()) {
                continue;
            }
            int maxSelect = Math.min(probe.requestedCount(), probe.candidates().size());
            if (maxSelect <= 0 || probe.candidates().size() <= maxSelect) {
                continue;
            }
            return new SupportDecisionPlan(
                normalizeEffectType(type),
                1,
                maxSelect,
                probe.candidates()
            );
        }
        return null;
    }

    public Map<String, Object> applyArtDamage(
        Long matchId,
        Long userId,
        int baseDamage,
        Long targetHolomemCardInstanceId
    ) {
        if (baseDamage <= 0) {
            throw new IllegalArgumentException("藝能傷害必須大於 0");
        }
        JsonNode effectNode = objectMapper.valueToTree(Map.of("type", "DAMAGE", "value", baseDamage));
        return executeDamageEffect(
            matchId,
            userId,
            "ART_DAMAGE",
            effectNode,
            "ENEMY",
            targetHolomemCardInstanceId
        );
    }

    public List<Map<String, Object>> applyGiftTriggeredEffectsOnArt(
        Long matchId,
        Long userId,
        Long attackerCardInstanceId,
        Long attackTargetCardInstanceId,
        int turnNumber
    ) {
        if (matchId == null || userId == null || attackerCardInstanceId == null || turnNumber <= 0) {
            return List.of();
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
            userId
        );
        if (holders.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> triggered = new ArrayList<>();
        for (Map<String, Object> holder : holders) {
            Long holderHolomemId = asLong(holder.get("holomem_id"));
            Long holderCardInstanceId = asLong(holder.get("match_card_id"));
            String holderZone = normalize(asText(holder.get("zone")));
            String holderLevel = normalizeLevelType(asText(holder.get("current_level")));
            String giftText = loadGiftEffectText(asText(holder.get("passive_text")));
            if (!StringUtils.hasText(giftText)) {
                continue;
            }
            if (!giftText.contains("アーツ") || !giftText.contains("使った時")) {
                continue;
            }
            if (giftText.contains("このホロメンが") && !attackerCardInstanceId.equals(holderCardInstanceId)) {
                continue;
            }
            if (giftText.contains("センターポジション限定") && !"CENTER".equals(holderZone)) {
                continue;
            }
            if (giftText.contains("コラボポジション限定") && !"COLLAB".equals(holderZone)) {
                continue;
            }
            if (giftText.contains("1stホロメンからBloomしているこのホロメン")) {
                if (!Set.of("SECOND", "BUZZ").contains(holderLevel)) {
                    continue;
                }
            }
            if (giftText.contains("ターンに1回") && isGiftAlreadyUsedThisTurn(matchId, userId, turnNumber, holderHolomemId)) {
                continue;
            }

            JsonNode giftNode = objectMapper.valueToTree(Map.of("rawText", giftText));
            List<String> effectTypes = inferBloomEffectTypes(giftText);
            List<Map<String, Object>> executed = new ArrayList<>();
            List<String> unsupported = new ArrayList<>();
            List<Map<String, Object>> skippedEffects = new ArrayList<>();
            for (String effectType : effectTypes) {
                String targetType = inferBloomTargetType(effectType);
                try {
                    switch (effectType) {
                        case "DRAW" -> executed.add(executeDrawEffect(matchId, userId, effectType, giftNode));
                        case "SEARCH" -> executed.add(executeSearchEffect(matchId, userId, effectType, giftNode, null));
                        case "RETURN_TO_HAND" -> executed.add(
                            executeReturnToHandEffect(matchId, userId, effectType, giftNode, null)
                        );
                        case "RETURN_TO_DECK_TOP" -> executed.add(
                            executeReturnToDeckTopEffect(matchId, userId, effectType, giftNode, null)
                        );
                        case "ADD_CHEER" -> executed.add(
                            executeAddCheerEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId)
                        );
                        case "DAMAGE" -> executed.add(
                            executeDamageEffect(
                                matchId,
                                userId,
                                effectType,
                                giftNode,
                                targetType,
                                attackTargetCardInstanceId
                            )
                        );
                        case "REATTACH" -> executed.add(
                            executeReattachEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId)
                        );
                        case "SUMMON_TO_STAGE" -> executed.add(executeSummonToStageEffect(matchId, userId, effectType, giftNode));
                        case "REVEAL_TO_ARCHIVE" -> executed.add(
                            executeRevealToArchiveEffect(matchId, userId, effectType, giftNode)
                        );
                        case "BLOOM_FROM_ARCHIVE" -> executed.add(
                            executeBloomFromArchiveEffect(matchId, userId, effectType, giftNode)
                        );
                        case "RETURN_CHEER_TO_DECK_BOTTOM" -> executed.add(
                            executeReturnCheerToDeckBottomEffect(matchId, userId, effectType, giftNode)
                        );
                        case "DISCARD_HAND" -> executed.add(executeDiscardHandEffect(matchId, userId, effectType, giftNode));
                        case "REST" -> executed.add(
                            executeRestEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId)
                        );
                        case "SWAP_CENTER_BACK" -> executed.add(
                            executeSwapCenterBackEffect(matchId, userId, effectType, giftNode)
                        );
                        case "MOVE_TO_HOLOPOWER" -> executed.add(
                            executeMoveToHolopowerEffect(matchId, userId, effectType, giftNode)
                        );
                        case "DOWN_NO_LIFE" -> executed.add(
                            executeDownNoLifeEffect(matchId, userId, effectType, giftNode)
                        );
                        case "DOWN_EXTRA_LIFE" -> executed.add(
                            executeDownExtraLifeEffect(matchId, userId, effectType, giftNode)
                        );
                        case "BATON_TOUCH_COST_MODIFIER" -> executed.add(
                            executeBatonTouchCostModifierEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId)
                        );
                        case "ACTION_LOCK" -> executed.add(
                            executeActionLockEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId)
                        );
                        case "ALLOW_EXTRA_BLOOM" -> executed.add(
                            executeAllowExtraBloomEffect(matchId, userId, effectType, giftNode)
                        );
                        case "LOOK_TOP_DECK" -> executed.add(
                            executeLookTopDeckEffect(matchId, userId, effectType, giftNode)
                        );
                        case "LOOK_OPPONENT_HAND" -> executed.add(
                            executeLookOpponentHandEffect(matchId, userId, effectType, giftNode)
                        );
                        case "LOOK_HOLOPOWER" -> executed.add(
                            executeLookHolopowerEffect(matchId, userId, effectType, giftNode)
                        );
                        case "SWAP_WITH_COLLAB" -> executed.add(
                            executeSwapWithCollabEffect(matchId, userId, effectType, giftNode, holderCardInstanceId)
                        );
                        case "HEAL" -> executed.add(
                            executeHealEffect(matchId, userId, effectType, giftNode, targetType, holderCardInstanceId)
                        );
                        case "BUFF", "DEBUFF" -> executed.add(
                            executeBuffDebuffEffect(matchId, userId, effectType, giftNode, targetType)
                        );
                        case "MATCH_RESULT", "WIN", "LOSE" -> executed.add(
                            executeMatchResultEffect(matchId, userId, effectType, giftNode)
                        );
                        case "UNIMPLEMENTED" -> executed.add(
                            executeNoOpEffect(effectType, giftNode, "尚未支援的 GIFT 效果")
                        );
                        default -> {
                            unsupported.add(effectType);
                            Map<String, Object> skipped = buildSkippedEffect(effectType, "UNSUPPORTED_EFFECT");
                            executed.add(skipped);
                            skippedEffects.add(skipped);
                        }
                    }
                } catch (RuntimeException ex) {
                    Map<String, Object> skipped = buildSkippedEffect(effectType, ex.getMessage());
                    executed.add(skipped);
                    skippedEffects.add(skipped);
                }
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("triggerType", "ART_USED");
            summary.put("giftHolderHolomemId", holderHolomemId);
            summary.put("giftHolderCardInstanceId", holderCardInstanceId);
            summary.put("giftHolderCardId", asText(holder.get("card_id")));
            summary.put("giftHolderZone", holderZone);
            summary.put("rawText", giftText);
            summary.put("requestedEffects", effectTypes);
            summary.put("executedEffects", executed);
            summary.put("unsupportedEffects", unsupported);
            summary.put("skippedEffects", skippedEffects);
            summary.put("partiallyResolved", !skippedEffects.isEmpty() || !unsupported.isEmpty());
            triggered.add(summary);
        }
        return triggered;
    }

    public int resolveAttachedSupportHpBonus(Long matchId, Long matchHolomemId) {
        return resolveAttachedSupportStatBonus(matchId, matchHolomemId, ATTACHED_SUPPORT_HP_PATTERN);
    }

    public int resolveAttachedSupportArtBonus(Long matchId, Long matchHolomemId) {
        return resolveAttachedSupportStatBonus(matchId, matchHolomemId, ATTACHED_SUPPORT_ARTS_PATTERN);
    }

    public Map<String, Object> applyBloomTriggeredEffects(
        Long matchId,
        Long userId,
        String bloomCardId,
        Long selfHolomemCardInstanceId
    ) {
        BloomEffectPlan bloomPlan = resolveBloomEffectPlan(bloomCardId);
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
        List<Map<String, Object>> executed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<Map<String, Object>> skippedEffects = new ArrayList<>();
        Integer diceRoll = bloomPlan.diceRoll();
        JsonNode bloomEffectNode = bloomPlan.effectNode();

        for (String effectType : effectTypes) {
            String targetType = inferBloomTargetType(effectType);
            try {
                switch (effectType) {
                    case "DRAW" -> executed.add(executeDrawEffect(matchId, userId, effectType, bloomEffectNode));
                    case "SEARCH" -> executed.add(
                        executeSearchEffect(matchId, userId, effectType, bloomEffectNode, null)
                    );
                    case "RETURN_TO_HAND" -> executed.add(
                        executeReturnToHandEffect(matchId, userId, effectType, bloomEffectNode, null)
                    );
                    case "RETURN_TO_DECK_TOP" -> executed.add(
                        executeReturnToDeckTopEffect(matchId, userId, effectType, bloomEffectNode, null)
                    );
                    case "ADD_CHEER" -> executed.add(
                        executeAddCheerEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "DAMAGE" -> executed.add(
                        executeDamageEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            null
                        )
                    );
                    case "REATTACH" -> executed.add(
                        executeReattachEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "SUMMON_TO_STAGE" -> executed.add(
                        executeSummonToStageEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "REVEAL_TO_ARCHIVE" -> executed.add(
                        executeRevealToArchiveEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "BLOOM_FROM_ARCHIVE" -> executed.add(
                        executeBloomFromArchiveEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "RETURN_CHEER_TO_DECK_BOTTOM" -> executed.add(
                        executeReturnCheerToDeckBottomEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "DISCARD_HAND" -> executed.add(
                        executeDiscardHandEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "REST" -> executed.add(
                        executeRestEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "SWAP_CENTER_BACK" -> executed.add(
                        executeSwapCenterBackEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "MOVE_TO_HOLOPOWER" -> executed.add(
                        executeMoveToHolopowerEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "DOWN_NO_LIFE" -> executed.add(
                        executeDownNoLifeEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "DOWN_EXTRA_LIFE" -> executed.add(
                        executeDownExtraLifeEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "BATON_TOUCH_COST_MODIFIER" -> executed.add(
                        executeBatonTouchCostModifierEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "ACTION_LOCK" -> executed.add(
                        executeActionLockEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "ALLOW_EXTRA_BLOOM" -> executed.add(
                        executeAllowExtraBloomEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "LOOK_TOP_DECK" -> executed.add(
                        executeLookTopDeckEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "LOOK_OPPONENT_HAND" -> executed.add(
                        executeLookOpponentHandEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "LOOK_HOLOPOWER" -> executed.add(
                        executeLookHolopowerEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "MOVE_ZONE" -> executed.add(
                        executeMoveZoneEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            null
                        )
                    );
                    case "SWAP_WITH_COLLAB" -> executed.add(
                        executeSwapWithCollabEffect(matchId, userId, effectType, bloomEffectNode, selfHolomemCardInstanceId)
                    );
                    case "HEAL" -> executed.add(
                        executeHealEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "BUFF", "DEBUFF" -> executed.add(
                        executeBuffDebuffEffect(
                            matchId,
                            userId,
                            effectType,
                            bloomEffectNode,
                            targetType
                        )
                    );
                    case "MATCH_RESULT", "WIN", "LOSE" -> executed.add(
                        executeMatchResultEffect(matchId, userId, effectType, bloomEffectNode)
                    );
                    case "UNIMPLEMENTED" -> executed.add(
                        executeNoOpEffect(effectType, bloomEffectNode, "尚未支援的 BLOOM 效果")
                    );
                    default -> {
                        unsupported.add(effectType);
                        Map<String, Object> skipped = buildSkippedEffect(effectType, "UNSUPPORTED_EFFECT");
                        executed.add(skipped);
                        skippedEffects.add(skipped);
                    }
                }
            } catch (RuntimeException ex) {
                Map<String, Object> skipped = buildSkippedEffect(effectType, ex.getMessage());
                executed.add(skipped);
                skippedEffects.add(skipped);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hasBloomEffect", true);
        summary.put("requestedEffects", effectTypes);
        summary.put("executedEffects", executed);
        summary.put("unsupportedEffects", unsupported);
        summary.put("skippedEffects", skippedEffects);
        summary.put("partiallyResolved", !skippedEffects.isEmpty() || !unsupported.isEmpty());
        summary.put("rawText", bloomPlan.rawText());
        if (diceRoll != null) {
            summary.put("diceRoll", diceRoll);
        }
        return summary;
    }

    public Map<String, Object> applyCollabTriggeredEffects(
        Long matchId,
        Long userId,
        String collabCardId,
        Long selfHolomemCardInstanceId
    ) {
        BloomEffectPlan collabPlan = resolveCollabEffectPlan(collabCardId);
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
        JsonNode collabEffectNode = collabPlan.effectNode();

        for (String effectType : effectTypes) {
            String targetType = inferBloomTargetType(effectType);
            try {
                switch (effectType) {
                    case "DRAW" -> executed.add(executeDrawEffect(matchId, userId, effectType, collabEffectNode));
                    case "SEARCH" -> executed.add(
                        executeSearchEffect(matchId, userId, effectType, collabEffectNode, null)
                    );
                    case "RETURN_TO_HAND" -> executed.add(
                        executeReturnToHandEffect(matchId, userId, effectType, collabEffectNode, null)
                    );
                    case "RETURN_TO_DECK_TOP" -> executed.add(
                        executeReturnToDeckTopEffect(matchId, userId, effectType, collabEffectNode, null)
                    );
                    case "ADD_CHEER" -> executed.add(
                        executeAddCheerEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "DAMAGE" -> executed.add(
                        executeDamageEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            null
                        )
                    );
                    case "REATTACH" -> executed.add(
                        executeReattachEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "SUMMON_TO_STAGE" -> executed.add(
                        executeSummonToStageEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "REVEAL_TO_ARCHIVE" -> executed.add(
                        executeRevealToArchiveEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "BLOOM_FROM_ARCHIVE" -> executed.add(
                        executeBloomFromArchiveEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "RETURN_CHEER_TO_DECK_BOTTOM" -> executed.add(
                        executeReturnCheerToDeckBottomEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "DISCARD_HAND" -> executed.add(
                        executeDiscardHandEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "REST" -> executed.add(
                        executeRestEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "SWAP_CENTER_BACK" -> executed.add(
                        executeSwapCenterBackEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "MOVE_TO_HOLOPOWER" -> executed.add(
                        executeMoveToHolopowerEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "DOWN_NO_LIFE" -> executed.add(
                        executeDownNoLifeEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "DOWN_EXTRA_LIFE" -> executed.add(
                        executeDownExtraLifeEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "BATON_TOUCH_COST_MODIFIER" -> executed.add(
                        executeBatonTouchCostModifierEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "ACTION_LOCK" -> executed.add(
                        executeActionLockEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "ALLOW_EXTRA_BLOOM" -> executed.add(
                        executeAllowExtraBloomEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "LOOK_TOP_DECK" -> executed.add(
                        executeLookTopDeckEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "LOOK_OPPONENT_HAND" -> executed.add(
                        executeLookOpponentHandEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "LOOK_HOLOPOWER" -> executed.add(
                        executeLookHolopowerEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "MOVE_ZONE" -> executed.add(
                        executeMoveZoneEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            null
                        )
                    );
                    case "SWAP_WITH_COLLAB" -> executed.add(
                        executeSwapWithCollabEffect(matchId, userId, effectType, collabEffectNode, selfHolomemCardInstanceId)
                    );
                    case "HEAL" -> executed.add(
                        executeHealEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType,
                            selfHolomemCardInstanceId
                        )
                    );
                    case "BUFF", "DEBUFF" -> executed.add(
                        executeBuffDebuffEffect(
                            matchId,
                            userId,
                            effectType,
                            collabEffectNode,
                            targetType
                        )
                    );
                    case "MATCH_RESULT", "WIN", "LOSE" -> executed.add(
                        executeMatchResultEffect(matchId, userId, effectType, collabEffectNode)
                    );
                    case "UNIMPLEMENTED" -> executed.add(
                        executeNoOpEffect(effectType, collabEffectNode, "尚未支援的 COLLAB 效果")
                    );
                    default -> {
                        unsupported.add(effectType);
                        Map<String, Object> skipped = buildSkippedEffect(effectType, "UNSUPPORTED_EFFECT");
                        executed.add(skipped);
                        skippedEffects.add(skipped);
                    }
                }
            } catch (RuntimeException ex) {
                Map<String, Object> skipped = buildSkippedEffect(effectType, ex.getMessage());
                executed.add(skipped);
                skippedEffects.add(skipped);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hasCollabEffect", true);
        summary.put("requestedEffects", effectTypes);
        summary.put("executedEffects", executed);
        summary.put("unsupportedEffects", unsupported);
        summary.put("skippedEffects", skippedEffects);
        summary.put("partiallyResolved", !skippedEffects.isEmpty() || !unsupported.isEmpty());
        summary.put("rawText", collabPlan.rawText());
        if (diceRoll != null) {
            summary.put("diceRoll", diceRoll);
        }
        return summary;
    }

    private Map<String, Object> executeDrawEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
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

    private Map<String, Object> executeSearchEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        List<Long> selectedCardInstanceIds
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        int requestedCount = resolveSearchCount(effectNode);
        int searchCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        int lookTopCount = resolveSearchLookTopCount(effectNode, rawText);
        boolean requiresDeckBottomReorder = lookTopCount > 0 && rawText.contains("好きな順でデッキの下に戻す");

        List<Map<String, Object>> searchPool = lookTopCount > 0
            ? loadTopDeckWindow(matchId, userId, lookTopCount)
            : loadSearchCandidates(matchId, userId, criteria);
        List<Map<String, Object>> candidates = lookTopCount > 0
            ? filterCandidatesByCriteria(searchPool, criteria)
            : searchPool;

        List<Map<String, Object>> selected = selectSearchCards(candidates, selectedCardInstanceIds, searchCount);
        List<Long> movedCardInstanceIds = new ArrayList<>();
        List<String> movedCardIds = new ArrayList<>();
        int nextHandOrder = nextZoneOrder(matchId, userId, "HAND");
        for (Map<String, Object> row : selected) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
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
                  AND zone = 'DECK'
                """,
                nextHandOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                continue;
            }
            movedCardInstanceIds.add(cardInstanceId);
            movedCardIds.add(cardId);
        }

        List<Map<String, Object>> reorderCandidates = new ArrayList<>();
        if (requiresDeckBottomReorder) {
            Set<Long> selectedIds = new LinkedHashSet<>();
            for (Map<String, Object> row : selected) {
                Long id = asLong(row.get("id"));
                if (id != null && id > 0) {
                    selectedIds.add(id);
                }
            }
            for (Map<String, Object> row : searchPool) {
                Long id = asLong(row.get("id"));
                if (id == null || selectedIds.contains(id)) {
                    continue;
                }
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("cardInstanceId", id);
                candidate.put("cardId", asText(row.get("card_id")));
                candidate.put("name", asText(row.get("name")));
                candidate.put("cardType", normalize(asText(row.get("card_type"))));
                candidate.put("levelType", normalizeLevelType(asText(row.get("level_type"))));
                candidate.put("zone", "DECK");
                reorderCandidates.add(candidate);
            }
            if (reorderCandidates.size() == 1) {
                Long onlyCardInstanceId = asLong(reorderCandidates.get(0).get("cardInstanceId"));
                moveDeckCardToBottom(matchId, userId, onlyCardInstanceId);
                reorderCandidates.clear();
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("searchRequested", searchCount);
        summary.put("candidateCount", candidates.size());
        summary.put("searchPoolCount", searchPool.size());
        summary.put("lookTopCount", lookTopCount);
        summary.put("searchApplied", movedCardInstanceIds.size());
        summary.put("selectedByClient", selectedCardInstanceIds != null && !selectedCardInstanceIds.isEmpty());
        summary.put("searchedCardInstanceIds", movedCardInstanceIds);
        summary.put("searchedCardIds", movedCardIds);
        summary.put("requiresDeckBottomReorder", !reorderCandidates.isEmpty());
        summary.put(
            "deckBottomReorderCandidateCardInstanceIds",
            reorderCandidates.stream()
                .map(row -> asLong(row.get("cardInstanceId")))
                .filter(id -> id != null && id > 0)
                .toList()
        );
        summary.put("deckBottomReorderCandidates", reorderCandidates);
        summary.put("criteria", buildCriteriaSummary(criteria));
        return summary;
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

    private Map<String, Object> executeReturnToHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        List<Long> selectedCardInstanceIds
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        int requestedCount = resolveActionCount(effectNode, "手札に戻", 1);
        int returnCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        boolean excludeLimitedSupport = rawText.contains("LIMITED以外");

        List<Map<String, Object>> candidates = loadCandidatesFromZone(
            matchId,
            userId,
            "ARCHIVE",
            criteria,
            excludeLimitedSupport
        );
        List<Map<String, Object>> selected = selectSearchCards(candidates, selectedCardInstanceIds, returnCount);

        List<Long> movedCardInstanceIds = new ArrayList<>();
        List<String> movedCardIds = new ArrayList<>();
        int nextHandOrder = nextZoneOrder(matchId, userId, "HAND");
        for (Map<String, Object> row : selected) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
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
            movedCardInstanceIds.add(cardInstanceId);
            movedCardIds.add(cardId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("returnRequested", returnCount);
        summary.put("candidateCount", candidates.size());
        summary.put("returnApplied", movedCardInstanceIds.size());
        summary.put("selectedByClient", selectedCardInstanceIds != null && !selectedCardInstanceIds.isEmpty());
        summary.put("returnedCardInstanceIds", movedCardInstanceIds);
        summary.put("returnedCardIds", movedCardIds);
        Map<String, Object> criteriaSummary = buildCriteriaSummary(criteria);
        criteriaSummary.put("excludeLimitedSupport", excludeLimitedSupport);
        summary.put("criteria", criteriaSummary);
        return summary;
    }

    private Map<String, Object> executeReturnToDeckTopEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        List<Long> selectedCardInstanceIds
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        int requestedCount = resolveActionCount(effectNode, "デッキの上に戻", 1);
        int returnCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);

        List<Map<String, Object>> candidates = loadCandidatesFromZone(matchId, userId, "ARCHIVE", criteria, false);
        List<Map<String, Object>> selected = selectSearchCards(candidates, selectedCardInstanceIds, returnCount);

        List<Long> movedCardInstanceIds = new ArrayList<>();
        List<String> movedCardIds = new ArrayList<>();
        Integer topDeckOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MIN(order_index), 1) - 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'DECK'
            """,
            Integer.class,
            matchId,
            userId
        );
        int nextTopOrder = topDeckOrder == null ? 0 : topDeckOrder;
        for (Map<String, Object> row : selected) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            int updated = jdbcTemplate.update(
                """
                UPDATE match_cards
                SET zone = 'DECK',
                    order_index = ?,
                    is_face_down = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'ARCHIVE'
                """,
                nextTopOrder--,
                cardInstanceId,
                matchId,
                userId
            );
            if (updated != 1) {
                continue;
            }
            movedCardInstanceIds.add(cardInstanceId);
            movedCardIds.add(cardId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("returnRequested", returnCount);
        summary.put("candidateCount", candidates.size());
        summary.put("returnApplied", movedCardInstanceIds.size());
        summary.put("selectedByClient", selectedCardInstanceIds != null && !selectedCardInstanceIds.isEmpty());
        summary.put("returnedCardInstanceIds", movedCardInstanceIds);
        summary.put("returnedCardIds", movedCardIds);
        summary.put("criteria", buildCriteriaSummary(criteria));
        return summary;
    }

    private Map<String, Object> executeReattachEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        if (!rawText.contains("エール")) {
            return executeNoOpEffect(effectType, effectNode, "目前僅支援 Cheer 的付け/付け替え");
        }

        boolean opponentContext = rawText.contains("相手の");
        Long sourceOwnerUserId = opponentContext ? resolveOpponentUserId(matchId, userId) : userId;
        if (sourceOwnerUserId == null) {
            return executeNoOpEffect(effectType, effectNode, "找不到可操作的玩家");
        }
        String effectiveTargetType = opponentContext ? "ENEMY" : targetType;

        Long targetHolomemId = resolveEffectTargetHolomemId(
            matchId,
            userId,
            effectiveTargetType,
            targetHolomemCardInstanceId,
            false
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

        int requestedCount = resolveActionCount(effectNode, "付け", 1);
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
                    INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    targetHolomemId,
                    cheerCardId
                );
                movedCheerCardIds.add(cheerCardId);
                if (cheerRowId != null) {
                    movedCheerRowIds.add(cheerRowId);
                }
            }
        } else {
            sourceMode = "STAGE";
            List<Map<String, Object>> attachedRows = jdbcTemplate.queryForList(
                """
                SELECT c.id AS cheer_row_id,
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
                    INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, FALSE)
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    targetHolomemId,
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
                        INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                        VALUES (?, ?, FALSE)
                        RETURNING id
                        """,
                        rs -> rs.next() ? rs.getLong("id") : null,
                        targetHolomemId,
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

    private Map<String, Object> executeSummonToStageEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int requestedCount = resolveActionCount(effectNode, "ステージに出", 1);
        int summonCount = Math.max(requestedCount, 1);
        SearchCriteria resolved = resolveSearchCriteria(effectNode);
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

    private Map<String, Object> executeRevealToArchiveEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int requestedCount = resolveActionCount(effectNode, "アーカイブ", 1);
        int archiveCount = Math.max(requestedCount, 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
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

    private Map<String, Object> executeBloomFromArchiveEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int currentTurn = resolveCurrentTurnNumber(matchId);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
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

    private Map<String, Object> executeReturnCheerToDeckBottomEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        String colorFilter = resolveCheerColorFilter(rawText);
        int requestedCount = resolveActionCount(effectNode, "エールデッキの下に戻", 1);
        int returnCount = Math.max(requestedCount, 1);

        List<Map<String, Object>> candidates = jdbcTemplate.query(
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

        List<Long> movedCardInstanceIds = new ArrayList<>();
        List<String> movedCardIds = new ArrayList<>();
        int nextCheerDeckOrder = nextZoneOrder(matchId, userId, "CHEER_DECK");
        for (Map<String, Object> row : candidates) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
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
                  AND zone = 'ARCHIVE'
                """,
                nextCheerDeckOrder++,
                cardInstanceId,
                matchId,
                userId
            );
            if (moved != 1) {
                continue;
            }
            movedCardInstanceIds.add(cardInstanceId);
            movedCardIds.add(cardId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("returnRequested", returnCount);
        summary.put("returnApplied", movedCardInstanceIds.size());
        summary.put("colorFilter", colorFilter);
        summary.put("returnedCardInstanceIds", movedCardInstanceIds);
        summary.put("returnedCardIds", movedCardIds);
        return summary;
    }

    private Map<String, Object> executeDiscardHandEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        int requestedCount = resolveActionCount(effectNode, "手札", 1);
        int discardCount = Math.max(requestedCount, 1);

        List<Map<String, Object>> handCards = jdbcTemplate.query(
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
        summary.put("discardedCardInstanceIds", discardedCardInstanceIds);
        summary.put("discardedCardIds", discardedCardIds);
        return summary;
    }

    private Map<String, Object> executeRestEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
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

    private Map<String, Object> executeSwapCenterBackEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
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

    private Map<String, Object> executeMoveToHolopowerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, effectType)) {
            return executeNoOpEffect(effectType, effectNode, "骰子條件未命中");
        }
        int requestedCount = resolveActionCount(effectNode, "ホロパワー", 1);
        int moveCount = Math.max(requestedCount, 1);

        List<Long> movedCardInstanceIds = new ArrayList<>();
        for (int i = 0; i < moveCount; i++) {
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
                  AND zone = 'DECK'
                """,
                nextOrder,
                deckCardInstanceId,
                matchId,
                userId
            );
            if (moved == 1) {
                movedCardInstanceIds.add(deckCardInstanceId);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("moveRequested", moveCount);
        summary.put("moveApplied", movedCardInstanceIds.size());
        summary.put("movedCardInstanceIds", movedCardInstanceIds);
        return summary;
    }

    private Map<String, Object> executeDownNoLifeEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
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

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", true);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", targetCardInstanceId);
        summary.put("targetOwnerUserId", opponentUserId);
        summary.put("downed", true);
        summary.put("lifeReduced", false);
        summary.put("archivedCheerCardInstanceIds", archivedCheerCardInstanceIds);
        summary.put("archivedSupportCardInstanceIds", archivedSupportCardInstanceIds);
        summary.put("archivedHolomemCardInstanceIds", archivedHolomemCardInstanceIds);
        return summary;
    }

    private Map<String, Object> executeDownExtraLifeEffect(
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

    private Map<String, Object> executeBatonTouchCostModifierEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        int modifier = extractByPattern(rawText, BATON_TOUCH_COST_MODIFIER_PATTERN);
        if (modifier <= 0) {
            modifier = extractInt(effectNode, 0, "modifier", "value", "amount");
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
            toJsonString(Map.of("targetHolomemId", targetHolomemId, "rawText", rawText))
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

    private Map<String, Object> executeAllowExtraBloomEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
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
        if (currentLife > 3) {
            return executeNoOpEffect(effectType, effectNode, "條件不成立：目前 Life 大於 3");
        }

        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        List<String> allowedNames = new ArrayList<>();
        if (rawText.contains("〈さくらみこ〉")) {
            allowedNames.add("さくらみこ");
        }
        if (rawText.contains("〈星街すいせい〉")) {
            allowedNames.add("星街すいせい");
        }

        Map<String, Object> target = jdbcTemplate.query(
            """
            SELECT h.id AS holomem_id,
                   h.match_card_id,
                   h.card_id,
                   c.name
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone = 'CENTER'
              AND h.last_bloom_turn = ?
            ORDER BY h.id
            """,
            rs -> {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (!allowedNames.isEmpty() && !containsAnyName(name, allowedNames)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("holomem_id", rs.getLong("holomem_id"));
                    row.put("match_card_id", rs.getLong("match_card_id"));
                    row.put("card_id", rs.getString("card_id"));
                    row.put("name", name);
                    return row;
                }
                return null;
            },
            matchId,
            userId,
            currentLife <= 3 ? resolveCurrentTurnNumber(matchId) : -1
        );
        if (target == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有符合條件且本回合已 Bloom 的 CENTER 目標");
        }

        Long targetHolomemId = asLong(target.get("holomem_id"));
        int currentTurn = resolveCurrentTurnNumber(matchId);
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
            toJsonString(
                Map.of(
                    "targetHolomemId", targetHolomemId,
                    "targetHolomemCardInstanceId", asLong(target.get("match_card_id")),
                    "targetCardId", asText(target.get("card_id")),
                    "targetName", asText(target.get("name"))
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
        summary.put("expiresTurn", currentTurn);
        return summary;
    }

    private Map<String, Object> executeActionLockEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
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
            toJsonString(payload)
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

    private Map<String, Object> executeLookTopDeckEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (rawText.contains("マスコットが付いている")) {
            Integer mascotAttachedCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_holomem_supports hs
                JOIN support_cards sc ON sc.card_id = hs.support_card_id
                WHERE hs.match_id = ?
                  AND hs.owner_user_id = ?
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

    private Map<String, Object> executeLookOpponentHandEffect(
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

    private Map<String, Object> executeLookHolopowerEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
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

    private Map<String, Object> executeSwapWithCollabEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        Long selfHolomemCardInstanceId
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
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

    private Map<String, Object> executeAddCheerEffect(
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
            throw new IllegalStateException("ADD_CHEER 需要指定可用的我方 Holomen");
        }
        int requestedCount = resolveCheerCount(effectNode, 1);
        int attachCount = Math.max(requestedCount, 1);

        List<Long> attachedCardInstanceIds = new ArrayList<>();
        List<String> sourceZones = new ArrayList<>();
        for (int i = 0; i < attachCount; i++) {
            Map<String, Object> source = findAttachableCheerCard(matchId, userId);
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
                    INSERT INTO match_holomem_cheers (match_holomem_id, cheer_card_id, is_face_down)
                    VALUES (?, ?, FALSE)
                    """,
                    targetHolomemId,
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

    private Map<String, Object> executeDamageEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
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
        int attachedSupportHpBonus = resolveAttachedSupportHpBonus(matchId, targetHolomemId);
        int hp = Math.max(baseHp + attachedSupportHpBonus, 0);
        int damageTaken = asInt(holomemState.get("damage_taken"));

        boolean downed = hp > 0 && damageTaken >= hp;
        boolean lifeReduced = false;
        Long lostLifeCardInstanceId = null;
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
        summary.put("lostLifeCardInstanceId", lostLifeCardInstanceId);
        return summary;
    }

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
            toJsonString(payload)
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

    private Map<String, Object> executeHealEffect(
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

    private Map<String, Object> executeRemoveCheerEffect(
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
            SELECT id, cheer_card_id
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
            Long archivedCardInstanceId = moveCheerCardInstanceToArchive(matchId, targetOwnerUserId, cheerCardId);
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

    private Map<String, Object> executeMoveZoneEffect(
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
        String rawText = extractText(effectNode, "rawText", "rawEffect");
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

    private Map<String, Object> executeBuffDebuffEffect(
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
        int inserted = 0;
        if (modifier != 0 && !affectedUserIds.isEmpty()) {
            for (Long affectedUserId : affectedUserIds) {
                if (affectedUserId == null || affectedUserId <= 0) {
                    continue;
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
                    toJsonString(Map.of("rawText", extractText(effectNode, "rawText", "rawEffect", "rawHeader")))
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
        if (modifier == 0) {
            summary.put("reason", "找不到可用的傷害修正值");
        }
        return summary;
    }

    private Map<String, Object> executeNoOpEffect(String effectType, JsonNode effectNode, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", false);
        summary.put("reason", reason);
        summary.put("rawText", extractText(effectNode, "rawText", "rawEffect"));
        return summary;
    }

    private Map<String, Object> buildSkippedEffect(String effectType, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", normalizeEffectType(effectType));
        summary.put("applied", false);
        summary.put("skipped", true);
        summary.put("reason", StringUtils.hasText(reason) ? reason : "EFFECT_SKIPPED");
        return summary;
    }

    private Map<String, Object> executeMatchResultEffect(
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
        summary.put("effectType", normalizeEffectType(effectType));
        summary.put("applied", true);
        summary.put("matchResult", matchResult);
        return summary;
    }

    private MatchResultDecision resolveMatchResultDecision(
        String effectType,
        JsonNode effectNode,
        Long actorUserId,
        Long opponentUserId
    ) {
        String explicitResult = normalizeEffectType(readText(effectNode, "result", "outcome", "matchResult"));
        String normalizedEffectType = normalizeEffectType(effectType);
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect", "rawHeader"));

        String resolvedReason = readText(effectNode, "reason");
        if (!StringUtils.hasText(resolvedReason)) {
            resolvedReason = "CARD_EFFECT_MATCH_RESULT";
        }

        String winnerToken = normalizeEffectType(readText(effectNode, "winner", "winnerSide", "winnerUser"));
        String loserToken = normalizeEffectType(readText(effectNode, "loser", "loserSide", "loserUser"));

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

    private boolean isBothToken(String token) {
        String normalized = normalizeEffectType(token);
        return "BOTH".equals(normalized) || "ALL".equals(normalized);
    }

    private Long resolveSideUserId(String sideToken, Long actorUserId, Long opponentUserId) {
        String normalized = normalizeEffectType(sideToken);
        return switch (normalized) {
            case "SELF", "YOU", "ME", "ACTOR", "CURRENT" -> actorUserId;
            case "OPPONENT", "ENEMY", "OTHER" -> opponentUserId;
            default -> null;
        };
    }

    private SelectionProbe probeSelectionCandidates(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode
    ) {
        String normalizedType = normalizeEffectType(effectType);
        return switch (normalizedType) {
            case "SEARCH" -> probeSearchCandidates(matchId, userId, effectNode);
            case "RETURN_TO_HAND" -> probeReturnToHandCandidates(matchId, userId, effectNode);
            case "RETURN_TO_DECK_TOP" -> probeReturnToDeckTopCandidates(matchId, userId, effectNode);
            default -> null;
        };
    }

    private SelectionProbe probeSearchCandidates(Long matchId, Long userId, JsonNode effectNode) {
        int requestedCount = Math.max(resolveSearchCount(effectNode), 1);
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        int lookTopCount = resolveSearchLookTopCount(effectNode, rawText);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        List<Map<String, Object>> rows = lookTopCount > 0
            ? filterCandidatesByCriteria(loadTopDeckWindow(matchId, userId, lookTopCount), criteria)
            : loadSearchCandidates(matchId, userId, criteria);
        return new SelectionProbe(
            requestedCount,
            mapDecisionCandidates(rows, "DECK")
        );
    }

    private SelectionProbe probeReturnToHandCandidates(Long matchId, Long userId, JsonNode effectNode) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, "RETURN_TO_HAND")) {
            return null;
        }
        int requestedCount = Math.max(resolveActionCount(effectNode, "手札に戻", 1), 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        boolean excludeLimitedSupport = rawText.contains("LIMITED以外");
        List<Map<String, Object>> rows = loadCandidatesFromZone(
            matchId,
            userId,
            "ARCHIVE",
            criteria,
            excludeLimitedSupport
        );
        return new SelectionProbe(
            requestedCount,
            mapDecisionCandidates(rows, "ARCHIVE")
        );
    }

    private SelectionProbe probeReturnToDeckTopCandidates(Long matchId, Long userId, JsonNode effectNode) {
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (!shouldApplyByDice(rawText, effectNode, "RETURN_TO_DECK_TOP")) {
            return null;
        }
        int requestedCount = Math.max(resolveActionCount(effectNode, "デッキの上に戻", 1), 1);
        SearchCriteria criteria = resolveSearchCriteria(effectNode);
        List<Map<String, Object>> rows = loadCandidatesFromZone(
            matchId,
            userId,
            "ARCHIVE",
            criteria,
            false
        );
        return new SelectionProbe(
            requestedCount,
            mapDecisionCandidates(rows, "ARCHIVE")
        );
    }

    private List<DecisionCandidate> mapDecisionCandidates(List<Map<String, Object>> rows, String zone) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DecisionCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long cardInstanceId = asLong(row.get("id"));
            String cardId = asText(row.get("card_id"));
            if (cardInstanceId == null || !StringUtils.hasText(cardId)) {
                continue;
            }
            candidates.add(
                new DecisionCandidate(
                    cardInstanceId,
                    cardId,
                    asText(row.get("name")),
                    normalize(asText(row.get("card_type"))),
                    normalizeLevelType(asText(row.get("level_type"))),
                    normalize(zone)
                )
            );
        }
        return candidates;
    }

    private String loadPassiveEffectText(String bloomCardId) {
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

    private BloomEffectPlan resolveBloomEffectPlan(String bloomCardId) {
        String passiveText = loadPassiveEffectText(bloomCardId);
        if (!StringUtils.hasText(passiveText)) {
            return new BloomEffectPlan(false, List.of(), objectMapper.createObjectNode(), null, null);
        }
        JsonNode passiveNode = parseEffectJson(passiveText);
        BloomEffectPlan structured = resolveStructuredBloomEffectPlan(passiveNode);
        if (structured != null && structured.hasBloomEffect()) {
            return structured;
        }

        String bloomText = loadBloomEffectText(passiveText);
        if (!StringUtils.hasText(bloomText)) {
            return new BloomEffectPlan(false, List.of(), objectMapper.createObjectNode(), null, null);
        }
        List<String> effectTypes = inferBloomEffectTypes(bloomText);
        Integer diceRoll = resolveBloomDiceRoll(bloomText);
        Map<String, Object> bloomEffectPayload = new LinkedHashMap<>();
        bloomEffectPayload.put("type", "UNIMPLEMENTED");
        bloomEffectPayload.put("effects", effectTypes);
        bloomEffectPayload.put("rawText", bloomText);
        if (diceRoll != null) {
            bloomEffectPayload.put("diceRoll", diceRoll);
        }
        return new BloomEffectPlan(
            true,
            effectTypes,
            objectMapper.valueToTree(bloomEffectPayload),
            bloomText,
            diceRoll
        );
    }

    private BloomEffectPlan resolveCollabEffectPlan(String collabCardId) {
        String passiveText = loadPassiveEffectText(collabCardId);
        if (!StringUtils.hasText(passiveText)) {
            return new BloomEffectPlan(false, List.of(), objectMapper.createObjectNode(), null, null);
        }
        JsonNode passiveNode = parseEffectJson(passiveText);
        BloomEffectPlan structured = resolveStructuredCollabEffectPlan(passiveNode);
        if (structured != null && structured.hasBloomEffect()) {
            return structured;
        }

        String collabText = loadCollabEffectText(passiveText);
        if (!StringUtils.hasText(collabText)) {
            return new BloomEffectPlan(false, List.of(), objectMapper.createObjectNode(), null, null);
        }
        List<String> effectTypes = inferBloomEffectTypes(collabText);
        Integer diceRoll = resolveBloomDiceRoll(collabText);
        Map<String, Object> collabEffectPayload = new LinkedHashMap<>();
        collabEffectPayload.put("type", "UNIMPLEMENTED");
        collabEffectPayload.put("effects", effectTypes);
        collabEffectPayload.put("rawText", collabText);
        if (diceRoll != null) {
            collabEffectPayload.put("diceRoll", diceRoll);
        }
        return new BloomEffectPlan(
            true,
            effectTypes,
            objectMapper.valueToTree(collabEffectPayload),
            collabText,
            diceRoll
        );
    }

    private BloomEffectPlan resolveStructuredBloomEffectPlan(JsonNode passiveNode) {
        if (passiveNode == null || passiveNode.isNull() || !passiveNode.isObject()) {
            return null;
        }
        JsonNode bloomNode = passiveNode.get("bloomEffect");
        if (bloomNode == null || bloomNode.isNull() || !bloomNode.isObject()) {
            return null;
        }

        List<String> effectTypes = resolveEffectTypes(readText(bloomNode, "type"), bloomNode);
        String rawText = readText(bloomNode, "rawText", "rawEffect", "text");
        if (effectTypes.isEmpty() && StringUtils.hasText(rawText)) {
            effectTypes = inferBloomEffectTypes(rawText);
        }
        if (effectTypes.isEmpty()) {
            effectTypes = List.of("UNIMPLEMENTED");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", normalizeEffectType(readText(bloomNode, "type")));
        payload.put("effects", effectTypes);
        if (StringUtils.hasText(rawText)) {
            payload.put("rawText", rawText);
        }
        if (bloomNode.has("searchCriteria")) {
            payload.put("searchCriteria", bloomNode.get("searchCriteria"));
        }
        if (bloomNode.has("value")) {
            payload.put("value", bloomNode.get("value").asInt());
        }
        if (bloomNode.has("cards")) {
            payload.put("cards", bloomNode.get("cards").asInt());
        }
        if (bloomNode.has("amount")) {
            payload.put("amount", bloomNode.get("amount").asInt());
        }
        if (bloomNode.has("diceCondition")) {
            payload.put("diceCondition", readText(bloomNode, "diceCondition"));
        }
        if (bloomNode.has("effectDiceConditions")) {
            payload.put("effectDiceConditions", bloomNode.get("effectDiceConditions"));
        }

        Integer diceRoll = null;
        if (bloomNode.has("diceCondition") || bloomNode.has("effectDiceConditions")) {
            diceRoll = resolveDiceRoll(bloomNode);
            payload.put("diceRoll", diceRoll);
        }
        return new BloomEffectPlan(true, effectTypes, objectMapper.valueToTree(payload), rawText, diceRoll);
    }

    private BloomEffectPlan resolveStructuredCollabEffectPlan(JsonNode passiveNode) {
        if (passiveNode == null || passiveNode.isNull() || !passiveNode.isObject()) {
            return null;
        }
        JsonNode collabNode = passiveNode.get("collabEffect");
        if (collabNode == null || collabNode.isNull() || !collabNode.isObject()) {
            return null;
        }

        List<String> effectTypes = resolveEffectTypes(readText(collabNode, "type"), collabNode);
        String rawText = readText(collabNode, "rawText", "rawEffect", "text");
        if (effectTypes.isEmpty() && StringUtils.hasText(rawText)) {
            effectTypes = inferBloomEffectTypes(rawText);
        }
        if (effectTypes.isEmpty()) {
            effectTypes = List.of("UNIMPLEMENTED");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", normalizeEffectType(readText(collabNode, "type")));
        payload.put("effects", effectTypes);
        if (StringUtils.hasText(rawText)) {
            payload.put("rawText", rawText);
        }
        if (collabNode.has("searchCriteria")) {
            payload.put("searchCriteria", collabNode.get("searchCriteria"));
        }
        if (collabNode.has("value")) {
            payload.put("value", collabNode.get("value").asInt());
        }
        if (collabNode.has("cards")) {
            payload.put("cards", collabNode.get("cards").asInt());
        }
        if (collabNode.has("amount")) {
            payload.put("amount", collabNode.get("amount").asInt());
        }
        if (collabNode.has("diceCondition")) {
            payload.put("diceCondition", readText(collabNode, "diceCondition"));
        }
        if (collabNode.has("effectDiceConditions")) {
            payload.put("effectDiceConditions", collabNode.get("effectDiceConditions"));
        }

        Integer diceRoll = null;
        if (collabNode.has("diceCondition") || collabNode.has("effectDiceConditions")) {
            diceRoll = resolveDiceRoll(collabNode);
            payload.put("diceRoll", diceRoll);
        }
        return new BloomEffectPlan(true, effectTypes, objectMapper.valueToTree(payload), rawText, diceRoll);
    }

    private String loadBloomEffectText(String passiveText) {
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("ブルームエフェクト")) {
            return null;
        }
        return normalizeBloomText(passiveText);
    }

    private String loadCollabEffectText(String passiveText) {
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("コラボエフェクト")) {
            return null;
        }
        return normalizeCollabText(passiveText);
    }

    private String loadGiftEffectText(String passiveText) {
        if (!StringUtils.hasText(passiveText) || !passiveText.contains("ギフト")) {
            return null;
        }
        return normalizeGiftText(passiveText);
    }

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

    private List<String> inferBloomEffectTypes(String bloomText) {
        Set<String> effectTypes = new LinkedHashSet<>();
        String text = normalizeDigits(bloomText == null ? "" : bloomText);
        if (!StringUtils.hasText(text)) {
            effectTypes.add("UNIMPLEMENTED");
            return new ArrayList<>(effectTypes);
        }

        if (text.contains("手札に加える")) {
            effectTypes.add("SEARCH");
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
        if (text.contains("手札") && text.contains("アーカイブする")) {
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

    private String inferBloomTargetType(String effectType) {
        return switch (effectType) {
            case "DAMAGE", "DEBUFF", "MOVE_ZONE", "REST", "DOWN_NO_LIFE", "DOWN_EXTRA_LIFE" -> "ENEMY";
            default -> "SELF";
        };
    }

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

    public int clearExpiredTurnEffects(Long matchId, int currentTurn) {
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

    private List<String> resolveEffectTypes(String effectType, JsonNode effectNode) {
        Set<String> effectTypes = new LinkedHashSet<>();
        boolean hasStructuredEffects = false;
        if (effectNode != null) {
            JsonNode effectsNode = effectNode.get("effects");
            if (effectsNode != null && effectsNode.isArray()) {
                for (JsonNode node : effectsNode) {
                    if (node.isTextual() && StringUtils.hasText(node.asText())) {
                        hasStructuredEffects = true;
                        effectTypes.add(normalizeEffectType(node.asText()));
                    }
                }
            }
        }
        if (!hasStructuredEffects && StringUtils.hasText(effectType)) {
            effectTypes.add(normalizeEffectType(effectType));
        }
        if (!hasStructuredEffects && effectNode != null && effectNode.hasNonNull("type")) {
            effectTypes.add(normalizeEffectType(effectNode.path("type").asText()));
        }
        if (hasStructuredEffects && StringUtils.hasText(effectType)) {
            effectTypes.add(normalizeEffectType(effectType));
        }
        if (hasStructuredEffects && effectNode != null && effectNode.hasNonNull("type")) {
            effectTypes.add(normalizeEffectType(effectNode.path("type").asText()));
        }
        return new ArrayList<>(effectTypes);
    }

    private int resolveSearchCount(JsonNode effectNode) {
        int fromFields = extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        Matcher range = SEARCH_RANGE_PATTERN.matcher(text);
        if (range.find()) {
            try {
                return Integer.parseInt(range.group(2));
            } catch (NumberFormatException ignored) {
                // 解析失敗時回退到下一規則
            }
        }
        int count = extractByPattern(text, SEARCH_COUNT_PATTERN);
        if (count > 0) {
            return count;
        }
        return text.contains("手札に加える") ? 1 : 0;
    }

    private int resolveSearchLookTopCount(JsonNode effectNode, String rawText) {
        int fromFields = extractInt(effectNode, 0, "lookTopCount", "lookCount", "peekCount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = normalizeDigits(rawText);
        Matcher matcher = SEARCH_LOOK_TOP_COUNT_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private int resolveActionCount(JsonNode effectNode, String fallbackToken, int defaultValue) {
        int fromFields = extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        Matcher range = SEARCH_RANGE_PATTERN.matcher(text);
        if (range.find()) {
            try {
                return Integer.parseInt(range.group(2));
            } catch (NumberFormatException ignored) {
                // 解析失敗時回退到下一規則
            }
        }
        int count = extractByPattern(text, SEARCH_COUNT_PATTERN);
        if (count > 0) {
            return count;
        }
        if (StringUtils.hasText(fallbackToken) && text.contains(fallbackToken)) {
            return 1;
        }
        return defaultValue;
    }

    private Integer resolveBloomDiceRoll(String bloomText) {
        String text = normalizeDigits(bloomText);
        if (!StringUtils.hasText(text) || !text.contains("サイコロ")) {
            return null;
        }
        return diceService.rollD6();
    }

    private boolean shouldApplyByDice(String rawText, JsonNode effectNode, String effectType) {
        String explicitCondition = resolveExplicitDiceCondition(effectNode, effectType);
        if (StringUtils.hasText(explicitCondition)) {
            int diceRoll = resolveDiceRoll(effectNode);
            if (diceRoll <= 0) {
                return true;
            }
            return evaluateDiceCondition(explicitCondition, diceRoll);
        }
        String text = normalizeDigits(rawText);
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

    private String resolveExplicitDiceCondition(JsonNode effectNode, String effectType) {
        if (effectNode == null || effectNode.isNull()) {
            return null;
        }
        JsonNode perEffect = effectNode.get("effectDiceConditions");
        String normalizedEffectType = normalizeEffectType(effectType);
        if (perEffect != null && perEffect.isObject()) {
            JsonNode conditionNode = perEffect.get(normalizedEffectType);
            if (conditionNode == null) {
                conditionNode = perEffect.get(effectType);
            }
            if (conditionNode != null && conditionNode.isTextual()) {
                return normalizeEffectType(conditionNode.asText());
            }
        }
        return normalizeEffectType(readText(effectNode, "diceCondition", "dice_condition"));
    }

    private boolean evaluateDiceCondition(String condition, int diceRoll) {
        if (!StringUtils.hasText(condition)) {
            return true;
        }
        String normalized = normalizeEffectType(condition);
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

    private int resolveDiceRoll(JsonNode effectNode) {
        int fromNode = extractInt(effectNode, 0, "diceRoll");
        if (fromNode >= 1 && fromNode <= 6) {
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

    private DiceResolution resolveDiceResolution(JsonNode effectNode) {
        int rollCount = resolveDiceRollCount(effectNode);
        String strategy = resolveDicePickStrategy(effectNode);
        Integer fixedDiceValue = resolveFixedDiceValue(effectNode);
        List<Integer> rolls = new ArrayList<>();
        for (int i = 0; i < rollCount; i++) {
            int roll = diceService.rollD6();
            if (i == 0 && fixedDiceValue != null && fixedDiceValue >= 1 && fixedDiceValue <= 6) {
                roll = fixedDiceValue;
            }
            if (roll < 1 || roll > 6) {
                roll = 1;
            }
            rolls.add(roll);
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

    private int resolveDiceRollCount(JsonNode effectNode) {
        int fromField = extractInt(effectNode, 0, "diceRollCount", "diceCount", "rollCount");
        if (fromField > 0) {
            return Math.min(fromField, 6);
        }
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
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

    private String resolveDicePickStrategy(JsonNode effectNode) {
        String explicit = normalizeEffectType(readText(effectNode, "dicePickStrategy", "dicePick", "diceSelect"));
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        if (rawText.contains("大きい方")) {
            return "MAX";
        }
        if (rawText.contains("小さい方")) {
            return "MIN";
        }
        return "FIRST";
    }

    private Integer resolveFixedDiceValue(JsonNode effectNode) {
        int fromField = extractInt(effectNode, 0, "fixedDiceValue", "diceFixedValue", "forcedDiceValue");
        if (fromField >= 1 && fromField <= 6) {
            return fromField;
        }
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
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

    private SearchCriteria resolveSearchCriteria(JsonNode effectNode) {
        JsonNode criteriaNode = effectNode == null ? null : effectNode.get("searchCriteria");
        String rawText = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        return resolveSearchCriteriaNode(criteriaNode, rawText, true);
    }

    private SearchCriteria resolveSearchCriteriaNode(JsonNode criteriaNode, String rawText, boolean allowRawInference) {
        String cardType = normalizeCardType(readText(criteriaNode, "cardType"));
        String levelType = normalizeLevelType(readText(criteriaNode, "level", "levelType"));
        String tag = readText(criteriaNode, "tag");
        String nameContains = readText(criteriaNode, "nameContains");
        String color = normalizeColorType(readText(criteriaNode, "color", "mainColor", "cheerColor"));
        Boolean rested = readBoolean(criteriaNode, "rested", "isRested", "requireRested", "mustBeRested");
        Boolean active = readBoolean(criteriaNode, "active", "isActive", "mustBeActive");
        if (rested == null && active != null) {
            rested = !active;
        }
        Integer minRemainHp = extractNullableInt(
            criteriaNode,
            "minRemainHp",
            "remainHpMin",
            "remainingHpMin",
            "minHp",
            "hpMin",
            "hpAtLeast"
        );
        Integer maxRemainHp = extractNullableInt(
            criteriaNode,
            "maxRemainHp",
            "remainHpMax",
            "remainingHpMax",
            "maxHp",
            "hpMax",
            "hpAtMost"
        );
        List<SearchCriteria> allOf = resolveCriteriaList(criteriaNode == null ? null : criteriaNode.get("allOf"), rawText);
        List<SearchCriteria> anyOf = resolveCriteriaList(criteriaNode == null ? null : criteriaNode.get("anyOf"), rawText);

        if (!allowRawInference) {
            return new SearchCriteria(cardType, levelType, tag, nameContains, color, rested, minRemainHp, maxRemainHp, allOf, anyOf);
        }

        if (!StringUtils.hasText(cardType)) {
            if (rawText.contains("ホロメン")) {
                cardType = "MEMBER";
            } else if (rawText.contains("エール")) {
                cardType = "CHEER";
            } else if (
                rawText.contains("サポート")
                || rawText.contains("ツール")
                || rawText.contains("イベント")
                || rawText.contains("ファン")
                || rawText.contains("マスコット")
            ) {
                cardType = "SUPPORT";
            }
        }
        if (!StringUtils.hasText(levelType)) {
            if (rawText.contains("Debut")) {
                levelType = "DEBUT";
            } else if (rawText.contains("1st")) {
                levelType = "FIRST";
            } else if (rawText.contains("2nd")) {
                levelType = "SECOND";
            } else if (rawText.contains("Buzz")) {
                levelType = "BUZZ";
            } else if (rawText.contains("Spot")) {
                levelType = "SPOT";
            }
        }
        if (!StringUtils.hasText(tag)) {
            tag = resolveTagFromKnownTags(rawText);
        }
        if (!StringUtils.hasText(tag)) {
            Matcher matcher = TAG_PATTERN.matcher(rawText);
            if (matcher.find()) {
                tag = "#" + matcher.group(1);
            }
        }
        if (!StringUtils.hasText(nameContains)) {
            Matcher nameTokenMatcher = NAME_TOKEN_PATTERN.matcher(rawText);
            if (nameTokenMatcher.find()) {
                nameContains = nameTokenMatcher.group(1).trim();
            }
        }
        if (!StringUtils.hasText(color)) {
            color = normalizeColorType(resolveCheerColorFilter(rawText));
        }
        return new SearchCriteria(cardType, levelType, tag, nameContains, color, rested, minRemainHp, maxRemainHp, allOf, anyOf);
    }

    private List<SearchCriteria> resolveCriteriaList(JsonNode criteriaArrayNode, String rawText) {
        if (criteriaArrayNode == null || !criteriaArrayNode.isArray() || criteriaArrayNode.isEmpty()) {
            return List.of();
        }
        List<SearchCriteria> criteriaList = new ArrayList<>();
        for (JsonNode child : criteriaArrayNode) {
            if (child == null || child.isNull()) {
                continue;
            }
            criteriaList.add(resolveSearchCriteriaNode(child, rawText, false));
        }
        return criteriaList;
    }

    private String resolveTagFromKnownTags(String rawText) {
        if (!StringUtils.hasText(rawText) || !rawText.contains("#")) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT t.tag
            FROM (
                SELECT DISTINCT jsonb_array_elements_text(COALESCE(tags_json, '[]'::jsonb)) AS tag
                FROM cards
                WHERE tags_json IS NOT NULL
            ) t
            WHERE ? LIKE '%' || t.tag || '%'
            ORDER BY POSITION(t.tag IN ?), LENGTH(t.tag) DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getString("tag") : null,
            rawText,
            rawText
        );
    }

    private List<Map<String, Object>> loadSearchCandidates(
        Long matchId,
        Long userId,
        SearchCriteria criteria
    ) {
        return loadCandidatesFromZone(matchId, userId, "DECK", criteria, false);
    }

    private List<Map<String, Object>> loadTopDeckWindow(Long matchId, Long userId, int count) {
        if (count <= 0) {
            return List.of();
        }
        int limit = Math.min(count, 20);
        return jdbcTemplate.query(
            """
            SELECT mc.id,
                   mc.card_id,
                   c.card_type,
                   m.level_type,
                   c.name,
                   c.tags_json::text AS tags_json,
                   m.main_color,
                   m.sub_color,
                   cc.color AS cheer_color,
                   h.is_rested,
                   GREATEST(COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0), 0) AS remain_hp
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            LEFT JOIN cheer_cards cc ON cc.card_id = mc.card_id
            LEFT JOIN match_holomems h
              ON h.match_card_id = mc.id
             AND h.match_id = mc.match_id
             AND h.owner_user_id = mc.owner_user_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'DECK'
            ORDER BY mc.order_index NULLS LAST, mc.id
            LIMIT ?
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("card_type", rs.getString("card_type"));
                row.put("level_type", rs.getString("level_type"));
                row.put("name", rs.getString("name"));
                row.put("tags_json", rs.getString("tags_json"));
                row.put("main_color", rs.getString("main_color"));
                row.put("sub_color", rs.getString("sub_color"));
                row.put("cheer_color", rs.getString("cheer_color"));
                row.put("is_rested", rs.getObject("is_rested"));
                row.put("remain_hp", rs.getObject("remain_hp"));
                return row;
            },
            matchId,
            userId,
            limit
        );
    }

    private List<Map<String, Object>> loadSearchCandidates(
        Long matchId,
        Long userId,
        String cardType,
        String levelType,
        String tag,
        String nameContains
    ) {
        return loadSearchCandidates(matchId, userId, new SearchCriteria(cardType, levelType, tag, nameContains));
    }

    private List<Map<String, Object>> loadCandidatesFromZone(
        Long matchId,
        Long userId,
        String zone,
        SearchCriteria criteria,
        boolean excludeLimitedSupport
    ) {
        SearchCriteria resolved = criteria == null ? SearchCriteria.empty() : criteria;
        List<Map<String, Object>> rows = jdbcTemplate.query(
            """
            SELECT mc.id,
                   mc.card_id,
                   c.card_type,
                   m.level_type,
                   c.name,
                   c.tags_json::text AS tags_json,
                   m.main_color,
                   m.sub_color,
                   cc.color AS cheer_color,
                   h.is_rested,
                   GREATEST(COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0), 0) AS remain_hp
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            LEFT JOIN cheer_cards cc ON cc.card_id = mc.card_id
            LEFT JOIN support_cards sc ON sc.card_id = mc.card_id
            LEFT JOIN match_holomems h
              ON h.match_card_id = mc.id
             AND h.match_id = mc.match_id
             AND h.owner_user_id = mc.owner_user_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = ?
              AND (? = '' OR c.card_type = ?)
              AND (? = '' OR m.level_type = ?)
              AND (? = '' OR c.name ILIKE '%' || ? || '%')
              AND (? = FALSE OR c.card_type <> 'SUPPORT' OR COALESCE(sc.is_limited, FALSE) = FALSE)
              AND (
                    ? = ''
                    OR EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements_text(COALESCE(c.tags_json, '[]'::jsonb)) AS t(tag)
                        WHERE t.tag = ?
                    )
                  )
            ORDER BY mc.order_index NULLS LAST, mc.id
            """,
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("card_type", rs.getString("card_type"));
                row.put("level_type", rs.getString("level_type"));
                row.put("name", rs.getString("name"));
                row.put("tags_json", rs.getString("tags_json"));
                row.put("main_color", rs.getString("main_color"));
                row.put("sub_color", rs.getString("sub_color"));
                row.put("cheer_color", rs.getString("cheer_color"));
                row.put("is_rested", rs.getObject("is_rested"));
                row.put("remain_hp", rs.getObject("remain_hp"));
                return row;
            },
            matchId,
            userId,
            zone,
            nullToEmpty(resolved.cardType()),
            nullToEmpty(resolved.cardType()),
            nullToEmpty(resolved.levelType()),
            nullToEmpty(resolved.levelType()),
            nullToEmpty(resolved.nameContains()),
            nullToEmpty(resolved.nameContains()),
            excludeLimitedSupport,
            nullToEmpty(resolved.tag()),
            nullToEmpty(resolved.tag())
        );
        return filterCandidatesByCriteria(rows, resolved);
    }

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
        return loadCandidatesFromZone(
            matchId,
            userId,
            zone,
            new SearchCriteria(cardType, levelType, tag, nameContains),
            excludeLimitedSupport
        );
    }

    private List<Map<String, Object>> filterCandidatesByCriteria(List<Map<String, Object>> rows, SearchCriteria criteria) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        if (criteria == null || criteria.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (matchesSearchCriteria(row, criteria)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private boolean matchesSearchCriteria(Map<String, Object> row, SearchCriteria criteria) {
        if (!matchesBasicSearchCriteria(row, criteria)) {
            return false;
        }
        if (!criteria.allOf().isEmpty()) {
            for (SearchCriteria subCriteria : criteria.allOf()) {
                if (!matchesSearchCriteria(row, subCriteria)) {
                    return false;
                }
            }
        }
        if (!criteria.anyOf().isEmpty()) {
            for (SearchCriteria subCriteria : criteria.anyOf()) {
                if (matchesSearchCriteria(row, subCriteria)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private boolean matchesBasicSearchCriteria(Map<String, Object> row, SearchCriteria criteria) {
        String cardType = normalize(asText(row.get("card_type")));
        if (StringUtils.hasText(criteria.cardType()) && !criteria.cardType().equals(cardType)) {
            return false;
        }
        String levelType = normalizeLevelType(asText(row.get("level_type")));
        if (StringUtils.hasText(criteria.levelType()) && !criteria.levelType().equals(levelType)) {
            return false;
        }
        String name = asText(row.get("name"));
        if (
            StringUtils.hasText(criteria.nameContains()) &&
            (!StringUtils.hasText(name) || !name.toLowerCase(Locale.ROOT).contains(criteria.nameContains().toLowerCase(Locale.ROOT)))
        ) {
            return false;
        }
        if (StringUtils.hasText(criteria.tag()) && !rowTagsContains(asText(row.get("tags_json")), criteria.tag())) {
            return false;
        }
        if (StringUtils.hasText(criteria.color()) && !matchesAnyColor(row, criteria.color())) {
            return false;
        }
        if (criteria.rested() != null) {
            Boolean rowRested = readRowBoolean(row.get("is_rested"));
            if (rowRested == null || !rowRested.equals(criteria.rested())) {
                return false;
            }
        }
        if (criteria.minRemainHp() != null || criteria.maxRemainHp() != null) {
            Long remainHp = asLong(row.get("remain_hp"));
            if (remainHp == null) {
                return false;
            }
            if (criteria.minRemainHp() != null && remainHp < criteria.minRemainHp()) {
                return false;
            }
            if (criteria.maxRemainHp() != null && remainHp > criteria.maxRemainHp()) {
                return false;
            }
        }
        return true;
    }

    private boolean rowTagsContains(String tagsJson, String targetTag) {
        if (!StringUtils.hasText(tagsJson) || !StringUtils.hasText(targetTag)) {
            return false;
        }
        JsonNode tagsNode = parseEffectJson(tagsJson);
        if (tagsNode == null || !tagsNode.isArray()) {
            return false;
        }
        for (JsonNode tagNode : tagsNode) {
            if (tagNode == null || !tagNode.isTextual()) {
                continue;
            }
            if (targetTag.equals(tagNode.asText().trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyColor(Map<String, Object> row, String color) {
        if (!StringUtils.hasText(color)) {
            return true;
        }
        String expected = normalizeColorType(color);
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        return expected.equals(normalizeColorType(asText(row.get("main_color"))))
            || expected.equals(normalizeColorType(asText(row.get("sub_color"))))
            || expected.equals(normalizeColorType(asText(row.get("cheer_color"))));
    }

    private List<Map<String, Object>> selectSearchCards(
        List<Map<String, Object>> candidates,
        List<Long> selectedCardInstanceIds,
        int searchCount
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<String, Object>> candidateById = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            Long id = asLong(candidate.get("id"));
            if (id != null) {
                candidateById.put(id, candidate);
            }
        }

        if (selectedCardInstanceIds == null || selectedCardInstanceIds.isEmpty()) {
            return new ArrayList<>(candidates.subList(0, Math.min(searchCount, candidates.size())));
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        for (Long requestedId : selectedCardInstanceIds) {
            if (requestedId == null || requestedId <= 0 || !visited.add(requestedId)) {
                continue;
            }
            Map<String, Object> candidate = candidateById.get(requestedId);
            if (candidate == null) {
                throw new IllegalArgumentException("SEARCH 選牌無效：包含不在候選中的 cardInstanceId=" + requestedId);
            }
            selected.add(candidate);
            if (selected.size() >= searchCount) {
                break;
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("SEARCH 選牌無效：未選到可用卡片");
        }
        return selected;
    }

    private String readText(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private Boolean readBoolean(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isInt() || value.isLong()) {
                return value.asInt() != 0;
            }
            if (value.isTextual()) {
                String normalized = value.asText().trim().toLowerCase(Locale.ROOT);
                if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
                    return true;
                }
                if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
                    return false;
                }
            }
        }
        return null;
    }

    private Boolean readRowBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
                return false;
            }
        }
        return null;
    }

    private Integer extractNullableInt(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode valueNode = node.get(fieldName);
            if (valueNode == null || valueNode.isNull()) {
                continue;
            }
            if (valueNode.isInt() || valueNode.isLong()) {
                return valueNode.asInt();
            }
            if (valueNode.isTextual()) {
                try {
                    return Integer.parseInt(normalizeDigits(valueNode.asText()).trim());
                } catch (NumberFormatException ignored) {
                    // ignore invalid string value
                }
            }
        }
        return null;
    }

    private Map<String, Object> buildCriteriaSummary(SearchCriteria criteria) {
        SearchCriteria resolved = criteria == null ? SearchCriteria.empty() : criteria;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cardType", resolved.cardType());
        summary.put("levelType", resolved.levelType());
        summary.put("tag", resolved.tag());
        summary.put("nameContains", resolved.nameContains());
        summary.put("color", resolved.color());
        summary.put("rested", resolved.rested());
        summary.put("minRemainHp", resolved.minRemainHp());
        summary.put("maxRemainHp", resolved.maxRemainHp());
        if (!resolved.allOf().isEmpty()) {
            List<Map<String, Object>> allOfSummaries = new ArrayList<>();
            for (SearchCriteria sub : resolved.allOf()) {
                allOfSummaries.add(buildCriteriaSummary(sub));
            }
            summary.put("allOf", allOfSummaries);
        }
        if (!resolved.anyOf().isEmpty()) {
            List<Map<String, Object>> anyOfSummaries = new ArrayList<>();
            for (SearchCriteria sub : resolved.anyOf()) {
                anyOfSummaries.add(buildCriteriaSummary(sub));
            }
            summary.put("anyOf", anyOfSummaries);
        }
        return summary;
    }

    private String normalizeCardType(String cardType) {
        String normalized = normalize(cardType);
        if ("MEMBER".equals(normalized) || "SUPPORT".equals(normalized) || "CHEER".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private String normalizeColorType(String color) {
        String normalized = normalize(color);
        return switch (normalized) {
            case "RED", "BLUE", "GREEN", "WHITE", "PURPLE", "YELLOW", "COLORLESS" -> normalized;
            default -> "";
        };
    }

    private String normalizeLevelType(String levelType) {
        String normalized = normalize(levelType);
        return switch (normalized) {
            case "DEBUT", "FIRST", "SECOND", "SPOT", "BUZZ" -> normalized;
            case "1ST" -> "FIRST";
            case "2ND" -> "SECOND";
            default -> "";
        };
    }

    private String normalizeHolomemLevel(String levelType) {
        String normalized = normalizeLevelType(levelType);
        if ("FIRST".equals(normalized) || "SECOND".equals(normalized) || "SPOT".equals(normalized) || "BUZZ".equals(normalized)) {
            return normalized;
        }
        return "DEBUT";
    }

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
        return "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

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
            JsonNode payload = parseEffectJson(payloadText);
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

    private boolean isOpponentTargetType(String targetType) {
        return targetType.contains("ENEMY") || targetType.contains("OPPONENT");
    }

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

    private int resolveDrawCount(JsonNode effectNode) {
        int fromFields = extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        String text = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
        int exact = extractByPattern(text, DRAW_COUNT_PATTERN);
        if (exact > 0) {
            return exact;
        }
        int fallback = extractByPattern(text, DRAW_COUNT_FALLBACK_PATTERN);
        if (fallback > 0) {
            return fallback;
        }
        return text.contains("引く") ? 1 : 0;
    }

    private int resolveBuffDebuffModifier(JsonNode effectNode, String effectType) {
        int fromFields = extractInt(effectNode, 0, "modifier", "damageModifier", "amount", "value");
        if (fromFields != 0) {
            return normalizeModifierSign(fromFields, effectType);
        }
        String text = normalizeDigits(extractText(effectNode, "rawText", "rawEffect"));
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

    private int normalizeModifierSign(int modifier, String effectType) {
        if (modifier == 0) {
            return 0;
        }
        return "DEBUFF".equals(effectType)
            ? -Math.abs(modifier)
            : modifier;
    }

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

    private int resolveAttachedSupportStatBonus(Long matchId, Long matchHolomemId, Pattern pattern) {
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

    private String extractAttachedSupportRawText(String effectJsonText) {
        try {
            JsonNode node = objectMapper.readTree(effectJsonText);
            return normalizeDigits(extractText(node, "rawText", "rawEffect", "rawHeader"));
        } catch (Exception ignored) {
            return normalizeDigits(effectJsonText);
        }
    }

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

    private int resolveCheerCount(JsonNode effectNode, int defaultValue) {
        int fromFields = extractInt(effectNode, 0, "value", "cards", "amount");
        if (fromFields > 0) {
            return fromFields;
        }
        int byText = extractByPattern(normalizeDigits(extractText(effectNode, "rawText", "rawEffect")), CHEER_COUNT_PATTERN);
        if (byText > 0) {
            return byText;
        }
        return defaultValue;
    }

    private int resolveHealValue(JsonNode effectNode) {
        int fromFields = extractInt(effectNode, 0, "value", "amount", "heal");
        if (fromFields > 0) {
            return fromFields;
        }
        return extractByPattern(normalizeDigits(extractText(effectNode, "rawText", "rawEffect")), HEAL_PATTERN);
    }

    private String resolveMoveDestinationZone(JsonNode effectNode) {
        String explicit = normalizeEffectType(extractText(effectNode, "toZone", "targetZone"));
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        String text = extractText(effectNode, "rawText", "rawEffect");
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

    private boolean shouldRestAfterMove(JsonNode effectNode) {
        String text = extractText(effectNode, "rawText", "rawEffect");
        return text.contains("お休み");
    }

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

    private List<Long> archiveAttachedCheerCards(Long matchId, Long matchHolomemId, Long ownerUserId) {
        if (matchHolomemId == null || ownerUserId == null) {
            return List.of();
        }
        List<String> cheerCardIds = jdbcTemplate.query(
            """
            SELECT cheer_card_id
            FROM match_holomem_cheers
            WHERE match_holomem_id = ?
            ORDER BY id
            """,
            (rs, rowNum) -> rs.getString("cheer_card_id"),
            matchHolomemId
        );
        if (cheerCardIds.isEmpty()) {
            return List.of();
        }
        List<Long> archived = new ArrayList<>();
        for (String cheerCardId : cheerCardIds) {
            Long archivedCardInstanceId = moveCheerCardInstanceToArchive(matchId, ownerUserId, cheerCardId);
            if (archivedCardInstanceId != null) {
                archived.add(archivedCardInstanceId);
            }
        }
        return archived;
    }

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

    private Long moveCheerCardInstanceToArchive(Long matchId, Long ownerUserId, String cheerCardId) {
        if (!StringUtils.hasText(cheerCardId)) {
            return null;
        }
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
            cheerCardInstanceId,
            matchId,
            ownerUserId
        );
        return updated == 1 ? cheerCardInstanceId : null;
    }

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

    private Long resolveTargetHolomemId(Long matchId, Long userId, Long targetHolomemCardInstanceId) {
        if (targetHolomemCardInstanceId != null && targetHolomemCardInstanceId > 0) {
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
                userId,
                targetHolomemCardInstanceId
            );
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

    private boolean isGiftAlreadyUsedThisTurn(Long matchId, Long userId, int turnNumber, Long holderHolomemId) {
        if (matchId == null || userId == null || turnNumber <= 0 || holderHolomemId == null || holderHolomemId <= 0) {
            return false;
        }
        Integer used = jdbcTemplate.query(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = 'GIFT_TRIGGER'
              AND payload ->> 'giftHolderHolomemId' = ?
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            matchId,
            userId,
            turnNumber,
            holderHolomemId.toString()
        );
        return used != null && used > 0;
    }

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

    private Map<String, Object> findCheerCardFromZone(Long matchId, Long userId, String zone) {
        String normalizedZone = normalize(zone);
        if (!"CHEER_DECK".equals(normalizedZone) && !"ARCHIVE".equals(normalizedZone) && !"STAGE".equals(normalizedZone)) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT mc.id, mc.card_id, mc.zone
            FROM match_cards mc
            JOIN cheer_cards cc ON cc.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = ?
            ORDER BY mc.order_index NULLS LAST, mc.id
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
            userId,
            normalizedZone
        );
    }

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

    private boolean isDownWithoutLifeLoss(JsonNode effectNode) {
        if (effectNode != null && effectNode.has("downDoesNotReduceLife")) {
            return effectNode.path("downDoesNotReduceLife").asBoolean(false);
        }
        String merged = extractText(effectNode, "rawText", "rawEffect");
        return StringUtils.hasText(merged) && merged.contains("ダウンしても相手のライフは減らない");
    }

    private int resolveDownExtraLifeCount(JsonNode effectNode) {
        int fromField = extractInt(effectNode, 0, "extraLifeLoss", "lifeLoss", "value", "amount");
        if (fromField > 0) {
            return fromField;
        }
        String merged = normalizeDigits(extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        int byPattern = extractByPattern(merged, DOWN_EXTRA_LIFE_PATTERN);
        if (byPattern > 0) {
            return byPattern;
        }
        if (StringUtils.hasText(merged) && merged.contains("ライフ")) {
            return 1;
        }
        return 0;
    }

    private int resolveDamageValue(JsonNode effectNode) {
        int fromFields = extractInt(effectNode, 0, "value", "amount", "damage");
        if (fromFields > 0) {
            return fromFields;
        }
        String merged = normalizeDigits(extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        int special = extractByPattern(merged, SPECIAL_DAMAGE_PATTERN);
        if (special > 0) {
            return special;
        }
        int normal = extractByPattern(merged, DAMAGE_PATTERN);
        if (normal > 0) {
            return normal;
        }
        return 0;
    }

    private int extractByPattern(String value, Pattern pattern) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String extractText(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (String fieldName : fieldNames) {
            JsonNode textNode = node.get(fieldName);
            if (textNode != null && textNode.isTextual() && StringUtils.hasText(textNode.asText())) {
                if (!merged.isEmpty()) {
                    merged.append('\n');
                }
                merged.append(textNode.asText());
            }
        }
        return merged.toString();
    }

    private JsonNode parseEffectJson(String effectJson) {
        if (!StringUtils.hasText(effectJson)) {
            return null;
        }
        try {
            return objectMapper.readTree(effectJson);
        } catch (Exception ex) {
            return null;
        }
    }

    private String toJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private int extractInt(JsonNode node, int defaultValue, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return defaultValue;
        }
        for (String fieldName : fieldNames) {
            JsonNode valueNode = node.get(fieldName);
            if (valueNode == null || valueNode.isNull()) {
                continue;
            }
            if (valueNode.isInt() || valueNode.isLong()) {
                return valueNode.asInt();
            }
            if (valueNode.isTextual()) {
                try {
                    return Integer.parseInt(normalizeDigits(valueNode.asText()).trim());
                } catch (NumberFormatException ignored) {
                    // 略過非法字串，改讀其他欄位
                }
            }
        }
        return defaultValue;
    }

    private String normalizeDigits(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c >= '０' && c <= '９') {
                builder.append((char) ('0' + (c - '０')));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private String normalizeEffectType(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if (normalized.isEmpty()) {
                return false;
            }
            return "1".equals(normalized) || "true".equalsIgnoreCase(normalized) || "yes".equalsIgnoreCase(normalized);
        }
        return false;
    }

    private record SearchCriteria(
        String cardType,
        String levelType,
        String tag,
        String nameContains,
        String color,
        Boolean rested,
        Integer minRemainHp,
        Integer maxRemainHp,
        List<SearchCriteria> allOf,
        List<SearchCriteria> anyOf
    ) {
        private SearchCriteria {
            cardType = normalizeToken(cardType);
            levelType = normalizeToken(levelType);
            tag = normalizeToken(tag);
            nameContains = normalizeToken(nameContains);
            color = normalizeToken(color);
            allOf = allOf == null ? List.of() : List.copyOf(allOf);
            anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
        }

        private SearchCriteria(String cardType, String levelType, String tag, String nameContains) {
            this(cardType, levelType, tag, nameContains, "", null, null, null, List.of(), List.of());
        }

        private static SearchCriteria empty() {
            return new SearchCriteria("", "", "", "", "", null, null, null, List.of(), List.of());
        }

        private static String normalizeToken(String value) {
            return value == null ? "" : value.trim();
        }

        private boolean isEmpty() {
            return cardType.isEmpty()
                && levelType.isEmpty()
                && tag.isEmpty()
                && nameContains.isEmpty()
                && color.isEmpty()
                && rested == null
                && minRemainHp == null
                && maxRemainHp == null
                && allOf.isEmpty()
                && anyOf.isEmpty();
        }
    }

    public record DecisionCandidate(
        Long cardInstanceId,
        String cardId,
        String name,
        String cardType,
        String levelType,
        String zone
    ) {}

    public record SupportDecisionPlan(
        String effectType,
        int minSelect,
        int maxSelect,
        List<DecisionCandidate> candidates
    ) {}

    private record SelectionProbe(
        int requestedCount,
        List<DecisionCandidate> candidates
    ) {}

    private record BloomEffectPlan(
        boolean hasBloomEffect,
        List<String> effectTypes,
        JsonNode effectNode,
        String rawText,
        Integer diceRoll
    ) {}

    private record MatchResultDecision(
        boolean draw,
        Long winnerUserId,
        Long loserUserId,
        String reason
    ) {}

    private record DiceResolution(
        int chosenRoll,
        List<Integer> rolls,
        String strategy,
        boolean fixedApplied
    ) {}
}
