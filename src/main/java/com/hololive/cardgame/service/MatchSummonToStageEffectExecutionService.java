package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchSummonToStageEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final SearchCriteriaParser searchCriteriaParser;
    private final MatchCardSelectionRequestResolver cardSelectionRequestResolver;
    private final MatchEffectSearchService searchService;

    MatchSummonToStageEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        SearchCriteriaParser searchCriteriaParser,
        MatchCardSelectionRequestResolver cardSelectionRequestResolver,
        MatchEffectSearchService searchService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.searchCriteriaParser = searchCriteriaParser;
        this.cardSelectionRequestResolver = cardSelectionRequestResolver;
        this.searchService = searchService;
    }

    /**
     * 執行上場效果：從牌庫公開 Holomem 並放到可用舞台區位。
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
        List<Map<String, Object>> candidates = searchService.loadCandidatesFromZone(
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
            Long cardInstanceId = MatchEffectValueHelper.asLong(row.get("id"));
            String cardId = MatchEffectValueHelper.asText(row.get("card_id"));
            String levelType = MatchEffectValueHelper.asText(row.get("level_type"));
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
        summary.put("criteria", searchService.buildCriteriaSummary(criteria));
        return summary;
    }

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

    private String resolveAvailableStageZone(Long matchId, Long userId, String preferredZone) {
        String preferred = MatchEffectValueHelper.normalize(preferredZone);
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

    private String normalizeHolomemLevel(String levelType) {
        String normalized = normalizeLevelType(levelType);
        if ("FIRST".equals(normalized) || "SECOND".equals(normalized) || "SPOT".equals(normalized) || "BUZZ".equals(normalized)) {
            return normalized;
        }
        return "DEBUT";
    }

    private String normalizeLevelType(String levelType) {
        String normalized = MatchEffectValueHelper.normalize(levelType);
        return switch (normalized) {
            case "DEBUT", "FIRST", "SECOND", "SPOT", "BUZZ" -> normalized;
            case "1ST" -> "FIRST";
            case "2ND" -> "SECOND";
            default -> "";
        };
    }
}
