package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

final class MatchResultEffectExecutionService {

    private final EffectTextParser effectTextParser;
    private final OpponentUserResolver opponentUserResolver;

    MatchResultEffectExecutionService(
        EffectTextParser effectTextParser,
        OpponentUserResolver opponentUserResolver
    ) {
        this.effectTextParser = effectTextParser;
        this.opponentUserResolver = opponentUserResolver;
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
        Long opponentUserId = opponentUserResolver.resolve(matchId, userId);
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

    private boolean isBothToken(String token) {
        String normalized = effectTextParser.normalizeEffectType(token);
        return "BOTH".equals(normalized) || "ALL".equals(normalized);
    }

    private Long resolveSideUserId(String sideToken, Long actorUserId, Long opponentUserId) {
        String normalized = effectTextParser.normalizeEffectType(sideToken);
        return switch (normalized) {
            case "SELF", "YOU", "ME", "ACTOR", "CURRENT" -> actorUserId;
            case "OPPONENT", "ENEMY", "OTHER" -> opponentUserId;
            default -> null;
        };
    }

    private Map<String, Object> executeNoOpEffect(String effectType, JsonNode effectNode, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", false);
        summary.put("reason", reason);
        summary.put("rawText", effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        return summary;
    }

    private String readText(JsonNode node, String... fields) {
        return MatchEffectValueHelper.readText(node, fields);
    }

    @FunctionalInterface
    interface OpponentUserResolver {
        Long resolve(Long matchId, Long userId);
    }

    private record MatchResultDecision(
        boolean draw,
        Long winnerUserId,
        Long loserUserId,
        String reason
    ) {}
}
