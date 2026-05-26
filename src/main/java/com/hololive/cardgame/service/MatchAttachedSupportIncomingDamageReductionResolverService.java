package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchAttachedSupportIncomingDamageReductionResolverService {

    private static final Pattern ATTACHED_SUPPORT_DAMAGE_REDUCTION_PATTERN = Pattern.compile(
        "受けるダメージ\\s*[−-]\\s*(\\d+)"
    );

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;

    MatchAttachedSupportIncomingDamageReductionResolverService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
    }

    int resolveAttachedSupportIncomingDamageReduction(
        Long matchId,
        Long matchHolomemId,
        String targetStageZone
    ) {
        if (matchId == null || matchHolomemId == null) {
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
            total += extractAttachedSupportIncomingDamageReduction(effectJsonText, targetStageZone);
        }
        return total;
    }

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

    private String extractAttachedSupportRawText(String effectJsonText) {
        JsonNode node = effectTextParser.parseEffectJson(effectJsonText);
        if (node == null) {
            return effectTextParser.normalizeDigits(effectJsonText);
        }
        return effectTextParser.normalizeDigits(effectTextParser.extractText(node, "rawText", "rawEffect", "rawHeader"));
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
