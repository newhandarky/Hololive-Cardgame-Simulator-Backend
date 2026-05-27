package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

class MatchAddCheerTargetResolverService {

    private final JdbcTemplate jdbcTemplate;
    private final SearchCriteriaParser searchCriteriaParser;
    private final EffectTargetResolver effectTargetResolver;

    MatchAddCheerTargetResolverService(
        JdbcTemplate jdbcTemplate,
        SearchCriteriaParser searchCriteriaParser,
        EffectTargetResolver effectTargetResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.searchCriteriaParser = searchCriteriaParser;
        this.effectTargetResolver = effectTargetResolver;
    }

    /**
     * ADD_CHEER 會混到兩種不同需求：
     * 1. 純粹把某張可用 Cheer 貼到預設目標。
     * 2. 文案明確限制「只能從 Archive」或「只能貼到帶指定 tag 的 Holomem」。
     *
     * 這裡先把「不需要額外互動、可由文案穩定推斷」的條件集中處理，
     * 讓 Gift / Bloom / Collab 共用同一套 deterministic 選目標規則。
     */
    Long resolvePreferredAddCheerTargetHolomemId(
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
            Long resolvedTarget = effectTargetResolver.resolve(
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

    /**
     * 由文案判斷 ADD_CHEER 的目標區位限制。
     */
    String resolveRequiredAddCheerTargetZone(String rawText, boolean preferSelfBackTarget) {
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
    String resolveRequiredAddCheerTargetLevelType(String rawText) {
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
     */
    private SearchCriteria resolveSearchCriteriaFromRawText(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return SearchCriteria.empty();
        }
        ObjectNode probe = JsonNodeFactory.instance.objectNode();
        probe.put("rawText", rawText);
        return searchCriteriaParser.resolveSearchCriteria(probe);
    }

    /**
     * 從目標子句推斷名稱限制。
     */
    private String resolveTargetNameContains(String targetClause) {
        return resolveSearchCriteriaFromRawText(targetClause).nameContains();
    }

    @FunctionalInterface
    interface EffectTargetResolver {
        Long resolve(
            Long matchId,
            Long userId,
            String targetType,
            Long requestedTargetCardInstanceId,
            boolean defaultOpponent
        );
    }
}
