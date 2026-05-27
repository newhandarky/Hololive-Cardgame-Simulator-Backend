package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

class MatchAddCheerSourceResolverService {

    private final JdbcTemplate jdbcTemplate;
    private final SearchCriteriaParser searchCriteriaParser;
    private final CheerZoneFinder cheerZoneFinder;

    MatchAddCheerSourceResolverService(
        JdbcTemplate jdbcTemplate,
        SearchCriteriaParser searchCriteriaParser,
        CheerZoneFinder cheerZoneFinder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.searchCriteriaParser = searchCriteriaParser;
        this.cheerZoneFinder = cheerZoneFinder;
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
    Map<String, Object> resolvePreferredAddCheerSource(Long matchId, Long userId, String rawText) {
        String sourceClause = extractAddCheerSourceClause(rawText);
        SearchCriteria sourceCriteria = resolveSearchCriteriaFromRawText(sourceClause);

        if (StringUtils.hasText(sourceClause) && sourceClause.contains("アーカイブの")) {
            return cheerZoneFinder.find(matchId, userId, "ARCHIVE", sourceCriteria);
        }
        if (StringUtils.hasText(sourceClause) && sourceClause.contains("エールデッキ")) {
            return cheerZoneFinder.find(matchId, userId, "CHEER_DECK", sourceCriteria);
        }
        return findAttachableCheerCard(matchId, userId);
    }

    /**
     * 從送 Cheer 效果段中擷取來源描述。
     *
     * <p>例如：
     * - `自分のアーカイブの黄エール1枚を自分の〈虎金妃笑虎〉に送る`
     * 這裡真正決定來源的是前半句 `自分のアーカイブの黄エール1枚`。
     */
    String extractAddCheerSourceClause(String rawText) {
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
     * 直接把一小段 raw text 轉成 SearchCriteria。
     */
    private SearchCriteria resolveSearchCriteriaFromRawText(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return SearchCriteria.empty();
        }
        ObjectNode probe = JsonNodeFactory.instance.objectNode();
        probe.put("rawText", rawText);
        return searchCriteriaParser.resolveSearchCriteria(probe);
    }

    @FunctionalInterface
    interface CheerZoneFinder {
        Map<String, Object> find(Long matchId, Long userId, String zone, SearchCriteria criteria);
    }
}
