package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteria;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchPassiveGiftIncomingDamageReductionResolverService {

    private static final Pattern PASSIVE_GIFT_DAMAGE_REDUCTION_VALUE_PATTERN = Pattern.compile(
        "受ける(?:アーツ)?ダメージ\\s*[ー\\-−]\\s*(\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_DICE_ODD_DAMAGE_REDUCTION_PATTERN = Pattern.compile(
        "奇数なら、[^。]*?受ける(?:アーツ)?ダメージ\\s*[ー\\-−]\\s*(\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_DICE_EVEN_DAMAGE_REDUCTION_PATTERN = Pattern.compile(
        "偶数なら、[^。]*?受ける(?:アーツ)?ダメージ\\s*[ー\\-−]\\s*(\\d+)"
    );
    private static final Pattern PASSIVE_GIFT_SELF_DAMAGE_CLAUSE_PATTERN = Pattern.compile(
        "このホロメン(?:[^。]*?)受ける(?:アーツ)?ダメージ"
    );
    private static final Pattern PASSIVE_GIFT_OWN_COLLAB_DAMAGE_CLAUSE_PATTERN = Pattern.compile(
        "自分のコラボホロメン(?:[^。]*?)受ける(?:アーツ)?ダメージ"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EffectTextParser effectTextParser;
    private final GiftTriggerMatcher giftTriggerMatcher;
    private final SearchCriteriaParser searchCriteriaParser;
    private final DiceService diceService;
    private final GiftTurnUsageReader giftTurnUsageReader;
    private final PassiveGiftTriggerActionWriter passiveGiftTriggerActionWriter;

    MatchPassiveGiftIncomingDamageReductionResolverService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        EffectTextParser effectTextParser,
        GiftTriggerMatcher giftTriggerMatcher,
        SearchCriteriaParser searchCriteriaParser,
        DiceService diceService,
        GiftTurnUsageReader giftTurnUsageReader,
        PassiveGiftTriggerActionWriter passiveGiftTriggerActionWriter
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.effectTextParser = effectTextParser;
        this.giftTriggerMatcher = giftTriggerMatcher;
        this.searchCriteriaParser = searchCriteriaParser;
        this.diceService = diceService;
        this.giftTurnUsageReader = giftTurnUsageReader;
        this.passiveGiftTriggerActionWriter = passiveGiftTriggerActionWriter;
    }

    int resolvePassiveGiftIncomingDamageReduction(
        Long matchId,
        Long userId,
        Long targetHolomemId,
        String incomingSourceLevelType
    ) {
        if (matchId == null || userId == null || targetHolomemId == null) {
            return 0;
        }
        PassiveGiftIncomingDamageReductionTargetContext targetContext =
            loadPassiveGiftIncomingDamageReductionTargetContext(
                matchId,
                userId,
                targetHolomemId,
                incomingSourceLevelType
            );
        if (targetContext == null) {
            return 0;
        }
        List<PassiveGiftHolderContext> holderContexts = loadPassiveGiftHolderContexts(matchId, userId);
        if (holderContexts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (PassiveGiftHolderContext holderContext : holderContexts) {
            total += resolvePassiveGiftIncomingDamageReductionFromHolder(
                matchId,
                userId,
                holderContext,
                targetContext
            );
        }
        return total;
    }

    PassiveGiftIncomingDamageReductionTargetContext loadPassiveGiftIncomingDamageReductionTargetContext(
        Long matchId,
        Long userId,
        Long holomemId,
        String incomingSourceLevelType
    ) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   h.current_level,
                   c.name,
                   COALESCE(c.tags_json, '[]'::jsonb)::text AS tags_json_text,
                   oc.name AS oshi_card_name
            FROM match_holomems h
            JOIN cards c ON c.card_id = h.card_id
            JOIN match_players mp
              ON mp.match_id = h.match_id
             AND mp.user_id = h.owner_user_id
            JOIN cards oc ON oc.card_id = mp.oshi_card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.id = ?
            LIMIT 1
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new PassiveGiftIncomingDamageReductionTargetContext(
                    rs.getLong("id"),
                    effectTextParser.normalizeEffectType(rs.getString("zone")),
                    effectTextParser.normalizeEffectType(rs.getString("current_level")),
                    rs.getString("name"),
                    parseTagsJson(rs.getString("tags_json_text")),
                    rs.getString("oshi_card_name"),
                    effectTextParser.normalizeEffectType(incomingSourceLevelType)
                );
            },
            matchId,
            userId,
            holomemId
        );
    }

    List<PassiveGiftHolderContext> loadPassiveGiftHolderContexts(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT h.id,
                   h.zone,
                   mc.passive_effect_json::text AS passive_effect_json_text
            FROM match_holomems h
            JOIN member_cards mc ON mc.card_id = h.card_id
            WHERE h.match_id = ?
              AND h.owner_user_id = ?
              AND h.zone IN ('CENTER', 'COLLAB')
              AND mc.passive_effect_json IS NOT NULL
            ORDER BY CASE h.zone
                        WHEN 'CENTER' THEN 1
                        WHEN 'COLLAB' THEN 2
                        ELSE 9
                     END,
                     h.id
            """,
            (rs, rowNum) -> new PassiveGiftHolderContext(
                rs.getLong("id"),
                effectTextParser.normalizeEffectType(rs.getString("zone")),
                rs.getString("passive_effect_json_text")
            ),
            matchId,
            userId
        );
    }

    int resolvePassiveGiftIncomingDamageReductionFromHolder(
        Long matchId,
        Long userId,
        PassiveGiftHolderContext holderContext,
        PassiveGiftIncomingDamageReductionTargetContext targetContext
    ) {
        String rawText = extractPassiveGiftRawText(holderContext.passiveEffectJsonText());
        if (!StringUtils.hasText(rawText) || targetContext == null) {
            return 0;
        }
        if (!giftTriggerMatcher.matchesGiftHolderZoneRestriction(rawText, holderContext.stageZone())) {
            return 0;
        }
        if (!matchesPassiveGiftRequiredOshiName(rawText, targetContext.oshiCardName())) {
            return 0;
        }
        if (!matchesIncomingDamageSourceLevelRestriction(rawText, targetContext.incomingSourceLevelType())) {
            return 0;
        }
        Integer diceConditionalReduction = resolveDiceConditionalPassiveGiftIncomingDamageReduction(
            matchId,
            userId,
            holderContext,
            targetContext,
            rawText
        );
        if (diceConditionalReduction != null) {
            return diceConditionalReduction;
        }
        boolean protectsSelfAndOwnCollab = rawText.contains("このホロメンと自分のコラボホロメンが受けるダメージ");
        boolean mentionsSelfDamageClause = PASSIVE_GIFT_SELF_DAMAGE_CLAUSE_PATTERN.matcher(rawText).find();
        boolean mentionsOwnCollabDamageClause = PASSIVE_GIFT_OWN_COLLAB_DAMAGE_CLAUSE_PATTERN.matcher(rawText).find();
        boolean protectsSelf = (mentionsSelfDamageClause || protectsSelfAndOwnCollab)
            && Objects.equals(holderContext.holomemId(), targetContext.holomemId());
        boolean protectsOwnCollab = (mentionsOwnCollabDamageClause || protectsSelfAndOwnCollab)
            && "COLLAB".equals(targetContext.stageZone());
        if (!protectsSelf && !protectsOwnCollab) {
            if (!matchesPassiveGiftTargetZoneRestriction(rawText, targetContext.stageZone())) {
                return 0;
            }
            String targetClause = extractPassiveGiftIncomingDamageReductionTargetClause(rawText);
            if (!matchesPassiveGiftTargetAttachedSupportCondition(targetClause, targetContext.holomemId())) {
                return 0;
            }
            SearchCriteria criteria = resolveMemberCriteriaFromRawText(
                stripPassiveGiftTargetAttachedSupportCondition(targetClause)
            );
            if (StringUtils.hasText(criteria.levelType()) && !criteria.levelType().equals(targetContext.levelType())) {
                return 0;
            }
            if (StringUtils.hasText(criteria.tag()) && !targetContext.tags().contains(criteria.tag())) {
                return 0;
            }
            if (StringUtils.hasText(criteria.nameContains())
                && !nullToEmpty(targetContext.cardName()).contains(criteria.nameContains())) {
                return 0;
            }
        }
        Matcher matcher = PASSIVE_GIFT_DAMAGE_REDUCTION_VALUE_PATTERN.matcher(rawText);
        if (!matcher.find()) {
            return 0;
        }
        return Math.max(parseSignedNumber("-" + matcher.group(1)) * -1, 0);
    }

    private Integer resolveDiceConditionalPassiveGiftIncomingDamageReduction(
        Long matchId,
        Long userId,
        PassiveGiftHolderContext holderContext,
        PassiveGiftIncomingDamageReductionTargetContext targetContext,
        String rawText
    ) {
        if (!StringUtils.hasText(rawText)
            || !rawText.contains("サイコロを1回振れる")
            || !rawText.contains("奇数なら")
            || !rawText.contains("偶数なら")) {
            return null;
        }
        if (!rawText.contains("このホロメン以外の自分のホロメンが相手からダメージを受ける時")) {
            return null;
        }
        if (Objects.equals(holderContext.holomemId(), targetContext.holomemId())) {
            return 0;
        }
        int turnNumber = loadCurrentTurnNumber(matchId);
        if (rawText.contains("ターンに1回")
            && giftTurnUsageReader.isGiftAlreadyUsedThisTurn(matchId, userId, turnNumber, holderContext.holomemId())) {
            return 0;
        }
        int oddReduction = effectTextParser.extractByPattern(rawText, PASSIVE_GIFT_DICE_ODD_DAMAGE_REDUCTION_PATTERN);
        int evenReduction = effectTextParser.extractByPattern(rawText, PASSIVE_GIFT_DICE_EVEN_DAMAGE_REDUCTION_PATTERN);
        if (oddReduction <= 0 && evenReduction <= 0) {
            return 0;
        }
        int diceRoll = diceService.rollD6();
        int reduction = (diceRoll % 2 == 1) ? oddReduction : evenReduction;
        passiveGiftTriggerActionWriter.appendIncomingDamageReductionTrigger(
            matchId,
            userId,
            turnNumber,
            holderContext.holomemId(),
            rawText,
            diceRoll
        );
        return Math.max(reduction, 0);
    }

    private int loadCurrentTurnNumber(Long matchId) {
        if (matchId == null) {
            return 0;
        }
        Integer turn = jdbcTemplate.query(
            "SELECT turn_number FROM matches WHERE id = ?",
            rs -> rs.next() ? rs.getInt("turn_number") : 0,
            matchId
        );
        return turn == null ? 0 : turn;
    }

    private boolean matchesPassiveGiftRequiredOshiName(String rawText, String actualOshiCardName) {
        String requiredOshiName = resolveRequiredOshiName(rawText);
        if (!StringUtils.hasText(requiredOshiName)) {
            return true;
        }
        return StringUtils.hasText(actualOshiCardName) && actualOshiCardName.contains(requiredOshiName);
    }

    private String resolveRequiredOshiName(String rawText) {
        if (!StringUtils.hasText(rawText) || !rawText.contains("推しホロメン")) {
            return null;
        }
        Matcher matcher = Pattern.compile("推しホロメンが〈([^〉]+)〉").matcher(rawText);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private boolean matchesIncomingDamageSourceLevelRestriction(String rawText, String incomingSourceLevelType) {
        if (!StringUtils.hasText(rawText)) {
            return true;
        }
        String normalizedText = rawText.toUpperCase(Locale.ROOT);
        String normalizedLevel = effectTextParser.normalizeEffectType(incomingSourceLevelType);
        boolean mentionsDebut = normalizedText.contains("DEBUTホロメンから受けるダメージ")
            || normalizedText.contains("DEBUTホロメンから受けるアーツダメージ");
        boolean mentionsFirst = normalizedText.contains("1STホロメンから受けるダメージ")
            || normalizedText.contains("FIRSTホロメンから受けるダメージ")
            || normalizedText.contains("1STホロメンから受けるアーツダメージ")
            || normalizedText.contains("FIRSTホロメンから受けるアーツダメージ");
        boolean mentionsSecond = normalizedText.contains("2NDホロメンから受けるダメージ")
            || normalizedText.contains("SECONDホロメンから受けるダメージ")
            || normalizedText.contains("2NDホロメンから受けるアーツダメージ")
            || normalizedText.contains("SECONDホロメンから受けるアーツダメージ");
        boolean mentionsSpot = normalizedText.contains("SPOTホロメンから受けるダメージ")
            || normalizedText.contains("SPOTホロメンから受けるアーツダメージ");
        boolean mentionsBuzz = normalizedText.contains("BUZZホロメンから受けるダメージ")
            || normalizedText.contains("BUZZホロメンから受けるアーツダメージ");
        if (!mentionsDebut && !mentionsFirst && !mentionsSecond && !mentionsSpot && !mentionsBuzz) {
            return true;
        }
        if (mentionsDebut && "DEBUT".equals(normalizedLevel)) {
            return true;
        }
        if (mentionsFirst && "FIRST".equals(normalizedLevel)) {
            return true;
        }
        if (mentionsSecond && "SECOND".equals(normalizedLevel)) {
            return true;
        }
        if (mentionsSpot && "SPOT".equals(normalizedLevel)) {
            return true;
        }
        return mentionsBuzz && "BUZZ".equals(normalizedLevel);
    }

    private boolean matchesPassiveGiftTargetZoneRestriction(String rawText, String targetStageZone) {
        if (!StringUtils.hasText(rawText)) {
            return true;
        }
        boolean mentionsCenterHolomem = rawText.contains("センターホロメン");
        boolean mentionsCollabHolomem = rawText.contains("コラボホロメン");
        boolean mentionsBackHolomem = rawText.contains("バックホロメン");
        boolean mentionsCenterPositionDamageTarget = rawText.contains("センターポジションで受けるダメージ");
        boolean mentionsCollabPositionDamageTarget = rawText.contains("コラボポジションで受けるダメージ");
        boolean mentionsBackPositionDamageTarget = rawText.contains("バックポジションで受けるダメージ");
        if (!mentionsCenterHolomem
            && !mentionsCollabHolomem
            && !mentionsBackHolomem
            && !mentionsCenterPositionDamageTarget
            && !mentionsCollabPositionDamageTarget
            && !mentionsBackPositionDamageTarget) {
            return true;
        }
        if ((mentionsCenterHolomem || mentionsCenterPositionDamageTarget) && "CENTER".equals(targetStageZone)) {
            return true;
        }
        if ((mentionsCollabHolomem || mentionsCollabPositionDamageTarget) && "COLLAB".equals(targetStageZone)) {
            return true;
        }
        return (mentionsBackHolomem || mentionsBackPositionDamageTarget) && "BACK".equals(targetStageZone);
    }

    private boolean matchesPassiveGiftTargetAttachedSupportCondition(String targetClause, Long targetHolomemId) {
        if (!StringUtils.hasText(targetClause) || targetHolomemId == null) {
            return true;
        }
        int attachedIndex = targetClause.indexOf("が付いている");
        if (attachedIndex < 0) {
            return true;
        }
        String requirementPrefix = targetClause.substring(0, attachedIndex);
        if (requirementPrefix.contains("マスコット")) {
            return loadAttachedSupportTypes(targetHolomemId).contains("MASCOT");
        }
        if (requirementPrefix.contains("ツール")) {
            return loadAttachedSupportTypes(targetHolomemId).contains("TOOL");
        }
        if (requirementPrefix.contains("ファン")) {
            return loadAttachedSupportTypes(targetHolomemId).contains("FAN");
        }
        List<String> requiredSupportNames = giftTriggerMatcher.extractNameTokens(requirementPrefix);
        if (requiredSupportNames.isEmpty()) {
            return true;
        }
        List<String> attachedSupportNames = loadAttachedSupportNames(targetHolomemId);
        if (attachedSupportNames.isEmpty()) {
            return false;
        }
        for (String attachedSupportName : attachedSupportNames) {
            if (!StringUtils.hasText(attachedSupportName)) {
                continue;
            }
            for (String requiredSupportName : requiredSupportNames) {
                if (attachedSupportName.contains(requiredSupportName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> loadAttachedSupportTypes(Long holomemId) {
        if (holomemId == null) {
            return Set.of();
        }
        List<String> supportTypes = jdbcTemplate.query(
            """
            SELECT hs.support_type
            FROM match_holomem_supports hs
            WHERE hs.match_holomem_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> normalize(rs.getString("support_type")),
            holomemId
        );
        if (supportTypes == null || supportTypes.isEmpty()) {
            return Set.of();
        }
        return supportTypes.stream()
            .filter(StringUtils::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> loadAttachedSupportNames(Long holomemId) {
        if (holomemId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
            SELECT c.name
            FROM match_holomem_supports hs
            JOIN cards c ON c.card_id = hs.support_card_id
            WHERE hs.match_holomem_id = ?
            ORDER BY hs.id
            """,
            (rs, rowNum) -> rs.getString("name"),
            holomemId
        );
    }

    private String extractPassiveGiftIncomingDamageReductionTargetClause(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        int gaIndex = rawText.indexOf("が受ける");
        int deIndex = rawText.indexOf("で受ける");
        int markerIndex;
        if (gaIndex >= 0 && deIndex >= 0) {
            markerIndex = Math.min(gaIndex, deIndex);
        } else {
            markerIndex = Math.max(gaIndex, deIndex);
        }
        if (markerIndex < 0) {
            return rawText.trim();
        }
        int clauseStart = Math.max(
            Math.max(rawText.lastIndexOf('、', markerIndex), rawText.lastIndexOf('。', markerIndex)),
            rawText.lastIndexOf('\n', markerIndex)
        );
        return rawText.substring(clauseStart < 0 ? 0 : clauseStart + 1, markerIndex).trim();
    }

    private String stripPassiveGiftTargetAttachedSupportCondition(String targetClause) {
        if (!StringUtils.hasText(targetClause)) {
            return "";
        }
        int attachedIndex = targetClause.indexOf("が付いている");
        if (attachedIndex < 0) {
            return targetClause;
        }
        return targetClause.substring(attachedIndex + "が付いている".length()).trim();
    }

    private SearchCriteria resolveMemberCriteriaFromRawText(String rawText) {
        ObjectNode probe = objectMapper.createObjectNode();
        probe.put("rawText", rawText);
        return searchCriteriaParser.resolveSearchCriteria(probe);
    }

    private String extractPassiveGiftRawText(String passiveEffectJsonText) {
        try {
            JsonNode node = objectMapper.readTree(passiveEffectJsonText);
            return effectTextParser.normalizeDigits(
                effectTextParser.extractText(node, "キーワード", "rawText", "rawEffect", "rawHeader")
            );
        } catch (Exception ignored) {
            return effectTextParser.normalizeDigits(passiveEffectJsonText);
        }
    }

    private Set<String> parseTagsJson(String tagsJsonText) {
        if (!StringUtils.hasText(tagsJsonText)) {
            return Set.of();
        }
        try {
            JsonNode node = objectMapper.readTree(tagsJsonText);
            if (node == null || !node.isArray()) {
                return Set.of();
            }
            Set<String> tags = new LinkedHashSet<>();
            for (JsonNode child : node) {
                if (child != null && child.isTextual() && StringUtils.hasText(child.asText())) {
                    tags.add(child.asText().trim());
                }
            }
            return tags;
        } catch (Exception ignored) {
            return Set.of();
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record PassiveGiftHolderContext(
        Long holomemId,
        String stageZone,
        String passiveEffectJsonText
    ) {}

    record PassiveGiftIncomingDamageReductionTargetContext(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        Set<String> tags,
        String oshiCardName,
        String incomingSourceLevelType
    ) {}
}
