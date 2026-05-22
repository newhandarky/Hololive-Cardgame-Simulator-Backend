package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchExtraBloomAllowanceEffectExecutionService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final GiftTriggerMatcher giftTriggerMatcher;
    private final CurrentTurnResolver currentTurnResolver;
    private final RequiredOshiNameResolver requiredOshiNameResolver;
    private final OpponentStageHolomemLevelChecker opponentStageHolomemLevelChecker;
    private final NameMatcher nameMatcher;

    MatchExtraBloomAllowanceEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        GiftTriggerMatcher giftTriggerMatcher,
        CurrentTurnResolver currentTurnResolver,
        RequiredOshiNameResolver requiredOshiNameResolver,
        OpponentStageHolomemLevelChecker opponentStageHolomemLevelChecker,
        NameMatcher nameMatcher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.giftTriggerMatcher = giftTriggerMatcher;
        this.currentTurnResolver = currentTurnResolver;
        this.requiredOshiNameResolver = requiredOshiNameResolver;
        this.opponentStageHolomemLevelChecker = opponentStageHolomemLevelChecker;
        this.nameMatcher = nameMatcher;
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

        String requiredOshiName = requiredOshiNameResolver.resolve(rawText);
        if (StringUtils.hasText(requiredOshiName)) {
            String currentOshiName = resolvePlayerOshiCardName(matchId, userId);
            if (!requiredOshiName.equals(currentOshiName)) {
                return executeNoOpEffect(effectType, effectNode, "條件不成立：推しホロメン不符合要求");
            }
        }
        if (
            rawText.contains("相手のステージに1stホロメンがいる")
                && !opponentStageHolomemLevelChecker.exists(matchId, userId, "FIRST")
        ) {
            return executeNoOpEffect(effectType, effectNode, "條件不成立：相手ステージ沒有 1st Holomem");
        }

        List<String> allowedNames = resolveAllowedTargetNames(rawText);
        int currentTurn = currentTurnResolver.resolve(matchId);
        Map<String, Object> target = resolveTarget(
            matchId,
            userId,
            rawText,
            currentTurn,
            preferredTargetHolomemId,
            allowedNames
        );
        if (target == null) {
            return executeNoOpEffect(effectType, effectNode, "沒有符合條件且本回合已 Bloom 的目標");
        }

        Long targetHolomemId = MatchEffectValueHelper.asLong(target.get("holomem_id"));
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetHolomemId", targetHolomemId);
        payload.put("targetHolomemCardInstanceId", MatchEffectValueHelper.asLong(target.get("match_card_id")));
        payload.put("targetCardId", MatchEffectValueHelper.asText(target.get("card_id")));
        payload.put("targetName", MatchEffectValueHelper.asText(target.get("name")));
        payload.put("holderCardInstanceId", holderCardInstanceId);
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
            ) VALUES (?, ?, ?, ?, 'ALLOW_EXTRA_BLOOM', 1, ?, CAST(? AS jsonb))
            """,
            matchId,
            userId,
            userId,
            "BUFF",
            currentTurn,
            effectTextParser.toJsonString(payload)
        );

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", inserted == 1);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("targetHolomemCardInstanceId", MatchEffectValueHelper.asLong(target.get("match_card_id")));
        summary.put("targetCardId", MatchEffectValueHelper.asText(target.get("card_id")));
        summary.put("targetName", MatchEffectValueHelper.asText(target.get("name")));
        summary.put("targetZone", MatchEffectValueHelper.asText(target.get("zone")));
        summary.put("expiresTurn", currentTurn);
        return summary;
    }

    private Map<String, Object> resolveTarget(
        Long matchId,
        Long userId,
        String rawText,
        int currentTurn,
        Long preferredTargetHolomemId,
        List<String> allowedNames
    ) {
        if (preferredTargetHolomemId != null && rawText.contains("このホロメン")) {
            return jdbcTemplate.query(
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
                    if (MatchEffectValueHelper.asInt(rs.getObject("last_bloom_turn")) != currentTurn) {
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
        }

        String requiredZone = rawText.contains("センターホロメン") ? "CENTER" : null;
        return jdbcTemplate.query(
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
                    if (!allowedNames.isEmpty() && !nameMatcher.containsAny(name, allowedNames)) {
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

    private List<String> resolveAllowedTargetNames(String rawText) {
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
        return allowedNames;
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

    private Map<String, Object> executeNoOpEffect(String effectType, JsonNode effectNode, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("applied", false);
        summary.put("reason", reason);
        summary.put("rawText", effectTextParser.extractText(effectNode, "rawText", "rawEffect"));
        return summary;
    }

    @FunctionalInterface
    interface CurrentTurnResolver {
        int resolve(Long matchId);
    }

    @FunctionalInterface
    interface RequiredOshiNameResolver {
        String resolve(String rawText);
    }

    @FunctionalInterface
    interface OpponentStageHolomemLevelChecker {
        boolean exists(Long matchId, Long userId, String levelType);
    }

    @FunctionalInterface
    interface NameMatcher {
        boolean containsAny(String source, List<String> candidates);
    }
}
