package com.hololive.cardgame.service;

import static com.hololive.cardgame.service.MatchEffectValueHelper.asLong;
import static com.hololive.cardgame.service.MatchEffectValueHelper.asText;
import static com.hololive.cardgame.service.MatchEffectValueHelper.normalize;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftExecutionSummary;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.GiftTriggerPreviewService;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MatchGiftTriggerService {

    private final JdbcTemplate jdbcTemplate;
    private final MatchEffectService matchEffectService;
    private final MatchGiftTriggerContextService giftTriggerContextService;
    private final GiftTriggerMatcher giftTriggerMatcher;
    private final MatchGiftTriggerOrchestrationService giftTriggerOrchestrationService;
    private final MatchGiftTriggerEligibilityService giftTriggerEligibilityService;

    public MatchGiftTriggerService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MatchEffectService matchEffectService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.matchEffectService = matchEffectService;

        EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
        this.giftTriggerContextService = new MatchGiftTriggerContextService(
            jdbcTemplate,
            objectMapper,
            effectTextParser
        );
        GiftTriggerPreviewService giftTriggerPreviewService = new GiftTriggerPreviewService();
        SearchCriteriaParser searchCriteriaParser = new SearchCriteriaParser(jdbcTemplate, effectTextParser);
        this.giftTriggerMatcher = new GiftTriggerMatcher();
        MatchGiftTriggerSummaryService giftTriggerSummaryService = new MatchGiftTriggerSummaryService(
            giftTriggerPreviewService
        );
        this.giftTriggerOrchestrationService = new MatchGiftTriggerOrchestrationService(
            jdbcTemplate,
            giftTriggerSummaryService,
            effectTextParser
        );
        MatchGiftTriggerConditionService giftTriggerConditionService = new MatchGiftTriggerConditionService(
            jdbcTemplate,
            effectTextParser,
            giftTriggerMatcher,
            searchCriteriaParser
        );
        this.giftTriggerEligibilityService = new MatchGiftTriggerEligibilityService(
            giftTriggerConditionService,
            giftTriggerMatcher
        );
    }

    /**
     * 記錄表演階段開始時的快照，供 Gift 的表演結束條件判斷使用。
     */
    public void recordPerformancePhaseSnapshot(
        Long matchId,
        Long sourceUserId,
        Long affectedUserId,
        int turnNumber
    ) {
        giftTriggerContextService.recordPerformancePhaseSnapshot(matchId, sourceUserId, affectedUserId, turnNumber);
    }

    public List<Map<String, Object>> applyGiftTriggeredEffectsOnArt(
        Long matchId,
        Long userId,
        Long attackerCardInstanceId,
        Long attackTargetCardInstanceId,
        int turnNumber
    ) {
        return executeGiftTrigger(
            giftTriggerRequest(
                matchId,
                userId,
                "ART_USED",
                attackerCardInstanceId,
                attackTargetCardInstanceId,
                turnNumber
            )
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnArt(
        Long matchId,
        Long userId,
        Long attackerCardInstanceId,
        Long attackTargetCardInstanceId,
        int turnNumber
    ) {
        return previewGiftTriggeredEffectsOnArt(
            matchId,
            userId,
            attackerCardInstanceId,
            attackTargetCardInstanceId,
            turnNumber,
            null
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnArt(
        Long matchId,
        Long userId,
        Long attackerCardInstanceId,
        Long attackTargetCardInstanceId,
        int turnNumber,
        String attackerArtName
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(
                matchId,
                userId,
                "ART_USED",
                attackerCardInstanceId,
                attackTargetCardInstanceId,
                turnNumber,
                null,
                attackerArtName
            )
        );
    }

    public List<Map<String, Object>> applyGiftTriggeredEffectsOnDownedOpponent(
        Long matchId,
        Long userId,
        Long attackerCardInstanceId,
        Long downedTargetCardInstanceId,
        int turnNumber
    ) {
        return executeGiftTrigger(
            giftTriggerRequest(
                matchId,
                userId,
                "OPPONENT_DOWNED",
                attackerCardInstanceId,
                downedTargetCardInstanceId,
                turnNumber
            )
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnDownedOpponent(
        Long matchId,
        Long userId,
        Long attackerCardInstanceId,
        Long downedTargetCardInstanceId,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(
                matchId,
                userId,
                "OPPONENT_DOWNED",
                attackerCardInstanceId,
                downedTargetCardInstanceId,
                turnNumber
            )
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnSelfDowned(
        Long matchId,
        Long userId,
        Long downedCardInstanceId,
        String downedStageZone,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(
                matchId,
                userId,
                "SELF_DOWNED",
                downedCardInstanceId,
                downedCardInstanceId,
                turnNumber,
                downedStageZone,
                null
            )
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnSelfDowned(
        Long matchId,
        Long userId,
        Long downedCardInstanceId,
        String downedStageZone,
        int turnNumber,
        Map<String, Object> holderSnapshot
    ) {
        if (holderSnapshot == null || holderSnapshot.isEmpty()) {
            return previewGiftTriggeredEffectsOnSelfDowned(
                matchId,
                userId,
                downedCardInstanceId,
                downedStageZone,
                turnNumber
            );
        }
        Map<String, Object> summary = buildGiftTriggerSummary(
            matchId,
            userId,
            turnNumber,
            downedCardInstanceId,
            downedCardInstanceId,
            "SELF_DOWNED",
            resolveSourceContext(
                giftTriggerRequest(
                    matchId,
                    userId,
                    "SELF_DOWNED",
                    downedCardInstanceId,
                    downedCardInstanceId,
                    turnNumber,
                    downedStageZone,
                    null
                )
            ),
            holderSnapshot,
            false
        );
        return summary == null ? List.of() : List.of(summary);
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnAllyDowned(
        Long matchId,
        Long userId,
        Long downedCardInstanceId,
        String downedStageZone,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(
                matchId,
                userId,
                "ALLY_DOWNED",
                downedCardInstanceId,
                downedCardInstanceId,
                turnNumber,
                downedStageZone,
                null
            )
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnStageEnter(
        Long matchId,
        Long userId,
        Long enteredCardInstanceId,
        String enteredStageZone,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(
                matchId,
                userId,
                "STAGE_ENTER",
                enteredCardInstanceId,
                enteredCardInstanceId,
                turnNumber,
                enteredStageZone,
                null
            )
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnCollab(
        Long matchId,
        Long userId,
        Long collabCardInstanceId,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(
                matchId,
                userId,
                "COLLAB",
                collabCardInstanceId,
                collabCardInstanceId,
                turnNumber,
                "COLLAB",
                null
            )
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnBatonTouchBack(
        Long matchId,
        Long userId,
        Long movedToBackCardInstanceId,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(
                matchId,
                userId,
                "BATON_TOUCH_BACK",
                movedToBackCardInstanceId,
                movedToBackCardInstanceId,
                turnNumber,
                "BACK",
                null
            )
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnOwnPerformanceStart(
        Long matchId,
        Long userId,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(matchId, userId, "PERFORMANCE_START_SELF", null, null, turnNumber)
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnOwnMainStep(
        Long matchId,
        Long userId,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(matchId, userId, "MAIN_STEP_SELF", null, null, turnNumber)
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnOpponentPerformanceStart(
        Long matchId,
        Long userId,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(matchId, userId, "PERFORMANCE_START_OPPONENT", null, null, turnNumber)
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnOwnPerformanceEnd(
        Long matchId,
        Long userId,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(matchId, userId, "PERFORMANCE_END_SELF", null, null, turnNumber)
        );
    }

    public List<Map<String, Object>> previewGiftTriggeredEffectsOnOpponentPerformanceEnd(
        Long matchId,
        Long userId,
        int turnNumber
    ) {
        return previewGiftTrigger(
            giftTriggerRequest(matchId, userId, "PERFORMANCE_END_OPPONENT", null, null, turnNumber)
        );
    }

    public Map<String, Object> applySingleGiftTriggeredEffect(
        Long matchId,
        Long userId,
        String triggerType,
        Long sourceCardInstanceId,
        Long triggerTargetCardInstanceId,
        int turnNumber,
        Long giftHolderHolomemId
    ) {
        if (
            matchId == null
                || userId == null
                || sourceCardInstanceId == null
                || turnNumber <= 0
                || giftHolderHolomemId == null
                || giftHolderHolomemId <= 0
        ) {
            return null;
        }
        GiftTriggerRequest request = giftTriggerRequest(
            matchId,
            userId,
            triggerType,
            sourceCardInstanceId,
            triggerTargetCardInstanceId,
            turnNumber
        );
        String normalizedTriggerType = normalizeGiftTriggerType(request.triggerType());
        Map<String, Object> holder = giftTriggerOrchestrationService.loadGiftHolder(matchId, userId, giftHolderHolomemId);
        if (holder == null) {
            return null;
        }
        MatchGiftTriggerSourceContext sourceContext = resolveSourceContext(request);
        return buildGiftTriggerSummary(
            request.matchId(),
            request.userId(),
            request.turnNumber(),
            request.sourceCardInstanceId(),
            request.triggerTargetCardInstanceId(),
            normalizedTriggerType,
            sourceContext,
            holder,
            true
        );
    }

    public Map<String, Object> applyStoredGiftTriggeredEffect(
        Long matchId,
        Long userId,
        String triggerType,
        Long sourceCardInstanceId,
        Long triggerTargetCardInstanceId,
        Map<String, Object> storedTrigger
    ) {
        if (matchId == null || userId == null || storedTrigger == null || storedTrigger.isEmpty()) {
            return null;
        }
        Long holderHolomemId = asLong(storedTrigger.get("giftHolderHolomemId"));
        Long holderCardInstanceId = asLong(storedTrigger.get("giftHolderCardInstanceId"));
        if (holderCardInstanceId == null || holderCardInstanceId <= 0) {
            return null;
        }
        String normalizedTriggerType = normalizeGiftTriggerType(triggerType);
        String giftText = matchEffectService.loadGiftEffectText(asText(storedTrigger.get("rawText")));
        if (!StringUtils.hasText(giftText)) {
            return null;
        }

        GiftExecutionSummary execution = matchEffectService.executeGiftEffectsForHolder(
            matchId,
            userId,
            holderCardInstanceId,
            triggerTargetCardInstanceId,
            giftText,
            storedTrigger
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("triggerType", normalizedTriggerType);
        summary.put("giftHolderHolomemId", holderHolomemId);
        summary.put("giftHolderCardInstanceId", holderCardInstanceId);
        summary.put("giftHolderCardId", asText(storedTrigger.get("giftHolderCardId")));
        summary.put("giftHolderZone", normalize(asText(storedTrigger.get("giftHolderZone"))));
        summary.put("sourceCardInstanceId", sourceCardInstanceId);
        summary.put("triggerTargetCardInstanceId", triggerTargetCardInstanceId);
        summary.put("rawText", giftText);
        summary.put("requestedEffects", execution.requestedEffects());
        summary.put("executedEffects", execution.executedEffects());
        summary.put("unsupportedEffects", execution.unsupportedEffects());
        summary.put("skippedEffects", execution.skippedEffects());
        return summary;
    }

    public Map<String, Object> loadGiftHolderSnapshot(Long matchId, Long userId, Long giftHolderHolomemId) {
        return giftTriggerContextService.loadGiftHolderSnapshot(matchId, userId, giftHolderHolomemId);
    }

    private List<Map<String, Object>> executeGiftTrigger(GiftTriggerRequest request) {
        return applyGiftTriggeredEffectsByTrigger(request, true, resolveSourceContext(request));
    }

    private List<Map<String, Object>> previewGiftTrigger(GiftTriggerRequest request) {
        return applyGiftTriggeredEffectsByTrigger(request, false, resolveSourceContext(request));
    }

    private List<Map<String, Object>> applyGiftTriggeredEffectsByTrigger(
        GiftTriggerRequest request,
        boolean executeEffects,
        MatchGiftTriggerSourceContext sourceContext
    ) {
        if (request.matchId() == null || request.userId() == null || request.turnNumber() <= 0) {
            return List.of();
        }
        String normalizedTriggerType = normalizeGiftTriggerType(request.triggerType());
        return giftTriggerOrchestrationService.buildTriggeredSummaries(
            request.matchId(),
            request.userId(),
            request.sourceCardInstanceId(),
            normalizedTriggerType,
            sourceContext,
            (resolvedMatchId, effectiveSourceCardInstanceId, holder) -> loadGiftTriggerSourceContext(
                resolvedMatchId,
                effectiveSourceCardInstanceId,
                asText(holder.get("zone")),
                request.sourceArtName()
            ),
            (holder, effectiveSourceCardInstanceId, effectiveSourceContext, resolvedTriggerType) -> buildGiftTriggerSummary(
                request.matchId(),
                request.userId(),
                request.turnNumber(),
                effectiveSourceCardInstanceId,
                request.triggerTargetCardInstanceId(),
                resolvedTriggerType,
                effectiveSourceContext,
                holder,
                executeEffects
            )
        );
    }

    private MatchGiftTriggerSourceContext resolveSourceContext(GiftTriggerRequest request) {
        if (request == null) {
            return null;
        }
        return loadGiftTriggerSourceContext(
            request.matchId(),
            request.sourceCardInstanceId(),
            request.fallbackStageZone(),
            request.sourceArtName()
        );
    }

    private GiftTriggerRequest giftTriggerRequest(
        Long matchId,
        Long userId,
        String triggerType,
        Long sourceCardInstanceId,
        Long triggerTargetCardInstanceId,
        int turnNumber
    ) {
        return giftTriggerRequest(
            matchId,
            userId,
            triggerType,
            sourceCardInstanceId,
            triggerTargetCardInstanceId,
            turnNumber,
            null,
            null
        );
    }

    private GiftTriggerRequest giftTriggerRequest(
        Long matchId,
        Long userId,
        String triggerType,
        Long sourceCardInstanceId,
        Long triggerTargetCardInstanceId,
        int turnNumber,
        String fallbackStageZone,
        String sourceArtName
    ) {
        return new GiftTriggerRequest(
            matchId,
            userId,
            triggerType,
            sourceCardInstanceId,
            triggerTargetCardInstanceId,
            turnNumber,
            fallbackStageZone,
            sourceArtName
        );
    }

    private Map<String, Object> buildGiftTriggerSummary(
        Long matchId,
        Long userId,
        int turnNumber,
        Long sourceCardInstanceId,
        Long triggerTargetCardInstanceId,
        String normalizedTriggerType,
        MatchGiftTriggerSourceContext sourceContext,
        Map<String, Object> holder,
        boolean executeEffects
    ) {
        Long holderHolomemId = asLong(holder.get("holomem_id"));
        Long holderCardInstanceId = asLong(holder.get("match_card_id"));
        String holderZone = normalize(asText(holder.get("zone")));
        String holderLevel = normalizeLevelType(asText(holder.get("current_level")));
        String giftText = matchEffectService.loadGiftEffectText(asText(holder.get("passive_text")));
        if (!StringUtils.hasText(giftText)) {
            return null;
        }
        if (!giftTriggerMatcher.matchesGiftTriggerType(giftText, normalizedTriggerType)) {
            return null;
        }
        if (
            !giftTriggerEligibilityService.isEligible(
                matchId,
                userId,
                turnNumber,
                holderHolomemId,
                holderCardInstanceId,
                holderZone,
                holderLevel,
                sourceCardInstanceId,
                sourceContext,
                giftText,
                normalizedTriggerType,
                matchEffectService::matchesPassiveGiftAttachedSupportCondition,
                matchEffectService::isGiftAlreadyUsedThisTurn
            )
        ) {
            return null;
        }
        return giftTriggerOrchestrationService.buildTriggerSummary(
            matchId,
            userId,
            sourceCardInstanceId,
            triggerTargetCardInstanceId,
            normalizedTriggerType,
            giftText,
            holder,
            executeEffects,
            matchEffectService::resolveGiftTriggerExecution,
            matchEffectService::resolveGiftSelectionPreview
        );
    }

    private String normalizeGiftTriggerType(String triggerType) {
        String normalized = normalize(triggerType);
        return switch (normalized) {
            case "DAMAGE_RECEIVED", "ON_DAMAGE_RECEIVED", "ON_TAKE_DAMAGE", "TAKE_DAMAGE" -> "DAMAGE_RECEIVED";
            case "OPPONENT_DOWNED", "DOWNED", "DOWNED_OPPONENT" -> "OPPONENT_DOWNED";
            case "SELF_DOWNED", "DOWNED_SELF", "OWN_SELF_DOWNED" -> "SELF_DOWNED";
            case "ALLY_DOWNED", "OWN_DOWNED", "OWN_HOLOMEM_DOWNED", "FRIENDLY_DOWNED" -> "ALLY_DOWNED";
            case "COLLAB", "ON_COLLAB", "SELF_COLLAB" -> "COLLAB";
            case "BATON_TOUCH_BACK", "BATON_TOUCH_MOVE_TO_BACK", "ON_BATON_TOUCH_BACK" -> "BATON_TOUCH_BACK";
            case "PERFORMANCE_START_SELF", "OWN_PERFORMANCE_START", "PERFORMANCE_START" -> "PERFORMANCE_START_SELF";
            case "MAIN_STEP_SELF", "OWN_MAIN_STEP", "MAIN_STEP_START_SELF" -> "MAIN_STEP_SELF";
            case "PERFORMANCE_START_OPPONENT", "OPPONENT_PERFORMANCE_START" -> "PERFORMANCE_START_OPPONENT";
            case "PERFORMANCE_END_SELF", "OWN_PERFORMANCE_END", "PERFORMANCE_END" -> "PERFORMANCE_END_SELF";
            case "PERFORMANCE_END_OPPONENT", "OPPONENT_PERFORMANCE_END" -> "PERFORMANCE_END_OPPONENT";
            case "STAGE_ENTER", "ENTER_STAGE", "HOLOMEM_ENTER", "ON_HOLOMEM_ENTER" -> "STAGE_ENTER";
            default -> "ART_USED";
        };
    }

    private MatchGiftTriggerSourceContext loadGiftTriggerSourceContext(
        Long matchId,
        Long sourceCardInstanceId,
        String fallbackStageZone,
        String sourceArtName
    ) {
        if (matchId == null || sourceCardInstanceId == null || sourceCardInstanceId <= 0) {
            return null;
        }
        return jdbcTemplate.query(
            """
            SELECT mc.card_id,
                   c.name,
                   m.level_type,
                   c.tags_json::text AS tags_json,
                   h.zone AS stage_zone
            FROM match_cards mc
            JOIN cards c ON c.card_id = mc.card_id
            LEFT JOIN member_cards m ON m.card_id = mc.card_id
            LEFT JOIN match_holomems h
              ON h.match_id = mc.match_id
             AND h.match_card_id = mc.id
            WHERE mc.match_id = ?
              AND mc.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                String stageZone = normalize(rs.getString("stage_zone"));
                if (!StringUtils.hasText(stageZone)) {
                    stageZone = normalize(fallbackStageZone);
                }
                return new MatchGiftTriggerSourceContext(
                    rs.getString("card_id"),
                    rs.getString("name"),
                    rs.getString("level_type"),
                    stageZone,
                    rs.getString("tags_json"),
                    sourceArtName
                );
            },
            matchId,
            sourceCardInstanceId
        );
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

    private record GiftTriggerRequest(
        Long matchId,
        Long userId,
        String triggerType,
        Long sourceCardInstanceId,
        Long triggerTargetCardInstanceId,
        int turnNumber,
        String fallbackStageZone,
        String sourceArtName
    ) {}
}
