package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.SearchCriteria;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.util.StringUtils;

final class MatchEffectSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;

    MatchEffectSearchService(JdbcTemplate jdbcTemplate, EffectTextParser effectTextParser) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
    }

    List<Map<String, Object>> loadSearchCandidates(
        Long matchId,
        Long userId,
        SearchCriteria criteria
    ) {
        return loadCandidatesFromZone(matchId, userId, "DECK", criteria, false);
    }

    List<Map<String, Object>> loadTopDeckWindow(Long matchId, Long userId, int count) {
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
            (rs, rowNum) -> mapSearchCandidateRow(rs, false),
            matchId,
            userId,
            limit
        );
    }

    List<Map<String, Object>> loadSearchCandidates(
        Long matchId,
        Long userId,
        String cardType,
        String levelType,
        String tag,
        String nameContains
    ) {
        return loadSearchCandidates(matchId, userId, new SearchCriteria(cardType, levelType, tag, nameContains));
    }

    List<Map<String, Object>> loadCandidatesFromZone(
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
            (rs, rowNum) -> mapSearchCandidateRow(rs, false),
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

    List<Map<String, Object>> loadCandidatesByCardInstanceIds(
        Long matchId,
        Long userId,
        List<Long> cardInstanceIds,
        SearchCriteria criteria
    ) {
        if (matchId == null || userId == null || cardInstanceIds == null || cardInstanceIds.isEmpty()) {
            return List.of();
        }
        List<Long> orderedIds = cardInstanceIds.stream()
            .filter(Objects::nonNull)
            .filter(id -> id > 0)
            .distinct()
            .toList();
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        SearchCriteria resolved = criteria == null ? SearchCriteria.empty() : criteria;
        String placeholders = String.join(", ", Collections.nCopies(orderedIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(matchId);
        args.add(userId);
        args.addAll(orderedIds);
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
                   GREATEST(COALESCE(m.hp, 0) - COALESCE(h.damage_taken, 0), 0) AS remain_hp,
                   mc.zone
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
              AND mc.id IN (PLACEHOLDER_IDS)
            """
                .replace("PLACEHOLDER_IDS", placeholders),
            (rs, rowNum) -> mapSearchCandidateRow(rs, true),
            args.toArray()
        );
        List<Map<String, Object>> filtered = filterCandidatesByCriteria(rows, resolved);
        Map<Long, Map<String, Object>> rowById = new LinkedHashMap<>();
        for (Map<String, Object> row : filtered) {
            Long id = MatchEffectValueHelper.asLong(row.get("id"));
            if (id != null && id > 0) {
                rowById.put(id, row);
            }
        }
        List<Map<String, Object>> orderedRows = new ArrayList<>();
        for (Long orderedId : orderedIds) {
            Map<String, Object> row = rowById.get(orderedId);
            if (row != null) {
                orderedRows.add(row);
            }
        }
        return orderedRows;
    }

    List<Map<String, Object>> loadCandidatesFromZone(
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

    List<Map<String, Object>> filterCandidatesByCriteria(List<Map<String, Object>> rows, SearchCriteria criteria) {
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

    boolean matchesSearchCriteria(Map<String, Object> row, SearchCriteria criteria) {
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

    boolean matchesBasicSearchCriteria(Map<String, Object> row, SearchCriteria criteria) {
        String cardType = MatchEffectValueHelper.normalize(MatchEffectValueHelper.asText(row.get("card_type")));
        if (StringUtils.hasText(criteria.cardType()) && !criteria.cardType().equals(cardType)) {
            return false;
        }
        String levelType = normalizeLevelType(MatchEffectValueHelper.asText(row.get("level_type")));
        if (StringUtils.hasText(criteria.levelType()) && !criteria.levelType().equals(levelType)) {
            return false;
        }
        String name = MatchEffectValueHelper.asText(row.get("name"));
        if (
            StringUtils.hasText(criteria.nameContains()) &&
            (!StringUtils.hasText(name) || !name.toLowerCase(Locale.ROOT).contains(criteria.nameContains().toLowerCase(Locale.ROOT)))
        ) {
            return false;
        }
        if (StringUtils.hasText(criteria.tag()) && !rowTagsContains(MatchEffectValueHelper.asText(row.get("tags_json")), criteria.tag())) {
            return false;
        }
        if (StringUtils.hasText(criteria.color()) && !matchesAnyColor(row, criteria.color())) {
            return false;
        }
        if (criteria.rested() != null) {
            Boolean rowRested = MatchEffectValueHelper.readRowBoolean(row.get("is_rested"));
            if (rowRested == null || !rowRested.equals(criteria.rested())) {
                return false;
            }
        }
        if (criteria.minRemainHp() != null || criteria.maxRemainHp() != null) {
            Long remainHp = MatchEffectValueHelper.asLong(row.get("remain_hp"));
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

    boolean rowTagsContains(String tagsJson, String targetTag) {
        if (!StringUtils.hasText(tagsJson) || !StringUtils.hasText(targetTag)) {
            return false;
        }
        JsonNode tagsNode = effectTextParser.parseEffectJson(tagsJson);
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

    boolean matchesAnyColor(Map<String, Object> row, String color) {
        if (!StringUtils.hasText(color)) {
            return true;
        }
        String expected = normalizeColorType(color);
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        return expected.equals(normalizeColorType(MatchEffectValueHelper.asText(row.get("main_color"))))
            || expected.equals(normalizeColorType(MatchEffectValueHelper.asText(row.get("sub_color"))))
            || expected.equals(normalizeColorType(MatchEffectValueHelper.asText(row.get("cheer_color"))));
    }

    List<Map<String, Object>> selectSearchCards(
        List<Map<String, Object>> candidates,
        List<Long> selectedCardInstanceIds,
        int searchCount
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<String, Object>> candidateById = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates) {
            Long id = MatchEffectValueHelper.asLong(candidate.get("id"));
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

    Map<String, Object> buildCriteriaSummary(SearchCriteria criteria) {
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

    private Map<String, Object> mapSearchCandidateRow(ResultSet rs, boolean includeZone) throws SQLException {
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
        if (includeZone) {
            row.put("zone", rs.getString("zone"));
        }
        return row;
    }

    private String normalizeColorType(String color) {
        String normalized = MatchEffectValueHelper.normalize(color);
        return switch (normalized) {
            case "RED", "BLUE", "GREEN", "WHITE", "PURPLE", "YELLOW", "COLORLESS" -> normalized;
            default -> "";
        };
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
