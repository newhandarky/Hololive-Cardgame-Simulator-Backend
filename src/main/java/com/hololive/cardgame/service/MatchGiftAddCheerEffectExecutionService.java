package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.game.action.SendCheerAction;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchGiftAddCheerEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final GameActionExecutor gameActionExecutor;
    private final CurrentTurnResolver currentTurnResolver;
    private final CheerCountResolver cheerCountResolver;
    private final MatchAddCheerTargetResolverService addCheerTargetResolverService;
    private final MatchAddCheerSourceResolverService addCheerSourceResolverService;
    private final HolomemCardInstanceResolver holomemCardInstanceResolver;

    MatchGiftAddCheerEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        GameActionExecutor gameActionExecutor,
        CurrentTurnResolver currentTurnResolver,
        CheerCountResolver cheerCountResolver,
        MatchAddCheerTargetResolverService addCheerTargetResolverService,
        MatchAddCheerSourceResolverService addCheerSourceResolverService,
        HolomemCardInstanceResolver holomemCardInstanceResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.gameActionExecutor = gameActionExecutor;
        this.currentTurnResolver = currentTurnResolver;
        this.cheerCountResolver = cheerCountResolver;
        this.addCheerTargetResolverService = addCheerTargetResolverService;
        this.addCheerSourceResolverService = addCheerSourceResolverService;
        this.holomemCardInstanceResolver = holomemCardInstanceResolver;
    }

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

        Long targetHolomemId = addCheerTargetResolverService.resolvePreferredAddCheerTargetHolomemId(
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
        int requestedCount = cheerCountResolver.resolve(effectNode, 1);
        int attachCount = Math.max(requestedCount, 1);

        List<Long> attachedCardInstanceIds = new ArrayList<>();
        List<String> sourceZones = new ArrayList<>();
        for (int i = 0; i < attachCount; i++) {
            Map<String, Object> source = addCheerSourceResolverService.resolvePreferredAddCheerSource(
                matchId,
                userId,
                addCheerEffectClause
            );
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
                currentTurnResolver.resolve(matchId),
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
        summary.put("targetHolomemCardInstanceId", holomemCardInstanceResolver.resolve(targetHolomemId));
        summary.put("attachedCheerCardInstanceIds", attachedCardInstanceIds);
        summary.put("sourceZones", sourceZones);
        return summary;
    }

    private boolean isOpponentTargetType(String targetType) {
        return targetType.contains("ENEMY") || targetType.contains("OPPONENT");
    }

    private String extractResolvedEffectClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int splitIndex = findClauseSeparator(rawText);
        return splitIndex < 0 || splitIndex + 1 >= rawText.length() ? rawText : rawText.substring(splitIndex + 1).trim();
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

    private String normalize(Object value) {
        return MatchEffectValueHelper.normalize(value);
    }

    private Long asLong(Object value) {
        return MatchEffectValueHelper.asLong(value);
    }

    private String asText(Object value) {
        return MatchEffectValueHelper.asText(value);
    }

    @FunctionalInterface
    interface CurrentTurnResolver {
        int resolve(Long matchId);
    }

    @FunctionalInterface
    interface CheerCountResolver {
        int resolve(JsonNode effectNode, int defaultValue);
    }

    @FunctionalInterface
    interface HolomemCardInstanceResolver {
        Long resolve(Long matchHolomemId);
    }
}
